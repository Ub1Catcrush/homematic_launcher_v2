package com.tvcs.homematic

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

/**
 * ExoPlayerRtspEngine
 *
 * Wraps Media3 ExoPlayer for RTSP playback.
 * Used as the primary (first-attempt) engine.
 *
 * Known limitation: fails with "missing attribute fmtp" for cameras that
 * omit the optional SDP fmtp line. In that case [Listener.onError] is called
 * with unrecoverable=true so the controller can switch to [VlcRtspEngine].
 */
@OptIn(UnstableApi::class)
class ExoPlayerRtspEngine(
    private val context:    Context,
    private val playerView: PlayerView       // existing PlayerView in the layout
) : RtspEngine {

    companion object {
        private const val TAG = "ExoEngine"

        /** Keywords that indicate a structural SDP/codec incompatibility. */
        private val UNRECOVERABLE_HINTS = listOf(
            "missing attribute",
            "fmtp",
            "IllegalArgumentException",
            "malformed",
            "unsupported sdp",
            "no supported track"
        )
    }

    override val name = "ExoPlayer"

    /** ExoPlayer renders into the PlayerView's internal SurfaceView. */
    override val surfaceView: SurfaceView
        get() = playerView.videoSurfaceView as? SurfaceView
            ?: error("PlayerView does not use a SurfaceView")

    private var player:    ExoPlayer? = null
    private val handler  = Handler(Looper.getMainLooper())

    override fun start(url: String, muted: Boolean, listener: RtspEngine.Listener) {
        release()

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val exo = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .build()
            .also { player = it }

        playerView.player = exo

        val source = RtspMediaSource.Factory()
            .createMediaSource(MediaItem.fromUri(url.toUri()))

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY   -> { /* onRenderedFirstFrame fires earlier */ }
                    Player.STATE_ENDED   -> handler.post { listener.onEnded() }
                    else -> {}
                }
            }

            override fun onRenderedFirstFrame() {
                Log.i(TAG, "First frame rendered")
                listener.onPlaying()
            }

            override fun onPlayerError(error: PlaybackException) {
                val msg = buildErrorMessage(error)
                val unrecoverable = isUnrecoverable(error)
                Log.w(TAG, "Player error (unrecoverable=$unrecoverable): $msg")
                // Post to next Looper cycle — never call release() inside a Player callback
                handler.post {
                    listener.onError(msg, unrecoverable)
                }
            }
        })

        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
        exo.volume = if (muted) 0f else 1f
    }

    override fun release() {
        player?.let {
            try { it.stop(); it.release() } catch (e: Exception) {
                Log.w(TAG, "release: ${e.message}")
            }
        }
        player = null
        playerView.player = null
    }

    override fun setMuted(muted: Boolean) {
        player?.volume = if (muted) 0f else 1f
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildErrorMessage(e: PlaybackException): String {
        val layers = listOf(e, e.cause, e.cause?.cause)
        return layers.firstNotNullOfOrNull { it?.message?.takeIf { m -> m.isNotBlank() } }
            ?: "ExoPlayer error"
    }

    private fun isUnrecoverable(e: PlaybackException): Boolean {
        val text = buildString {
            append(e.message ?: "")
            append(e.cause?.message ?: "")
            append(e.cause?.cause?.message ?: "")
        }.lowercase()
        return UNRECOVERABLE_HINTS.any { hint -> text.contains(hint) }
    }
}
