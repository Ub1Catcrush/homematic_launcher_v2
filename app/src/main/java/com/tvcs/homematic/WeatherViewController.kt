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
 * Fetches weather forecasts for configurable time horizons
 * (next 1 h, 3 h, 6 h, today) and cycles through them as a slideshow.
 *
 * All timings and which horizons are shown are controlled via SharedPreferences.
 *
 * Two display modes (controlled by WEATHER_DISPLAY_MODE pref):
 *   "room"    — appears as a special tile in the room grid (injected by RoomAdapter)
 *   "overlay" — compact bar overlaid at the top of the camera panel
 *
 * Refresh:  every WEATHER_REFRESH_MIN minutes (default 30).
 * Slideshow: each slide visible for WEATHER_SLIDE_DURATION_SEC seconds (default 5).
 */
class WeatherViewController(
    private val context:      Context,
    private val cameraPanel:  android.view.ViewGroup?,
    private val lifecycleOwner: LifecycleOwner
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG                   = "WeatherVC"
        private const val DEFAULT_REFRESH_MIN   = 30L
        private const val DEFAULT_SLIDE_SEC     = 5L
    }

    private val prefs   = PreferenceManager.getDefaultSharedPreferences(context)
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var refreshJob:   Job? = null
    private var slideshowJob: Job? = null

    /** Latest set of forecasts — consumed by RoomAdapter for "room" mode. */
    @Volatile var lastForecasts: WeatherRepository.AllForecasts? = null
        private set

    /** Index of the slide currently shown in overlay mode. */
    @Volatile private var slideIndex = 0

    /** Overlay view injected into cameraPanel. */
    private var overlayView: TextView? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun attachToLifecycle() = lifecycleOwner.lifecycle.addObserver(this)

    override fun onStart(owner: LifecycleOwner)   { if (isEnabled()) startAll() }
    override fun onStop(owner: LifecycleOwner)    { stopAll() }
    override fun onDestroy(owner: LifecycleOwner) { ioScope.cancel() }

    fun applyPrefsChange() {
        stopAll()
        if (isEnabled()) startAll() else {
            lastForecasts = null
            removeOverlay()
        }
    }

    fun isEnabled()     = prefs.getBoolean(PreferenceKeys.WEATHER_ENABLED, false)
    fun displayMode()   = prefs.getString(PreferenceKeys.WEATHER_DISPLAY_MODE, "room") ?: "room"

    // ── Start / Stop ──────────────────────────────────────────────────────────

    private fun startAll() {
        startRefreshing()
        if (displayMode() == "overlay") startSlideshow()
    }

    private fun stopAll() {
        refreshJob?.cancel();   refreshJob   = null
        slideshowJob?.cancel(); slideshowJob = null
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    private fun startRefreshing() {
        refreshJob?.cancel()
        val intervalMs = (prefs.getString(PreferenceKeys.WEATHER_REFRESH_MIN, DEFAULT_REFRESH_MIN.toString())
            ?.toLongOrNull()?.coerceAtLeast(5L) ?: DEFAULT_REFRESH_MIN) * 60_000L
        refreshJob = ioScope.launch {
            while (isActive) {
                try { refresh() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { android.util.Log.e(TAG, "Weather refresh error: ${e.message}", e) }
                delay(intervalMs)
            }
        }
    }

    private suspend fun refresh() {
        val (lat, lon) = resolveLatLon() ?: return
        when (val result = WeatherRepository.getAllForecasts(lat, lon)) {
            is WeatherRepository.Result.Success -> {
                lastForecasts = result.data
                // Reset slide index so the first enabled slide is shown after a refresh
                slideIndex = 0
                withContext(Dispatchers.Main) { applyDisplay(result.data) }
            }
            is WeatherRepository.Result.Error ->
                android.util.Log.w(TAG, "Weather fetch failed: ${result.message}")
        }
    }

    private suspend fun resolveLatLon(): Pair<Double, Double>? {
        val lat = prefs.getString(PreferenceKeys.WEATHER_LAT, "")?.toDoubleOrNull()
        val lon = prefs.getString(PreferenceKeys.WEATHER_LON, "")?.toDoubleOrNull()
        if (lat != null && lon != null) return lat to lon
        val city = prefs.getString(PreferenceKeys.WEATHER_CITY, "") ?: ""
        if (city.isBlank()) return null
        val resolved = WeatherRepository.geocode(city) ?: return null
        prefs.edit()
            .putString(PreferenceKeys.WEATHER_LAT, resolved.first.toString())
            .putString(PreferenceKeys.WEATHER_LON, resolved.second.toString())
            .apply()
        return resolved
    }

    // ── Slideshow ─────────────────────────────────────────────────────────────

    private fun startSlideshow() {
        slideshowJob?.cancel()
        val durationMs = (prefs.getString(PreferenceKeys.WEATHER_SLIDE_DURATION_SEC, DEFAULT_SLIDE_SEC.toString())
            ?.toLongOrNull()?.coerceAtLeast(1L) ?: DEFAULT_SLIDE_SEC) * 1_000L
        slideshowJob = ioScope.launch {
            while (isActive) {
                delay(durationMs)
                withContext(Dispatchers.Main) { advanceSlide() }
            }
        }
    }

    /** Move to the next enabled slide; wraps around. */
    private fun advanceSlide() {
        val slides = enabledSlides()
        if (slides.isEmpty()) return
        slideIndex = (slideIndex + 1) % slides.size
        showOverlay(slides[slideIndex])
    }

    /** Returns only the forecasts that are enabled in settings. */
    private fun enabledSlides(): List<WeatherRepository.WeatherForecast> {
        val all = lastForecasts ?: return emptyList()
        val list = mutableListOf<WeatherRepository.WeatherForecast>()
        if (prefs.getBoolean(PreferenceKeys.WEATHER_SHOW_1H,    true))  all.next1h?.let { list += it }
        if (prefs.getBoolean(PreferenceKeys.WEATHER_SHOW_3H,    true))  all.next3h?.let { list += it }
        if (prefs.getBoolean(PreferenceKeys.WEATHER_SHOW_6H,    true))  all.next6h?.let { list += it }
        if (prefs.getBoolean(PreferenceKeys.WEATHER_SHOW_DAILY, true))  all.daily?.let  { list += it }
        return list
    }

    // ── Display ───────────────────────────────────────────────────────────────

    private fun applyDisplay(all: WeatherRepository.AllForecasts) {
        if (displayMode() == "overlay") {
            val slides = enabledSlides()
            if (slides.isNotEmpty()) {
                slideIndex = slideIndex.coerceIn(0, slides.lastIndex)
                showOverlay(slides[slideIndex])
                // Ensure slideshow is running (it may not have started yet)
                if (slideshowJob?.isActive != true) startSlideshow()
            }
        }
        // room mode: RoomAdapter polls lastForecasts
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
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                )
            }
            panel.addView(overlayView)
        }
        overlayView?.apply {
            text = buildOverlayText(fc)
            visibility = View.VISIBLE
            bringToFront()
        }
    }

    private fun removeOverlay() {
        overlayView?.visibility = View.GONE
    }

    private fun buildOverlayText(fc: WeatherRepository.WeatherForecast): String {
        val sb = StringBuilder()
        sb.append("${fc.label}  ${fc.icon}  ${fc.description}")
        sb.append("  ▲ ${"%.1f".format(fc.tempMax)}°")
        sb.append("  ▼ ${"%.1f".format(fc.tempMin)}°")
        if (fc.precipMm > 0f) sb.append("  💧 ${"%.1f".format(fc.precipMm)} mm")
        return sb.toString()
    }

    // ── Room tile ─────────────────────────────────────────────────────────────

    /** Build a tile for the room grid. Shows the first enabled horizon. */
    fun buildRoomTile(): LinearLayout {
        return try { buildRoomTileInternal() } catch (e: Exception) {
            android.util.Log.e(TAG, "buildRoomTile error: ${e.message}", e)
            LinearLayout(context)
        }
    }

    private fun buildRoomTileInternal(): LinearLayout {
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

            val slides = enabledSlides()
            if (slides.isEmpty()) {
                addView(tv("Kein Wetter", 12f))
                return@apply
            }

            // Show all enabled horizons stacked in the tile
            slides.forEach { fc ->
                addView(tv("${fc.label}", 10f, 0xFFAAAAAA.toInt()))
                addView(tv("${fc.icon} ${fc.description}  ▲${"%.1f".format(fc.tempMax)}° ▼${"%.1f".format(fc.tempMin)}°", 11f))
                if (fc.precipMm > 0f)
                    addView(tv("💧 ${"%.1f".format(fc.precipMm)} mm", 10f, 0xFF88CCFF.toInt()))
            }
        }
    }
}
