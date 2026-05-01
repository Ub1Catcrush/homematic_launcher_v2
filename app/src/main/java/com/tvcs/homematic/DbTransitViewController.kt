package com.tvcs.homematic

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*

/**
 * DbTransitViewController — shows the next 3 departures for a configured
 * From → To connection above the camera panel.
 *
 * Refreshes every REFRESH_INTERVAL_S seconds while the Activity is started.
 */
class DbTransitViewController(
    private val context: Context,
    private val panel:        LinearLayout,
    private val headerLabel:  TextView,
    private val row1:         TransitRowView,
    private val row2:         TransitRowView,
    private val row3:         TransitRowView,
    private val errorLabel:   TextView
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG               = "DbTransitVC"
        private const val REFRESH_INTERVAL_S = 60L
    }

    private val prefs     = PreferenceManager.getDefaultSharedPreferences(context)
    private val ioScope   = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var refreshJob: Job? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun attachToLifecycle(owner: LifecycleOwner) = owner.lifecycle.addObserver(this)

    override fun onStart(owner: LifecycleOwner)   { if (isEnabled()) startRefreshing() }
    override fun onStop(owner: LifecycleOwner)    { stopRefreshing() }
    override fun onDestroy(owner: LifecycleOwner) { ioScope.cancel() }

    // ── Public API ────────────────────────────────────────────────────────────

    fun applyPrefsChange() {
        stopRefreshing()
        if (isEnabled()) startRefreshing() else hide()
    }

    fun isEnabled(): Boolean {
        if (!prefs.getBoolean(PreferenceKeys.TRANSIT_ENABLED, false)) return false
        val from = prefs.getString(PreferenceKeys.TRANSIT_FROM_ID, "") ?: ""
        val to   = prefs.getString(PreferenceKeys.TRANSIT_TO_ID,   "") ?: ""
        return from.isNotBlank() && to.isNotBlank()
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun startRefreshing() {
        show()
        refreshJob?.cancel()
        refreshJob = ioScope.launch {
            while (isActive) {
                refresh()
                delay(REFRESH_INTERVAL_S * 1_000L)
            }
        }
    }

    private fun stopRefreshing() { refreshJob?.cancel(); refreshJob = null }

    private suspend fun refresh() {
        val fromId   = prefs.getString(PreferenceKeys.TRANSIT_FROM_ID, "") ?: ""
        val toId     = prefs.getString(PreferenceKeys.TRANSIT_TO_ID,   "") ?: ""
        val fromName = prefs.getString(PreferenceKeys.TRANSIT_FROM_NAME, fromId) ?: fromId
        val toName   = prefs.getString(PreferenceKeys.TRANSIT_TO_NAME,   toId)   ?: toId

        when (val result = DbTransitRepository.getDepartures(fromId, toId)) {
            is DbTransitRepository.Result.Success -> {
                val deps = result.data
                withContext(Dispatchers.Main) {
                    headerLabel.text = context.getString(R.string.transit_header, fromName, toName)
                    errorLabel.visibility = View.GONE
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
                }
            }
        }
    }

    private fun show() { panel.visibility = View.VISIBLE }
    private fun hide() { panel.visibility = View.GONE    }
}

/**
 * A single departure row — shows line, planned time, delay badge, direction.
 * Constructed programmatically to keep layout XML simple (just 3 stub LinearLayouts).
 */
class TransitRowView(context: Context) : LinearLayout(context) {

    private val tvLine      = TextView(context)
    private val tvPlanned   = TextView(context)
    private val tvDelay     = TextView(context)
    private val tvDirection = TextView(context)

    init {
        orientation = HORIZONTAL
        val pad = (4 * resources.displayMetrics.density).toInt()
        setPadding(pad * 2, pad, pad * 2, pad)

        tvLine.textSize = 12f
        tvLine.setTextColor(Color.WHITE)
        tvLine.minWidth = (48 * resources.displayMetrics.density).toInt()

        tvPlanned.textSize = 13f
        tvPlanned.setTextColor(Color.WHITE)
        tvPlanned.minWidth = (44 * resources.displayMetrics.density).toInt()

        tvDelay.textSize = 12f
        tvDelay.minWidth = (36 * resources.displayMetrics.density).toInt()
        tvDelay.setPadding(pad, 0, pad, 0)

        tvDirection.textSize = 11f
        tvDirection.setTextColor(0xAAFFFFFF.toInt())
        tvDirection.layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)

        addView(tvLine)
        addView(tvPlanned)
        addView(tvDelay)
        addView(tvDirection)
    }

    fun bind(dep: DbTransitRepository.Departure) {
        tvLine.text = dep.line

        if (dep.cancelled) {
            tvPlanned.text  = dep.plannedTime
            tvPlanned.setTextColor(0xFFFF4444.toInt())
            tvDelay.text    = context.getString(R.string.transit_cancelled)
            tvDelay.setTextColor(0xFFFF4444.toInt())
        } else {
            tvPlanned.setTextColor(Color.WHITE)
            val rt = dep.realtimeTime
            val delay = dep.delayMinutes

            if (rt != null && rt != dep.plannedTime) {
                // Show realtime time, strike through planned
                tvPlanned.text = rt
            } else {
                tvPlanned.text = dep.plannedTime
            }

            when {
                delay == null -> {
                    tvDelay.text = ""
                    tvDelay.setTextColor(Color.TRANSPARENT)
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
    }
}
