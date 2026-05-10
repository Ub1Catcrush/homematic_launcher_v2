package com.tvcs.homematic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.core.app.NotificationCompat
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MotionDetectionService
 *
 * Foreground service that runs motion detection continuously — even when the
 * screen is off, the app is in the background, or the device is in standby.
 *
 * Two detection sources are managed here:
 *   1. Device camera (front / rear)  via [LocalCameraMotionSource]
 *   2. Webcam snapshot polling        via a coroutine loop (if snapshot URL is set)
 *
 * When motion is detected, the service sends [ACTION_MOTION_DETECTED] as an
 * ordered broadcast. [MainActivity] receives it and calls [ScreenWakeController].
 *
 * The service is started/stopped by [MainActivity] based on pref state.
 * Bind with [LocalBinder] to call [applyPrefs] after settings changes.
 */
class MotionDetectionService : LifecycleService() {

    companion object {
        private const val TAG               = "MotionSvc"
        const val ACTION_MOTION_DETECTED    = "com.tvcs.homematic.MOTION_DETECTED"
        const val ACTION_START              = "com.tvcs.homematic.MOTION_SVC_START"
        const val ACTION_STOP               = "com.tvcs.homematic.MOTION_SVC_STOP"
        const val CHANNEL_ID                = "motion_detection"
        const val NOTIF_ID                  = 9001

        fun start(context: Context) {
            val intent = Intent(context, MotionDetectionService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MotionDetectionService::class.java).apply {
                    action = ACTION_STOP
                }
            )
        }

