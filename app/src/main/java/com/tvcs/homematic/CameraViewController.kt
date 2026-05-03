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
class CameraViewController(
    private val context: Context,
    private val playerView:    PlayerView,
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
    private val ioScope   = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var exoPlayer:       ExoPlayer? = null
    private var snapshotJob:     Job?        = null
    private var rtspTimeoutJob:  Runnable?   = null
    private var inSnapshotMode  = false
    private var started         = false
    private var rtspFailReason: String? = null
    private var isMuted         = false

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
        show()
        started = true
        inSnapshotMode = false
        rtspFailReason = null
        startRtsp()
    }

    @MainThread
    fun stop() {
        started = false
        cancelRtspTimeout()
        stopSnapshotLoop()
        releasePlayer()
    }

    fun applyPrefsChange() {
        if (!started) return
        stop()
        start()
    }

    // ── Lifecycle observer ────────────────────────────────────────────────────

    override fun onStart(owner: LifecycleOwner) { if (isEnabled()) start() }
    override fun onStop(owner: LifecycleOwner)  { stop() }

    override fun onDestroy(owner: LifecycleOwner) {
        ioScope.cancel()
        stop()
    }

    // ── RTSP ──────────────────────────────────────────────────────────────────

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
            .setEnableDecoderFallback(true) // Versucht alternative Decoder bei Fehlern
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .build()
            .also { exoPlayer = it }

        playerView.player = player
        playerView.visibility = View.VISIBLE
        snapshotView.visibility = View.GONE

        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)    // TCP avoids UDP packet-loss AND fixes NPE in RtspClient response parsing
            .createMediaSource(MediaItem.fromUri(rtspUrl.toUri()))

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        cancelRtspTimeout()
                        setStatus(context.getString(R.string.camera_status_live_rtsp))
                        Log.i(TAG, "RTSP playing")
                    }
                    Player.STATE_BUFFERING ->
                        setStatus(context.getString(R.string.camera_status_buffering))
                    Player.STATE_ENDED ->
                        fallbackToSnapshot("RTSP stream ended")
                    else -> { /* IDLE — ignore */ }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                // Catch-all: ExoPlayer can throw NPEs from RtspClient internals
                // (e.g. checkNotNull in RtspClient$MessageListener.handleRtspResponse)
                val msg = error.message ?: error.cause?.message ?: "RTSP error"
                Log.w(TAG, "RTSP player error: $msg", error)
                try { fallbackToSnapshot(msg) } catch (t: Throwable) {
                    Log.e(TAG, "fallbackToSnapshot itself threw", t)
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
            Log.w(TAG, "RTSP timeout after ${timeoutMs}ms — falling back to snapshot")
            fallbackToSnapshot(context.getString(R.string.camera_status_rtsp_timeout))
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

        releasePlayer()
        playerView.visibility  = View.GONE
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
                    withContext(Dispatchers.Main) {
                        snapshotView.setImageBitmap(bmp)
                        // Always show RTSP failure reason so user knows live stream is not active
                        val rtspFail = rtspFailReason
                        if (rtspFail != null)
                            setStatus(context.getString(R.string.camera_status_rtsp_failed_snapshot_ok, rtspFail))
                        else
                            setStatus(context.getString(R.string.camera_status_snapshot_mode))
                    }
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun releasePlayer() {
        exoPlayer?.let { it.stop(); it.release() }
        exoPlayer = null
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
