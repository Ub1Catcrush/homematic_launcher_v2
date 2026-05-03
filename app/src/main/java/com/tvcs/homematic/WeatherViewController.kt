package com.tvcs.homematic

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View

import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*

/**
 * WeatherViewController
 *
 * Two display modes (controlled by WEATHER_DISPLAY_MODE pref):
 *   "room"    — appears as a special tile in the room grid (injected by RoomAdapter)
 *   "overlay" — compact bar overlaid at the top of the camera panel
 *
 * Refresh: every WEATHER_REFRESH_MIN minutes (default 30).
 */
class WeatherViewController(
    private val context:     Context,
    /** The camera panel FrameLayout — used for overlay mode. */
    private val cameraPanel: android.view.ViewGroup?,
    private val lifecycleOwner: LifecycleOwner
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG             = "WeatherVC"
        private const val DEFAULT_REFRESH = 30L  // minutes
    }

    private val prefs   = PreferenceManager.getDefaultSharedPreferences(context)
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var refreshJob: Job? = null

    /** Latest fetched forecast — consumed by RoomAdapter for "room" mode. */
    @Volatile var lastForecast: WeatherRepository.WeatherForecast? = null
        private set

    /** Overlay view injected into cameraPanel. */
    private var overlayView: TextView? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun attachToLifecycle() = lifecycleOwner.lifecycle.addObserver(this)

    override fun onStart(owner: LifecycleOwner)   { if (isEnabled()) startRefreshing() }
    override fun onStop(owner: LifecycleOwner)    { stopRefreshing() }
    override fun onDestroy(owner: LifecycleOwner) { ioScope.cancel() }

    fun applyPrefsChange() {
        stopRefreshing()
        if (isEnabled()) startRefreshing() else {
            lastForecast = null
            removeOverlay()
        }
    }

    fun isEnabled() = prefs.getBoolean(PreferenceKeys.WEATHER_ENABLED, false)

    fun displayMode(): String =
        prefs.getString(PreferenceKeys.WEATHER_DISPLAY_MODE, "room") ?: "room"

    // ── Refresh ───────────────────────────────────────────────────────────────

    private fun startRefreshing() {
        refreshJob?.cancel()
        val intervalMs = (prefs.getString(PreferenceKeys.WEATHER_REFRESH_MIN, DEFAULT_REFRESH.toString())
            ?.toLongOrNull()?.coerceAtLeast(10L) ?: DEFAULT_REFRESH) * 60_000L
        refreshJob = ioScope.launch {
            while (isActive) {
                refresh()
                delay(intervalMs)
            }
        }
    }

    private fun stopRefreshing() { refreshJob?.cancel(); refreshJob = null }

    private suspend fun refresh() {
        val (lat, lon) = resolveLatLon() ?: return
        when (val result = WeatherRepository.getForecast(lat, lon)) {
            is WeatherRepository.Result.Success -> {
                lastForecast = result.data
                withContext(Dispatchers.Main) { applyDisplay(result.data) }
            }
            is WeatherRepository.Result.Error ->
                android.util.Log.w(TAG, "Weather fetch failed: ${result.message}")
        }
    }

    private suspend fun resolveLatLon(): Pair<Double, Double>? {
        val latStr  = prefs.getString(PreferenceKeys.WEATHER_LAT, "") ?: ""
        val lonStr  = prefs.getString(PreferenceKeys.WEATHER_LON, "") ?: ""
        val lat = latStr.toDoubleOrNull()
        val lon = lonStr.toDoubleOrNull()
        if (lat != null && lon != null) return lat to lon

        // Try geocoding from city name
        val city = prefs.getString(PreferenceKeys.WEATHER_CITY, "") ?: ""
        if (city.isBlank()) return null
        val resolved = WeatherRepository.geocode(city) ?: return null
        // Cache the resolved coordinates
        prefs.edit()
            .putString(PreferenceKeys.WEATHER_LAT, resolved.first.toString())
            .putString(PreferenceKeys.WEATHER_LON, resolved.second.toString())
            .apply()
        return resolved
    }

    // ── Display ───────────────────────────────────────────────────────────────

    private fun applyDisplay(fc: WeatherRepository.WeatherForecast) {
        when (displayMode()) {
            "overlay" -> showOverlay(fc)
            else      -> { /* room mode: RoomAdapter polls lastForecast */ }
        }
    }

    private fun showOverlay(fc: WeatherRepository.WeatherForecast) {
        val panel = cameraPanel ?: return
        if (overlayView == null) {
            overlayView = TextView(context).apply {
                id = View.generateViewId()
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(12, 4, 12, 4)
                setBackgroundColor(0xCC000000.toInt())
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP
                )
            }
            panel.addView(overlayView)
        }
        overlayView?.apply {
            text = buildOverlayText(fc)
            visibility = View.VISIBLE
        }
    }

    private fun removeOverlay() {
        overlayView?.visibility = View.GONE
    }

    private fun buildOverlayText(fc: WeatherRepository.WeatherForecast) =
        "${fc.icon}  ${fc.description}  ▲ ${"%.1f".format(fc.tempMax)}°  " +
        "▼ ${"%.1f".format(fc.tempMin)}°" +
        (if (fc.precipMm > 0f) "  💧 ${"%.1f".format(fc.precipMm)} mm" else "")

    /** Build a standalone room-style tile view for use by RoomAdapter. */
    fun buildRoomTile(): LinearLayout {
        val fc = lastForecast ?: return LinearLayout(context)
        val dp = context.resources.displayMetrics.density

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (8 * dp).toInt()
            setPadding(pad, pad, pad, pad)

            fun tv(text: String, sizeSp: Float, color: Int = Color.WHITE) =
                TextView(context).apply {
                    this.text = text
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                    setTextColor(color)
                }

            addView(tv("${fc.icon} ${fc.description}", 13f))
            addView(tv("▲ ${"%.1f".format(fc.tempMax)}°  ▼ ${"%.1f".format(fc.tempMin)}°", 12f))
            if (fc.precipMm > 0f)
                addView(tv("💧 ${"%.1f".format(fc.precipMm)} mm", 11f, 0xFF88CCFF.toInt()))
        }
    }
}
