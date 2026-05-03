package com.tvcs.homematic

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.homematic.Room

/**
 * RoomAdapter — no longer calls the HomeMatic singleton directly.
 * All data access goes through [HmRepository] which is injected at construction
 * time. In production pass [HomeMatic.asRepository()]; in tests inject
 * [FakeHmRepository] to avoid any CCU / Android dependency.
 */
class RoomAdapter(
    private val context: Context,
    private val rooms: MutableList<Room>,
    private val repo: HmRepository,
    var weatherVC: WeatherViewController? = null
) :
    RecyclerView.Adapter<RoomAdapter.ViewHolder>() {

    private val isLandscape get() = context.resources.configuration.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private val textSizeSp get() = AppThemeHelper.fontRoomData(context).let { if (isLandscape) it * 0.87f else it }
    private val textColor    get() = AppThemeHelper.textRoom(context)
    private val textColorDim get() = AppThemeHelper.textDim(context)

    var onSetTemperatureRequest: ((iseId: Int, currentValue: Double, roomName: String) -> Unit)? = null
    var onRoomTapped: ((room: Room) -> Unit)? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tableLayout:   TableLayout = view.findViewById(R.id.item_content)
        val titleTextView: TextView    = view.findViewById(R.id.item_title_text)
    }

    companion object {
        private const val TYPE_WEATHER = 0
        private const val TYPE_ROOM    = 1
    }

    private fun hasWeatherTile(): Boolean {
        val wvc = weatherVC
        return wvc != null && wvc.isEnabled() && wvc.displayMode() == "room" && wvc.lastForecast != null
    }

    override fun getItemViewType(position: Int): Int =
        if (position == 0 && hasWeatherTile()) TYPE_WEATHER else TYPE_ROOM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.room_item, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.tableLayout.removeAllViews()
        holder.titleTextView.clearAnimation()

        if (getItemViewType(position) == TYPE_WEATHER) {
            holder.titleTextView.text = context.getString(R.string.weather_tile_title)
            holder.tableLayout.addView(weatherVC!!.buildRoomTile())
            holder.itemView.setOnClickListener(null)
            return
        }

        val roomIdx = if (hasWeatherTile()) position - 1 else position
        val room = rooms[roomIdx]
        holder.titleTextView.text = room.name
        holder.titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, AppThemeHelper.fontRoomTitle(context))
        holder.titleTextView.setTextColor(AppThemeHelper.textRoom(context))

        try {
            addRoomView(holder.tableLayout, room, holder.titleTextView)
        } catch (e: Exception) {
            Log.e("RoomAdapter", "Failed to build tile for room '${room.name}': ${e.message}", e)
            showErrorTile(holder.tableLayout, room.name)
        }

        holder.itemView.setOnClickListener { onRoomTapped?.invoke(room) }
    }

    override fun getItemCount(): Int = rooms.size + if (hasWeatherTile()) 1 else 0

    // ── DiffUtil ──────────────────────────────────────────────────────────────

    fun rebindAll(newRooms: List<Room>) {
        rooms.clear(); rooms.addAll(newRooms); notifyDataSetChanged()
    }

    fun updateRooms(newRooms: List<Room>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = rooms.size
            override fun getNewListSize() = newRooms.size
            override fun areItemsTheSame(o: Int, n: Int) = rooms[o].ise_id == newRooms[n].ise_id
            override fun areContentsTheSame(o: Int, n: Int) = rooms[o] == newRooms[n]
        })
        rooms.clear(); rooms.addAll(newRooms); diff.dispatchUpdatesTo(this)
    }

    // ── Room tile builder ─────────────────────────────────────────────────────

