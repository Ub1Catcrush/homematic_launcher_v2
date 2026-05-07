package com.tvcs.homematic

import android.content.SharedPreferences
import android.util.Log

/**
 * MotionPrefsHelper
 *
 * Reads all motion detection parameters from SharedPreferences and applies
 * them to a [MotionDetectionEngine]. Call after any pref change.
 */
object MotionPrefsHelper {

    private const val TAG = "MotionPrefsHelper"

    /**
     * Apply all motion prefs to [engine].
     * [sensitivityKey] selects which sensitivity pref to use
     * (webcam vs local camera have separate sensitivity settings).
     */
    fun applyTo(
        engine:         MotionDetectionEngine,
        prefs:          SharedPreferences,
        sensitivityKey: String,
        enabledKey:     String
    ) {
        engine.sensitivityPct   = prefs.getString(sensitivityKey, "8")
            ?.toIntOrNull()?.coerceIn(1, 30) ?: 8

        engine.lumaThreshold    = prefs.getString(PreferenceKeys.MOTION_LUMA_THRESHOLD, "20")
            ?.toIntOrNull()?.coerceIn(1, 80) ?: 20

        engine.cooldownMs       = (prefs.getString(PreferenceKeys.MOTION_COOLDOWN_SEC, "2")
            ?.toLongOrNull()?.coerceIn(1L, 60L) ?: 2L) * 1_000L

        engine.analysisIntervalMs = (prefs.getString(PreferenceKeys.MOTION_INTERVAL_SEC, "1")
            ?.toLongOrNull()?.coerceIn(1L, 10L) ?: 1L) * 1_000L

        engine.adaptationRate   = (prefs.getString(PreferenceKeys.MOTION_ADAPTATION_RATE, "5")
            ?.toIntOrNull()?.coerceIn(0, 20) ?: 5) / 100f

        engine.roi = parseRoi(prefs.getString(PreferenceKeys.MOTION_ROI, "") ?: "")

        engine.timeWindow = parseTimeWindow(
            prefs.getString(PreferenceKeys.MOTION_TIME_START, "") ?: "",
            prefs.getString(PreferenceKeys.MOTION_TIME_END,   "") ?: ""
        )

        engine.enabled = prefs.getBoolean(enabledKey, false)
        engine.reset()
        Log.d(TAG, "Applied: sens=${engine.sensitivityPct}% luma=${engine.lumaThreshold} " +
              "cooldown=${engine.cooldownMs}ms interval=${engine.analysisIntervalMs}ms " +
              "adapt=${engine.adaptationRate} roi=${engine.roi} tw=${engine.timeWindow}")
    }

    private fun parseRoi(raw: String): MotionDetectionEngine.RoiRect {
        if (raw.isBlank()) return MotionDetectionEngine.RoiRect()
        val p = raw.split(",").mapNotNull { it.trim().toFloatOrNull() }
        return if (p.size == 4) {
            MotionDetectionEngine.RoiRect(
                left   = p[0].coerceIn(0f, 1f),
                top    = p[1].coerceIn(0f, 1f),
                right  = p[2].coerceIn(0f, 1f),
                bottom = p[3].coerceIn(0f, 1f)
            )
        } else MotionDetectionEngine.RoiRect()
    }

    private fun parseTimeWindow(start: String, end: String): MotionDetectionEngine.TimeWindow? {
        if (start.isBlank() || end.isBlank()) return null
        val (sh, sm) = parseHHMM(start) ?: return null
        val (eh, em) = parseHHMM(end)   ?: return null
        return MotionDetectionEngine.TimeWindow(sh, sm, eh, em)
    }

    private fun parseHHMM(s: String): Pair<Int,Int>? {
        val parts = s.split(":").mapNotNull { it.trim().toIntOrNull() }
        return if (parts.size == 2) Pair(parts[0].coerceIn(0,23), parts[1].coerceIn(0,59)) else null
    }

    /** Format an RoiRect back to the pref string "left,top,right,bottom". */
    fun roiToString(roi: MotionDetectionEngine.RoiRect) =
        "%.2f,%.2f,%.2f,%.2f".format(roi.left, roi.top, roi.right, roi.bottom)
}
