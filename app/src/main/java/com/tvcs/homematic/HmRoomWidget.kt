package com.tvcs.homematic

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import androidx.preference.PreferenceManager
import androidx.work.*
import com.homematic.Datapoint
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * AppWidgetProvider — shows a single room's temperature, humidity, and window state
 * on the Android home screen.
 *
 * Configuration: the "displayed room" is the first room in the sorted room list.
 * A future HmRoomWidgetConfigureActivity could let the user pick a room per widget instance.
 *
 * Update cycle: Android calls onUpdate() at most every 30 min (OS enforced floor).
 * We also trigger an update from MainActivity after every successful CCU load.
 */
class HmRoomWidget : AppWidgetProvider() {

    companion object {
        private const val TAG = "HmRoomWidget"
        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** Call from MainActivity after a successful load to push fresh data to all widgets. */
        fun requestUpdate(context: Context) {
            val intent = Intent(context, HmRoomWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            context.sendBroadcast(intent)
        }

        internal fun updateAllWidgets(context: Context, manager: AppWidgetManager, ids: IntArray) {
            HomeMatic.init(context)
            val state = HomeMatic.state
            if (state == null || !HomeMatic.isLoaded) {
                Log.d(TAG, "State not loaded — skipping widget update")
                return
            }

            val prefs       = PreferenceManager.getDefaultSharedPreferences(context)
            val outdoorName = HomeMatic.getOutdoorRoomName()
            val stateDevSet = HomeMatic.STATE_DEVICES

            // Use the first non-outdoor room as the default widget room
            val room = state.roomList.rooms.firstOrNull { it.name != outdoorName }
                ?: state.roomList.rooms.firstOrNull()
                ?: return

            var actualTemp  = ""
            var setTemp     = ""
            var humidity    = ""
            var windowState = ""
            val notif       = state.notifications.values.firstOrNull { n ->
                room.channels.any { rc ->
                    val chan  = state.channels[rc.ise_id] ?: return@any false
                    val devId = state.channel2Device[chan.ise_id] ?: return@any false
                    val dev   = state.devices[devId] ?: return@any false
                    dev.name == n.name
                }
            }

            for (rc in room.channels) {
                val chan = state.channels[rc.ise_id] ?: continue
                for (dp in chan.datapoints) {
                    when (dp.type) {
                        Datapoint.TYPE_ACTUAL_TEMPERATURE, Datapoint.TYPE_TEMPERATURE ->
                            if (actualTemp.isEmpty()) actualTemp = "%.1f°C".format(dp.value.toFloatOrNull() ?: 0f)
                        Datapoint.TYPE_SET_TEMPERATURE ->
                            if (setTemp.isEmpty()) setTemp = context.getString(R.string.widget_set_temp_fmt, dp.value.toFloatOrNull() ?: 0f)
                        Datapoint.TYPE_HUMIDITY ->
                            if (humidity.isEmpty()) humidity = "${dp.value}${dp.valueunit}"
                        Datapoint.TYPE_STATE -> {
                            val devId = state.channel2Device[chan.ise_id] ?: continue
                            val dev   = state.devices[devId] ?: continue
                            if (dev.device_type !in stateDevSet) continue
                            if (windowState.isEmpty()) {
                                windowState = when (dp.value) {
                                    "0", "false" -> context.getString(R.string.widget_window_closed)
                                    "1"          -> context.getString(R.string.widget_window_tilted)
                                    "2", "true"  -> context.getString(R.string.widget_window_open)
                                    else         -> ""
                                }
                            }
                        }
                    }
                }
            }

            val badge = when (notif?.type) {
                "SABOTAGE"        -> "⚠ SABOTAGE"
                "FAULT_REPORTING" -> "⚠ FAULT"
                "LOWBAT"          -> "🔋 LOWBAT"
                else              -> ""
            }

            val lastUpdate = context.getString(R.string.widget_last_update, timeFormatter.format(LocalTime.now()))

            val tapIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_hm_room).apply {
                    setTextViewText(R.id.widget_room_name, room.name)
                    setTextViewText(R.id.widget_actual_temp, actualTemp.ifEmpty { "--.-°C" })
                    setTextViewText(R.id.widget_set_temp, setTemp)
                    setTextViewText(R.id.widget_humidity, humidity)
                    setTextViewText(R.id.widget_window_state, windowState)
                    setTextViewText(R.id.widget_notification_badge, badge)
                    setTextViewText(R.id.widget_last_update, lastUpdate)
                    setOnClickPendingIntent(R.id.widget_room_name, tapIntent)
                }
                manager.updateAppWidget(id, views)
            }
            Log.d(TAG, "Widget updated: room=${room.name} temp=$actualTemp hum=$humidity")
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAllWidgets(context, manager, ids)
    }
}
