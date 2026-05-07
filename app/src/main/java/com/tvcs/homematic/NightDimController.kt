package com.tvcs.homematic

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import java.util.Calendar

/**
 * NightDimController
 *
 * Checks the current time every minute and adjusts screen brightness:
 *   - During the configured night window → dim to [nightBrightness]
 *   - Outside the window              → restore to system default (-1f)
 *
 * Brightness values follow [WindowManager.LayoutParams.screenBrightness]:
 *   -1f  = use system setting
 *    0f  = minimum (nearly off)
 *    1f  = maximum
 *
 * Example: nightBrightness = 0.05f means 5% brightness at night.
 *
 * All times are in local wall-clock hours (0–23).
 *
 * Call [start] to activate, [stop] in onDestroy.
 */
class NightDimController(private val activity: Activity) {

    companion object {
        private const val TAG          = "NightDim"
        private const val CHECK_INTERVAL_MS = 60_000L   // re-check every minute
    }

    // ── Configurable properties ───────────────────────────────────────────────

    /** Is night dimming enabled at all? */
    var enabled: Boolean = false
        set(v) { field = v; applyNow() }

    /** Hour (0–23) when night dimming starts. Default 22 = 22:00. */
    var nightStartHour: Int = 22

    /** Minute (0–59) when night dimming starts. */
    var nightStartMinute: Int = 0

    /** Hour (0–23) when night dimming ends (screen returns to normal). Default 7 = 07:00. */
    var nightEndHour: Int = 7

    /** Minute (0–59) when night dimming ends. */
    var nightEndMinute: Int = 0

    /**
     * Target brightness during night window (0.01f – 1.0f).
     * 0.02 = very dark, 0.3 = moderately dimmed. Default 0.05.
     */
    var nightBrightness: Float = 0.05f

    // ── Internal ──────────────────────────────────────────────────────────────

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var isCurrentlyDimmed = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            applyNow()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    /** Start periodic brightness checks. Call from onResume/onCreate. */
    fun start() {
        if (running) return
        running = true
        handler.post(tickRunnable)
        Log.d(TAG, "Started (nightStart=$nightStartHour:$nightStartMinute, " +
                "nightEnd=$nightEndHour:$nightEndMinute, brightness=$nightBrightness)")
    }

    /** Stop and restore normal brightness. Call from onStop/onDestroy. */
    fun stop() {
        running = false
        handler.removeCallbacks(tickRunnable)
        restoreBrightness()
    }

    /** Force an immediate re-evaluation (call after pref changes). */
    fun applyNow() {
        if (!running) return
        if (!activity.isFinishing && !activity.isDestroyed) {
            if (enabled && isNightTime()) dim() else restoreBrightness()
        }
    }

    // ── Brightness helpers ────────────────────────────────────────────────────

    private fun dim() {
        if (isCurrentlyDimmed) return
        isCurrentlyDimmed = true
        Log.d(TAG, "Night dim ON (brightness=${nightBrightness})")
        setBrightness(nightBrightness.coerceIn(0.01f, 1.0f))
    }

    private fun restoreBrightness() {
        if (!isCurrentlyDimmed) return
        isCurrentlyDimmed = false
        Log.d(TAG, "Night dim OFF — restoring system brightness")
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)  // = -1f
    }

    private fun setBrightness(value: Float) {
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            val params = activity.window.attributes
            params.screenBrightness = value
            activity.window.attributes = params
        } catch (e: Exception) {
            Log.w(TAG, "setBrightness failed: ${e.message}")
        }
    }

    /**
     * Returns true if the current wall-clock time falls within the configured night window.
     * Handles overnight spans correctly (e.g. 22:00 – 07:00).
     */
    private fun isNightTime(): Boolean {
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val startMin = nightStartHour * 60 + nightStartMinute
        val endMin   = nightEndHour   * 60 + nightEndMinute

        return if (startMin < endMin) {
            // Same-day window (e.g. 02:00 – 06:00)
            nowMin in startMin until endMin
        } else {
            // Overnight window (e.g. 22:00 – 07:00)
            nowMin >= startMin || nowMin < endMin
        }
    }
}
