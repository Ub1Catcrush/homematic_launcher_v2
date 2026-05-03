package com.tvcs.homematic

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*

/**
 * DbTransitViewController
 *
 * Row layout — 4 columns, always at the same horizontal positions:
 *
 *   Col 1  wrap+minWidth  Line name (line 1) + "+X" transfers (line 2, if >0)
 *   Col 2  wrap+minWidth  Departure time only  "HH:MM"
 *   Col 3  wrap+minWidth  "✓" / "+5'" / "Ausfall"
 *   Col 4  weight=1       All transit stops: origin → transfers → destination
 *                         with arrival times  (no walking legs)
 *
 * The number of visible rows is configurable (TRANSIT_ROW_COUNT, default 3).
 * The panel height stays fixed; if more rows than visible, the list scrolls.
 */
class DbTransitViewController(
    private val context:         Context,
    private val panel:           LinearLayout,
    private val headerLabel:     TextView,
    private val row1:            TransitRowView,
    private val row2:            TransitRowView,
    private val row3:            TransitRowView,
    private val errorLabel:      TextView,
    private val fragmentManager: androidx.fragment.app.FragmentManager
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG               = "DbTransitVC"
        private const val DEFAULT_REFRESH_S = 120L
        private const val DEFAULT_ROW_COUNT = 3
    }

    private val prefs   = PreferenceManager.getDefaultSharedPreferences(context)
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var refreshJob: Job? = null
    private var isHinfahrt      = true
    private var connectionIndex = 0

    private fun refreshIntervalS(): Long =
        prefs.getString(PreferenceKeys.TRANSIT_REFRESH_INTERVAL, DEFAULT_REFRESH_S.toString())
            ?.toLongOrNull()?.coerceAtLeast(30L) ?: DEFAULT_REFRESH_S

    private fun rowCount(): Int =
        prefs.getString(PreferenceKeys.TRANSIT_ROW_COUNT, DEFAULT_ROW_COUNT.toString())
            ?.toIntOrNull()?.coerceIn(1, 20) ?: DEFAULT_ROW_COUNT

    /** Cache for detail sheet. */
    private var lastDepartures: List<DbTransitRepository.Departure> = emptyList()

    // Dynamic rows — rebuilt when row count changes
    private var dynamicRows: MutableList<TransitRowView> = mutableListOf()
    private var rowScrollView: ScrollView? = null
    private var rowContainer: LinearLayout? = null

    private val controlRow: LinearLayout by lazy { buildControlRow() }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun attachToLifecycle(owner: LifecycleOwner) = owner.lifecycle.addObserver(this)
    override fun onStart(owner: LifecycleOwner)   { if (isEnabled()) startRefreshing() }
    override fun onStop(owner: LifecycleOwner)    { stopRefreshing() }
    override fun onDestroy(owner: LifecycleOwner) { ioScope.cancel() }

    fun applyPrefsChange() {
        stopRefreshing()
        // Rebuild row list in case row count changed
        rebuildRowViews()
        if (isEnabled()) { connectionIndex = 0; startRefreshing() } else hide()
    }

    fun isEnabled(): Boolean {
        if (!prefs.getBoolean(PreferenceKeys.TRANSIT_ENABLED, false)) return false
        return getConnections().isNotEmpty()
    }

    // ── Row infrastructure ─────────────────────────────────────────────────────

    /**
     * Rebuilds the scrollable row container inside the panel.
     * Called once on init and whenever TRANSIT_ROW_COUNT changes.
     */
    private fun rebuildRowViews() {
        val dp = context.resources.displayMetrics.density
        val count = rowCount()
        val isLand = context.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        // Height of a single row in dp (match the vPad used in TransitRowView)
        val rowHeightDp = if (isLand) 26 else 32
        val rowHeightPx = (rowHeightDp * dp).toInt()
        // Fixed panel content height = rowCount rows
        val scrollHeightPx = rowHeightPx * count

        // Remove old scroll container if present
        rowScrollView?.let { panel.removeView(it) }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        rowContainer = container

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                scrollHeightPx
            )
            isVerticalScrollBarEnabled = true
            addView(container)
        }
        rowScrollView = scroll

        // Insert scroll after header, before errorLabel
        val errorIdx = panel.indexOfChild(errorLabel)
        panel.addView(scroll, if (errorIdx >= 0) errorIdx else panel.childCount - 1)

        // Create fresh TransitRowViews
        dynamicRows.clear()
        repeat(count) {
            val row = TransitRowView(context)
            row.visibility = View.GONE
            container.addView(row)
            dynamicRows.add(row)
        }
    }

    private fun ensureRowViews() {
        if (rowContainer == null) rebuildRowViews()
    }

    // ── Control row ───────────────────────────────────────────────────────────

    private fun buildControlRow(): LinearLayout {
        val dp     = context.resources.displayMetrics.density
        val isLand = context.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val touch  = ((if (isLand) 40 else 48) * dp).toInt()
        val iconSp = if (isLand) 15f else 18f
        val labSp  = if (isLand) 10f else 11f

        fun btn(label: String, action: () -> Unit) = TextView(context).apply {
            text = label; textSize = iconSp
            setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            minWidth = touch; minHeight = touch
            setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
            isFocusable = true; isClickable = true
            setOnClickListener { action() }
            val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            background = ta.getDrawable(0); ta.recycle()
        }

        val btnToggle = btn("⇄") { isHinfahrt = !isHinfahrt; refreshNow() }
        val btnPrev   = btn("‹") {
            val n = getConnections().size
            if (n > 1) { connectionIndex = (connectionIndex - 1 + n) % n; refreshNow() }
        }
        val label = TextView(context).apply {
            id = View.generateViewId(); textSize = labSp
            setTextColor(0xCCFFFFFF.toInt()); gravity = Gravity.CENTER
            minHeight = touch; setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
        }
        val btnNext = btn("›") {
            val n = getConnections().size
            if (n > 1) { connectionIndex = (connectionIndex + 1) % n; refreshNow() }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((4 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt(), (2 * dp).toInt())
            gravity = Gravity.CENTER_VERTICAL
            addView(btnToggle)
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
            addView(btnPrev); addView(label); addView(btnNext)
            tag = label
        }
    }

    private fun updateControlRow(conns: List<ConnectionConfig>) {
        val tv  = controlRow.tag as? TextView ?: return
        val dir = if (isHinfahrt) context.getString(R.string.transit_hin)
                  else             context.getString(R.string.transit_rueck)
        tv.text = if (conns.size > 1) "$dir  ${connectionIndex + 1}/${conns.size}" else dir
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    private fun startRefreshing() {
        show(); ensureControlRow(); ensureRowViews(); refreshJob?.cancel()
        refreshJob = ioScope.launch { while (isActive) { refresh(); delay(refreshIntervalS() * 1_000L) } }
    }
    private fun stopRefreshing() { refreshJob?.cancel(); refreshJob = null }
    private fun refreshNow() {
        refreshJob?.cancel()
        refreshJob = ioScope.launch { refresh(); delay(refreshIntervalS() * 1_000L)
            while (isActive) { refresh(); delay(refreshIntervalS() * 1_000L) } }
    }
    private fun ensureControlRow() {
        if (controlRow.parent == null) panel.addView(controlRow, 0)
        // Tap the header label to force an immediate refresh
        headerLabel.isClickable = true
        headerLabel.isFocusable = true
        val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        headerLabel.background = ta.getDrawable(0)
        ta.recycle()
        headerLabel.setOnClickListener { refreshNow() }
    }

    private suspend fun refresh() {
        val conns = getConnections()
        if (conns.isEmpty()) { withContext(Dispatchers.Main) { hide() }; return }
        if (connectionIndex >= conns.size) connectionIndex = 0
        val conn = conns[connectionIndex]
        val fromId   = if (isHinfahrt) conn.fromId   else conn.toId
        val toId     = if (isHinfahrt) conn.toId     else conn.fromId
        val fromName = if (isHinfahrt) conn.fromName else conn.toName
        val toName   = if (isHinfahrt) conn.toName   else conn.fromName

        val rawUrl = prefs.getString(PreferenceKeys.TRANSIT_BASE_URL, DbTransitRepository.DEFAULT_BASE)
        val baseUrl = rawUrl?.trimEnd('/')?.ifBlank { DbTransitRepository.DEFAULT_BASE }
            ?: DbTransitRepository.DEFAULT_BASE

        when (val r = DbTransitRepository.getDepartures(baseUrl, fromId, toId,
            watchedStationNames = (prefs.getString(PreferenceKeys.TRANSIT_WATCHED_STATIONS, "") ?: "")
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        )) {
            is DbTransitRepository.Result.Success -> {
                val deps = r.data
                withContext(Dispatchers.Main) {
                    lastDepartures = deps
                    headerLabel.text = context.getString(R.string.transit_header, fromName, toName)
                    errorLabel.visibility = View.GONE
                    updateControlRow(conns)
                    ensureRowViews()
                    dynamicRows.forEachIndexed { idx, row ->
                        val dep = deps.getOrNull(idx)
                        if (dep != null) {
                            row.bind(dep); row.visibility = View.VISIBLE
                            row.setOnClickListener {
                                TransitDetailBottomSheet.show(fragmentManager, lastDepartures, idx)
                            }
                        } else { row.visibility = View.GONE; row.setOnClickListener(null) }
                    }
                    if (deps.isEmpty()) {
                        errorLabel.text = context.getString(R.string.transit_no_departures)
                        errorLabel.visibility = View.VISIBLE
                    }
                }
            }
            is DbTransitRepository.Result.Error -> {
                Log.w(TAG, "Refresh error: ${r.message}")
                withContext(Dispatchers.Main) {
                    errorLabel.text = context.getString(R.string.transit_error, r.message)
                    errorLabel.visibility = View.VISIBLE
                    updateControlRow(conns)
                }
            }
        }
    }

    // ── Connections ───────────────────────────────────────────────────────────

    data class ConnectionConfig(
        val fromId: String, val fromName: String,
        val toId:   String, val toName:   String
    )

    private fun getConnections(): List<ConnectionConfig> {
        val list = mutableListOf<ConnectionConfig>()
        val f0 = prefs.getString(PreferenceKeys.TRANSIT_FROM_ID,   "") ?: ""
        val t0 = prefs.getString(PreferenceKeys.TRANSIT_TO_ID,     "") ?: ""
        if (f0.isNotBlank() && t0.isNotBlank()) {
            list += ConnectionConfig(f0,
                prefs.getString(PreferenceKeys.TRANSIT_FROM_NAME, "") ?: "",
                t0, prefs.getString(PreferenceKeys.TRANSIT_TO_NAME, "") ?: "")
        }
        val extras = prefs.getString(PreferenceKeys.TRANSIT_EXTRA_CONNECTIONS, "") ?: ""
        if (extras.isNotBlank()) try {
            val arr = org.json.JSONArray(extras)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val fi = o.optString("fromId", ""); val ti = o.optString("toId", "")
                if (fi.isNotBlank() && ti.isNotBlank())
                    list += ConnectionConfig(fi, o.optString("fromName",""), ti, o.optString("toName",""))
            }
        } catch (_: Exception) {}
        return list
    }

    private fun show() { panel.visibility = View.VISIBLE }
    private fun hide() { panel.visibility = View.GONE }
}


