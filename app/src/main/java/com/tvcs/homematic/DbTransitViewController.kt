package com.tvcs.homematic

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*

/**
 * DbTransitViewController
 *
 * Row layout — columns at fixed positions, full width:
 *   Col 1  wrap_content  Line name   e.g. "RE 10", "S 1 +2"
 *   Col 2  wrap_content  Time        "HH:MM" only, no direction
 *   Col 3  wrap_content  Status      "✓" / "+5'" / "Ausfall"
 *   Col 4  weight=1      Transfer    rest of width — Umstieg info
 *
 * All three data rows share the same parent width so weights give identical
 * column start positions regardless of content.
 */
class DbTransitViewController(
    private val context: Context,
    private val panel:           LinearLayout,
    private val headerLabel:     TextView,
    private val row1:            TransitRowView,
    private val row2:            TransitRowView,
    private val row3:            TransitRowView,
    private val errorLabel:      TextView,
    private val fragmentManager: androidx.fragment.app.FragmentManager
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG                = "DbTransitVC"
        private const val REFRESH_INTERVAL_S = 60L
    }

    private val prefs   = PreferenceManager.getDefaultSharedPreferences(context)
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var refreshJob: Job? = null

    private var isHinfahrt     = true
    private var connectionIndex = 0

    /** Most recently fetched departures — used by the detail sheet. */
    private var lastDepartures: List<DbTransitRepository.Departure> = emptyList()

    private val controlRow: LinearLayout by lazy { buildControlRow() }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun attachToLifecycle(owner: LifecycleOwner) = owner.lifecycle.addObserver(this)

    override fun onStart(owner: LifecycleOwner)   { if (isEnabled()) startRefreshing() }
    override fun onStop(owner: LifecycleOwner)    { stopRefreshing() }
    override fun onDestroy(owner: LifecycleOwner) { ioScope.cancel() }

    fun applyPrefsChange() {
        stopRefreshing()
        if (isEnabled()) { connectionIndex = 0; startRefreshing() } else hide()
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
        val minTouch = ((if (isLand) 40 else 48) * dp).toInt()
        val iconSp   = if (isLand) 15f else 18f
        val labelSp  = if (isLand) 10f else 11f

        fun iconBtn(label: String, action: () -> Unit) = TextView(context).apply {
            text = label; textSize = iconSp
            setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            minWidth = minTouch; minHeight = minTouch
            setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
            isFocusable = true; isClickable = true
            setOnClickListener { action() }
            val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            background = ta.getDrawable(0); ta.recycle()
        }

        val btnToggle = iconBtn("⇄") { isHinfahrt = !isHinfahrt; refreshNow() }
        val btnPrev   = iconBtn("‹") {
            val n = getConnections().size
            if (n > 1) { connectionIndex = (connectionIndex - 1 + n) % n; refreshNow() }
        }
        val tvLabel = TextView(context).apply {
            id = View.generateViewId(); textSize = labelSp
            setTextColor(0xCCFFFFFF.toInt()); gravity = Gravity.CENTER
            minHeight = minTouch; setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
        }
        val btnNext = iconBtn("›") {
            val n = getConnections().size
            if (n > 1) { connectionIndex = (connectionIndex + 1) % n; refreshNow() }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((4 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt(), (2 * dp).toInt())
            gravity = Gravity.CENTER_VERTICAL
            addView(btnToggle)
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
            addView(btnPrev); addView(tvLabel); addView(btnNext)
            tag = tvLabel
        }
    }

    private fun updateControlRow(connections: List<ConnectionConfig>) {
        val label = controlRow.tag as? TextView ?: return
        val dir   = if (isHinfahrt) context.getString(R.string.transit_hin)
                    else             context.getString(R.string.transit_rueck)
        label.text = if (connections.size > 1) "$dir  ${connectionIndex + 1}/${connections.size}" else dir
    }

    // ── Refresh loop ──────────────────────────────────────────────────────────

    private fun startRefreshing() {
        show(); ensureControlRowAttached(); refreshJob?.cancel()
        refreshJob = ioScope.launch {
            while (isActive) { refresh(); delay(REFRESH_INTERVAL_S * 1_000L) }
        }
    }

    private fun stopRefreshing() { refreshJob?.cancel(); refreshJob = null }

    private fun refreshNow() {
        refreshJob?.cancel()
        refreshJob = ioScope.launch {
            refresh(); delay(REFRESH_INTERVAL_S * 1_000L)
            while (isActive) { refresh(); delay(REFRESH_INTERVAL_S * 1_000L) }
        }
    }

    private fun ensureControlRowAttached() {
        if (controlRow.parent == null) panel.addView(controlRow, 0)
    }

    private suspend fun refresh() {
        val connections = getConnections()
        if (connections.isEmpty()) { withContext(Dispatchers.Main) { hide() }; return }
        if (connectionIndex >= connections.size) connectionIndex = 0
        val conn = connections[connectionIndex]

        val fromId   = if (isHinfahrt) conn.fromId   else conn.toId
        val toId     = if (isHinfahrt) conn.toId     else conn.fromId
        val fromName = if (isHinfahrt) conn.fromName else conn.toName
        val toName   = if (isHinfahrt) conn.toName   else conn.fromName

        val watched = (prefs.getString(PreferenceKeys.TRANSIT_WATCHED_STATIONS, "") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

        when (val result = DbTransitRepository.getDepartures(fromId, toId, watched)) {
            is DbTransitRepository.Result.Success -> {
                val deps = result.data
                withContext(Dispatchers.Main) {
                    lastDepartures = deps
                    headerLabel.text = context.getString(R.string.transit_header, fromName, toName)
                    errorLabel.visibility = View.GONE
                    updateControlRow(connections)
                    listOf(row1, row2, row3).forEachIndexed { idx, row ->
                        val dep = deps.getOrNull(idx)
                        if (dep != null) {
                            row.bind(dep)
                            row.visibility = View.VISIBLE
                            row.setOnClickListener {
                                TransitDetailBottomSheet.show(fragmentManager, lastDepartures, idx)
                            }
                        } else {
                            row.visibility = View.GONE
                            row.setOnClickListener(null)
                        }
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

    // ── Connection config ─────────────────────────────────────────────────────

    data class ConnectionConfig(
        val fromId: String, val fromName: String,
        val toId:   String, val toName:   String
    )

    private fun getConnections(): List<ConnectionConfig> {
        val result = mutableListOf<ConnectionConfig>()
        val f0 = prefs.getString(PreferenceKeys.TRANSIT_FROM_ID,   "") ?: ""
        val t0 = prefs.getString(PreferenceKeys.TRANSIT_TO_ID,     "") ?: ""
        if (f0.isNotBlank() && t0.isNotBlank()) {
            result += ConnectionConfig(
                f0, prefs.getString(PreferenceKeys.TRANSIT_FROM_NAME, "") ?: "",
                t0, prefs.getString(PreferenceKeys.TRANSIT_TO_NAME,   "") ?: ""
            )
        }
        val extrasJson = prefs.getString(PreferenceKeys.TRANSIT_EXTRA_CONNECTIONS, "") ?: ""
        if (extrasJson.isNotBlank()) {
            try {
                val arr = org.json.JSONArray(extrasJson)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val fId = o.optString("fromId", ""); val tId = o.optString("toId", "")
                    if (fId.isNotBlank() && tId.isNotBlank()) {
                        result += ConnectionConfig(fId, o.optString("fromName",""), tId, o.optString("toName",""))
                    }
                }
            } catch (_: Exception) { }
        }
        return result
    }

    private fun show() { panel.visibility = View.VISIBLE }
    private fun hide() { panel.visibility = View.GONE }
}

// ─────────────────────────────────────────────────────────────────────────────
// TransitRowView
// ─────────────────────────────────────────────────────────────────────────────

/**
 * One departure row. Column layout:
 *
 *   ┌──────────────┬───────┬──────┬─────────────────────┐
 *   │ Col 1        │ Col 2 │ Col 3│ Col 4               │
 *   │ Line         │ Time  │ Stat │ Transfer (fills rest)│
 *   │ wrap_content │ wrap  │ wrap │ weight=1             │
 *   └──────────────┴───────┴──────┴─────────────────────┘
 *
 * Cols 1-3 use wrap_content with explicit minWidth so they never shrink below
 * the widest text they ever need to show. This guarantees all rows align at the
 * same column boundaries regardless of content.
 *
 * Col 1 min = "S 10 +2" ≈ 7 chars → 56 dp
 * Col 2 min = "00:00"   ≈ 5 chars → 44 dp
 * Col 3 min = "+99'"    ≈ 4 chars → 40 dp
 */
class TransitRowView(context: Context) : LinearLayout(context) {

    private val dp = resources.displayMetrics.density
    private val isLand get() = resources.configuration.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private val tvLine     = TextView(context)
    private val tvTime     = TextView(context)
    private val tvStatus   = TextView(context)
    private val tvTransfer = TextView(context)

    init {
        orientation = HORIZONTAL
        isClickable = true; isFocusable = true
        val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        background = ta.getDrawable(0); ta.recycle()

        val land  = isLand
        val vPad  = if (land) (2 * dp).toInt() else (4 * dp).toInt()
        val hPad  = if (land) (4 * dp).toInt() else (6 * dp).toInt()
        // Gap between columns
        val gap   = (8 * dp).toInt()

        setPadding(hPad, vPad, hPad, vPad)

        // Col 1 — Line name: "RE 10", "S 1 +2", "ICE"
        // minWidth covers longest expected token; text is never clipped
        tvLine.apply {
            textSize  = if (land) 11f else 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity   = Gravity.CENTER_VERTICAL
            maxLines  = 1
            minWidth  = (56 * dp).toInt()
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).also {
                it.marginEnd = gap
            }
        }

        // Col 2 — Departure time only: "HH:MM"
        tvTime.apply {
            textSize  = if (land) 12f else 13f
            setTextColor(Color.WHITE)
            gravity   = Gravity.CENTER_VERTICAL
            maxLines  = 1
            minWidth  = (44 * dp).toInt()
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).also {
                it.marginEnd = gap
            }
        }

        // Col 3 — Status: "✓", "+5'", "Ausfall"
        tvStatus.apply {
            textSize  = if (land) 10f else 11f
            gravity   = Gravity.CENTER_VERTICAL
            maxLines  = 1
            minWidth  = (40 * dp).toInt()
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).also {
                it.marginEnd = gap
            }
        }

        // Col 4 — Transfer info: fills all remaining width
        tvTransfer.apply {
            textSize  = if (land) 9f else 10f
            setTextColor(0xBBFFFFFF.toInt())
            gravity   = Gravity.CENTER_VERTICAL
            maxLines  = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            // INVISIBLE (not GONE) so column width is always reserved
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        }

        addView(tvLine)
        addView(tvTime)
        addView(tvStatus)
        addView(tvTransfer)
    }

    fun bind(dep: DbTransitRepository.Departure) {
        tvLine.text = dep.line

        if (dep.cancelled) {
            tvTime.text = dep.plannedTime
            tvTime.setTextColor(0xFFFF4444.toInt())
            tvStatus.text = context.getString(R.string.transit_cancelled)
            tvStatus.setTextColor(0xFFFF4444.toInt())
        } else {
            tvTime.setTextColor(Color.WHITE)
            tvTime.text = dep.realtimeTime ?: dep.plannedTime

            val delay = dep.delayMinutes
            when {
                delay == null -> { tvStatus.text = ""; }
                delay <= 0    -> { tvStatus.text = "✓";  tvStatus.setTextColor(0xFF66DD66.toInt()) }
                delay <= 5    -> { tvStatus.text = "+${delay}'"; tvStatus.setTextColor(0xFFFFAA00.toInt()) }
                else          -> { tvStatus.text = "+${delay}'"; tvStatus.setTextColor(0xFFFF4444.toInt()) }
            }
        }

        val ti = dep.transferInfo
        if (ti != null) {
            val dStr = if ((ti.delayMinutes ?: 0) > 0) " +${ti.delayMinutes}'" else ""
            tvTransfer.text = "→ ${ti.stationName}\n${ti.arrivalTime}$dStr"
            tvTransfer.visibility = View.VISIBLE
        } else {
            tvTransfer.text = ""
            tvTransfer.visibility = View.INVISIBLE
        }
    }
}
