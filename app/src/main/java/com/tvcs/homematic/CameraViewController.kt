package com.tvcs.homematic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import androidx.core.net.toUri
import androidx.media3.exoplayer.DefaultRenderersFactory
import org.videolan.libvlc.util.VLCVideoLayout
import android.os.Build
import android.view.PixelCopy
import android.view.SurfaceView

/**
 * CameraViewController — manages RTSP (ExoPlayer) + VLC + MJPEG-snapshot fallback.
 *
 * ── Strategy ─────────────────────────────────────────────────────────────────
 * 1. Start ExoPlayer with the configured RTSP URL.
 * 2. If RTSP_TIMEOUT_MS elapses without a successful first frame, or if ExoPlayer
 *    fires a PlaybackException, try libVLC next.
 * 3. Transient errors (network blip, stream ended) trigger a reconnect with
 *    exponential backoff (up to MAX_RETRIES attempts) before falling back to snapshots.
 * 4. In snapshot mode: poll CAMERA_SNAPSHOT_URL every CAMERA_SNAPSHOT_INTERVAL
 *    seconds and display the JPEG in an ImageView.
 * 5. The camera area is tappable at all times — a tap resets the controller
 *    fully and retries from ExoPlayer (unless a hard engine override is set).
 * 6. On every new Activity start, try RTSP again (network may have recovered).
 *
 * ── Lifecycle ────────────────────────────────────────────────────────────────
 * Attach to the Activity's lifecycle via attachToLifecycle() so the controller
 * automatically pauses/resumes with the Activity.
 */