// ─────────────────────────────────────────────────────────────────────────────
// TransitRowView
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Col 1  wrap+minWidth  Line name bold; line 2 "+X Umstiege" (no walks counted)
 * Col 2  wrap+minWidth  Time "HH:MM" only
 * Col 3  wrap+minWidth  "✓" / "+5'" / "Ausfall"
 * Col 4  weight=1       Origin → [transfer stops] → Final destination with times
 *                       INVISIBLE when not used so columns always align
 */
class TransitRowView(context: Context) : LinearLayout(context) {

    private val dp     = resources.displayMetrics.density
    private val isLand get() = resources.configuration.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // Col 1 — two-line: line name + optional "+X"
    private val colLine    = LinearLayout(context)
    private val tvLine     = TextView(context)
    private val tvXfer     = TextView(context)
    // Col 2 — time only
    private val tvTime     = TextView(context)
    // Col 3 — status
    private val tvStatus   = TextView(context)
    // Col 4 — stop summary
    private val tvStops    = TextView(context)

    init {
        orientation = HORIZONTAL
        isClickable = true; isFocusable = true
        val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        background = ta.getDrawable(0); ta.recycle()

        val land  = isLand
        val vPad  = if (land) (2 * dp).toInt() else (4 * dp).toInt()
        val hPad  = if (land) (4 * dp).toInt() else (6 * dp).toInt()
        val gap   = (8 * dp).toInt()
        setPadding(hPad, vPad, hPad, vPad)
        gravity = Gravity.TOP

        // Col 1: line name (bold) + transfer count below
        tvLine.apply {
            textSize = if (land) 11f else 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 1
        }
        tvXfer.apply {
            textSize = if (land) 9f else 10f
            setTextColor(0xAAFFFFFF.toInt())
            maxLines = 1
        }
        colLine.apply {
            orientation = VERTICAL
            gravity = Gravity.TOP
            minimumWidth = (52 * dp).toInt()
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.marginEnd = gap
            }
            addView(tvLine); addView(tvXfer)
        }

