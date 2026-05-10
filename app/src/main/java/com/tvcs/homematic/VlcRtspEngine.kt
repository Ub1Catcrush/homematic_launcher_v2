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
        private val VLC_OPTIONS = arrayListOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--rtsp-tcp",               // TCP transport — avoids UDP packet-loss on local nets
            "--network-caching=150",    // 150 ms buffer — good balance for LAN cameras
            "--live-caching=150",
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

            // Wire VLC event listener
            mp.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        Log.i(TAG, "VLC playing")
                        handler.post { listener.onPlaying() }
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        val msg = "VLC playback error"
                        Log.w(TAG, msg)
                        handler.post { listener.onError(msg, unrecoverable = false) }
                    }
                    MediaPlayer.Event.EndReached -> {
                        Log.i(TAG, "VLC stream ended")
                        handler.post { listener.onEnded() }
                    }
                    else -> { /* buffering, opening, etc. — ignored */ }
                }
            }

            val media = Media(vlc, android.net.Uri.parse(url))
            // Additional per-stream options
            media.addOption(":network-caching=150")
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