        /** Returns true if at least one motion source is enabled in prefs. */
        fun isAnySourceEnabled(context: Context): Boolean {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return prefs.getBoolean(PreferenceKeys.MOTION_LOCAL_ENABLED, false)
                || prefs.getBoolean(PreferenceKeys.MOTION_WEBCAM_ENABLED, false)
                || prefs.getBoolean(PreferenceKeys.MOTION_DETECT_ENABLED, false)
        }
    }

    // ── Binder ────────────────────────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        val service get() = this@MotionDetectionService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private var localCameraSource: LocalCameraMotionSource? = null
    private var webcamMotionEngine: MotionDetectionEngine?  = null
    private var webcamJob: Job?          = null
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Short-lived wake lock used to turn the screen on before the broadcast reaches MainActivity. */
    @Suppress("DEPRECATION")
    private val motionWakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "HomeMaticLauncher:MotionSvcWake"
            ).also { it.setReferenceCounted(false) }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private var webcamSourceActive = false
    private var localSourceActive  = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            else        -> applyPrefs()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        stopAllSources()
        serviceScope.cancel()
        try { if (motionWakeLock.isHeld) motionWakeLock.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Re-read prefs and restart sources accordingly. Call after settings change. */
    fun applyPrefs() {
        stopAllSources()
        // Recreate scope in case it was cancelled during a previous stop
        if (!serviceScope.isActive) serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // Source 1: local device camera
        if (prefs.getBoolean(PreferenceKeys.MOTION_LOCAL_ENABLED, false)) {
            startLocalCamera(prefs)
        }

        // Source 2: webcam snapshot polling
        val webcamEnabled = prefs.getBoolean(PreferenceKeys.MOTION_WEBCAM_ENABLED, false)
            || prefs.getBoolean(PreferenceKeys.MOTION_DETECT_ENABLED, false)
        if (webcamEnabled) {
            startWebcamPolling(prefs)
        }

        // If nothing is running, stop the service to save battery
        if (localCameraSource == null && webcamJob == null) {
            Log.i(TAG, "No sources active — stopping self to preserve battery")
            stopSelf()
        }
    }

    // ── Source management ─────────────────────────────────────────────────────

    private fun startLocalCamera(prefs: android.content.SharedPreferences) {
        if (!hasPermission(android.Manifest.permission.CAMERA)) {
            Log.w(TAG, "CAMERA permission not granted — skipping local camera source")
            return
        }
        val facingPref  = prefs.getString(PreferenceKeys.MOTION_LOCAL_FACING, "front")
        val facing      = if (facingPref == "back") CameraSelector.LENS_FACING_BACK
                          else CameraSelector.LENS_FACING_FRONT
        val sensitivity = prefs.getString(PreferenceKeys.MOTION_LOCAL_SENSITIVITY, "8")
            ?.toIntOrNull()?.coerceIn(1, 30) ?: 8

        val engine = MotionDetectionEngine(onMotionDetected = { broadcastMotion() })
        MotionPrefsHelper.applyTo(engine, prefs,
            PreferenceKeys.MOTION_LOCAL_SENSITIVITY, PreferenceKeys.MOTION_LOCAL_ENABLED)

        localCameraSource = LocalCameraMotionSource(
            context        = this,
            lifecycleOwner = this,
            motionEngine   = engine,
            facing         = facing
        )
        localCameraSource?.start()
        Log.i(TAG, "Local camera source started (facing=$facing, sensitivity=$sensitivity)")
        localSourceActive = true
        updateNotification(webcamSourceActive, localSourceActive)
    }

    private fun startWebcamPolling(prefs: android.content.SharedPreferences) {
        val snapshotUrl = prefs.getString(PreferenceKeys.CAMERA_SNAPSHOT_URL, null)
            ?.takeIf { it.isNotBlank() } ?: run {
                Log.w(TAG, "No snapshot URL configured — skipping webcam source")
                return
            }
        val username    = prefs.getString(PreferenceKeys.CAMERA_USERNAME, null)
        val password    = prefs.getString(PreferenceKeys.CAMERA_PASSWORD, null)
        val sensitivity = (prefs.getString(PreferenceKeys.MOTION_WEBCAM_SENSITIVITY, null)
            ?: prefs.getString(PreferenceKeys.MOTION_DETECT_SENSITIVITY, "8"))
            ?.toIntOrNull()?.coerceIn(1, 30) ?: 8
        // Use motion-specific interval, not camera display interval
        val intervalSec = prefs.getString(PreferenceKeys.MOTION_INTERVAL_SEC, "2")
            ?.toLongOrNull()?.coerceAtLeast(1L) ?: 2L

        webcamMotionEngine = MotionDetectionEngine(onMotionDetected = { broadcastMotion() })
        val wcEnabledKey = if (prefs.contains(PreferenceKeys.MOTION_WEBCAM_ENABLED))
            PreferenceKeys.MOTION_WEBCAM_ENABLED else PreferenceKeys.MOTION_DETECT_ENABLED
        MotionPrefsHelper.applyTo(webcamMotionEngine!!, prefs,
            PreferenceKeys.MOTION_WEBCAM_SENSITIVITY, wcEnabledKey)
        // engine.enabled is set by MotionPrefsHelper above — no separate check needed

        webcamJob = serviceScope.launch(Dispatchers.IO) {
            Log.i(TAG, "Webcam polling started ($snapshotUrl, every ${intervalSec}s)")
            webcamSourceActive = true
            updateNotification(webcamSourceActive, localSourceActive)
            while (isActive) {
                try {
                    val bmp = fetchSnapshot(snapshotUrl, username, password)
                    if (bmp != null) {
                        webcamMotionEngine?.process(bmp)
                        bmp.recycle()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Webcam snapshot fetch failed: ${e.message}")
                }
                delay(intervalSec * 1000L)
            }
        }
    }

    private suspend fun fetchSnapshot(
        url: String,
        username: String?,
        password: String?
    ): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
                val creds = android.util.Base64.encodeToString(
                    "$username:$password".toByteArray(), android.util.Base64.NO_WRAP)
                connection.setRequestProperty("Authorization", "Basic $creds")
            }
            connection.connectTimeout = 5_000
            connection.readTimeout    = 5_000
            connection.connect()
            if (connection.responseCode == 200) {
                android.graphics.BitmapFactory.decodeStream(connection.inputStream)
            } else {
                Log.w(TAG, "Snapshot HTTP ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchSnapshot: ${e.message}")
            null
        }
    }

    private fun stopAllSources() {
        localCameraSource?.stop()
        localCameraSource = null
        val job = webcamJob; webcamJob = null
        job?.cancel()
        webcamMotionEngine?.enabled = false
        webcamMotionEngine = null
        webcamSourceActive = false
        localSourceActive  = false
    }

    // ── Broadcast ─────────────────────────────────────────────────────────────

    private fun broadcastMotion() {
        Log.i(TAG, "Motion detected — broadcasting")
        // Acquire a brief wake lock so the screen physically turns on before
        // MainActivity processes the broadcast and sets its window flags.
        // The lock is intentionally short (5 s) — ScreenWakeController re-acquires
        // its own longer lock once the Activity is in the foreground.
        try {
            if (!motionWakeLock.isHeld) motionWakeLock.acquire(5_000L)
        } catch (e: Exception) {
            Log.w(TAG, "motionWakeLock.acquire: ${e.message}")
        }
        val intent = Intent(ACTION_MOTION_DETECTED).apply {
            `package` = packageName
        }
        sendBroadcast(intent)
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_motion_name),
            NotificationManager.IMPORTANCE_LOW          // silent, no sound
        ).apply {
            description = getString(R.string.notif_channel_motion_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    fun buildNotification(webcamActive: Boolean = false, localActive: Boolean = false): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val sources = buildList {
            if (webcamActive) add(getString(R.string.notif_source_webcam))
            if (localActive)  add(getString(R.string.notif_source_local))
        }.joinToString(", ").ifBlank { getString(R.string.notif_source_starting) }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOptText = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
            && !pm.isIgnoringBatteryOptimizations(packageName)) {
            "  ⚠ " + getString(R.string.notif_battery_opt_warning)
        } else ""

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_motion_title))
            .setContentText(sources + batteryOptText)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(sources + batteryOptText))
            .setSmallIcon(R.drawable.ic_notifications_black_24dp)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun updateNotification(webcamActive: Boolean, localActive: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { nm.notify(NOTIF_ID, buildNotification(webcamActive, localActive)) }
        catch (_: Exception) {}
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun hasPermission(permission: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(this, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED


}
