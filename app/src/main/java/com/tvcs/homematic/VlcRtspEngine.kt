package com.tvcs.homematic

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * VlcRtspEngine
 *
 * Wraps libVLC for RTSP playback.
 * Used as the fallback engine when ExoPlayer fails with structural SDP errors.
 *
 * libVLC is much more tolerant of non-standard RTSP/SDP (missing fmtp, unusual
 * codecs, broken interleaving) because VLC has been battle-tested against
 * thousands of real-world camera implementations.
 *
 * Dependency (add to build.gradle before using this engine):
 *   implementation 'org.videolan.android:libvlc-all:3.6.4'
 *
 * The [vlcLayout] is a [VLCVideoLayout] that must be added to the camera panel
 * FrameLayout BEFORE calling [start]. [CameraViewController] manages this.
 */
class VlcRtspEngine(
    private val context:   Context,
    val vlcLayout: VLCVideoLayout   // surface managed by VLC, owned by caller
) : RtspEngine {

    companion object {
        private const val TAG = "VlcEngine"

        /** libVLC options — optimised for low-latency live camera streams. */
        /** After VLC reports Playing, wait this long for a real decoded frame. */
        private const val BLACKFRAME_TIMEOUT_MS = 3_000L   // LAN: Vout kommt in <2 s (war 6_000)

        private val VLC_OPTIONS = arrayListOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--rtsp-tcp",               // TCP transport — avoids UDP packet-loss on local nets
            "--network-caching=50",     // 50 ms — LAN hat keinen Jitter (war 150)
            "--live-caching=50",        // 50 ms (war 150)
            "--file-caching=50",        // explizit niedrig setzen
            "--clock-jitter=0",
            "--clock-synchro=0",
            "--no-audio",               // disable audio by default; re-enabled via setMuted(false)
            "--verbose=-1"              // suppress VLC's own log spam
        )
    }

    override val name = "libVLC"

    // VlcRtspEngine uses VLCVideoLayout's internal surface — expose a SurfaceView view of it
    override val surfaceView: SurfaceView
        get() = vlcLayout.getChildAt(0) as? SurfaceView
            ?: SurfaceView(context)   // safe fallback; VLC manages its own surface

    private var libVlc:      LibVLC?      = null
    private var mediaPlayer: MediaPlayer? = null
    private var audioEnabled = false
    private val handler = Handler(Looper.getMainLooper())

    override fun start(url: String, muted: Boolean, listener: RtspEngine.Listener) {
        release()
        audioEnabled = !muted

        try {
            val vlc = LibVLC(context, VLC_OPTIONS).also { libVlc = it }
            val mp  = MediaPlayer(vlc).also { mediaPlayer = it }

            mp.attachViews(vlcLayout, null, false, false)
            mp.volume = if (muted) 0 else 100

            // ── Video-output watchdog ─────────────────────────────────────────
            // VLC fires Event.Playing when the demuxer opens, NOT when a decoded
            // frame reaches the surface. ACodec errors (setPortMode -1010) or
            // network issues can leave VLC in Playing state with a black screen.
            //
            // Reliable signal: MediaPlayer.Event.Vout fires when VLC hands the
            // first decoded frame to the video output. We wait BLACKFRAME_TIMEOUT_MS
            // after Playing for a Vout event. If none arrives → error → retry.
            var voutReceived     = false
            var watchdogRunnable: Runnable? = null
            fun cancelWatchdog() {
                watchdogRunnable?.let { handler.removeCallbacks(it) }
                watchdogRunnable = null
            }

            // Wire VLC event listener
            mp.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        // Event.Playing fires when the demuxer opens — NOT when a decoded
                        // frame reaches the surface. Do NOT call onPlaying() here yet.
                        // Instead, arm the black-frame watchdog and wait for Vout.
                        Log.i(TAG, "VLC playing — waiting for Vout (video-output) event")
                        handler.post {
                            if (!voutReceived) {
                                watchdogRunnable = Runnable {
                                    watchdogRunnable = null
                                    if (!voutReceived) {
                                        Log.w(TAG, "VLC Vout watchdog: no video frame after ${BLACKFRAME_TIMEOUT_MS}ms — black screen, reporting error")
                                        listener.onError("VLC no video output (black screen)", unrecoverable = false)
                                    }
                                }.also { handler.postDelayed(it, BLACKFRAME_TIMEOUT_MS) }
                            }
                        }
                    }
                    MediaPlayer.Event.Vout -> {
                        // First real decoded frame delivered to surface.
                        // Only NOW is it safe to cancel the CameraViewController's RTSP
                        // timeout watchdog and declare the stream as live.
                        if (!voutReceived) {
                            voutReceived = true
                            cancelWatchdog()
                            Log.i(TAG, "VLC Vout: first video frame confirmed — cancelling watchdog")
                            handler.post { listener.onPlaying() }
                        }
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        cancelWatchdog()
                        val msg = "VLC playback error"
                        Log.w(TAG, msg)
                        handler.post { listener.onError(msg, unrecoverable = false) }
                    }
                    MediaPlayer.Event.EndReached -> {
                        cancelWatchdog()
                        Log.i(TAG, "VLC stream ended")
                        handler.post { listener.onEnded() }
                    }
                    else -> { /* buffering, opening, etc. — ignored */ }
                }
            }

            val media = Media(vlc, android.net.Uri.parse(url))
            // Per-stream: enforce TCP (redundant with global option but harmless as override)
            media.addOption(":rtsp-tcp")
            mp.media = media
            media.release()   // MediaPlayer holds its own reference

            mp.play()
            Log.i(TAG, "VLC started: ${sanitise(url)}")
        } catch (e: Exception) {
            Log.e(TAG, "VLC start failed: ${e.message}", e)
            handler.post { listener.onError(e.message ?: "VLC init error", unrecoverable = false) }
        }
    }

    /**
     * Detach VLC output from its VLCVideoLayout before the container goes GONE.
     * VLC keeps its network connection and internal decoder running — no reconnect.
     * Call reattachViews() when the container becomes VISIBLE again.
     */
    fun detachViews() {
        try {
            mediaPlayer?.detachViews()
            Log.i(TAG, "detachViews: surface detached (still streaming internally)")
        } catch (e: Exception) {
            Log.w(TAG, "detachViews failed: ${e.message}")
        }
    }

    /**
     * Re-bind the already-running MediaPlayer to its VLCVideoLayout after the
     * container returns from GONE to VISIBLE.
     *
     * VLC keeps decoding into its internal buffer while the view is INVISIBLE;
     * the Surface is never destroyed.  Calling attachViews() again is enough to
     * make the output appear — no stop/reconnect required, so playback resumes
     * instantly.
     *
     * Only call this if you have concrete evidence the Surface was actually
     * destroyed (e.g. the container was briefly GONE).  For plain INVISIBLE→
     * VISIBLE transitions this call is a no-op from VLC's perspective but still
     * harmless.
     */
    fun reattachViews() {
        try {
            mediaPlayer?.attachViews(vlcLayout, null, false, false)
            Log.i(TAG, "reattachViews: surface re-bound (no reconnect)")
        } catch (e: Exception) {
            Log.w(TAG, "reattachViews failed: ${e.message}")
        }
    }

    override fun release() {
        try { mediaPlayer?.stop(); mediaPlayer?.detachViews() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        try { libVlc?.release()      } catch (_: Exception) {}
        mediaPlayer = null
        libVlc      = null
    }

    override fun setMuted(muted: Boolean) {
        mediaPlayer?.volume = if (muted) 0 else 100
    }

    private fun sanitise(url: String) =
        url.replace(Regex("(?<=:)[^:@/]+(?=@)"), "***")
}