@OptIn(UnstableApi::class)
class CameraViewController(
    private val context: Context,
    private val playerView:    PlayerView,
    private val vlcLayout:     VLCVideoLayout,
    private val snapshotView:  ImageView,
    private val statusLabel:   TextView,
    private val muteButton:    android.widget.ImageButton? = null,
    /** When non-null, this config overrides SharedPrefs camera fields. */
    var cameraConfig: CameraConfig? = null
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "CameraVC"
        private const val DEFAULT_RTSP_TIMEOUT_MS  = 8_000L
        private const val DEFAULT_SNAPSHOT_INTERVAL = 5

        /** How many reconnect attempts before giving up and going to snapshot. */
        private const val MAX_RETRIES = 3

        /** Base delay for exponential back-off: 5 s, 10 s, 20 s. */
        private const val RETRY_BASE_MS = 5_000L

        /**
         * Keywords that indicate a structural SDP/codec incompatibility.
         * Only these trigger rtspPermanentlyFailed — NOT generic network errors.
         */
        private val UNRECOVERABLE_HINTS = listOf(
            "missing attribute fmtp",
            "missing attribute",
            "unsupported sdp",
            "no supported track"
        )
    }

    private val prefs       = PreferenceManager.getDefaultSharedPreferences(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ioScope     = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Set to false for background slots in multi-camera mode so they don't
     * overwrite the status label while a different camera is visible.
     * Always true in single-camera mode (default).
     */
    var isActiveSlot: Boolean = true

    var onMotionDetected: (() -> Unit)? = null

    private val motionEngine = MotionDetectionEngine(
        sensitivityPct   = prefs.getString(PreferenceKeys.MOTION_DETECT_SENSITIVITY, "8")?.toIntOrNull() ?: 8,
        onMotionDetected = { mainHandler.post { onMotionDetected?.invoke() } }
    )

    private var exoPlayer:      ExoPlayer? = null
    private var snapshotJob:    Job?        = null
    private var rtspSampleJob:  Job?        = null
    private var rtspTimeoutJob: Runnable?   = null
    private var retryJob:       Runnable?   = null

    private var inSnapshotMode        = false
    private var started               = false
    private var rtspFailReason:String? = null
    private var isMuted               = false
    private var retryCount            = 0

    /**
     * Set to true only for hard structural SDP failures that will always fail.
     * NOT set for transient network errors.
     */
    private var rtspPermanentlyFailed = false

    private enum class EngineChoice { EXO, VLC, SNAPSHOT }
    private var nextEngine      = EngineChoice.EXO
    private var activeVlcEngine: VlcRtspEngine? = null

    // ── Mute ─────────────────────────────────────────────────────────────────

    fun toggleMute() {
        isMuted = !isMuted
        exoPlayer?.volume = if (isMuted) 0f else 1f
        activeVlcEngine?.setMuted(isMuted)
        updateMuteButton()
    }

    private fun updateMuteButton() {
        muteButton ?: return
        muteButton.setImageResource(
            if (isMuted) android.R.drawable.ic_lock_silent_mode
            else         android.R.drawable.ic_lock_silent_mode_off
        )
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun attachToLifecycle(owner: LifecycleOwner) {
        owner.lifecycle.addObserver(this)
    }

    @MainThread
    fun start() {
        if (!isEnabled()) { hide(); return }
        if (!ioScope.isActive) ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        show()
        started        = true
        inSnapshotMode = false
        rtspFailReason = null
        retryCount     = 0
        applyMotionPrefs()
        nextEngine = when (cfgEngine()) {
            "vlc"      -> EngineChoice.VLC
            "snapshot" -> EngineChoice.SNAPSHOT
            else       -> if (rtspPermanentlyFailed) EngineChoice.VLC else EngineChoice.EXO
        }
        startWithEngine(nextEngine)
        installTapReload()
    }

    @MainThread
    fun stop() {
        started = false
        cancelRtspTimeout()
        cancelRetry()
        stopSnapshotLoop()
        stopRtspMotionSampler()
        releasePlayer()
        motionEngine.enabled = false
        motionEngine.reset()
    }

    fun applyPrefsChange() {
        if (!started) return
        rtspPermanentlyFailed = false
        retryCount = 0
        stop()
        start()
    }

    fun applyMotionPrefs() {
        val sensKey    = if (prefs.contains(PreferenceKeys.MOTION_WEBCAM_SENSITIVITY))
            PreferenceKeys.MOTION_WEBCAM_SENSITIVITY else PreferenceKeys.MOTION_DETECT_SENSITIVITY
        val enabledKey = if (prefs.contains(PreferenceKeys.MOTION_WEBCAM_ENABLED))
            PreferenceKeys.MOTION_WEBCAM_ENABLED else PreferenceKeys.MOTION_DETECT_ENABLED
        MotionPrefsHelper.applyTo(motionEngine, prefs, sensKey, enabledKey)
    }

    /**
     * Re-publishes the controller's current status string to the shared label,
     * and restores the correct view visibility for this slot (in case it was an
     * invisible background slot whose views were set to GONE while inactive).
     * Called by MultiCameraController when this slot becomes the active one.
     */
    fun refreshStatus() {
        if (!isActiveSlot) return
        // Restore view visibility to match current state
        when {
            inSnapshotMode -> {
                playerView.visibility                           = View.GONE
                (vlcLayout.parent as? View)?.visibility        = View.GONE
                snapshotView.visibility                        = View.VISIBLE
            }
            activeVlcEngine != null -> {
                playerView.visibility                          = View.GONE
                (vlcLayout.parent as? View)?.visibility        = View.VISIBLE
                snapshotView.visibility                        = View.GONE
            }
            else -> {
                // ExoPlayer active or still connecting — show playerView
                playerView.visibility                          = View.VISIBLE
                (vlcLayout.parent as? View)?.visibility        = View.GONE
                snapshotView.visibility                        = View.GONE
            }
        }
        // Re-publish status text
        val msg = when {
            !started                                                               -> return
            inSnapshotMode && rtspFailReason != null ->
                context.getString(R.string.camera_status_rtsp_failed_snapshot_ok, rtspFailReason)
            inSnapshotMode ->
                context.getString(R.string.camera_status_snapshot_mode)
            retryJob != null ->
                context.getString(R.string.camera_status_retrying, retryCount, MAX_RETRIES)
            exoPlayer?.playbackState == androidx.media3.common.Player.STATE_BUFFERING ->
                context.getString(R.string.camera_status_buffering)
            exoPlayer?.playbackState == androidx.media3.common.Player.STATE_READY ->
                context.getString(R.string.camera_status_live_rtsp)
            activeVlcEngine != null ->
                context.getString(R.string.camera_status_live_vlc)
            else ->
                context.getString(R.string.camera_status_connecting)
        }
        statusLabel.text = msg
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onStart(owner: LifecycleOwner) { if (isEnabled()) start() }
    override fun onStop(owner: LifecycleOwner)  { stop() }
    override fun onDestroy(owner: LifecycleOwner) { ioScope.cancel(); stop() }

    // ── Engine dispatch ───────────────────────────────────────────────────────

    private fun startWithEngine(choice: EngineChoice) {
        if (!started) return
        when (choice) {
            EngineChoice.EXO      -> startRtsp()
            EngineChoice.VLC      -> startVlc()
            EngineChoice.SNAPSHOT -> fallbackToSnapshot(rtspFailReason ?: "No RTSP engine succeeded")
        }
    }

    // ── ExoPlayer RTSP ───────────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    private fun startRtsp() {
        val rawUrl = cfgRtspUrl()
        if (rawUrl.isBlank()) { fallbackToSnapshot("No RTSP URL configured"); return }

        val rtspUrl = injectCredentials(rawUrl,
            user = cfgUsername(),
            pass = cfgPassword(),
            at   = true)

        Log.i(TAG, "Starting ExoPlayer RTSP (retry=$retryCount): ${sanitiseUrl(rtspUrl)}")
        setStatus(context.getString(R.string.camera_status_connecting))
        releasePlayer()

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .build()
            .also { exoPlayer = it }

        playerView.player = player
        playerView.visibility  = View.VISIBLE
        snapshotView.visibility = View.GONE
        (vlcLayout.parent as? View)?.visibility = View.GONE

        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(rtspUrl.toUri()))

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY    -> {
                        // onRenderedFirstFrame fires earlier for first-frame confirmation
                    }
                    Player.STATE_BUFFERING ->
                        setStatus(context.getString(R.string.camera_status_buffering))
                    Player.STATE_ENDED    ->
                        // Stream ended cleanly (camera periodic reconnect) — retry before snapshot
                        mainHandler.post {
                            if (started && !inSnapshotMode)
                                scheduleRetry("RTSP stream ended", EngineChoice.EXO)
                        }
                    else -> {}
                }
            }

            override fun onRenderedFirstFrame() {
                cancelRtspTimeout()
                retryCount = 0   // successful connection resets the counter
                playerView.visibility = View.VISIBLE
                setStatus(context.getString(R.string.camera_status_live_rtsp))
                Log.i(TAG, "ExoPlayer: first frame rendered")
                startRtspMotionSampler()
            }

            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                val msg   = error.message ?: cause?.message ?: "RTSP error"
                Log.w(TAG, "ExoPlayer error: $msg", error)

                // Only hard SDP/codec incompatibilities are unrecoverable
                val isUnrecoverable = isStructuralError(cause?.message, cause?.cause?.message)
                if (isUnrecoverable) {
                    Log.e(TAG, "Unrecoverable SDP error — skipping ExoPlayer for this session")
                    rtspPermanentlyFailed = true
                }

                mainHandler.post {
                    if (!started || inSnapshotMode) return@post
                    rtspFailReason = msg
                    if (isUnrecoverable) {
                        // Hard codec failure → go straight to VLC, no retry
                        nextEngine = EngineChoice.VLC
                        try { startWithEngine(EngineChoice.VLC) }
                        catch (t: Throwable) { fallbackToSnapshot(msg) }
                    } else {
                        // Transient error → retry with back-off, escalate to VLC after MAX_RETRIES
                        scheduleRetry(msg, EngineChoice.EXO)
                    }
                }
            }
        })

        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
        player.volume = if (isMuted) 0f else 1f

        muteButton?.visibility = View.VISIBLE
        updateMuteButton()

        val timeoutMs = cfgTimeoutMs()
        rtspTimeoutJob = Runnable {
            Log.w(TAG, "ExoPlayer timeout after ${timeoutMs}ms")
            rtspFailReason = context.getString(R.string.camera_status_rtsp_timeout)
            if (started && !inSnapshotMode) scheduleRetry(rtspFailReason!!, EngineChoice.VLC)
        }
        mainHandler.postDelayed(rtspTimeoutJob!!, timeoutMs)
    }

    // ── libVLC RTSP ───────────────────────────────────────────────────────────

    private fun startVlc() {
        if (!started) return
        val rawUrl = cfgRtspUrl()
        if (rawUrl.isBlank()) { fallbackToSnapshot("No RTSP URL"); return }
        val url = injectCredentials(rawUrl,
            user = cfgUsername(),
            pass = cfgPassword(),
            at   = true)

        Log.i(TAG, "Starting libVLC RTSP (retry=$retryCount): ${sanitiseUrl(url)}")
        setStatus(context.getString(R.string.camera_status_connecting_vlc))
        releasePlayer()

        playerView.visibility   = View.GONE
        (vlcLayout.parent as? View)?.visibility = View.VISIBLE
        snapshotView.visibility = View.GONE

        val vlcEngine = VlcRtspEngine(context, vlcLayout)
        activeVlcEngine = vlcEngine

        val timeoutMs = cfgTimeoutMs()
        rtspTimeoutJob = Runnable {
            Log.w(TAG, "VLC timeout — scheduling retry")
            if (started && !inSnapshotMode)
                scheduleRetry(rtspFailReason ?: "VLC timeout", EngineChoice.VLC)
        }
        mainHandler.postDelayed(rtspTimeoutJob!!, timeoutMs)

        vlcEngine.start(url, isMuted, object : RtspEngine.Listener {
            override fun onPlaying() {
                cancelRtspTimeout()
                retryCount = 0
                setStatus(context.getString(R.string.camera_status_live_vlc))
                muteButton?.visibility = View.VISIBLE
                updateMuteButton()
                startVlcMotionSamplerIfEnabled()
            }
            override fun onError(message: String, unrecoverable: Boolean) {
                Log.w(TAG, "VLC error: $message")
                cancelRtspTimeout()
                rtspFailReason = message
                if (started && !inSnapshotMode)
                    mainHandler.post { scheduleRetry(message, EngineChoice.VLC) }
            }
            override fun onEnded() {
                // Camera closed connection — retry before snapshot
                mainHandler.post {
                    if (started && !inSnapshotMode)
                        scheduleRetry("VLC stream ended", EngineChoice.VLC)
                }
            }
        })
    }

    // ── Retry logic ───────────────────────────────────────────────────────────

    /**
     * Schedule a reconnect attempt with exponential back-off.
     * After [MAX_RETRIES] attempts the engine is escalated (EXO→VLC→SNAPSHOT).
     */
    private fun scheduleRetry(reason: String, currentEngine: EngineChoice) {
        cancelRtspTimeout()
        cancelRetry()
        releasePlayer()

        retryCount++
        if (retryCount > MAX_RETRIES) {
            Log.w(TAG, "Max retries ($MAX_RETRIES) exceeded for $currentEngine — escalating")
            retryCount = 0
            val nextEsc = when (currentEngine) {
                EngineChoice.EXO -> EngineChoice.VLC
                else             -> EngineChoice.SNAPSHOT
            }
            if (nextEsc == EngineChoice.SNAPSHOT) {
                fallbackToSnapshot(reason)
            } else {
                nextEngine = nextEsc
                startWithEngine(nextEsc)
            }
            return
        }

        val delayMs = RETRY_BASE_MS * (1L shl (retryCount - 1))   // 5s, 10s, 20s
        Log.i(TAG, "Retry $retryCount/$MAX_RETRIES for $currentEngine in ${delayMs}ms: $reason")
        setStatus(context.getString(R.string.camera_status_retrying, retryCount, MAX_RETRIES))

        retryJob = Runnable {
            retryJob = null
            if (started && !inSnapshotMode) startWithEngine(currentEngine)
        }
        mainHandler.postDelayed(retryJob!!, delayMs)
    }

    private fun cancelRetry() {
        retryJob?.let { mainHandler.removeCallbacks(it) }
        retryJob = null
    }

    // ── Snapshot fallback ─────────────────────────────────────────────────────

    @MainThread
    private fun fallbackToSnapshot(reason: String) {
        if (inSnapshotMode) return
        inSnapshotMode = true
        rtspFailReason = reason
        Log.i(TAG, "Falling back to MJPEG snapshots: $reason")

        cancelRtspTimeout()
        cancelRetry()
        stopRtspMotionSampler()
        releasePlayer()
        playerView.visibility   = View.GONE
        (vlcLayout.parent as? View)?.visibility = View.GONE
        snapshotView.visibility = View.VISIBLE
        muteButton?.visibility  = View.GONE

        val snapshotUrl = cfgSnapshotUrl()
        if (snapshotUrl.isBlank()) {
            setStatus(context.getString(R.string.camera_status_tap_to_reload,
                context.getString(R.string.camera_status_no_snapshot_url)))
            return
        }

        val intervalSec = cfgIntervalSec()
        startSnapshotLoop(snapshotUrl, intervalSec)
    }

    // ── Snapshot loop ─────────────────────────────────────────────────────────

    private fun startSnapshotLoop(url: String, intervalSec: Int) {
        stopSnapshotLoop()
        snapshotJob = ioScope.launch {
            val snapshotUrl = injectCredentials(url,
                user = cfgUsername(),
                pass = cfgPassword(),
                at   = false)

            while (isActive) {
                try {
                    val bmp = fetchSnapshot(snapshotUrl)
                    motionEngine.process(bmp)
                    withContext(Dispatchers.Main) {
                        snapshotView.setImageBitmap(bmp)
                        val rtspFail = rtspFailReason
                        if (rtspFail != null)
                            setStatus(context.getString(
                                R.string.camera_status_rtsp_failed_snapshot_ok, rtspFail))
                        else
                            setStatus(context.getString(R.string.camera_status_snapshot_mode))
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Snapshot fetch failed: ${e.message}")
                    withContext(Dispatchers.Main) {
                        val rtspFail = rtspFailReason
                        val errMsg = if (rtspFail != null)
                            context.getString(R.string.camera_status_rtsp_and_snapshot_error,
                                rtspFail, e.message ?: "?")
                        else
                            context.getString(R.string.camera_status_snapshot_error,
                                e.message ?: "?")
                        // Show "tap to reload" hint after snapshot also fails
                        setStatus(context.getString(R.string.camera_status_tap_to_reload, errMsg))
                    }
                }
                delay(intervalSec * 1000L)
            }
        }
    }

    /**
     * Fetch a JPEG snapshot from [url].
     * Supports both query-param auth (?user=&password=) and HTTP Basic Auth header.
     */
    private fun fetchSnapshot(url: String): Bitmap {
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout    = 8_000
            // Fix 6: add Basic Auth header for cameras that require it
            val user = cfgUsername()
            val pass = cfgPassword()
            if (user.isNotBlank()) {
                val creds = Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
                setRequestProperty("Authorization", "Basic $creds")
            }
            connect()
        }
        return try {
            if (con.responseCode != HttpURLConnection.HTTP_OK)
                error("HTTP ${con.responseCode}")
            BitmapFactory.decodeStream(con.inputStream)
                ?: error("Empty image response")
        } finally {
            con.disconnect()
        }
    }

    // ── Tap-to-reload ─────────────────────────────────────────────────────────

    /**
     * Install a click listener on the camera container.
     * A tap always does a full reset — useful when the stream silently died.
     */
    private fun installTapReload() {
        val container = playerView.parent as? View ?: return
        container.setOnClickListener {
            Log.i(TAG, "Camera area tapped — reloading")
            applyPrefsChange()
        }
    }

    // ── VLC motion sampler ────────────────────────────────────────────────────

    private fun startVlcMotionSamplerIfEnabled() {
        if (!motionEngine.enabled) return
        val snapshotUrl = cfgSnapshotUrl()
        if (snapshotUrl.isBlank()) return
        val intervalSec = cfgIntervalSec()
        stopRtspMotionSampler()
        rtspSampleJob = ioScope.launch {
            val url = injectCredentials(snapshotUrl,
                user = cfgUsername(),
                pass = cfgPassword(),
                at   = false)
            while (isActive) {
                delay((intervalSec * 1000L).coerceAtLeast(2000L))
                try {
                    val bmp = fetchSnapshot(url)
                    motionEngine.process(bmp)
                    bmp.recycle()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                  catch (_: Exception) {}
            }
        }
    }

    // ── RTSP motion sampler (PixelCopy) ───────────────────────────────────────

    private fun startRtspMotionSampler() {
        stopRtspMotionSampler()
        if (!motionEngine.enabled) return
        rtspSampleJob = ioScope.launch {
            while (isActive) {
                delay(2_000L)
                if (!motionEngine.enabled) continue
                grabRtspFrame()
            }
        }
    }

    private fun stopRtspMotionSampler() { rtspSampleJob?.cancel(); rtspSampleJob = null }

    private suspend fun grabRtspFrame() {
        if (!started || inSnapshotMode) return
        val sv = playerView.videoSurfaceView ?: return
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            mainHandler.post {
                try {
                    if (sv.width <= 0 || sv.height <= 0) {
                        cont.resumeWith(Result.success(Unit)); return@post
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && sv is SurfaceView) {
                        val bmp = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
                        PixelCopy.request(sv, bmp, { result ->
                            if (result == PixelCopy.SUCCESS) motionEngine.process(bmp)
                            bmp.recycle()
                            if (cont.isActive) cont.resumeWith(Result.success(Unit))
                        }, mainHandler)
                    } else {
                        val bmp    = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bmp)
                        sv.draw(canvas)
                        motionEngine.process(bmp)
                        bmp.recycle()
                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "grabRtspFrame: ${e.message}")
                    if (cont.isActive) cont.resumeWith(Result.success(Unit))
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun releasePlayer() {
        exoPlayer?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        exoPlayer = null
        playerView.player = null
        activeVlcEngine?.release()
        activeVlcEngine = null
    }

    private fun stopSnapshotLoop() { snapshotJob?.cancel(); snapshotJob = null }

    private fun cancelRtspTimeout() {
        rtspTimeoutJob?.let { mainHandler.removeCallbacks(it) }
        rtspTimeoutJob = null
    }

    @MainThread
    private fun setStatus(msg: String) {
        if (isActiveSlot) statusLabel.text = msg
    }

    private fun show() {
        playerView.visibility   = View.VISIBLE
        (vlcLayout.parent as? View)?.visibility = View.GONE
        snapshotView.visibility = View.GONE
        statusLabel.visibility  = View.VISIBLE
        applyOverlayAlpha()
        applyScaleType()
    }

    @OptIn(UnstableApi::class)
    fun applyScaleType() {
        val scaleKey = prefs.getString(PreferenceKeys.CAMERA_SCALE_TYPE, "center_crop") ?: "center_crop"
        snapshotView.scaleType = when (scaleKey) {
            "fit_center"    -> ImageView.ScaleType.FIT_CENTER
            "center_inside" -> ImageView.ScaleType.CENTER_INSIDE
            "fit_xy"        -> ImageView.ScaleType.FIT_XY
            else            -> ImageView.ScaleType.CENTER_CROP
        }
        val exoMode = when (scaleKey) {
            "fit_center"    -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            "center_inside" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            "fit_xy"        -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            else            -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
        playerView.resizeMode = exoMode
    }

    fun applyOverlayAlpha() {
        val alphaPct = prefs.getString(PreferenceKeys.CAMERA_OVERLAY_ALPHA, "60")
            ?.toIntOrNull()?.coerceIn(0, 100) ?: 60
        val bgAlpha  = (alphaPct / 100f * 255).toInt()
        val statusRow = (statusLabel.parent as? View)
        statusRow?.background?.mutate()?.alpha = bgAlpha
        muteButton?.alpha = alphaPct / 100f
    }

    private fun hide() {
        playerView.visibility   = View.GONE
        (vlcLayout.parent as? View)?.visibility = View.GONE
        vlcLayout.visibility    = View.GONE
        snapshotView.visibility = View.GONE
        statusLabel.visibility  = View.GONE
        muteButton?.visibility  = View.GONE
    }

    fun isEnabled() = cameraConfig != null || prefs.getBoolean(PreferenceKeys.CAMERA_ENABLED, false)

    /** Returns true if this controller has been started and not yet stopped. */
    fun isStarted() = started

    // ── Config helpers: prefer cameraConfig over prefs ────────────────────────

    private fun cfgRtspUrl()     = cameraConfig?.rtspUrl       ?: prefs.getString(PreferenceKeys.CAMERA_RTSP_URL,          "") ?: ""
    private fun cfgSnapshotUrl() = cameraConfig?.snapshotUrl   ?: prefs.getString(PreferenceKeys.CAMERA_SNAPSHOT_URL,      "") ?: ""
    private fun cfgUsername()    = cameraConfig?.username      ?: prefs.getString(PreferenceKeys.CAMERA_USERNAME,          "") ?: ""
    private fun cfgPassword()    = cameraConfig?.password      ?: prefs.getString(PreferenceKeys.CAMERA_PASSWORD,          "") ?: ""
    private fun cfgEngine()      = cameraConfig?.rtspEngine    ?: prefs.getString(PreferenceKeys.CAMERA_RTSP_ENGINE,    "auto") ?: "auto"
    private fun cfgTimeoutMs()   = cameraConfig?.rtspTimeoutMs
                                    ?: prefs.getString(PreferenceKeys.CAMERA_RTSP_TIMEOUT_MS, "")?.toLongOrNull()
                                    ?: DEFAULT_RTSP_TIMEOUT_MS
    private fun cfgIntervalSec() = cameraConfig?.snapshotIntervalSec
                                    ?: prefs.getString(PreferenceKeys.CAMERA_SNAPSHOT_INTERVAL, "")?.toIntOrNull()
                                    ?: DEFAULT_SNAPSHOT_INTERVAL

    /**
     * Returns true only for hard structural SDP/codec errors that will always
     * fail regardless of network conditions.
     */
    private fun isStructuralError(vararg messages: String?): Boolean {
        val combined = messages.filterNotNull().joinToString(" ").lowercase()
        return UNRECOVERABLE_HINTS.any { combined.contains(it) }
    }

    private fun injectCredentials(url: String, user: String, pass: String, at: Boolean): String {
        if (user.isBlank()) return url
        return if (at) {
            if (url.contains("@")) return url
            val scheme = when {
                url.startsWith("rtsp://")  -> "rtsp://"
                url.startsWith("rtsps://") -> "rtsps://"
                else -> return url
            }
            "$scheme$user:$pass@${url.removePrefix(scheme)}"
        } else {
            val separator = if (url.contains("?")) "&" else "?"
            "${url}${separator}user=$user&password=$pass"
        }
    }

    private fun sanitiseUrl(url: String) = url
        .replace(Regex("(?<=:)[^:@/]+(?=@)"), "***")
        .replace(Regex("(?<=[?&](password)=)[^&/]+"), "***")
}