        // Col 2: time
        tvTime.apply {
            textSize = if (land) 12f else 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.TOP
            minWidth = (44 * dp).toInt()
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.marginEnd = gap
            }
        }

        // Col 3: status
        tvStatus.apply {
            textSize = if (land) 10f else 11f
            gravity = Gravity.TOP
            minWidth = (40 * dp).toInt()
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.marginEnd = gap
            }
        }

        // Col 4: stop summary fills rest
        tvStops.apply {
            textSize = if (land) 9f else 10f
            setTextColor(0xBBFFFFFF.toInt())
            gravity = Gravity.TOP
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        addView(colLine); addView(tvTime); addView(tvStatus); addView(tvStops)
    }

    fun bind(dep: DbTransitRepository.Departure) {
        // Col 1
        tvLine.text = dep.line
        if (dep.transfers > 0) {
            tvXfer.text = "+${dep.transfers}"
            tvXfer.visibility = View.VISIBLE
        } else {
            tvXfer.text = ""
            tvXfer.visibility = View.GONE
        }

        // Col 2 + 3
        if (dep.cancelled) {
            tvTime.text = dep.plannedTime
            tvTime.setTextColor(0xFFFF4444.toInt())
            tvStatus.text = context.getString(R.string.transit_cancelled)
            tvStatus.setTextColor(0xFFFF4444.toInt())
        } else {
            tvTime.setTextColor(Color.WHITE)
            tvTime.text = dep.realtimeTime ?: dep.plannedTime
            val d = dep.delayMinutes
            when {
                d == null -> { tvStatus.text = "" }
                d <= 0    -> { tvStatus.text = "✓";       tvStatus.setTextColor(0xFF66DD66.toInt()) }
                d <= 5    -> { tvStatus.text = "+${d}'";  tvStatus.setTextColor(0xFFFFAA00.toInt()) }
                else      -> { tvStatus.text = "+${d}'";  tvStatus.setTextColor(0xFFFF4444.toInt()) }
            }
        }

        // Col 4: build stop summary
        // Show: origin (dep time) → transfer station (arr time) → … → final dest (arr time)
        // Each transit leg contributes: its origin + (at transfers) its destination
        val legs = dep.legs
        if (legs.isNotEmpty()) {
            val sb = StringBuilder()
            legs.forEachIndexed { i, leg ->
                if (i == 0) {
                    // First leg: show origin with departure time
                    sb.append(leg.origin)
                    val t = leg.depRealtime ?: leg.depPlanned
                    if (t.isNotBlank()) sb.append(" $t")
                }
                // Every leg: show its destination with arrival time
                sb.append("\n→ ${leg.destination}")
                val t = leg.arrRealtime ?: leg.arrPlanned
                if (t.isNotBlank()) sb.append(" $t")
            }
            tvStops.text = sb.toString()
            tvStops.visibility = View.VISIBLE
        } else {
            tvStops.text = ""
            tvStops.visibility = View.INVISIBLE
        }
    }
}
