package com.tvcs.homematic

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
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

    /** Long-press on set-temperature row → thermostat slider dialog. */
    var onSetTemperatureRequest: ((iseId: Int, currentValue: Double, roomName: String) -> Unit)? = null
    /** Tap on room tile → detail bottom sheet (#14). */
    var onRoomTapped: ((room: Room) -> Unit)? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tableLayout:   TableLayout = view.findViewById(R.id.item_content)
        val titleTextView: TextView    = view.findViewById(R.id.item_title_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.room_item, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.tableLayout.removeAllViews()
        holder.titleTextView.clearAnimation()
        val room = rooms[position]
        holder.titleTextView.text = room.name

        // #1 — Error boundary: a bad datapoint must not crash the whole RecyclerView scroll
        try {
            addRoomView(holder.tableLayout, room, holder.titleTextView)
        } catch (e: Exception) {
            Log.e("RoomAdapter", "Failed to build tile for room '${room.name}': ${e.message}", e)
            showErrorTile(holder.tableLayout, room.name)
        }

        // #14 — Tap → detail bottom sheet
        holder.itemView.setOnClickListener { onRoomTapped?.invoke(room) }
    }

    override fun getItemCount(): Int = rooms.size

    // ── DiffUtil update ───────────────────────────────────────────────────────

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

    // ── Room tile builder ─────────────────────────────────────────────────────

    private fun addRoomView(table: TableLayout, room: Room, titleView: TextView) {
        if (room.channels.isEmpty()) return

        val prof          = HomeMatic.profile
        val outdoorName   = HomeMatic.getOutdoorRoomName()
        val maxIndicators = HomeMatic.getMaxWindowIndicators()
        val isOutdoor     = room.name == outdoorName

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
        var missingChannels = 0

        for (rc in room.channels) {
            // #3 — Log missing channels visibly instead of silently skipping
            val chan = HomeMatic.myChannels[rc.ise_id]
            if (chan == null) {
                missingChannels++
                Log.w("RoomAdapter", "Room '${room.name}': channel ise_id=${rc.ise_id} not found in state map")
                continue
            }

            val devId = HomeMatic.myChannel2Device[chan.ise_id]
            val dev   = devId?.let { HomeMatic.myDevices[it] }

            if (dev != null) {
                val n = HomeMatic.myNotifications[dev.name]
                if (n != null) {
                    val sev = HomeMatic.notificationSeverity(n.type, prof)
                    if (notifType == null || sev > HomeMatic.notificationSeverity(notifType!!, prof))
                        notifType = n.type
                }
            }

            for (dp in chan.datapoints) {
                when {
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
                    dp.type in prof.actualTempFields && !hasActualTemp -> {
                        actualTemp = dp.value.toDoubleOrNull() ?: 0.0
                        table.addDataRow(
                            if (isOutdoor) context.getString(R.string.label_outdoor_temp)
                            else           context.getString(R.string.label_actual_temp),
                            formatTemp(dp.value) + dp.valueunit
                        )
                        hasActualTemp = true
                    }
                    dp.type in prof.humidityFields && !hasHumidity -> {
                        relHum = dp.value.toDoubleOrNull() ?: 0.0
                        table.addDataRow(
                            if (isOutdoor) context.getString(R.string.label_outdoor_humidity)
                            else           context.getString(R.string.label_humidity),
                            "${dp.value}${dp.valueunit}"
                        )
                        hasHumidity = true
                    }
                    dp.type in prof.stateFields -> {
                        if (dev == null || dev.device_type !in prof.windowDeviceTypes) continue
                        if (availIndicators.isEmpty()) continue
                        val ind = availIndicators.removeLast()
                        ind.background = ColorDrawable(windowStateColor(dp.value, prof))
                    }
                }
            }
        }

        // #3 — Show a subtle indicator if data is inconsistent
        if (missingChannels > 0) {
            table.addDataRow(
                context.getString(R.string.label_data_warning),
                context.getString(R.string.label_channels_missing, missingChannels)
            ).also { row ->
                // Dim the row to distinguish from normal data
                row.alpha = 0.5f
            }
        }

        // #13 — Show data age when CCU is unreachable and we're showing stale data
        val loadTime = HomeMatic.lastLoadTime
        if (loadTime > 0L && HomeMatic.lastLoadError != null) {
            val ageSecs  = (System.currentTimeMillis() - loadTime) / 1000L
            val ageLabel = when {
                ageSecs < 60   -> context.getString(R.string.age_seconds, ageSecs)
                ageSecs < 3600 -> context.getString(R.string.age_minutes, ageSecs / 60)
                else           -> context.getString(R.string.age_hours,   ageSecs / 3600)
            }
            table.addDataRow(context.getString(R.string.label_data_age), ageLabel)
                .also { it.alpha = 0.5f }
        }

        val moldWarning = !isOutdoor && relHum > 0.0 && HomeMatic.getWarning(relHum, actualTemp) >= 1
        applyWarningAnimation(titleView, moldWarning || notifType != null, notifType)
    }

    /** Fallback tile shown when addRoomView() throws (#1). */
    private fun showErrorTile(table: TableLayout, roomName: String) {
        val row = TableRow(context)
        val tv  = makeTextView("⚠ ${context.getString(R.string.label_tile_error)}")
        tv.setTextColor(Color.YELLOW)
        row.addView(tv)
        table.addView(row)
    }

    // ── View factory helpers ──────────────────────────────────────────────────

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

    private fun windowStateColor(value: String, prof: DeviceProfile): Int = when {
        value in prof.stateClosedValues -> 0xFF00FF00.toInt()
        value in prof.stateTiltedValues -> 0xFFFFD700.toInt()
        value in prof.stateOpenValues   -> 0xFFFF0000.toInt()
        else                            -> 0xFF888888.toInt()
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