private fun addRoomView(table: TableLayout, room: Room, titleView: TextView) {
        if (room.channels.isEmpty()) return

        val prof          = repo.profile
        val outdoorName   = repo.getOutdoorRoomName()
        val maxIndicators = repo.getMaxWindowIndicators()
        val isOutdoor     = room.name == outdoorName

        // ── Single pass: collect everything ───────────────────────────────────
        // Use "best wins" logic: keep the first non-zero value per measurement,
        // since a room may have multiple sensors but we only want one row each.
        var actualTemp   = 0.0
        var relHum       = 0.0
        // For set-temp: one thermostat may have multiple channels (e.g. 3× eTRV
        // in the same room). We want exactly ONE set-temp row → take the first.
        var setTempVal   = Double.MIN_VALUE   // sentinel = not found
        var setTempIseId = -1
        var notifType: String? = null

        // Window: count by device (not channel) to avoid double-counting
        val seenWindowDevIds = mutableSetOf<Int>()
        var openCount    = 0
        var tiltedCount  = 0

        room.channels.forEach { rc ->
            val chan  = repo.myChannels[rc.ise_id] ?: return@forEach
            val devId = repo.myChannel2Device[chan.ise_id]
            val dev   = devId?.let { repo.myDevices[it] }

            // ── Notifications ─────────────────────────────────────────────────
            dev?.let { d ->
                val n = repo.myNotifications[d.name]
                if (n != null) {
                    val sev = repo.notificationSeverity(n.type, prof)
                    val curSev = notifType?.let { repo.notificationSeverity(it, prof) } ?: -1
                    if (sev > curSev) notifType = n.type
                }
            }

            // ── Window indicators (per unique device) ─────────────────────────
            if (!isOutdoor && devId != null && dev != null
                    && dev.device_type in prof.windowDeviceTypes
                    && seenWindowDevIds.add(devId)) {
                val state = chan.datapoints
                    .firstOrNull { it.type in prof.stateFields }?.value ?: ""
                when {
                    state in prof.stateOpenValues   -> openCount++
                    state in prof.stateTiltedValues -> tiltedCount++
                    // else: closed — counted via seenWindowDevIds.size
                }
            }

            // ── Datapoints ────────────────────────────────────────────────────
            chan.datapoints.forEach { dp ->
                val v = dp.value.replace(",", ".").toDoubleOrNull() ?: return@forEach
                when (dp.type) {
                    in prof.actualTempFields ->
                        if (actualTemp == 0.0 && v != 0.0) actualTemp = v
                    in prof.humidityFields ->
                        if (relHum == 0.0 && v != 0.0) relHum = v
                    in prof.setTempFields ->
                        // Only record set-temp once, only for thermostat channels
                        if (setTempIseId == -1
                                && v > 0.0
                                && dev?.device_type in prof.thermostatDeviceTypes) {
                            setTempVal   = v
                            setTempIseId = dp.ise_id
                        }
                }
            }
        }

        val windowCount = seenWindowDevIds.size

        // ── Row 1: window indicators  [title row already has room name] ───────
        if (windowCount > 0) {
            val countToShow  = windowCount.coerceAtMost(maxIndicators)
            val closedCount  = windowCount - openCount - tiltedCount

            // Build a plain TableRow: col-0 is empty (label side), col-1 has squares
            val indicatorRow = TableRow(context).apply {
                setPadding(0, 2, 0, 4)
            }
            // Empty label cell so the squares align with the value column
            indicatorRow.addView(View(context).apply {
                layoutParams = TableRow.LayoutParams(0,
                    TableRow.LayoutParams.WRAP_CONTENT, 1f)
            })
            // Squares container (right-aligned)
            val squaresLayout = LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            repeat(countToShow) { i ->
                val color = when {
                    i < openCount                         -> AppThemeHelper.windowOpen(context)
                    i < openCount + tiltedCount           -> AppThemeHelper.windowTilted(context)
                    else                                  -> AppThemeHelper.windowClosed(context)
                }
                squaresLayout.addView(buildIndicatorSquare(color))
            }
            if (windowCount > maxIndicators) {
                squaresLayout.addView(TextView(context).apply {
                    text = "+${windowCount - maxIndicators}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp * 0.75f)
                    setTextColor(textColorDim)
                    setPadding(4, 0, 0, 0)
                    gravity = Gravity.CENTER_VERTICAL
                })
            }
            indicatorRow.addView(squaresLayout)
            table.addView(indicatorRow)
        }

        // ── Row 2: set temperature ────────────────────────────────────────────
        if (setTempIseId != -1) {
            val capturedId  = setTempIseId
            val capturedVal = setTempVal
            addDataRow(
                table,
                context.getString(R.string.label_set_temp),
                "%.1f °C".format(capturedVal),
                dimmed = false,
                onLongClick = { onSetTemperatureRequest?.invoke(capturedId, capturedVal, room.name) }
            )
        }

        // ── Row 3: actual temperature ─────────────────────────────────────────
        if (actualTemp != 0.0) {
            val moldWarning = !isOutdoor && relHum > 0.0 && repo.getWarning(relHum, actualTemp) >= 1
            addDataRow(
                table,
                context.getString(R.string.label_temp),
                "%.1f °C".format(actualTemp),
                dimmed = false,
                blinking = moldWarning
            )
        }

        // ── Row 4: humidity ───────────────────────────────────────────────────
        if (relHum > 0.0) {
            val moldWarning = !isOutdoor && actualTemp != 0.0 && repo.getWarning(relHum, actualTemp) >= 1
            addDataRow(
                table,
                context.getString(R.string.label_humidity),
                "${relHum.toInt()} %",
                dimmed = false,
                blinking = moldWarning
            )
        }

        // ── Row 5: notification badge ─────────────────────────────────────────
        if (notifType != null) {
            val label = when {
                notifType in prof.lowbatFields   -> context.getString(R.string.notif_lowbat)
                notifType in prof.sabotageFields -> context.getString(R.string.notif_sabotage)
                notifType in prof.faultFields    -> context.getString(R.string.notif_fault)
                else                             -> notifType ?: ""
            }
            addDataRow(table, "⚠", label, dimmed = false, blinking = true,
                textColor = Color.YELLOW)
        }

        // ── Title blink for active notifications ──────────────────────────────
        if (notifType != null) {
            val duration = if (notifType in prof.sabotageFields) 300L else 600L
            titleView.startAnimation(AlphaAnimation(1f, 0.1f).apply {
                this.duration = duration
                repeatMode    = Animation.REVERSE
                repeatCount   = Animation.INFINITE
            })
        }
    }

    private fun buildIndicatorSquare(color: Int): View {
        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 10f, context.resources.displayMetrics).toInt()
        val marginPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 1f, context.resources.displayMetrics).toInt()
        return View(context).apply {
            layoutParams = TableRow.LayoutParams(sizePx, sizePx).also {
                it.setMargins(0, 0, marginPx, 0)
            }
            background = ColorDrawable(color)
        }
    }

    private fun addDataRow(
        table: TableLayout,
        label: String,
        value: String,
        dimmed: Boolean,
        blinking: Boolean = false,
        textColor: Int? = null,
        onLongClick: (() -> Unit)? = null
    ) {
        val row = TableRow(context)
        val lc  = textColor ?: if (dimmed) textColorDim else this.textColor
        val tv1 = TextView(context).apply {
            text = label; setTextColor(lc); setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tv2 = TextView(context).apply {
            text = value; setTextColor(lc); setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            gravity = Gravity.END
        }
        if (blinking) {
            listOf(tv1, tv2).forEach { tv ->
                tv.startAnimation(AlphaAnimation(1f, 0.1f).apply {
                    duration = 600; repeatMode = Animation.REVERSE; repeatCount = Animation.INFINITE
                })
            }
        }
        row.addView(tv1); row.addView(tv2)
        if (onLongClick != null) {
            row.isLongClickable = true
            row.setOnLongClickListener { onLongClick(); true }
        }
        table.addView(row)
    }

    private fun showErrorTile(table: TableLayout, roomName: String) {
        table.addView(TextView(context).apply {
            text = context.getString(R.string.room_tile_error, roomName)
            setTextColor(Color.RED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        })
    }
}
