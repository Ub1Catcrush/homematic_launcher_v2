package com.tvcs.homematic

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager

/**
 * ScreenWakeController
 *
 * Turns the screen on when motion is detected, then schedules automatic
 * screen-off after [timeoutMs] of inactivity (no motion, no touch).
 *
 * Wake strategy (best-effort, no root required):
 *   Android 8.0–26  → FULL_WAKE_LOCK (deprecated but functional)
 *   Android 8.1+    → Activity.setShowWhenLocked / setTurnScreenOn + WakeLock (PARTIAL)
 *   All versions    → FLAG_KEEP_SCREEN_ON while "awake" window is active
 *
 * The caller (Activity) must call [onUserInteraction] from its own
 * [Activity.onUserInteraction] override so touch activity resets the timer.
 *
 * Call [destroy] in [Activity.onDestroy].
 */
class ScreenWakeController(
    private val activity: Activity,
    /** Inactivity timeout in ms before screen is allowed to go off. Default 60 s. */
    var timeoutMs: Long = 60_000L
) {
    companion object {
        private const val TAG = "ScreenWakeCtrl"
        private const val WAKE_TAG = "HomeMaticLauncher:MotionWake"
    }

    private val handler       = Handler(Looper.getMainLooper())
    private val powerManager  = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguardMgr   = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    @Suppress("DEPRECATION")
    private val wakeLock = powerManager.newWakeLock(
        PowerManager.FULL_WAKE_LOCK or
        PowerManager.ACQUIRE_CAUSES_WAKEUP or
        PowerManager.ON_AFTER_RELEASE,
        WAKE_TAG
    )

    private var isAwake = false

    private val sleepRunnable = Runnable { releaseLock() }

    /** Called by [CameraViewController] / motion callback when motion is detected. */
    fun onMotion() {
        handler.post { wakeScreen() }
    }

    /** Reset the inactivity timer (called from Activity.onUserInteraction). */
    fun onUserInteraction() {
        if (isAwake) resetTimer()
    }

    /** Release wake lock immediately (e.g. activity goes to background). */
    fun suspend() {
        handler.post { releaseLock() }
    }

    /** Clean up — call from Activity.onDestroy. */
    fun destroy() {
        handler.removeCallbacks(sleepRunnable)
        if (wakeLock.isHeld) {
            try { wakeLock.release() } catch (e: Exception) { Log.w(TAG, "release: ${e.message}") }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun wakeScreen() {
        if (activity.isFinishing || activity.isDestroyed) return
        Log.d(TAG, "Waking screen (timeout=${timeoutMs}ms)")

        // 1. Acquire wake lock to physically turn the panel on
        try {
            if (!wakeLock.isHeld) wakeLock.acquire(timeoutMs + 5_000L)
        } catch (e: Exception) {
            Log.w(TAG, "wakeLock.acquire failed: ${e.message}")
        }

        // 2. Tell Android this window should show on the lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            keyguardMgr.requestDismissKeyguard(activity, null)
        } else {
            @Suppress("DEPRECATION")
            activity.window.addFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // 3. Keep screen on via window flag while our timer is active
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        isAwake = true
        resetTimer()
    }

    private fun resetTimer() {
        handler.removeCallbacks(sleepRunnable)
        handler.postDelayed(sleepRunnable, timeoutMs)
    }

    private fun releaseLock() {
        if (!isAwake) return
        Log.d(TAG, "Screen-off timeout reached — releasing wake lock")
        isAwake = false
        handler.removeCallbacks(sleepRunnable)

        // Remove FLAG_KEEP_SCREEN_ON — system will apply its own display timeout
        if (!activity.isDestroyed) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Release keyguard flags (legacy)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            @Suppress("DEPRECATION")
            activity.window.clearFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        try {
            if (wakeLock.isHeld) wakeLock.release()
        } catch (e: Exception) {
            Log.w(TAG, "release: ${e.message}")
        }
    }
}
