package com.tvcs.homematic

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

/**
 * MotionDetectionEngine
 *
 * Lightweight pixel-diff motion detector — no ML, no camera permissions.
 * Feed it consecutive Bitmaps (from RTSP frames or snapshots) and it calls
 * [onMotionDetected] whenever the changed-pixel ratio exceeds [sensitivityPct].
 *
 * Algorithm:
 *   1. Downsample each frame to a fixed small grid (default 64×36) to reduce CPU.
 *   2. Convert each pixel to luminance (Y = 0.299R + 0.587G + 0.114B).
 *   3. Count pixels where |Y_new − Y_prev| > LUMA_THRESHOLD.
 *   4. If changed_pixels / total_pixels > sensitivityPct/100 → motion.
 *
 * Thread-safety: [process] may be called from any thread; callback fires on
 * the same thread as the caller. Wrap the callback in runOnUiThread if needed.
 */
class MotionDetectionEngine(
    /** 0–100: lower = more sensitive. Default 8 means 8 % of pixels must change. */
    var sensitivityPct: Int = 8,
    /** Called when motion is detected — fired at most once per [cooldownMs]. */
    val onMotionDetected: () -> Unit
) {
    companion object {
        private const val TAG             = "MotionDetect"
        private const val SAMPLE_W        = 64
        private const val SAMPLE_H        = 36
        private const val LUMA_THRESHOLD  = 20   // 0–255 per channel
        private const val COOLDOWN_MS     = 2_000L
    }

    @Volatile private var prevLuma: IntArray? = null
    @Volatile private var lastTriggerMs = 0L
    @Volatile var enabled = false
    /** How many frames to consume as baseline before triggering. Default 3. */
    var warmupFrames: Int = 3
    @Volatile private var warmupRemaining = warmupFrames

    /** Reset: call after enable/disable or sensitivity change to avoid stale prev frame. */
    fun reset() { prevLuma = null; warmupRemaining = warmupFrames }

    /**
     * Process a new camera frame. Call from any thread (e.g. ExoPlayer video thread
     * or the IO coroutine that fetched a snapshot).
     */
    fun process(frame: Bitmap) {
        if (!enabled) return
        try {
        val scaled = Bitmap.createScaledBitmap(frame, SAMPLE_W, SAMPLE_H, false)
        val luma   = extractLuma(scaled)
        if (scaled !== frame) scaled.recycle()

        val prev = prevLuma
        prevLuma = luma

        if (prev == null) return   // need two frames to diff
        if (warmupRemaining > 0) { warmupRemaining--; return }

        val threshold = sensitivityPct.coerceIn(1, 100)
        val total     = luma.size
        var changed   = 0
        for (i in 0 until total) {
            if (Math.abs(luma[i] - prev[i]) > LUMA_THRESHOLD) changed++
        }

        val ratio = changed * 100 / total
        Log.v(TAG, "Frame diff: $ratio% changed (threshold $threshold%)")
        if (ratio >= threshold) {
            val now = System.currentTimeMillis()
            if (now - lastTriggerMs > COOLDOWN_MS) {
                lastTriggerMs = now
                Log.i(TAG, "Motion detected: $ratio% pixels changed (threshold $threshold%) — firing callback")
                onMotionDetected()
            }
        }
        } catch (e: Exception) {
            Log.w(TAG, "process() error: ${e.message}")
        }
    }

    private fun extractLuma(bmp: Bitmap): IntArray {
        val pixels = IntArray(SAMPLE_W * SAMPLE_H)
        bmp.getPixels(pixels, 0, SAMPLE_W, 0, 0, SAMPLE_W, SAMPLE_H)
        return IntArray(pixels.size) { i ->
            val p = pixels[i]
            // ITU-R BT.601 luma
            (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
        }
    }
}
