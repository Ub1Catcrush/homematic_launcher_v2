package com.tvcs.homematic

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import com.homematic.Datapoint
import com.homematic.Room

class RoomAdapter(context: Context, rooms: List<Room>) : ArrayAdapter<Room>(context, -1, rooms) {

    private val rooms: List<Room> = rooms
    private val inflater: LayoutInflater = LayoutInflater.from(context)

    // Text size in SP — respects user font scaling
    private val textSizeSp = 12f

    // Direct reference — STATE_DEVICES is a val Set, no conversion or allocation per call
    private val stateDeviceSet: Set<String> = HomeMatic.STATE_DEVICES

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        // Always inflate fresh — StaggeredGridView doesn't recycle in a standard way,
        // and the per-view ID map must be local to avoid cross-item corruption.
        val rowView = inflater.inflate(R.layout.room_item, parent, false)
        val tableLayout = rowView.findViewById<TableLayout>(R.id.item_content)
        val titleTextView = rowView.findViewById<TextView>(R.id.item_title_text)

        val room = rooms[position]
        titleTextView.text = room.name

        // Pass titleTextView directly — avoids rootView.findViewById traversal
        addRoomView(tableLayout, room, titleTextView)
        return rowView
    }

    private fun addRoomView(table: TableLayout, room: Room, titleView: TextView) {
        if (room.channels.isEmpty()) return

        val outdoorName = HomeMatic.getOutdoorRoomName()

        // First pass: does this room have any window sensors?
        val hasWindows = room.name != outdoorName && room.channels.any { channel ->
            val chan = HomeMatic.myChannels[channel.ise_id] ?: return@any false
            chan.datapoints.any { data ->
                if (data.type != Datapoint.TYPE_STATE) return@any false
                val devId = HomeMatic.myChannel2Device[chan.ise_id] ?: return@any false
                val dev = HomeMatic.myDevices[devId] ?: return@any false
                dev.device_type in stateDeviceSet
            }
        }

        // Indicator row — up to 3 window state slots
        val availIndicators = ArrayDeque<ImageView>(3)
        val indicatorRow = TableRow(context)

        indicatorRow.addView(
            if (hasWindows) makeTextView(context.getString(R.string.label_windows))
            else makeSpacer(minWidthDp = 100)
        )
        repeat(3) {
            indicatorRow.addView(makeSpacer())
            val indicator = makeIndicator()
            availIndicators.addLast(indicator)
            indicatorRow.addView(indicator)
        }
        table.addView(indicatorRow)

        // Per-tile view map — scoped here, avoids cross-item ID collisions
        val localViewMap = HashMap<Int, Int>(8)

        var temperature = 0.0
        var relHum = 0.0
        var hasSetTemp = false
        var hasActualTemp = false
        var lowBat = false

        for (channel in room.channels) {
            val chan = HomeMatic.myChannels[channel.ise_id] ?: continue

            for (data in chan.datapoints) {
                when (data.type) {

                    Datapoint.TYPE_LOWBAT ->
                        lowBat = data.value.equals("true", ignoreCase = true)

                    Datapoint.TYPE_SET_TEMPERATURE -> if (!hasSetTemp) {
                        table.addView(createDataRow(channel.ise_id,
                            context.getString(R.string.label_set_temp),
                            formatTemp(data.value) + data.valueunit, localViewMap))
                        hasSetTemp = true
                    }

                    Datapoint.TYPE_TEMPERATURE,
                    Datapoint.TYPE_ACTUAL_TEMPERATURE -> if (!hasActualTemp) {
                        temperature = data.value.toDoubleOrNull() ?: 0.0
                        table.addView(createDataRow(channel.ise_id,
                            context.getString(R.string.label_actual_temp),
                            formatTemp(data.value) + data.valueunit, localViewMap))
                        hasActualTemp = true
                    }

                    Datapoint.TYPE_HUMIDITY -> {
                        relHum = data.value.toDoubleOrNull() ?: 0.0
                        table.addView(createDataRow(channel.ise_id,
                            context.getString(R.string.label_humidity),
                            "${data.value}${data.valueunit}", localViewMap))
                    }

                    Datapoint.TYPE_STATE -> {
                        val devId = HomeMatic.myChannel2Device[chan.ise_id] ?: continue
                        val dev = HomeMatic.myDevices[devId] ?: continue
                        if (dev.device_type !in stateDeviceSet) continue
                        if (availIndicators.isEmpty()) continue
                        val indicator = availIndicators.removeLast()
                        localViewMap[chan.ise_id] = indicator.id
                        indicator.background = ColorDrawable(windowStateColor(data.value))
                    }
                }
            }
        }

        // Ventilation/LowBat warning → blink room title
        val warning = if (room.name != outdoorName && relHum > 0.0)
            HomeMatic.getWarning(relHum, temperature) >= 1 || lowBat
        else lowBat

        applyWarningAnimation(titleView, warning)
    }

    // ── View factory helpers ─────────────────────────────────────────────

    private fun makeTextView(text: String): TextView = TextView(context).apply {
        id = View.generateViewId()
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        setTextColor(Color.WHITE)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    private fun makeSpacer(minWidthDp: Int = 10): ImageView = ImageView(context).apply {
        id = View.generateViewId()
        minimumHeight = 1
        minimumWidth = dpToPx(minWidthDp)
    }

    private fun makeIndicator(): ImageView = ImageView(context).apply {
        id = View.generateViewId()
        scaleType = ImageView.ScaleType.FIT_XY
        val size = dpToPx(10)
        minimumHeight = size
        minimumWidth = size
        maxHeight = size
        maxWidth = size
        background = ColorDrawable(0xFF888888.toInt())  // default: grey = no data
    }

    private fun createDataRow(
        iseId: Int,
        label: String,
        value: String,
        localViewMap: HashMap<Int, Int>
    ): TableRow {
        val row = TableRow(context)
        val labelTv = makeTextView(label)
        val valueTv = TextView(context).apply {
            id = View.generateViewId()
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            setTextColor(Color.WHITE)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
        }
        row.addView(labelTv)
        row.addView(valueTv)
        localViewMap[iseId] = valueTv.id
        (valueTv.layoutParams as? TableRow.LayoutParams)?.apply {
            span = 5
            valueTv.layoutParams = this
        }
        return row
    }

    private fun applyWarningAnimation(view: View, warning: Boolean) {
        if (warning) {
            if (view.animation == null) {
                AlphaAnimation(0.2f, 1.0f).apply {
                    duration = 600
                    repeatMode = Animation.REVERSE
                    repeatCount = Animation.INFINITE
                    view.startAnimation(this)
                }
            }
        } else {
            view.clearAnimation()
        }
    }

    /** Maps HomeMatic STATE values to indicator colors. */
    private fun windowStateColor(value: String): Int = when (value) {
        "0", "false" -> 0xFF00FF00.toInt()   // geschlossen — grün
        "1"          -> 0xFFFFD700.toInt()   // gekippt     — gold
        "2", "true"  -> 0xFFFF0000.toInt()   // offen       — rot
        else         -> 0xFF888888.toInt()   // unbekannt   — grau
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun formatTemp(value: String): String =
        value.toFloatOrNull()?.let { "%.1f".format(it) } ?: value
}
