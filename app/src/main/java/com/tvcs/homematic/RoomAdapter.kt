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
import android.widget.ImageView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.homematic.Room

class RoomAdapter(private val context: Context, private val rooms: MutableList<Room>) :
    RecyclerView.Adapter<RoomAdapter.ViewHolder>() {

    private val textSizeSp = 12f

    var onSetTemperatureRequest: ((iseId: Int, currentValue: Double, roomName: String) -> Unit)? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tableLayout: TableLayout  = view.findViewById(R.id.item_content)
        val titleTextView: TextView   = view.findViewById(R.id.item_title_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.room_item, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.tableLayout.removeAllViews()
        holder.titleTextView.clearAnimation()
        val room = rooms[position]
        holder.titleTextView.text = room.name
        addRoomView(holder.tableLayout, room, holder.titleTextView)
    }

    override fun getItemCount(): Int = rooms.size

    fun updateRooms(newRooms: List<Room>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = rooms.size
            override fun getNewListSize() = newRooms.size
            override fun areItemsTheSame(o: Int, n: Int) = rooms[o].ise_id == newRooms[n].ise_id
            override fun areContentsTheSame(o: Int, n: Int) = rooms[o] == newRooms[n]
        })
        rooms.clear()
        rooms.addAll(newRooms)
        diff.dispatchUpdatesTo(this)
    }

    // ── Room tile builder ────────────────────────────────────────────────────

    private fun addRoomView(table: TableLayout, room: Room, titleView: TextView) {
        if (room.channels.isEmpty()) return

        // Snapshot profile once per tile — avoids repeated pref reads during bind
        val prof         = HomeMatic.profile
        val outdoorName  = HomeMatic.getOutdoorRoomName()
        val maxIndicators = HomeMatic.getMaxWindowIndicators()
        val isOutdoor    = room.name == outdoorName

        // ── Indicator row ────────────────────────────────────────────────────
        val availIndicators = ArrayDeque<ImageView>(maxIndicators)
        val indicatorRow    = TableRow(context)
        val hasWindows = !isOutdoor && room.channels.any { rc ->
            val chan  = HomeMatic.myChannels[rc.ise_id] ?: return@any false
            val devId = HomeMatic.myChannel2Device[chan.ise_id] ?: return@any false
            val dev   = HomeMatic.myDevices[devId] ?: return@any false
            dev.device_type in prof.windowDeviceTypes &&
                chan.datapoints.any { it.type in prof.stateFields }
        }
        indicatorRow.addView(
            if (hasWindows) makeTextView(context.getString(R.string.label_windows))
            else            makeSpacer(100)
        )
        repeat(maxIndicators) {
            indicatorRow.addView(makeSpacer(4))
            val ind = makeIndicator()
            availIndicators.addLast(ind)
            indicatorRow.addView(ind)
        }
        table.addView(indicatorRow)

        // ── Data rows ────────────────────────────────────────────────────────
        var actualTemp    = 0.0
        var relHum        = 0.0
        var hasActualTemp = false
        var hasSetTemp    = false
        var hasHumidity   = false
        var setTempIseId  = 0
        var setTempValue  = 0.0
        var notifType: String? = null

        for (rc in room.channels) {
            val chan  = HomeMatic.myChannels[rc.ise_id] ?: continue
            val devId = HomeMatic.myChannel2Device[chan.ise_id]
            val dev   = devId?.let { HomeMatic.myDevices[it] }

            // Notification badge — pick highest severity across all devices in room
            if (dev != null) {
                val n = HomeMatic.myNotifications[dev.name]
                if (n != null) {
                    val sev = HomeMatic.notificationSeverity(n.type, prof)
                    if (notifType == null || sev > HomeMatic.notificationSeverity(notifType, prof))
                        notifType = n.type
                }
            }

            for (dp in chan.datapoints) {
                when {
                    // ── Solltemperatur ───────────────────────────────────────
                    dp.type in prof.setTempFields && !hasSetTemp -> {
                        setTempIseId = dp.ise_id
                        setTempValue = dp.value.toDoubleOrNull() ?: 0.0
                        val row = table.addDataRow(
                            context.getString(R.string.label_set_temp),
                            formatTemp(dp.value) + dp.valueunit
                        )
                        if (setTempIseId > 0) {
                            row.setOnLongClickListener {
                                onSetTemperatureRequest?.invoke(setTempIseId, setTempValue, room.name)
                                true
                            }
                        }
                        hasSetTemp = true
                    }

                    // ── Ist-Temperatur ───────────────────────────────────────
                    dp.type in prof.actualTempFields && !hasActualTemp -> {
                        actualTemp = dp.value.toDoubleOrNull() ?: 0.0
                        table.addDataRow(
                            if (isOutdoor) context.getString(R.string.label_outdoor_temp)
                            else           context.getString(R.string.label_actual_temp),
                            formatTemp(dp.value) + dp.valueunit
                        )
                        hasActualTemp = true
                    }

                    // ── Luftfeuchtigkeit ─────────────────────────────────────
                    dp.type in prof.humidityFields && !hasHumidity -> {
                        relHum = dp.value.toDoubleOrNull() ?: 0.0
                        table.addDataRow(
                            if (isOutdoor) context.getString(R.string.label_outdoor_humidity)
                            else           context.getString(R.string.label_humidity),
                            "${dp.value}${dp.valueunit}"
                        )
                        hasHumidity = true
                    }

                    // ── Fensterzustand ───────────────────────────────────────
                    dp.type in prof.stateFields -> {
                        if (dev == null || dev.device_type !in prof.windowDeviceTypes) continue
                        if (availIndicators.isEmpty()) continue
                        val ind = availIndicators.removeLast()
                        ind.background = ColorDrawable(windowStateColor(dp.value, prof))
                    }
                }
            }
        }

        // ── Warning animation ────────────────────────────────────────────────
        val moldWarning = !isOutdoor && relHum > 0.0 && HomeMatic.getWarning(relHum, actualTemp) >= 1
        applyWarningAnimation(titleView, moldWarning || notifType != null, notifType)
    }

    // ── View factory helpers ─────────────────────────────────────────────────

    /** Adds a label/value row and returns it so callers can attach click listeners. */
    private fun TableLayout.addDataRow(label: String, value: String): TableRow {
        val row = TableRow(context)
        row.addView(makeTextView(label))
        val valueTv = TextView(context).apply {
            id = View.generateViewId()
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            setTextColor(Color.WHITE)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            (layoutParams as? TableRow.LayoutParams)?.span = 5
        }
        row.addView(valueTv)
        addView(row)
        return row
    }

    private fun makeTextView(text: String) = TextView(context).apply {
        id = View.generateViewId()
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        setTextColor(Color.WHITE)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    private fun makeSpacer(minWidthDp: Int = 10) = ImageView(context).apply {
        id = View.generateViewId()
        minimumHeight = 1
        minimumWidth  = dpToPx(minWidthDp)
    }

    private fun makeIndicator() = ImageView(context).apply {
        id = View.generateViewId()
        scaleType = ImageView.ScaleType.FIT_XY
        val sz = dpToPx(10)
        minimumHeight = sz; minimumWidth = sz; maxHeight = sz; maxWidth = sz
        background = ColorDrawable(0xFF888888.toInt())
    }

    // ── State colour — uses configurable profile values ───────────────────────

    private fun windowStateColor(value: String, prof: DeviceProfile): Int = when {
        value in prof.stateClosedValues -> 0xFF00FF00.toInt()   // green  — closed
        value in prof.stateTiltedValues -> 0xFFFFD700.toInt()   // gold   — tilted
        value in prof.stateOpenValues   -> 0xFFFF0000.toInt()   // red    — open
        else                            -> 0xFF888888.toInt()   // grey   — unknown
    }

    private fun applyWarningAnimation(view: View, warning: Boolean, notifType: String?) {
        if (warning) {
            if (view.animation != null) return
            val duration = if (notifType in HomeMatic.profile.sabotageFields) 300L else 600L
            AlphaAnimation(0.2f, 1.0f).apply {
                this.duration = duration
                repeatMode    = Animation.REVERSE
                repeatCount   = Animation.INFINITE
                view.startAnimation(this)
            }
        } else {
            view.clearAnimation()
        }
    }

    private fun dpToPx(dp: Int) = (dp * context.resources.displayMetrics.density + 0.5f).toInt()
    private fun formatTemp(v: String) = v.toFloatOrNull()?.let { "%.1f".format(it) } ?: v
}
