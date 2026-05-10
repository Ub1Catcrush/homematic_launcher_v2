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
 * CameraViewController — manages RTSP (ExoPlayer) + MJPEG-snapshot fallback.
 *
 * ── Strategy ─────────────────────────────────────────────────────────────────
 * 1. Start ExoPlayer with the configured RTSP URL.
 * 2. If RTSP_TIMEOUT_MS elapses without a successful first frame, or if ExoPlayer
 *    fires a PlaybackException, switch to MJPEG-snapshot mode automatically.
 * 3. In snapshot mode: poll CAMERA_SNAPSHOT_URL every CAMERA_SNAPSHOT_INTERVAL
 *    seconds and display the JPEG in an ImageView.
 * 4. On every new Activity start, try RTSP again (network may have recovered).
 *
 * ── Lifecycle ────────────────────────────────────────────────────────────────
 * Attach to the Activity's lifecycle via attachToLifecycle() so the controller
 * automatically pauses/resumes with the Activity.
 *
 * ── Usage ────────────────────────────────────────────────────────────────────
 *   val cameraCtrl = CameraViewController(context, playerView, snapshotView, statusLabel)
 *   cameraCtrl.attachToLifecycle(this)   // in onCreate
 *   cameraCtrl.start()                   // after prefs are confirmed enabled
 */
