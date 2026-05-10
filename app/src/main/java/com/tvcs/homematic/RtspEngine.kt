package com.tvcs.homematic

import android.view.SurfaceView

/**
 * RtspEngine
 *
 * Backend-agnostic interface for RTSP video playback.
 * Two implementations exist:
 *   - [ExoPlayerRtspEngine] — Media3/ExoPlayer (default, lower overhead)
 *   - [VlcRtspEngine]       — libVLC (fallback, tolerates non-standard SDP/codecs)
 *
 * The controller calls [start], listens via [Listener], and calls [release] when done.
 * The engine renders into [surfaceView].
 */
interface RtspEngine {

    interface Listener {
        /** First frame rendered — stream is live. */
        fun onPlaying()
        /**
         * Playback error.
         * @param unrecoverable true if the error is structural (e.g. SDP incompatible)
         *                      and retrying with this engine will always fail.
         */
        fun onError(message: String, unrecoverable: Boolean)
        /** Stream ended cleanly (camera closed connection). */
        fun onEnded()
    }

    /** The SurfaceView this engine renders into. Must be added to the camera panel layout. */
    val surfaceView: SurfaceView

    /** Prepare and start playback of [url] (already contains credentials if needed). */
    fun start(url: String, muted: Boolean, listener: Listener)

    /** Stop playback and release all resources. Safe to call multiple times. */
    fun release()

    /** Mute / unmute audio without stopping playback. */
    fun setMuted(muted: Boolean)

    /** Human-readable name for logging. */
    val name: String
}
