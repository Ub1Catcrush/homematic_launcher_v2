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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import org.videolan.libvlc.util.VLCVideoLayout
import android.os.Build
import android.view.PixelCopy
import android.view.SurfaceView

/**
 * CameraViewController — manages RTSP (Media3/ExoPlayer) + VLC + MJPEG-snapshot fallback.
 *
 * ── Failover order ────────────────────────────────────────────────────────────
 * 1. Media3 (ExoPlayer RTSP) — primary engine.
 * 2. libVLC RTSP              — fallback if Media3 fails.
 * 3. MJPEG snapshot polling   — last resort if both RTSP engines fail.
 *
 * If a running engine fails (after previously being live), the sequence always
 * restarts from Media3 (step 1), not from the engine that failed. This way a
 * temporary network blip that drops VLC doesn't strand the stream on MJPEG
 * when Media3 might now work fine.
 *
 * ── Background recovery probes ────────────────────────────────────────────────
 * While the stream is running on a fallback engine, invisible background probes
 * silently try to establish a better engine:
 *
 *   • On libVLC: probe Media3 every PROBE_MEDIA3_FROM_VLC_MS  (15 min).
 *   • On MJPEG:  probe libVLC   every PROBE_VLC_FROM_MJPEG_MS  (1 min).
 *                probe Media3   every PROBE_MEDIA3_FROM_MJPEG_MS (15 min).
 *
 * Probes are fully invisible — they create a detached (off-screen) engine
 * instance and discard it unless the first frame arrives, in which case the
 * controller seamlessly switches to it.
 *
 * ── Background buffering (multi-camera) ───────────────────────────────────────
 * All camera slots are started immediately and kept streaming in the background
 * even when not visible. Switching cameras is therefore instant — the background
 * slot is already live. The slot is kept INVISIBLE (not GONE) so ExoPlayer /
 * VLC can render to its Surface while hidden.
 *
 * ── Tap-to-reload ─────────────────────────────────────────────────────────────
 * A tap on the camera area always does a full reset and retries from Media3.
 *
 * ── Lifecycle ─────────────────────────────────────────────────────────────────
 * Attach to the Activity's lifecycle via attachToLifecycle().
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
        private const val DEFAULT_RTSP_TIMEOUT_MS  = 3_000L   // LAN-optimised (war 8_000)
        private const val DEFAULT_SNAPSHOT_INTERVAL = 5

        /** How many reconnect attempts before escalating to the next engine. */
        private const val MAX_RETRIES = 3

        /** Base delay for exponential back-off: 5 s, 10 s, 20 s. */
        private const val RETRY_BASE_MS = 5_000L

        // ── Background probe intervals ────────────────────────────────────────
        /** While on VLC: try Media3 silently this often. */
        private const val PROBE_MEDIA3_FROM_VLC_MS   = 15 * 60 * 1_000L   // 15 min
        /** While on MJPEG: try VLC silently this often. */
        private const val PROBE_VLC_FROM_MJPEG_MS    =      60 * 1_000L   // 1 min
        /** While on MJPEG: try Media3 silently this often. */
        private const val PROBE_MEDIA3_FROM_MJPEG_MS = 15 * 60 * 1_000L   // 15 min

        /**
         * Error substrings that indicate ExoPlayer cannot handle this camera's
         * RTSP/SDP — skip straight to VLC for the rest of the session.
         * Forced reload (applyPrefsChange) always resets and retries EXO first.
         */
        private val EXO_SESSION_SKIP_HINTS = listOf(
            "missing attribute fmtp",
            "missing attribute",
            "unsupported sdp media type",
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

    /**
     * Fired exactly once per [start] call, on the main thread, when this slot's
     * engine renders its first frame (ExoPlayer: onRenderedFirstFrame; VLC: Vout event).
     * MultiCameraController uses this to decide which slot to show first on startup.
     */
    var onFirstLive: (() -> Unit)? = null
    private var firstLiveFired = false

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
     * True when ExoPlayer has failed with a structural SDP error this session.
     * VLC is used directly until the next forced reload.
     */
    private var exoSkippedForSession  = false

    /**
     * Incremented every time a new engine is started.
     * Callbacks capture their generation at creation time and ignore stale events.
     */
    private var engineGeneration      = 0

    private enum class EngineChoice { EXO, VLC, SNAPSHOT }
    private var nextEngine      = EngineChoice.EXO
    private var activeVlcEngine: VlcRtspEngine? = null

    // ── Background probe state ────────────────────────────────────────────────

    /** Probe engine running in background — discarded unless first frame arrives. */
    private var probeExoPlayer:   ExoPlayer?     = null
    private var probeVlcEngine:   VlcRtspEngine? = null
    private var probeSchedule:    Runnable?      = null
    private var probeVlcSchedule: Runnable?      = null

    // ── Mute ──────────────────────────────────────────────────────────────────

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

    // ── Public API ─────────────────────────────────────────────────────────────

    fun attachToLifecycle(owner: LifecycleOwner) {
        owner.lifecycle.addObserver(this)
    }

    @MainThread
    fun start() {
        if (!isEnabled()) { hide(); return }
        if (!ioScope.isActive) ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        show()
        started         = true
        inSnapshotMode  = false
        rtspFailReason  = null
        retryCount      = 0
        firstLiveFired  = false
        applyMotionPrefs()
        nextEngine = firstEnabledEngine()
        startWithEngine(nextEngine)
        installTapReload()
    }

    @MainThread
    fun stop() {
        started              = false
        exoSkippedForSession = false   // always retry EXO on next start
        engineGeneration++
        cancelRtspTimeout()
        cancelRetry()
        cancelAllProbes()
        stopSnapshotLoop()
        stopRtspMotionSampler()
        releasePlayer()
        motionEngine.enabled = false
        motionEngine.reset()
    }

    fun applyPrefsChange() {
        if (!started) return
        exoSkippedForSession = false
        retryCount = 0
        stop()
        start()
    }

    /**
     * Clears [exoSkippedForSession] so the engine stack restarts from the
     * top on the next attempt. Call this from the dedicated "Reset player"
     * button in the UI.
     */
    fun resetEngineSkip() {
        exoSkippedForSession = false
        if (started) applyPrefsChange()
    }

    /**
     * Returns the first enabled engine from the configured stack, respecting
     * [exoSkippedForSession] to skip EXO if it has failed with an SDP error.
     */
    private fun firstEnabledEngine(): EngineChoice {
        val engines = cfgEnabledEngines()
        val start = if (exoSkippedForSession)
            engines.firstOrNull { it != "exo" } ?: engines.firstOrNull()
        else
            engines.firstOrNull()
        return when (start) {
            "vlc"      -> EngineChoice.VLC
            "snapshot" -> EngineChoice.SNAPSHOT
            "exo"      -> EngineChoice.EXO
            else       -> EngineChoice.SNAPSHOT
        }
    }

    /**
     * Returns the next engine to try after [current] fails, according to the
     * configured stack. Returns SNAPSHOT (i.e. last resort) if there is no
     * further engine.
     */
    private fun nextEngineAfter(current: EngineChoice): EngineChoice {
        val engines = cfgEnabledEngines()
        val currentName = when (current) {
            EngineChoice.EXO      -> "exo"
            EngineChoice.VLC      -> "vlc"
            EngineChoice.SNAPSHOT -> "snapshot"
        }
        val idx = engines.indexOf(currentName)
        val next = if (idx >= 0 && idx + 1 < engines.size) engines[idx + 1] else null
        return when (next) {
            "vlc"      -> EngineChoice.VLC
            "snapshot" -> EngineChoice.SNAPSHOT
            "exo"      -> EngineChoice.EXO
            else       -> EngineChoice.SNAPSHOT
        }
    }

    fun applyMotionPrefs() {
        val sensKey    = if (prefs.contains(PreferenceKeys.MOTION_WEBCAM_SENSITIVITY))
            PreferenceKeys.MOTION_WEBCAM_SENSITIVITY else PreferenceKeys.MOTION_DETECT_SENSITIVITY
        val enabledKey = if (prefs.contains(PreferenceKeys.MOTION_WEBCAM_ENABLED))
            PreferenceKeys.MOTION_WEBCAM_ENABLED else PreferenceKeys.MOTION_DETECT_ENABLED
        MotionPrefsHelper.applyTo(motionEngine, prefs, sensKey, enabledKey)
    }

    /**
     * Re-publishes the controller's current status string to the shared label
     * and restores correct view visibility for this slot when it becomes active.
     * Called by MultiCameraController when this slot becomes the active one.
     */
    fun refreshStatus() {
        if (!isActiveSlot) return
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
                playerView.visibility                          = View.VISIBLE
                (vlcLayout.parent as? View)?.visibility        = View.GONE
                snapshotView.visibility                        = View.GONE
            }
        }
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

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onStart(owner: LifecycleOwner) { if (isEnabled()) start() }
    override fun onStop(owner: LifecycleOwner)  { stop() }
    override fun onDestroy(owner: LifecycleOwner) { ioScope.cancel(); stop() }

    // ── Engine dispatch ────────────────────────────────────────────────────────

    private fun startWithEngine(choice: EngineChoice) {
        if (!started) return
        when (choice) {
            EngineChoice.EXO      -> startRtsp()
            EngineChoice.VLC      -> startVlc()
            EngineChoice.SNAPSHOT -> fallbackToSnapshot(rtspFailReason ?: "No RTSP engine succeeded")
        }
    }

    // ── Media3 / ExoPlayer RTSP ────────────────────────────────────────────────

    /** Dummy surface used while this slot's container is GONE (background decoding). */
    private var dummySurfaceTexture: android.graphics.SurfaceTexture? = null
    private var dummySurface:        android.view.Surface?            = null

    /**
     * Attach ExoPlayer to the real [playerView] when this slot becomes active.
     * Also starts the connect-timeout if the player hasn't reached STATE_READY yet.
     */
    @MainThread
    fun attachExoToPlayerView() {
        val player = exoPlayer ?: return
        releaseDummySurface()
        playerView.player       = player
        playerView.visibility   = View.VISIBLE
        (vlcLayout.parent as? View)?.visibility = View.GONE
        snapshotView.visibility = View.GONE
        Log.d(TAG, "ExoPlayer attached to PlayerView")

        // Start timeout now if not yet ready (STATE_READY or first frame not yet seen).
        if (rtspTimeoutJob == null &&
            player.playbackState != androidx.media3.common.Player.STATE_READY &&
            player.playbackState != androidx.media3.common.Player.STATE_ENDED) {
            val timeoutMs = cfgTimeoutMs()
            val gen       = engineGeneration
            rtspTimeoutJob = Runnable {
                if (engineGeneration != gen) return@Runnable
                Log.w(TAG, "Media3 timeout (on activate) after ${timeoutMs}ms gen=$gen")
                rtspFailReason = context.getString(R.string.camera_status_rtsp_timeout)
                if (started && !inSnapshotMode) scheduleRetry(rtspFailReason!!, EngineChoice.VLC)
            }
            mainHandler.postDelayed(rtspTimeoutJob!!, timeoutMs)
        }
    }

    /**
     * Detach ExoPlayer from [playerView] and redirect output to a dummy
     * SurfaceTexture so the player can keep decoding while the container is GONE.
     */
    @MainThread
    fun detachExoToBackground() {
        val player = exoPlayer ?: return
        playerView.player = null
        releaseDummySurface()
        try {
            val st = android.graphics.SurfaceTexture(false).also { dummySurfaceTexture = it }
            val s  = android.view.Surface(st).also { dummySurface = it }
            player.setVideoSurface(s)
            Log.d(TAG, "ExoPlayer detached to dummy surface (still decoding)")
        } catch (e: Exception) {
            Log.w(TAG, "detachExoToBackground: ${e.message}")
        }
    }

    private fun releaseDummySurface() {
        try { dummySurface?.release() }        catch (_: Exception) {}
        try { dummySurfaceTexture?.release() } catch (_: Exception) {}
        dummySurface        = null
        dummySurfaceTexture = null
    }

    @OptIn(UnstableApi::class)
    private fun startRtsp() {
        val rawUrl = cfgRtspUrl()
        if (rawUrl.isBlank()) { fallbackToSnapshot("No RTSP URL configured"); return }

        val rtspUrl = injectCredentials(rawUrl,
            user = cfgUsername(),
            pass = cfgPassword(),
            at   = true)

        cancelRtspTimeout()
        val myGen = ++engineGeneration
        Log.i(TAG, "Starting Media3 RTSP gen=$myGen (retry=$retryCount): ${sanitiseUrl(rtspUrl)}")
        setStatus(context.getString(R.string.camera_status_connecting))
        releasePlayer()

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        // LAN-optimised buffer: short min/max to reduce initial buffering time
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                500,    // minBufferMs      (default: 50 000)
                2_000,  // maxBufferMs      (default: 50 000)
                200,    // bufferForPlaybackMs
                500     // bufferForPlaybackAfterRebufferMs
            )
            .build()

        val player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .build()
            .also { exoPlayer = it }

        // If this slot is currently active (container VISIBLE), attach to the
        // real PlayerView immediately. Otherwise decode into a dummy surface so
        // the player buffers while the container stays GONE.
        if (isActiveSlot) {
            playerView.player = player
            playerView.visibility   = View.VISIBLE
            snapshotView.visibility = View.GONE
            (vlcLayout.parent as? View)?.visibility = View.GONE
        } else {
            // Background slot — keep container GONE, decode into dummy surface.
            try {
                val st = android.graphics.SurfaceTexture(false).also { dummySurfaceTexture = it }
                val s  = android.view.Surface(st).also { dummySurface = it }
                player.setVideoSurface(s)
            } catch (e: Exception) {
                Log.w(TAG, "startRtsp: dummy surface failed, falling back to PlayerView: ${e.message}")
                playerView.player = player
            }
        }

        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(rtspUrl.toUri()))

        val listenerGen = myGen
        player.addListener(object : Player.Listener {
            private fun stale() = engineGeneration != listenerGen
            override fun onPlaybackStateChanged(state: Int) {
                if (stale()) return
                when (state) {
                    Player.STATE_BUFFERING ->
                        setStatus(context.getString(R.string.camera_status_buffering))
                    Player.STATE_READY -> {
                        // STATE_READY fires reliably even when decoding into a dummy
                        // surface (background slot). Use it as the "connected" signal.
                        if (!isActiveSlot) {
                            // Background slot is ready — cancel timeout, signal first-live.
                            cancelRtspTimeout()
                            retryCount = 0
                            Log.i(TAG, "Media3 STATE_READY (background slot) gen=$listenerGen")
                            fireFirstLive()
                        }
                        // For active slots, wait for onRenderedFirstFrame (actual frame shown).
                    }
                    Player.STATE_ENDED ->
                        mainHandler.post {
                            if (started && !inSnapshotMode)
                                scheduleRetry("RTSP stream ended", EngineChoice.EXO)
                        }
                    else -> {}
                }
            }

            override fun onRenderedFirstFrame() {
                if (stale()) return
                cancelRtspTimeout()
                retryCount = 0
                playerView.visibility = View.VISIBLE
                setStatus(context.getString(R.string.camera_status_live_rtsp))
                Log.i(TAG, "Media3: first frame rendered gen=$listenerGen")
                startRtspMotionSampler()
                fireFirstLive()   // no-op if already fired via STATE_READY
            }

            override fun onPlayerError(error: PlaybackException) {
                if (stale()) return
                val cause    = error.cause
                val msg      = error.message ?: cause?.message ?: "RTSP error"
                val combined = listOfNotNull(msg, cause?.message, cause?.cause?.message)
                    .joinToString(" ").lowercase()
                val isSessionSkip = EXO_SESSION_SKIP_HINTS.any { combined.contains(it) }
                Log.w(TAG, "Media3 error (sessionSkip=$isSessionSkip): $msg")

                if (isSessionSkip) {
                    exoSkippedForSession = true
                    Log.i(TAG, "EXO_SESSION_SKIP: switching to VLC for this session")
                }

                mainHandler.post {
                    if (!started || inSnapshotMode) return@post
                    rtspFailReason = msg
                    if (isSessionSkip) {
                        cancelRtspTimeout()
                        cancelRetry()
                        releasePlayer()
                        startWithEngine(EngineChoice.VLC)
                    } else {
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

        // Only run the connect-timeout when this slot is visible. Background slots
        // use STATE_READY (fired without a display surface) as their ready signal.
        if (isActiveSlot) {
            val timeoutMs = cfgTimeoutMs()
            val exoGen    = myGen
            rtspTimeoutJob = Runnable {
                if (engineGeneration != exoGen) return@Runnable
                Log.w(TAG, "Media3 timeout after ${timeoutMs}ms (gen=$exoGen)")
                rtspFailReason = context.getString(R.string.camera_status_rtsp_timeout)
                if (started && !inSnapshotMode) scheduleRetry(rtspFailReason!!, EngineChoice.VLC)
            }
            mainHandler.postDelayed(rtspTimeoutJob!!, timeoutMs)
        }
    }

    // ── libVLC RTSP ────────────────────────────────────────────────────────────

    private fun startVlc() {
        if (!started) return
        val rawUrl = cfgRtspUrl()
        if (rawUrl.isBlank()) { fallbackToSnapshot("No RTSP URL"); return }
        val url = injectCredentials(rawUrl,
            user = cfgUsername(),
            pass = cfgPassword(),
            at   = true)

        cancelRtspTimeout()
        val myVlcGen = ++engineGeneration
        Log.i(TAG, "Starting libVLC RTSP gen=$myVlcGen (retry=$retryCount): ${sanitiseUrl(url)}")
        setStatus(context.getString(R.string.camera_status_connecting_vlc))
        releasePlayer()

        // Only manipulate sub-view visibility when this slot is the active one.
        // Background slots have their container GONE — touching sub-view visibility
        // here would have no effect and confuses refreshStatus() later.
        if (isActiveSlot) {
            playerView.visibility                   = View.GONE
            (vlcLayout.parent as? View)?.visibility = View.VISIBLE
            snapshotView.visibility                 = View.GONE
        }

        val vlcEngine = VlcRtspEngine(context, vlcLayout)
        activeVlcEngine = vlcEngine

        // Only start the connect-timeout when this slot is active.
        // Background VLC slots use onPlaying() as their ready signal.
        if (isActiveSlot) {
            val timeoutMs = cfgTimeoutMs()
            val vlcGen    = myVlcGen
            rtspTimeoutJob = Runnable {
                if (engineGeneration != vlcGen) return@Runnable
                Log.w(TAG, "VLC timeout after ${timeoutMs}ms (gen=$vlcGen)")
                if (started && !inSnapshotMode)
                    scheduleRetry("VLC timeout after ${timeoutMs}ms", EngineChoice.VLC)
            }
            mainHandler.postDelayed(rtspTimeoutJob!!, timeoutMs)
        }

        val vlcListenerGen = myVlcGen
        vlcEngine.start(url, isMuted, object : RtspEngine.Listener {
            private fun stale() = engineGeneration != vlcListenerGen
            override fun onPlaying() {
                if (stale()) return
                cancelRtspTimeout()
                retryCount = 0
                setStatus(context.getString(R.string.camera_status_live_vlc))
                muteButton?.visibility = View.VISIBLE
                updateMuteButton()
                startVlcMotionSamplerIfEnabled()
                // Schedule silent background probes trying to recover Media3
                scheduleMedia3ProbeFromVlc()
                fireFirstLive()
            }
            override fun onError(message: String, unrecoverable: Boolean) {
                if (stale()) return
                Log.w(TAG, "VLC error: $message")
                cancelRtspTimeout()
                cancelAllProbes()
                rtspFailReason = message
                if (started && !inSnapshotMode)
                    // VLC failed — restart the whole sequence from Media3
                    mainHandler.post { scheduleRetry(message, EngineChoice.EXO) }
            }
            override fun onEnded() {
                if (stale()) return
                cancelAllProbes()
                // Camera closed connection — restart from Media3
                mainHandler.post {
                    if (started && !inSnapshotMode)
                        scheduleRetry("VLC stream ended", EngineChoice.EXO)
                }
            }
        })
    }

    // ── Retry logic ────────────────────────────────────────────────────────────

    /**
     * Schedule a reconnect attempt with exponential back-off.
     *
     * After [MAX_RETRIES] attempts the engine is escalated:
     *   Media3 → libVLC → MJPEG
     *
     * If the previously *live* engine failed and the sequence has been exhausted,
     * escalation always restarts from Media3 (not from the failed engine) so that
     * a recovered network can re-establish the best available stream.
     */
    private fun scheduleRetry(reason: String, currentEngine: EngineChoice) {
        cancelRtspTimeout()
        cancelRetry()
        releasePlayer()

        retryCount++
        if (retryCount > MAX_RETRIES) {
            Log.w(TAG, "Max retries ($MAX_RETRIES) exceeded for $currentEngine — escalating")
            retryCount = 0
            val nextEsc = nextEngineAfter(currentEngine)
            if (nextEsc == EngineChoice.SNAPSHOT || !cfgEnabledEngines().contains(nextEsc.name.lowercase())) {
                fallbackToSnapshot(reason)
            } else {
                nextEngine = nextEsc
                startWithEngine(nextEsc)
            }
            return
        }

        val delayMs = RETRY_BASE_MS * (1L shl (retryCount - 1))   // 5 s, 10 s, 20 s
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

    // ── Background probe: try Media3 while on VLC ──────────────────────────────

    /**
     * While the stream is live on VLC, silently probe Media3 every 15 minutes.
     * If the probe succeeds (first frame received), seamlessly switch to Media3
     * and cancel VLC. The probe is invisible — it uses a detached PlayerView
     * (visibility GONE) that is never shown to the user.
     */
    private fun scheduleMedia3ProbeFromVlc() {
        cancelProbeMedia3()
        probeSchedule = Runnable {
            probeSchedule = null
            if (!started || inSnapshotMode || activeVlcEngine == null) return@Runnable
            Log.i(TAG, "Background probe: trying Media3 while on VLC")
            launchMedia3Probe(
                onSuccess = {
                    Log.i(TAG, "Background probe: Media3 succeeded — switching from VLC")
                    // Probe engine is already playing in probeExoPlayer.
                    // Swap it in as the main engine.
                    promoteProbeExoToMain()
                },
                onFailure = {
                    Log.d(TAG, "Background probe: Media3 still failing — staying on VLC")
                    releaseProbeExo()
                    if (started && !inSnapshotMode && activeVlcEngine != null)
                        scheduleMedia3ProbeFromVlc()   // try again next interval
                }
            )
        }.also { mainHandler.postDelayed(it, PROBE_MEDIA3_FROM_VLC_MS) }
    }

    // ── Background probes: try VLC / Media3 while on MJPEG ────────────────────

    private fun scheduleProbesFromMjpeg() {
        cancelAllProbes()
        // Probe 1: try VLC every minute
        probeVlcSchedule = Runnable {
            probeVlcSchedule = null
            if (!started || !inSnapshotMode) return@Runnable
            Log.i(TAG, "Background probe: trying VLC while on MJPEG")
            launchVlcProbe(
                onSuccess = {
                    Log.i(TAG, "Background probe: VLC succeeded — switching from MJPEG")
                    promoteProbeVlcToMain()
                },
                onFailure = {
                    Log.d(TAG, "Background probe: VLC still failing — staying on MJPEG")
                    releaseProbeVlc()
                    if (started && inSnapshotMode) scheduleVlcProbeFromMjpeg()
                }
            )
        }.also { mainHandler.postDelayed(it, PROBE_VLC_FROM_MJPEG_MS) }

        // Probe 2: try Media3 every 15 minutes
        probeSchedule = Runnable {
            probeSchedule = null
            if (!started || !inSnapshotMode) return@Runnable
            Log.i(TAG, "Background probe: trying Media3 while on MJPEG")
            launchMedia3Probe(
                onSuccess = {
                    Log.i(TAG, "Background probe: Media3 succeeded — switching from MJPEG")
                    promoteProbeExoToMain()
                },
                onFailure = {
                    Log.d(TAG, "Background probe: Media3 still failing — staying on MJPEG")
                    releaseProbeExo()
                    if (started && inSnapshotMode) scheduleMedia3ProbeFromMjpeg()
                }
            )
        }.also { mainHandler.postDelayed(it, PROBE_MEDIA3_FROM_MJPEG_MS) }
    }

    private fun scheduleVlcProbeFromMjpeg() {
        cancelProbeVlc()
        probeVlcSchedule = Runnable {
            probeVlcSchedule = null
            if (!started || !inSnapshotMode) return@Runnable
            launchVlcProbe(
                onSuccess = {
                    promoteProbeVlcToMain()
                },
                onFailure = {
                    releaseProbeVlc()
                    if (started && inSnapshotMode) scheduleVlcProbeFromMjpeg()
                }
            )
        }.also { mainHandler.postDelayed(it, PROBE_VLC_FROM_MJPEG_MS) }
    }

    private fun scheduleMedia3ProbeFromMjpeg() {
        cancelProbeMedia3()
        probeSchedule = Runnable {
            probeSchedule = null
            if (!started || !inSnapshotMode) return@Runnable
            launchMedia3Probe(
                onSuccess = { promoteProbeExoToMain() },
                onFailure = {
                    releaseProbeExo()
                    if (started && inSnapshotMode) scheduleMedia3ProbeFromMjpeg()
                }
            )
        }.also { mainHandler.postDelayed(it, PROBE_MEDIA3_FROM_MJPEG_MS) }
    }

    // ── Probe engine launchers ─────────────────────────────────────────────────

    /**
     * Spin up a hidden Media3 player on the RTSP URL.
     * The player renders to a detached SurfaceView (never added to the window),
     * so the user sees nothing. If a first frame arrives, [onSuccess] is called
     * on the main thread. Otherwise [onFailure].
     */
    @OptIn(UnstableApi::class)
    private fun launchMedia3Probe(onSuccess: () -> Unit, onFailure: () -> Unit) {
        releaseProbeExo()
        val rawUrl = cfgRtspUrl()
        if (rawUrl.isBlank()) { onFailure(); return }
        val url = injectCredentials(rawUrl, cfgUsername(), cfgPassword(), true)

        val probeGen = engineGeneration   // capture — probe must not interfere with live engine
        val timeoutMs = cfgTimeoutMs() + 3_000L   // give probe a little extra margin

        try {
            val renderersFactory = DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            val probeLoadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(500, 2_000, 200, 500)
                .build()
            val probePlayer = ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(probeLoadControl)
                .build()
                .also { probeExoPlayer = it }

            // Wire up a detached SurfaceView for rendering (never shown)
            val hiddenSurface = SurfaceView(context)
            probePlayer.setVideoSurfaceView(hiddenSurface)

            var resolved = false
            fun resolve(success: Boolean) {
                if (resolved) return
                resolved = true
                if (success) {
                    // Keep probePlayer alive; promoteProbeExoToMain() will take it over
                    mainHandler.post { if (engineGeneration == probeGen || success) onSuccess() }
                } else {
                    mainHandler.post { onFailure() }
                }
            }

            val timeoutRunnable = Runnable { resolve(false) }
            mainHandler.postDelayed(timeoutRunnable, timeoutMs)

            probePlayer.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    resolve(true)
                }
                override fun onPlayerError(error: PlaybackException) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    resolve(false)
                }
            })

            val source = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .createMediaSource(MediaItem.fromUri(url.toUri()))
            probePlayer.setMediaSource(source)
            probePlayer.prepare()
            probePlayer.playWhenReady = false   // don't play audio; just buffer/decode
            probePlayer.volume = 0f
        } catch (e: Exception) {
            Log.w(TAG, "Probe Media3 launch failed: ${e.message}")
            releaseProbeExo()
            onFailure()
        }
    }

    /**
     * Spin up a hidden VLC player on the RTSP URL.
     * Uses a detached VLCVideoLayout. Calls [onSuccess] on first Vout event.
     */
    private fun launchVlcProbe(onSuccess: () -> Unit, onFailure: () -> Unit) {
        releaseProbeVlc()
        val rawUrl = cfgRtspUrl()
        if (rawUrl.isBlank()) { onFailure(); return }
        val url = injectCredentials(rawUrl, cfgUsername(), cfgPassword(), true)
        val timeoutMs = cfgTimeoutMs() + 3_000L

        try {
            // Create a VLCVideoLayout that is never added to the window
            val hiddenVlcLayout = VLCVideoLayout(context)
            val probeVlc = VlcRtspEngine(context, hiddenVlcLayout)
                .also { probeVlcEngine = it }

            var resolved = false
            val timeoutRunnable = Runnable {
                if (!resolved) { resolved = true; onFailure() }
            }
            mainHandler.postDelayed(timeoutRunnable, timeoutMs)

            probeVlc.start(url, muted = true, listener = object : RtspEngine.Listener {
                override fun onPlaying() {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    if (!resolved) { resolved = true; mainHandler.post { onSuccess() } }
                }
                override fun onError(message: String, unrecoverable: Boolean) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    if (!resolved) { resolved = true; mainHandler.post { onFailure() } }
                }
                override fun onEnded() {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    if (!resolved) { resolved = true; mainHandler.post { onFailure() } }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Probe VLC launch failed: ${e.message}")
            releaseProbeVlc()
            onFailure()
        }
    }

    // ── Probe promotion ────────────────────────────────────────────────────────

    /**
     * A background Media3 probe succeeded. Release the current engine and
     * promote the probe player to be the visible, active engine.
     */
    @OptIn(UnstableApi::class)
    private fun promoteProbeExoToMain() {
        if (!started) { releaseProbeExo(); return }
        Log.i(TAG, "Promoting background Media3 probe to main engine")
        cancelAllProbes()
        cancelRtspTimeout()
        cancelRetry()
        stopSnapshotLoop()
        stopRtspMotionSampler()
        releasePlayer()   // releases current live engine (VLC or snapshot)

        val promotedPlayer = probeExoPlayer ?: run {
            // Probe was released before we got here — restart normally from Media3
            probeExoPlayer = null
            startRtsp(); return
        }
        probeExoPlayer = null

        inSnapshotMode = false
        rtspFailReason = null
        retryCount     = 0
        exoSkippedForSession = false

        exoPlayer = promotedPlayer
        promotedPlayer.volume = if (isMuted) 0f else 1f
        promotedPlayer.playWhenReady = true   // now let it play with audio/video

        // Re-attach to the visible PlayerView
        playerView.player = promotedPlayer
        playerView.visibility   = View.VISIBLE
        (vlcLayout.parent as? View)?.visibility = View.GONE
        snapshotView.visibility = View.GONE
        muteButton?.visibility  = View.VISIBLE
        updateMuteButton()
        setStatus(context.getString(R.string.camera_status_live_rtsp))

        engineGeneration++
        val myGen = engineGeneration
        // Re-attach listener on the now-promoted player
        promotedPlayer.addListener(object : Player.Listener {
            private fun stale() = engineGeneration != myGen
            override fun onPlaybackStateChanged(state: Int) {
                if (stale()) return
                if (state == Player.STATE_ENDED && started && !inSnapshotMode)
                    mainHandler.post { scheduleRetry("RTSP stream ended", EngineChoice.EXO) }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (stale()) return
                val msg = error.message ?: "RTSP error"
                mainHandler.post {
                    if (!started || inSnapshotMode) return@post
                    rtspFailReason = msg
                    scheduleRetry(msg, EngineChoice.EXO)
                }
            }
        })

        startRtspMotionSampler()
    }

    /**
     * A background VLC probe succeeded while on MJPEG. Promote it to main.
     */
    private fun promoteProbeVlcToMain() {
        if (!started) { releaseProbeVlc(); return }
        Log.i(TAG, "Promoting background VLC probe to main engine")
        cancelAllProbes()
        cancelRtspTimeout()
        cancelRetry()
        stopSnapshotLoop()

        val promotedVlc = probeVlcEngine ?: run { startVlc(); return }
        probeVlcEngine = null

        inSnapshotMode = false
        rtspFailReason = null
        retryCount     = 0

        activeVlcEngine = promotedVlc
        // VLC is already playing in its hiddenVlcLayout; we need to re-attach to our real vlcLayout.
        // The cleanest approach: release the probe and spin up a fresh VLC on our real layout.
        promotedVlc.release()
        activeVlcEngine = null

        playerView.visibility   = View.GONE
        (vlcLayout.parent as? View)?.visibility = View.VISIBLE
        snapshotView.visibility = View.GONE

        // Start fresh VLC on the real layout (it will be live within ~1s since we know stream works)
        startVlc()
    }

    // ── Probe cancellation / release ───────────────────────────────────────────

    private fun cancelProbeMedia3() {
        probeSchedule?.let { mainHandler.removeCallbacks(it) }
        probeSchedule = null
    }

    private fun cancelProbeVlc() {
        probeVlcSchedule?.let { mainHandler.removeCallbacks(it) }
        probeVlcSchedule = null
    }

    private fun cancelAllProbes() {
        cancelProbeMedia3()
        cancelProbeVlc()
        releaseProbeExo()
        releaseProbeVlc()
    }

    private fun releaseProbeExo() {
        probeExoPlayer?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        probeExoPlayer = null
    }

    private fun releaseProbeVlc() {
        probeVlcEngine?.let { try { it.release() } catch (_: Exception) {} }
        probeVlcEngine = null
    }

    // ── Snapshot fallback ──────────────────────────────────────────────────────

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

        // Only manipulate sub-view visibility when slot container is visible.
        // Background slots have container GONE — refreshStatus()/showSlot() will
        // set the correct sub-views visible when this slot is activated.
        if (isActiveSlot) {
            playerView.visibility                   = View.GONE
            (vlcLayout.parent as? View)?.visibility = View.GONE
            snapshotView.visibility                 = View.VISIBLE
            muteButton?.visibility                  = View.GONE
        }

        // Fire onFirstLive so MultiCameraController knows this slot has something
        // to show (even if it's only snapshots).
        fireFirstLive()

        val snapshotUrl = cfgSnapshotUrl()
        if (snapshotUrl.isBlank()) {
            setStatus(context.getString(R.string.camera_status_tap_to_reload,
                context.getString(R.string.camera_status_no_snapshot_url)))
            return
        }

        val intervalSec = cfgIntervalSec()
        startSnapshotLoop(snapshotUrl, intervalSec)
        scheduleProbesFromMjpeg()
    }

    // ── Snapshot loop ──────────────────────────────────────────────────────────

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
                        setStatus(context.getString(R.string.camera_status_tap_to_reload, errMsg))
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

    // ── Tap-to-reload ──────────────────────────────────────────────────────────

    private fun installTapReload() {
        val container = playerView.parent as? View ?: return
        container.setOnClickListener {
            Log.i(TAG, "Camera area tapped — reloading from Media3")
            applyPrefsChange()
        }
    }

    // ── VLC motion sampler ─────────────────────────────────────────────────────

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

    // ── RTSP motion sampler (PixelCopy) ────────────────────────────────────────

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

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun releasePlayer() {
        exoPlayer?.let {
            try {
                it.clearVideoSurface()   // must precede release() to avoid "resource failed to call end"
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        exoPlayer = null
        playerView.player = null
        releaseDummySurface()
        activeVlcEngine?.release()
        activeVlcEngine = null
    }

    /**
     * Fires [onFirstLive] exactly once per [start] cycle (guarded by [firstLiveFired]).
     * Always called on the main thread from within engine callbacks.
     */
    private fun fireFirstLive() {
        if (firstLiveFired) return
        firstLiveFired = true
        onFirstLive?.invoke()
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
        // In multi-camera mode, MultiCameraController owns the outer container
        // visibility (VISIBLE vs INVISIBLE).  CameraViewController only manages
        // the inner sub-views (playerView, vlcLayout, snapshotView).
        // We don't force playerView VISIBLE here — startRtsp() / startVlc() do
        // that themselves once an engine is chosen — to avoid the wrong sub-view
        // flashing while a background slot is still connecting.
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

    fun isStarted() = started

    fun isVlcActive()  = started && !inSnapshotMode && activeVlcEngine != null
    fun isExoActive()  = started && !inSnapshotMode && exoPlayer != null && activeVlcEngine == null

    /**
     * Zero-cost surface re-bind for GONE → VISIBLE slot switches.
     * VLC keeps decoding internally while detached; frames appear immediately.
     * Also starts the connect-timeout if VLC hasn't called onPlaying() yet.
     */
    @MainThread
    fun reattachVlcViews() {
        if (!started || inSnapshotMode) return
        activeVlcEngine?.reattachViews()

        if (rtspTimeoutJob == null) {
            val timeoutMs = cfgTimeoutMs()
            val gen       = engineGeneration
            rtspTimeoutJob = Runnable {
                if (engineGeneration != gen) return@Runnable
                Log.w(TAG, "VLC timeout (on activate) after ${timeoutMs}ms gen=$gen")
                if (started && !inSnapshotMode)
                    scheduleRetry("VLC timeout after ${timeoutMs}ms", EngineChoice.VLC)
            }
            mainHandler.postDelayed(rtspTimeoutJob!!, timeoutMs)
        }
    }

    /**
     * Detach VLC from its SurfaceView before the container goes GONE.
     * VLC continues streaming and decoding internally — no network reconnect.
     * Call reattachVlcViews() when the container becomes VISIBLE again.
     */
    @MainThread
    fun detachVlcViews() {
        if (!started || inSnapshotMode) return
        activeVlcEngine?.detachViews()
    }

    /**
     * Full VLC teardown + reconnect.  Only call this when the Surface was
     * genuinely destroyed (e.g. the container was set to GONE, or Android
     * destroyed the SurfaceHolder during an orientation change).
     *
     * For ordinary INVISIBLE → VISIBLE switches use [reattachVlcViews] instead.
     */
    @MainThread
    fun restartVlcSurface() {
        if (!started || inSnapshotMode) return
        Log.i(TAG, "restartVlcSurface: full VLC reconnect (surface was destroyed)")
        cancelRtspTimeout()
        cancelRetry()
        activeVlcEngine?.release()
        activeVlcEngine = null
        startVlc()
    }

    // ── Config helpers: prefer cameraConfig over prefs ─────────────────────────

    private fun cfgRtspUrl()     = cameraConfig?.rtspUrl
                                    ?: prefs.getString(PreferenceKeys.CAMERA_RTSP_URL, "") ?: ""
    private fun cfgSnapshotUrl() = cameraConfig?.snapshotUrl
                                    ?: prefs.getString(PreferenceKeys.CAMERA_SNAPSHOT_URL, "") ?: ""
    private fun cfgUsername()    = cameraConfig?.username
                                    ?: prefs.getString(PreferenceKeys.CAMERA_USERNAME, "") ?: ""
    private fun cfgPassword()    = cameraConfig?.password
                                    ?: prefs.getString(PreferenceKeys.CAMERA_PASSWORD, "") ?: ""

    /**
     * Returns the ordered list of enabled engine names from the camera config.
     * Falls back to the legacy single-engine pref if no CameraConfig is set.
     */
    private fun cfgEnabledEngines(): List<String> {
        cameraConfig?.let { return it.enabledEngines.ifEmpty { listOf("exo", "vlc", "snapshot") } }
        return when (prefs.getString(PreferenceKeys.CAMERA_RTSP_ENGINE, "auto") ?: "auto") {
            "vlc"      -> listOf("vlc", "snapshot")
            "snapshot" -> listOf("snapshot")
            else       -> listOf("exo", "vlc", "snapshot")
        }
    }

    private fun cfgTimeoutMs()   = cameraConfig?.rtspTimeoutMs
                                    ?: prefs.getString(PreferenceKeys.CAMERA_RTSP_TIMEOUT_MS, "")?.toLongOrNull()
                                    ?: DEFAULT_RTSP_TIMEOUT_MS
    private fun cfgIntervalSec() = cameraConfig?.snapshotIntervalSec
                                    ?: prefs.getString(PreferenceKeys.CAMERA_SNAPSHOT_INTERVAL, "")?.toIntOrNull()
                                    ?: DEFAULT_SNAPSHOT_INTERVAL

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