@OptIn(UnstableApi::class)
class CameraViewController(
    private val context: Context,
    private val playerView:    PlayerView,
    private val vlcLayout:     VLCVideoLayout,
    private val snapshotView:  ImageView,
    private val statusLabel:   TextView,
    private val muteButton:    android.widget.ImageButton? = null
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "CameraVC"
        private const val DEFAULT_RTSP_TIMEOUT_MS  = 8_000L
        private const val DEFAULT_SNAPSHOT_INTERVAL = 5
    }

    private val prefs     = PreferenceManager.getDefaultSharedPreferences(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ioScope   = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Optional callback fired on the main thread whenever motion is detected.
     * Wire this in MainActivity to drive [ScreenWakeController].
     */
    var onMotionDetected: (() -> Unit)? = null

    private val motionEngine = MotionDetectionEngine(
        sensitivityPct   = prefs.getString(PreferenceKeys.MOTION_DETECT_SENSITIVITY, "8")?.toIntOrNull() ?: 8,
        onMotionDetected = {
            mainHandler.post { onMotionDetected?.invoke() }
        }
    )

    private var exoPlayer:       ExoPlayer? = null
    private var snapshotJob:     Job?        = null
    private var rtspSampleJob:   Job?        = null   // periodic frame grab for motion (RTSP mode)
    private var rtspTimeoutJob:  Runnable?   = null
    private var inSnapshotMode  = false
    private var started         = false
    private var rtspFailReason: String? = null
    private var isMuted         = false
    /**
     * Set to true when RTSP fails with an unrecoverable SDP/codec error
     * (e.g. "missing attribute fmtp"). In that case we skip RTSP entirely
     * for the rest of this session and go straight to snapshot.
     * Reset when the user explicitly changes camera settings.
     */
    private var rtspPermanentlyFailed = false

    private enum class EngineChoice { EXO, VLC, SNAPSHOT }
    private var nextEngine = EngineChoice.EXO
    private var activeVlcEngine: VlcRtspEngine? = null

    // ── Mute toggle (public so MainActivity can wire the button if needed) ────
    fun toggleMute() {
        isMuted = !isMuted
        exoPlayer?.volume = if (isMuted) 0f else 1f
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
        // Recreate scope if cancelled by a previous onDestroy (e.g. config change)
        if (!ioScope.isActive) ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        show()
        started = true
        inSnapshotMode = false
        rtspFailReason = null
        applyMotionPrefs()
        nextEngine = when (prefs.getString(PreferenceKeys.CAMERA_RTSP_ENGINE, "auto")) {
            "vlc"      -> EngineChoice.VLC
            "snapshot" -> EngineChoice.SNAPSHOT
            else       -> if (rtspPermanentlyFailed) EngineChoice.VLC else EngineChoice.EXO
        }
        startWithEngine(nextEngine)
    }

    @MainThread
    fun stop() {
        started = false
        cancelRtspTimeout()
        stopSnapshotLoop()
        stopRtspMotionSampler()
        releasePlayer()
        motionEngine.enabled = false
        motionEngine.reset()
    }

    fun applyPrefsChange() {
        if (!started) return
        rtspPermanentlyFailed = false
        nextEngine = EngineChoice.EXO
        stop()
        start()
    }

    /** Update motion engine from current prefs without restarting the stream. */
    fun applyMotionPrefs() {
        val sensKey = if (prefs.contains(PreferenceKeys.MOTION_WEBCAM_SENSITIVITY))
            PreferenceKeys.MOTION_WEBCAM_SENSITIVITY else PreferenceKeys.MOTION_DETECT_SENSITIVITY
        val enabledKey = if (prefs.contains(PreferenceKeys.MOTION_WEBCAM_ENABLED))
            PreferenceKeys.MOTION_WEBCAM_ENABLED else PreferenceKeys.MOTION_DETECT_ENABLED
        MotionPrefsHelper.applyTo(motionEngine, prefs, sensKey, enabledKey)
    }

    // ── Lifecycle observer ────────────────────────────────────────────────────

    override fun onStart(owner: LifecycleOwner) { if (isEnabled()) start() }
    override fun onStop(owner: LifecycleOwner)  { stop() }

    override fun onDestroy(owner: LifecycleOwner) {
        ioScope.cancel()
        stop()
    }

    // ── RTSP ──────────────────────────────────────────────────────────────────

    private fun startWithEngine(choice: EngineChoice) {
        if (!started) return
        when (choice) {
            EngineChoice.EXO      -> startRtsp()
            EngineChoice.VLC      -> startVlc()
            EngineChoice.SNAPSHOT -> fallbackToSnapshot(rtspFailReason ?: "No RTSP engine succeeded")
        }
    }

    @OptIn(UnstableApi::class)
    private fun startRtsp() {
        val rawUrl  = prefs.getString(PreferenceKeys.CAMERA_RTSP_URL, "") ?: ""
        if (rawUrl.isBlank()) { fallbackToSnapshot("No RTSP URL configured"); return }

        val rtspUrl = injectCredentials(
            rawUrl,
            user = prefs.getString(PreferenceKeys.CAMERA_USERNAME, "") ?: "",
            pass = prefs.getString(PreferenceKeys.CAMERA_PASSWORD, "") ?: "",
            at = true
        )

        Log.i(TAG, "Starting RTSP: ${sanitiseUrl(rtspUrl)}")
        setStatus(context.getString(R.string.camera_status_connecting))

        releasePlayer()

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            // OFF avoids blocking codec scan on main thread (prevents ANR)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .build()
            .also { exoPlayer = it }

        playerView.player = player
        playerView.visibility = View.VISIBLE
        snapshotView.visibility = View.GONE

        val source = RtspMediaSource.Factory()
            // TCP tunneling is more robust than UDP for cameras that omit optional
            // SDP attributes like "fmtp" — ExoPlayer's RTSP parser throws
            // IllegalArgumentException("missing attribute fmtp") for some H.264
            // Baseline streams over UDP. TCP also handles NAT traversal better.
            .setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(rtspUrl.toUri()))

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        cancelRtspTimeout()
                        setStatus(context.getString(R.string.camera_status_live_rtsp))
                        Log.i(TAG, "RTSP playing")
                        startRtspMotionSampler()
                    }
                    Player.STATE_BUFFERING ->
                        setStatus(context.getString(R.string.camera_status_buffering))
                    Player.STATE_ENDED ->
                        mainHandler.post { if (started && !inSnapshotMode) fallbackToSnapshot("RTSP stream ended") }
                    else -> { /* IDLE — ignore */ }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                val msg   = error.message ?: cause?.message ?: "RTSP error"
                Log.w(TAG, "RTSP player error: $msg", error)

                // Detect unrecoverable SDP/codec errors that will fail on every retry.
                // "missing attribute fmtp" means the camera's SDP is incompatible with
                // ExoPlayer's strict parser — retrying is pointless.
                val isUnrecoverable = cause?.message?.contains("missing attribute", ignoreCase = true) == true
                    || cause?.message?.contains("fmtp", ignoreCase = true) == true
                    || cause?.message?.contains("IllegalArgumentException", ignoreCase = true) == true
                    || (cause?.cause?.message?.contains("missing attribute", ignoreCase = true) == true)
                if (isUnrecoverable) {
                    Log.e(TAG, "Unrecoverable RTSP SDP error — disabling RTSP for this session")
                    rtspPermanentlyFailed = true
                }

                // Post to next Looper cycle — escalate to VLC, never call release() inside Player callback
                mainHandler.post {
                    if (!started || inSnapshotMode) return@post
                    rtspFailReason = msg
                    nextEngine = EngineChoice.VLC
                    try { startWithEngine(EngineChoice.VLC) } catch (t: Throwable) {
                        Log.e(TAG, "startVlc threw: ${t.message}", t)
                        fallbackToSnapshot(msg)
                    }
                }
            }
            // Hide the PlayerView once we get the first rendered frame
            override fun onRenderedFirstFrame() {
                cancelRtspTimeout()
                playerView.visibility = View.VISIBLE
                setStatus(context.getString(R.string.camera_status_live_rtsp))
            }
        })

        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
        player.volume = if (isMuted) 0f else 1f   // restore mute state across reconnects

        muteButton?.visibility = View.VISIBLE
        updateMuteButton()

        // Schedule timeout — if no first frame within limit, fall back
        val timeoutMs = prefs.getString(PreferenceKeys.CAMERA_RTSP_TIMEOUT_MS, "")
            ?.toLongOrNull() ?: DEFAULT_RTSP_TIMEOUT_MS
        rtspTimeoutJob = Runnable {
            Log.w(TAG, "RTSP timeout after ${timeoutMs}ms — trying libVLC")
            rtspFailReason = context.getString(R.string.camera_status_rtsp_timeout)
            nextEngine = EngineChoice.VLC
            if (started && !inSnapshotMode) startWithEngine(EngineChoice.VLC)
        }
        mainHandler.postDelayed(rtspTimeoutJob!!, timeoutMs)
    }

    // ── Snapshot fallback ─────────────────────────────────────────────────────

    @MainThread
    private fun fallbackToSnapshot(reason: String) {
        if (inSnapshotMode) return
        inSnapshotMode = true
        rtspFailReason = reason
        Log.i(TAG, "Falling back to MJPEG snapshots: $reason")

        stopRtspMotionSampler()
        releasePlayer()
        playerView.visibility  = View.GONE
        (vlcLayout.parent as? android.view.View)?.visibility = View.GONE
        snapshotView.visibility = View.VISIBLE
        muteButton?.visibility = View.GONE   // no audio in snapshot mode
        // Show RTSP failure reason prominently — snapshot working doesn't hide the error
        setStatus(context.getString(R.string.camera_status_rtsp_failed_snapshot_ok, reason))

        val snapshotUrl = prefs.getString(PreferenceKeys.CAMERA_SNAPSHOT_URL, "") ?: ""
        if (snapshotUrl.isBlank()) {
            setStatus(context.getString(R.string.camera_status_no_snapshot_url))
            Log.w(TAG, "No snapshot URL configured — cannot show fallback")
            return
        }

        val intervalSec = prefs.getString(PreferenceKeys.CAMERA_SNAPSHOT_INTERVAL, "")
            ?.toIntOrNull() ?: DEFAULT_SNAPSHOT_INTERVAL

        startSnapshotLoop(snapshotUrl, intervalSec)
    }

    private fun startVlc() {
        if (!started) return
        val rawUrl = prefs.getString(PreferenceKeys.CAMERA_RTSP_URL, "") ?: ""
        if (rawUrl.isBlank()) { fallbackToSnapshot("No RTSP URL"); return }
        val url = injectCredentials(rawUrl,
            user = prefs.getString(PreferenceKeys.CAMERA_USERNAME, "") ?: "",
            pass = prefs.getString(PreferenceKeys.CAMERA_PASSWORD, "") ?: "",
            at   = true)
        Log.i(TAG, "Trying libVLC RTSP")
        setStatus(context.getString(R.string.camera_status_connecting_vlc))
        releasePlayer()
        playerView.visibility   = View.GONE
        (vlcLayout.parent as? android.view.View)?.visibility = View.VISIBLE
        snapshotView.visibility = View.GONE
        val vlcEngine = VlcRtspEngine(context, vlcLayout)
        activeVlcEngine = vlcEngine
        val timeoutMs = prefs.getString(PreferenceKeys.CAMERA_RTSP_TIMEOUT_MS, "")
            ?.toLongOrNull() ?: DEFAULT_RTSP_TIMEOUT_MS
        rtspTimeoutJob = Runnable {
            Log.w(TAG, "VLC timeout — falling back to snapshot")
            if (started && !inSnapshotMode) fallbackToSnapshot(rtspFailReason ?: "VLC timeout")
        }
        mainHandler.postDelayed(rtspTimeoutJob!!, timeoutMs)
        vlcEngine.start(url, isMuted, object : RtspEngine.Listener {
            override fun onPlaying() {
                cancelRtspTimeout()
                setStatus(context.getString(R.string.camera_status_live_vlc))
                muteButton?.visibility = View.VISIBLE
                updateMuteButton()
                startVlcMotionSamplerIfEnabled()
            }
            override fun onError(message: String, unrecoverable: Boolean) {
                Log.w(TAG, "VLC error: $message")
                cancelRtspTimeout()
                rtspFailReason = message
                if (started && !inSnapshotMode) mainHandler.post { fallbackToSnapshot(message) }
            }
            override fun onEnded() {
                mainHandler.post { if (started && !inSnapshotMode) fallbackToSnapshot("VLC stream ended") }
            }
        })
    }

    private fun startVlcMotionSamplerIfEnabled() {
        if (!motionEngine.enabled) return
        val snapshotUrl = prefs.getString(PreferenceKeys.CAMERA_SNAPSHOT_URL, "") ?: ""
        if (snapshotUrl.isBlank()) return
        val intervalSec = prefs.getString(PreferenceKeys.CAMERA_SNAPSHOT_INTERVAL, "")
            ?.toIntOrNull() ?: DEFAULT_SNAPSHOT_INTERVAL
        stopRtspMotionSampler()
        rtspSampleJob = ioScope.launch {
            val url = injectCredentials(snapshotUrl,
                user = prefs.getString(PreferenceKeys.CAMERA_USERNAME, "") ?: "",
                pass = prefs.getString(PreferenceKeys.CAMERA_PASSWORD, "") ?: "",
                at = false)
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

    private fun startSnapshotLoop(url: String, intervalSec: Int) {
        stopSnapshotLoop()
        snapshotJob = ioScope.launch {
            val snapshotUrl = injectCredentials(
                url,
                user = prefs.getString(PreferenceKeys.CAMERA_USERNAME, "") ?: "",
                pass = prefs.getString(PreferenceKeys.CAMERA_PASSWORD, "") ?: "",
                at = false
            )

            while (isActive) {
                try {
                    val bmp = fetchSnapshot(snapshotUrl)
                    motionEngine.process(bmp)   // analyse before displaying
                    withContext(Dispatchers.Main) {
                        snapshotView.setImageBitmap(bmp)
                        // Always show RTSP failure reason so user knows live stream is not active
                        val rtspFail = rtspFailReason
                        if (rtspFail != null)
                            setStatus(context.getString(R.string.camera_status_rtsp_failed_snapshot_ok, rtspFail))
                        else
                            setStatus(context.getString(R.string.camera_status_snapshot_mode))
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e   // propagate coroutine cancellation
                } catch (e: Exception) {
                    Log.w(TAG, "Snapshot fetch failed: ${e.message}")
                    withContext(Dispatchers.Main) {
                        val rtspFail = rtspFailReason
                        if (rtspFail != null)
                            setStatus(context.getString(R.string.camera_status_rtsp_and_snapshot_error,
                                rtspFail, e.message ?: "?"))
                        else
                            setStatus(context.getString(R.string.camera_status_snapshot_error, e.message ?: "?"))
                    }
                }
                delay(intervalSec * 1000L)
            }
        }
    }

    private fun fetchSnapshot(url: String): Bitmap {
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout    = 8_000
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

    // ── RTSP motion sampling (PixelCopy, every 2 s) ─────────────────────────

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

    private fun stopRtspMotionSampler() {
        rtspSampleJob?.cancel()
        rtspSampleJob = null
    }

    /**
     * Grabs a single frame from the RTSP PlayerView using PixelCopy (API 26+).
     * Falls back to View.drawToBitmap on older APIs (which works for TextureView).
     * Must post to main thread for PixelCopy, then process on the caller thread.
     */
    private suspend fun grabRtspFrame() {
        if (!started || inSnapshotMode) return
        val sv = playerView.videoSurfaceView ?: return

        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            mainHandler.post {
                try {
                    if (sv.width <= 0 || sv.height <= 0) { cont.resumeWith(Result.success(Unit)) ; return@post }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && sv is SurfaceView) {
                        val bmp = android.graphics.Bitmap.createBitmap(
                            sv.width, sv.height, android.graphics.Bitmap.Config.ARGB_8888)
                        PixelCopy.request(sv, bmp, { result ->
                            if (result == PixelCopy.SUCCESS) {
                                motionEngine.process(bmp)
                            }
                            bmp.recycle()
                            if (cont.isActive) cont.resumeWith(Result.success(Unit))
                        }, mainHandler)
                    } else {
                        // TextureView fallback — draw() works correctly here
                        val bmp = android.graphics.Bitmap.createBitmap(
                            sv.width, sv.height, android.graphics.Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bmp)
                        sv.draw(canvas)
                        motionEngine.process(bmp)
                        bmp.recycle()
                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "grabRtspFrame failed: ${e.message}")
                    if (cont.isActive) cont.resumeWith(Result.success(Unit))
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun releasePlayer() {
        exoPlayer?.let { it.stop(); it.release() }
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
    private fun setStatus(msg: String) { statusLabel.text = msg }

    private fun show() {
        playerView.visibility   = View.VISIBLE
        (vlcLayout.parent as? android.view.View)?.visibility = View.GONE
        snapshotView.visibility = View.GONE
        statusLabel.visibility  = View.VISIBLE
        applyOverlayAlpha()
        applyScaleType()
    }

    /** Applies the configured scale type to both the snapshot ImageView and the ExoPlayer resize mode. */
    @OptIn(UnstableApi::class)
    fun applyScaleType() {
        val scaleKey = prefs.getString(PreferenceKeys.CAMERA_SCALE_TYPE, "center_crop") ?: "center_crop"
        // ImageView scale type for MJPEG snapshot
        snapshotView.scaleType = when (scaleKey) {
            "fit_center"    -> ImageView.ScaleType.FIT_CENTER
            "center_inside" -> ImageView.ScaleType.CENTER_INSIDE
            "fit_xy"        -> ImageView.ScaleType.FIT_XY
            else            -> ImageView.ScaleType.CENTER_CROP   // "center_crop" default
        }
        // ExoPlayer resize mode for RTSP
        val exoMode = when (scaleKey) {
            "fit_center"    -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            "center_inside" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            "fit_xy"        -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            else            -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM  // center_crop
        }
        playerView.resizeMode = exoMode
    }

    /** Reads the configured alpha (0–100) and applies it to the status overlay strip. */
    fun applyOverlayAlpha() {
        val alphaPct = prefs.getString(PreferenceKeys.CAMERA_OVERLAY_ALPHA, "60")
                           ?.toIntOrNull()?.coerceIn(0, 100) ?: 60
        // alpha 0–100 % → background alpha 0–255
        val bgAlpha  = (alphaPct / 100f * 255).toInt()

        // Find the status row by ID — it has the semi-transparent background
        val statusRow = (statusLabel.parent as? android.view.View)
        statusRow?.background?.mutate()?.alpha = bgAlpha
        // Also dim the mute button to match
        muteButton?.alpha = alphaPct / 100f
    }

    private fun hide() {
        playerView.visibility   = View.GONE
        (vlcLayout.parent as? android.view.View)?.visibility = View.GONE
        vlcLayout.visibility    = View.GONE   // also hide VLC itself
        snapshotView.visibility = View.GONE
        statusLabel.visibility  = View.GONE
        muteButton?.visibility  = View.GONE
    }

    fun isEnabled() = prefs.getBoolean(PreferenceKeys.CAMERA_ENABLED, false)

    /**
     * Injects user:pass into rtsp:// URL if not already present.
     * rtsp://192.168.x.x/... → rtsp://user:pass@192.168.x.x/...
     */
    private fun injectCredentials(url: String, user: String, pass: String, at: Boolean): String {
        if (user.isBlank()) return url

        return if (at) {
            if (url.contains("@")) return url // Bereits Zugangsdaten vorhanden

            val scheme = when {
                url.startsWith("rtsp://")  -> "rtsp://"
                url.startsWith("rtsps://") -> "rtsps://"
                else -> return url
            }
            "$scheme$user:$pass@${url.removePrefix(scheme)}"
        } else {
            // Prüft, ob Parameter mit ? oder & angehängt werden müssen
            val separator = if (url.contains("?")) "&" else "?"
            "${url}${separator}user=$user&password=$pass"
        }
    }

    /** Remove password from URL for logging. */
    private fun sanitiseUrl(url: String): String {
        return url
            // Maskiert :passwort@
            .replace(Regex("(?<=:)[^:@/]+(?=@)"), "***")
            // Maskiert password=...
            .replace(Regex("(?<=[?&](password)=)[^&/]+"), "***")
    }
}
