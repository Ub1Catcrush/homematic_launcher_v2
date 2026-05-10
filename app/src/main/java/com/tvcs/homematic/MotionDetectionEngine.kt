package com.tvcs.homematic

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

/**
 * MotionDetectionEngine
 *
 * Lightweight pixel-diff motion detector with configurable ROI, adaptive
 * background, tunable thresholds and time-window gating.
 *
 * Algorithm:
 *   1. Downsample to [sampleW]×[sampleH] grid.
 *   2. Apply ROI mask — ignore pixels outside the active zone.
 *   3. Convert to luminance (ITU-R BT.601).
 *   4. Count pixels where |Y_new − Y_bg| > [lumaThreshold].
 *   5. If ratio ≥ [sensitivityPct] → motion detected.
 *   6. Slowly blend new frame into background (adaptive baseline).
 *
 * All fields are thread-safe via @Volatile or atomic ops.
 */
class MotionDetectionEngine(
    /** 1–100: percentage of changed pixels needed to trigger. Lower = more sensitive. */
    var sensitivityPct: Int = 8,
    val onMotionDetected: () -> Unit
) {
    companion object {
        private const val TAG = "MotionDetect"
        const val SAMPLE_W    = 64
        const val SAMPLE_H    = 36
    }

    // ── Tunable parameters ────────────────────────────────────────────────────

    /** Per-pixel luma delta that counts as "changed" (0–255). Default 20. */
    var lumaThreshold: Int = 20

    /**
     * Adaptive background blend rate (0.0–1.0).
     * 0.0 = static background (never adapts — good for fixed scenes).
     * 0.05 = adapts slowly to gradual lighting changes (default).
     * Higher values adapt faster but may learn moving objects into background.
     */
    /**
     * Background adaptation rate per SECOND (0.0–1.0).
     * 0.0   = static, never adapts (use for perfectly stable lighting).
     * 0.003 = slow adaptation, ~5 min half-life (default, good for indoor).
     * 0.01  = moderate, ~1 min half-life (for changing outdoor lighting).
     * 0.05  = fast (old default — caused false negatives after ~80 s).
     */
    var adaptationRate: Float = 0.003f

    /** Minimum ms between two triggers. Default 2000. */
    var cooldownMs: Long = 2_000L

    /**
     * How often to process a frame (ms). Frames arriving faster are dropped.
     * Default 1000 ms = 1 fps analysis.
     */
    var analysisIntervalMs: Long = 1_000L

    /**
     * Region of interest: fraction of the frame to analyse (0.0–1.0 each).
     * Default = full frame (0.0, 0.0, 1.0, 1.0).
     * Example: top half only → RoiRect(0f, 0f, 1f, 0.5f)
     */
    data class RoiRect(
        val left:   Float = 0f,
        val top:    Float = 0f,
        val right:  Float = 1f,
        val bottom: Float = 1f
    ) {
        fun toPixelBounds(w: Int, h: Int): IntArray {
            val l = (left   * w).toInt().coerceIn(0, w)
            val t = (top    * h).toInt().coerceIn(0, h)
            val r = (right  * w).toInt().coerceIn(l, w)
            val b = (bottom * h).toInt().coerceIn(t, h)
            return intArrayOf(l, t, r, b)
        }
        val isFullFrame get() = left == 0f && top == 0f && right == 1f && bottom == 1f
    }
    var roi: RoiRect = RoiRect()

    /**
     * Optional active time window. When set, motion detection only triggers
     * inside the given hour range (wall-clock, local time).
     * null = always active.
     * Example: TimeWindow(22, 0, 7, 0) = active 22:00–07:00 (overnight ok).
     */
    data class TimeWindow(
        val startHour: Int, val startMin: Int,
        val endHour:   Int, val endMin:   Int
    ) {
        fun isActive(): Boolean {
            val cal = java.util.Calendar.getInstance()
            val now  = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            val start = startHour * 60 + startMin
            val end   = endHour   * 60 + endMin
            return if (start < end) now in start until end
                   else now >= start || now < end   // overnight
        }
    }
    var timeWindow: TimeWindow? = null

    // ── State ─────────────────────────────────────────────────────────────────

    @Volatile var enabled = false
    var warmupFrames: Int = 3
    @Volatile private var warmupRemaining = warmupFrames
    @Volatile private var bgLuma: FloatArray? = null   // adaptive background
    @Volatile private var lastTriggerMs = 0L
    @Volatile private var lastAnalysisMs = 0L

    /** Reset baseline — call after enable/disable or parameter changes. */
    fun reset() {
        bgLuma = null
        warmupRemaining = warmupFrames
        lastTriggerMs = 0L
        lastAnalysisMs = 0L
    }

    // ── Core processing ───────────────────────────────────────────────────────

    fun process(frame: Bitmap) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (now - lastAnalysisMs < analysisIntervalMs) return
        lastAnalysisMs = now

        // Time-window gate
        timeWindow?.let { if (!it.isActive()) return }

        try {
            val scaled = Bitmap.createScaledBitmap(frame, SAMPLE_W, SAMPLE_H, false)
            val luma   = extractLuma(scaled)
            if (scaled !== frame) scaled.recycle()

            var bg = bgLuma
            if (bg == null) {
                bgLuma = luma.map { it.toFloat() }.toFloatArray()
                warmupRemaining = warmupFrames
                return
            }
            if (warmupRemaining > 0) {
                blendBackground(bg, luma)
                warmupRemaining--
                return
            }

            // ROI bounds
            val (rl, rt, rr, rb) = roi.toPixelBounds(SAMPLE_W, SAMPLE_H).let {
                arrayOf(it[0], it[1], it[2], it[3])
            }
            val roiPixels  = ((rr - rl) * (rb - rt)).coerceAtLeast(1)

            var changed = 0
            val luma_t = lumaThreshold.coerceIn(1, 254)
            for (y in rt until rb) {
                for (x in rl until rr) {
                    val i = y * SAMPLE_W + x
                    if (Math.abs(luma[i] - bg[i]) > luma_t) changed++
                }
            }

            // Blend new frame into background regardless of motion
            blendBackground(bg, luma)

            val ratio = changed * 100 / roiPixels
            Log.v(TAG, "Frame diff: $ratio% changed (threshold ${sensitivityPct}%, luma_t=$luma_t)")

            if (ratio >= sensitivityPct.coerceIn(1, 100)) {
                if (now - lastTriggerMs > cooldownMs) {
                    lastTriggerMs = now
                    Log.i(TAG, "Motion: $ratio% pixels changed — firing callback")
                    onMotionDetected()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "process() error: ${e.message}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun extractLuma(bmp: Bitmap): IntArray {
        val pixels = IntArray(SAMPLE_W * SAMPLE_H)
        bmp.getPixels(pixels, 0, SAMPLE_W, 0, 0, SAMPLE_W, SAMPLE_H)
        return IntArray(pixels.size) { i ->
            val p = pixels[i]
            (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
        }
    }

    /**
     * Blend new frame into background with time-weighted adaptation.
     *
     * Instead of a fixed per-frame rate (which caused background to fully absorb
     * a moving object after ~80 s at 2 s intervals), we scale the blend by the
     * elapsed time since the last analysis so the effective half-life is constant
     * regardless of analysis interval.
     *
     * adaptationRate is interpreted as: fraction to blend per SECOND.
     * Default 0.05 → 5% per second → half-life ~14 s for slow lighting changes.
     * Use 0.002 for very stable scenes; 0.01 for normal indoor use.
     */
    private fun blendBackground(bg: FloatArray, luma: IntArray) {
        val rate = adaptationRate.coerceIn(0f, 1f)
        if (rate == 0f) return
        // Scale by elapsed seconds since last frame so rate is time-consistent
        val elapsedSec = (analysisIntervalMs / 1000f).coerceIn(0.1f, 10f)
        val frameRate  = (rate * elapsedSec).coerceIn(0f, 0.5f)  // cap at 50% per frame
        for (i in bg.indices) {
            bg[i] = bg[i] * (1f - frameRate) + luma[i] * frameRate
        }
    }
}
