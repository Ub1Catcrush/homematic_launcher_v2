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
 * Wakes the device from standby/screen-off via FULL_WAKE_LOCK+ACQUIRE_CAUSES_WAKEUP,
 * then keeps the screen alive for [timeoutMs] after the last motion/touch event.
 *
 * Phase 1 — Physical wake (no Activity needed):
 *   FULL_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP turns the panel on even from deep sleep.
 *
 * Phase 2 — Override system timeout (Activity window):
 *   FLAG_KEEP_SCREEN_ON suppresses the system display timeout for [timeoutMs].
 *   setShowWhenLocked / FLAG_SHOW_WHEN_LOCKED makes the app visible on lock screen.
 */
class ScreenWakeController(
    private val activity: Activity,
    var timeoutMs: Long = 60_000L
) {
    companion object {
        private const val TAG      = "ScreenWakeCtrl"
        private const val WAKE_TAG = "HomeMaticLauncher:MotionWake"
    }

    private val handler      = Handler(Looper.getMainLooper())
    private val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguardMgr  = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    @Suppress("DEPRECATION")
    private val wakeLock = powerManager.newWakeLock(
        PowerManager.FULL_WAKE_LOCK or
        PowerManager.ACQUIRE_CAUSES_WAKEUP or
        PowerManager.ON_AFTER_RELEASE,
        WAKE_TAG
    ).also { it.setReferenceCounted(false) }

    private var isAwake     = false
    private var pendingWake = false

    private val sleepRunnable = Runnable { handleTimeout() }

    // ── Public API ────────────────────────────────────────────────────────────

    fun onMotion() {
        Log.d(TAG, "onMotion() — isInteractive=${powerManager.isInteractive}")
        handler.post { wakeScreen() }
    }

    fun onUserInteraction() {
        if (isAwake) resetTimer()
    }

    /** Call from Activity.onResume() to apply deferred window flags. */
    fun onActivityResumed() {
        if (pendingWake) {
            pendingWake = false
            applyWindowFlags()
            if (!isAwake) { isAwake = true; resetTimer() }
        }
    }

    fun suspend() {
        handler.post {
            handler.removeCallbacks(sleepRunnable)
            clearWindowFlags()
            releaseWakeLock()
            isAwake     = false
            pendingWake = false
        }
    }

    fun destroy() {
        handler.removeCallbacks(sleepRunnable)
        clearWindowFlags()
        releaseWakeLock()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun wakeScreen() {
        if (activity.isFinishing || activity.isDestroyed) return
        acquireWakeLock()
        if (!activity.isDestroyed && activity.window != null) {
            applyWindowFlags()
        } else {
            pendingWake = true
        }
        if (!isAwake) {
            isAwake = true
            Log.d(TAG, "Screen woken (timeout=${timeoutMs}ms)")
        }
        resetTimer()
    }

    private fun acquireWakeLock() {
        try {
            if (!wakeLock.isHeld) {
                wakeLock.acquire(timeoutMs + 5_000L)
                Log.d(TAG, "WakeLock acquired")
            }
        } catch (e: Exception) {
            Log.w(TAG, "wakeLock.acquire: ${e.message}")
        }
    }

    private fun applyWindowFlags() {
        if (activity.isDestroyed) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                keyguardMgr.requestDismissKeyguard(activity, null)
            } else {
                @Suppress("DEPRECATION")
                activity.window.addFlags(
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED  or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) {
            Log.w(TAG, "applyWindowFlags: ${e.message}")
        }
    }

    private fun resetTimer() {
        handler.removeCallbacks(sleepRunnable)
        handler.postDelayed(sleepRunnable, timeoutMs)
    }

    private fun handleTimeout() {
        Log.d(TAG, "Timeout — releasing screen")
        isAwake = false
        clearWindowFlags()
        releaseWakeLock()
    }

    private fun clearWindowFlags() {
        if (activity.isDestroyed) return
        try {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
                @Suppress("DEPRECATION")
                activity.window.clearFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "clearWindowFlags: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock.isHeld) { wakeLock.release(); Log.d(TAG, "WakeLock released") }
        } catch (e: Exception) {
            Log.w(TAG, "wakeLock.release: ${e.message}")
        }
    }
}
