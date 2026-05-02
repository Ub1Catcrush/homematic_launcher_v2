package com.tvcs.homematic

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*

/**
 * DbTransitViewController — shows the next 3 departures for one of several
 * configured From→To connections, with Hin/Rück toggle.
 *
 * Layout per row (4 columns):
 *   Spalte 1: Linie        (z.B. "RE 1")          — feste Breite 56dp
 *   Spalte 2: Zeit + Richtung (mehrzeilig)         — flex
 *   Spalte 3: ✓ / +Xmin / Ausfall                 — feste Breite 44dp
 *   Spalte 4: Umstieg AAA in XXX 09:12 +Z          — feste Breite 110dp
 *
 * Header-Zeile:
 *   [← Hin/Rück →]  [< Verbindung 1/3 >]  [Refresh-Zeitstempel]
 */
class DbTransitViewController(
    private val context: Context,
    private val panel:       LinearLayout,
    private val headerLabel: TextView,
    private val row1:        TransitRowView,
    private val row2:        TransitRowView,
    private val row3:        TransitRowView,
    private val errorLabel:  TextView
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG                = "DbTransitVC"
        private const val REFRESH_INTERVAL_S = 60L
    }

    private val prefs   = PreferenceManager.getDefaultSharedPreferences(context)
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var refreshJob: Job? = null

    /** true = Hinfahrt (A→B), false = Rückfahrt (B→A) */
    private var isHinfahrt = true

    /** Index of the currently displayed connection (0-based) */
    private var connectionIndex = 0

    // ── Header controls (created once, inserted before the existing rows) ─────

    private val controlRow: LinearLayout by lazy { buildControlRow() }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun attachToLifecycle(owner: LifecycleOwner) = owner.lifecycle.addObserver(this)

    override fun onStart(owner: LifecycleOwner)   { if (isEnabled()) startRefreshing() }
    override fun onStop(owner: LifecycleOwner)    { stopRefreshing() }
    override fun onDestroy(owner: LifecycleOwner) { ioScope.cancel() }

    // ── Public API ────────────────────────────────────────────────────────────

    fun applyPrefsChange() {
        stopRefreshing()
        if (isEnabled()) {
            // Reset to connection 0 when prefs change
            connectionIndex = 0
            startRefreshing()
        } else {
            hide()
        }
    }

    fun isEnabled(): Boolean {
        if (!prefs.getBoolean(PreferenceKeys.TRANSIT_ENABLED, false)) return false
        return getConnections().isNotEmpty()
    }

    // ── Control row ───────────────────────────────────────────────────────────

    private fun buildControlRow(): LinearLayout {
        val dp = context.resources.displayMetrics.density
        val isLand = context.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val minTouchPx = ((if (isLand) 40 else 48) * dp).toInt()
        val iconSp     = if (isLand) 15f else 18f
        val labelSp    = if (isLand) 10f else 11f

        /** Shared factory for the three icon buttons (⇄  ‹  ›) */
        fun iconBtn(label: String, onClick: () -> Unit) = TextView(context).apply {
            text      = label
            textSize  = iconSp
            setTextColor(0xFFFFFFFF.toInt())
            gravity   = Gravity.CENTER
            minWidth  = minTouchPx
            minHeight = minTouchPx
            setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
            isFocusable  = true
            isClickable  = true
            setOnClickListener { onClick() }
            // Ripple-style background so the user gets visual feedback
            val attrs  = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
            val ta     = context.obtainStyledAttributes(attrs)
            background = ta.getDrawable(0)
            ta.recycle()
        }

        // Hin/Rück toggle
        val btnToggle = iconBtn("⇄") {
            isHinfahrt = !isHinfahrt
            refreshNow()
        }

        // Connection prev/next + label
        val btnPrev = iconBtn("‹") {
            val count = getConnections().size
            if (count > 1) {
                connectionIndex = (connectionIndex - 1 + count) % count
                refreshNow()
            }
        }

        val tvConnLabel = TextView(context).apply {
            id        = View.generateViewId()
            textSize  = labelSp
            setTextColor(0xCCFFFFFF.toInt())
            gravity   = Gravity.CENTER
            minHeight = minTouchPx
            setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
        }

        val btnNext = iconBtn("›") {
            val count = getConnections().size
            if (count > 1) {
                connectionIndex = (connectionIndex + 1) % count
                refreshNow()
            }
        }

        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // Reduced vertical padding since buttons already carry their min-height
            setPadding((4 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt(), (2 * dp).toInt())
            gravity = Gravity.CENTER_VERTICAL

            addView(btnToggle)
            addView(spacer)
            addView(btnPrev)
            addView(tvConnLabel)
            addView(btnNext)

            tag = tvConnLabel
        }
    }

    private fun updateControlRow(connections: List<ConnectionConfig>) {
        val label = controlRow.tag as? TextView ?: return
        val count = connections.size
        val direction = if (isHinfahrt)
            context.getString(R.string.transit_hin)
        else
            context.getString(R.string.transit_rueck)

        label.text = if (count > 1)
            "$direction  ${connectionIndex + 1}/$count"
        else
            direction
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun startRefreshing() {
        show()
        ensureControlRowAttached()
        refreshJob?.cancel()
        refreshJob = ioScope.launch {
            while (isActive) {
                refresh()
                delay(REFRESH_INTERVAL_S * 1_000L)
            }
        }
    }

    private fun stopRefreshing() { refreshJob?.cancel(); refreshJob = null }

    private fun refreshNow() {
        refreshJob?.cancel()
        refreshJob = ioScope.launch {
            refresh()
            delay(REFRESH_INTERVAL_S * 1_000L)
            while (isActive) {
                refresh()
                delay(REFRESH_INTERVAL_S * 1_000L)
            }
        }
    }

    private fun ensureControlRowAttached() {
        if (controlRow.parent == null) {
            // Insert as first child of panel (before the header label which is index 0 externally,
            // but the panel may contain the headerLabel + rows. We add to panel at index 0.)
            panel.addView(controlRow, 0)
        }
    }

    private suspend fun refresh() {
        val connections = getConnections()
        if (connections.isEmpty()) {
            withContext(Dispatchers.Main) { hide() }
            return
        }

        // Clamp index
        if (connectionIndex >= connections.size) connectionIndex = 0
        val conn = connections[connectionIndex]

        val fromId   = if (isHinfahrt) conn.fromId   else conn.toId
        val toId     = if (isHinfahrt) conn.toId     else conn.fromId
        val fromName = if (isHinfahrt) conn.fromName else conn.toName
        val toName   = if (isHinfahrt) conn.toName   else conn.fromName

        val watchedStations = prefs
            .getString(PreferenceKeys.TRANSIT_WATCHED_STATIONS, "") ?: ""
        val watchedList = watchedStations
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        when (val result = DbTransitRepository.getDepartures(fromId, toId, watchedList)) {
            is DbTransitRepository.Result.Success -> {
                val deps = result.data
                withContext(Dispatchers.Main) {
                    headerLabel.text = context.getString(R.string.transit_header, fromName, toName)
                    errorLabel.visibility = View.GONE
                    updateControlRow(connections)
                    val rows = listOf(row1, row2, row3)
                    rows.forEachIndexed { idx, row ->
                        val dep = deps.getOrNull(idx)
                        if (dep != null) { row.bind(dep); row.visibility = View.VISIBLE }
                        else row.visibility = View.GONE
                    }
                    if (deps.isEmpty()) {
                        errorLabel.text = context.getString(R.string.transit_no_departures)
                        errorLabel.visibility = View.VISIBLE
                    }
                }
            }
            is DbTransitRepository.Result.Error -> {
                Log.w(TAG, "Refresh error: ${result.message}")
                withContext(Dispatchers.Main) {
                    errorLabel.text = context.getString(R.string.transit_error, result.message)
                    errorLabel.visibility = View.VISIBLE
                    updateControlRow(connections)
                }
            }
        }
    }

    // ── Connection config parsing ─────────────────────────────────────────────

    data class ConnectionConfig(
        val fromId: String, val fromName: String,
        val toId:   String, val toName:   String
    )

    private fun getConnections(): List<ConnectionConfig> {
        val result = mutableListOf<ConnectionConfig>()

        // Connection 0: the primary (existing keys)
        val f0Id   = prefs.getString(PreferenceKeys.TRANSIT_FROM_ID,   "") ?: ""
        val f0Name = prefs.getString(PreferenceKeys.TRANSIT_FROM_NAME, "") ?: ""
        val t0Id   = prefs.getString(PreferenceKeys.TRANSIT_TO_ID,     "") ?: ""
        val t0Name = prefs.getString(PreferenceKeys.TRANSIT_TO_NAME,   "") ?: ""
        if (f0Id.isNotBlank() && t0Id.isNotBlank()) {
            result += ConnectionConfig(f0Id, f0Name, t0Id, t0Name)
        }

        // Connections 1-4: extra connections stored as JSON array string
        val extrasJson = prefs.getString(PreferenceKeys.TRANSIT_EXTRA_CONNECTIONS, "") ?: ""
        if (extrasJson.isNotBlank()) {
            try {
                val arr = org.json.JSONArray(extrasJson)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val fId   = o.optString("fromId",   "")
                    val fName = o.optString("fromName", "")
                    val tId   = o.optString("toId",     "")
                    val tName = o.optString("toName",   "")
                    if (fId.isNotBlank() && tId.isNotBlank()) {
                        result += ConnectionConfig(fId, fName, tId, tName)
                    }
                }
            } catch (_: Exception) { }
        }
        return result
    }

    private fun show() { panel.visibility = View.VISIBLE }
    private fun hide() { panel.visibility = View.GONE    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TransitRowView — 4-Spalten-Layout
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A single departure row with 4 columns:
 *
 *  Col 1 (56dp)  | Col 2 (0, weight=1)     | Col 3 (44dp) | Col 4 (110dp)
 *  Linie         | Zeit                     | ✓ / +Xmin    | Umstieg-Info
 *                | Richtung (2nd line)       | Ausfall      |
 */
class TransitRowView(context: Context) : LinearLayout(context) {

    private val dp = resources.displayMetrics.density
    private val isLandscape get() = resources.configuration.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private val tvLine      = TextView(context)
    private val colMid      = LinearLayout(context)
    private val tvTime      = TextView(context)
    private val tvDirection = TextView(context)
    private val tvDelay     = TextView(context)
    private val tvTransfer  = TextView(context)

    init {
        orientation = HORIZONTAL

        // In landscape the transit panel is ~half the screen width → tighter sizing
        val land = isLandscape
        val vPad     = if (land) (2 * dp).toInt() else (3 * dp).toInt()
        val hPad     = if (land) (4 * dp).toInt() else (8 * dp).toInt()
        val lineW    = if (land) (44 * dp).toInt() else (56 * dp).toInt()
        val delayW   = if (land) (34 * dp).toInt() else (44 * dp).toInt()
        val delayM   = if (land) (4  * dp).toInt() else (8  * dp).toInt()
        val transferW= if (land) (80 * dp).toInt() else (110* dp).toInt()
        val tsLine   = if (land) 10f else 12f
        val tsTime   = if (land) 11f else 13f
        val tsDir    = if (land)  9f else 10f
        val tsDelay  = if (land) 10f else 12f
        val tsXfer   = if (land)  9f else 10f

        setPadding(hPad, vPad, hPad, vPad)

        // ── Col 1: Linie ──────────────────────────────────────────────────────
        tvLine.apply {
            textSize = tsLine
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LayoutParams(lineW, LayoutParams.WRAP_CONTENT)
        }

        // ── Col 2: Zeit + Richtung ────────────────────────────────────────────
        tvTime.apply {
            textSize = tsTime
            setTextColor(Color.WHITE)
        }
        tvDirection.apply {
            textSize = tsDir
            setTextColor(0xAAFFFFFF.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        colMid.apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            addView(tvTime)
            addView(tvDirection)
        }

        // ── Col 3: Verspätung / Haken ─────────────────────────────────────────
        tvDelay.apply {
            textSize = tsDelay
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(delayW, LayoutParams.MATCH_PARENT).also {
                it.setMargins(delayM, 0, delayM, 0)
            }
        }

        // ── Col 4: Umstieg-Info ───────────────────────────────────────────────
        tvTransfer.apply {
            textSize = tsXfer
            setTextColor(0xAAFFFFFF.toInt())
            maxLines = 2
            layoutParams = LayoutParams(transferW, LayoutParams.WRAP_CONTENT)
        }

        addView(tvLine)
        addView(colMid)
        addView(tvDelay)
        addView(tvTransfer)
    }

    fun bind(dep: DbTransitRepository.Departure) {
        tvLine.text = dep.line

        if (dep.cancelled) {
            tvTime.text  = dep.plannedTime
            tvTime.setTextColor(0xFFFF4444.toInt())
            tvDelay.text = context.getString(R.string.transit_cancelled)
            tvDelay.setTextColor(0xFFFF4444.toInt())
        } else {
            tvTime.setTextColor(Color.WHITE)
            val rt    = dep.realtimeTime
            val delay = dep.delayMinutes

            tvTime.text = if (rt != null && rt != dep.plannedTime) rt else dep.plannedTime

            when {
                delay == null -> {
                    tvDelay.text = ""
                }
                delay <= 0 -> {
                    tvDelay.text = context.getString(R.string.transit_on_time)
                    tvDelay.setTextColor(0xFF66DD66.toInt())
                }
                delay in 1..5 -> {
                    tvDelay.text = context.getString(R.string.transit_delay_min, delay)
                    tvDelay.setTextColor(0xFFFFAA00.toInt())
                }
                else -> {
                    tvDelay.text = context.getString(R.string.transit_delay_min, delay)
                    tvDelay.setTextColor(0xFFFF4444.toInt())
                }
            }
        }

        tvDirection.text = dep.direction

        // ── Spalte 4: Umstieg-Info ────────────────────────────────────────────
        val ti = dep.transferInfo
        if (ti != null) {
            val delayStr = when {
                ti.delayMinutes == null -> ""
                ti.delayMinutes <= 0   -> ""
                else                   -> " +${ti.delayMinutes}'"
            }
            // Format: "→ Hamburg Hbf\n09:12 +3'"
            val shortName = if (ti.stationName.length > 14)
                ti.stationName.take(13) + "…"
            else
                ti.stationName
            tvTransfer.text = "→ $shortName\n${ti.arrivalTime}$delayStr"
            tvTransfer.visibility = View.VISIBLE
        } else {
            tvTransfer.text = ""
            tvTransfer.visibility = View.GONE
        }
    }
}
