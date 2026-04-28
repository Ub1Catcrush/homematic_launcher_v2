package com.tvcs.homematic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import androidx.work.*
import java.util.concurrent.TimeUnit

class CcuNotificationWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG        = "CcuNotifWorker"
        const val WORK_NAME          = "ccu_background_notifications"
        const val CHANNEL_ALERT      = "hm_alert"
        const val CHANNEL_WINDOW     = "hm_window"
        const val NOTIF_LOWBAT       = 1001
        const val NOTIF_SABOTAGE     = 1002
        const val NOTIF_FAULT        = 1003
        const val NOTIF_WINDOW       = 1004

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(WORK_NAME); return }
            val req = PeriodicWorkRequestBuilder<CcuNotificationWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
            Log.i(TAG, "Scheduled (15 min, network required)")
        }

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ALERT,
                context.getString(R.string.notif_channel_alert_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = context.getString(R.string.notif_channel_alert_desc) })
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_WINDOW,
                context.getString(R.string.notif_channel_window_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.notif_channel_window_desc) })
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Worker started")
        HomeMatic.init(appContext)

        val prefs          = PreferenceManager.getDefaultSharedPreferences(appContext)
        val notifyLowbat   = prefs.getBoolean(PreferenceKeys.NOTIFY_LOWBAT,       true)
        val notifySabotage = prefs.getBoolean(PreferenceKeys.NOTIFY_SABOTAGE,     true)
        val notifyFault    = prefs.getBoolean(PreferenceKeys.NOTIFY_FAULT,        true)
        val notifyWindow   = prefs.getBoolean(PreferenceKeys.NOTIFY_WINDOW_OPEN,  false)

        if (HomeMatic.loadDataAsync(appContext).isFailure) {
            Log.w(TAG, "CCU load failed — retrying later")
            return Result.retry()
        }

        val state = HomeMatic.state ?: return Result.retry()
        val prof  = HomeMatic.profile

        // ── LOWBAT / SABOTAGE / FAULT — categorised via profile field sets ──
        val lowbatDevices   = mutableListOf<String>()
        val sabotageDevices = mutableListOf<String>()
        val faultDevices    = mutableListOf<String>()

        for ((name, n) in state.notifications) {
            when {
                n.type in prof.lowbatFields   -> lowbatDevices.add(name)
                n.type in prof.sabotageFields -> sabotageDevices.add(name)
                n.type in prof.faultFields    -> faultDevices.add(name)
            }
        }

        if (notifyLowbat && lowbatDevices.isNotEmpty()) {
            fireNotification(NOTIF_LOWBAT, CHANNEL_ALERT,
                appContext.getString(R.string.notif_lowbat_title),
                appContext.resources.getQuantityString(
                    R.plurals.notif_lowbat_text, lowbatDevices.size, lowbatDevices.size
                ) + ": " + lowbatDevices.joinToString(", ")
            )
        } else cancelNotification(NOTIF_LOWBAT)

        if (notifySabotage && sabotageDevices.isNotEmpty()) {
            fireNotification(NOTIF_SABOTAGE, CHANNEL_ALERT,
                appContext.getString(R.string.notif_sabotage_title),
                sabotageDevices.joinToString(", ")
            )
        } else cancelNotification(NOTIF_SABOTAGE)

        if (notifyFault && faultDevices.isNotEmpty()) {
            fireNotification(NOTIF_FAULT, CHANNEL_ALERT,
                appContext.getString(R.string.notif_fault_title),
                faultDevices.joinToString(", ")
            )
        } else cancelNotification(NOTIF_FAULT)

        // ── Open windows — state values via profile ──────────────────────────
        if (notifyWindow) {
            val openWindows = mutableListOf<String>()
            val outdoorName = HomeMatic.getOutdoorRoomName()
            for (room in state.roomList.rooms) {
                if (room.name == outdoorName) continue
                for (rc in room.channels) {
                    val chan  = state.channels[rc.ise_id] ?: continue
                    val devId = state.channel2Device[chan.ise_id] ?: continue
                    val dev   = state.devices[devId] ?: continue
                    if (dev.device_type !in prof.windowDeviceTypes) continue
                    val stateVal = chan.datapoints.firstOrNull { it.type in prof.stateFields }?.value ?: continue
                    if (stateVal in prof.stateTiltedValues || stateVal in prof.stateOpenValues)
                        openWindows.add("${room.name}: ${dev.name}")
                }
            }
            if (openWindows.isNotEmpty()) {
                fireNotification(NOTIF_WINDOW, CHANNEL_WINDOW,
                    appContext.getString(R.string.notif_window_title),
                    openWindows.joinToString("\n")
                )
            } else cancelNotification(NOTIF_WINDOW)
        }

        Log.i(TAG, "Worker finished OK")
        return Result.success()
    }

    private fun fireNotification(id: Int, channel: String, title: String, text: String) {
        val pi = PendingIntent.getActivity(
            appContext, id,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(appContext, channel)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(if (channel == CHANNEL_ALERT) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(appContext).notify(id, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted: ${e.message}")
        }
    }

    private fun cancelNotification(id: Int) = NotificationManagerCompat.from(appContext).cancel(id)
}
