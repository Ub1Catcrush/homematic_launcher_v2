package com.tvcs.homematic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * WeatherRepository — fetches today's forecast from the Open-Meteo API.
 *
 * Why Open-Meteo instead of wetteronline.de?
 * wetteronline.de has no documented public REST API; scraping it would be
 * fragile and likely against their ToS.  Open-Meteo (open-meteo.com) is a
 * free, open-source weather service with a fully public REST API that returns
 * daily forecasts including max/min temperature, precipitation sum and a
 * WMO weather code — exactly what the requirement asks for.
 * No API key required.
 *
 * Location is set in Settings as decimal lat/lon, or resolved from a city
 * name via the Open-Meteo geocoding endpoint.
 */
object WeatherRepository {

    private const val TAG = "WeatherRepo"
    private const val FORECAST_URL =
        "https://api.open-meteo.com/v1/forecast" +
        "?latitude=%s&longitude=%s" +
        "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weathercode" +
        "&timezone=auto&forecast_days=1"
    private const val GEO_URL =
        "https://geocoding-api.open-meteo.com/v1/search?name=%s&count=1&language=de&format=json"
    private const val TIMEOUT = 10_000

    // ── Data ──────────────────────────────────────────────────────────────────

    data class WeatherForecast(
        val tempMax:    Float,
        val tempMin:    Float,
        val precipMm:   Float,
        /** WMO weather code — mapped to emoji below. */
        val weatherCode: Int,
        val icon:       String,
        val description: String
    )

    sealed class Result<out T> {
        data class Success<T>(val data: T) : Result<T>()
        data class Error(val message: String)  : Result<Nothing>()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun getForecast(lat: Double, lon: Double): Result<WeatherForecast> =
        withContext(Dispatchers.IO) {
            val url = FORECAST_URL.format(
                java.util.Locale.US, // Erzwingt den Punkt als Dezimaltrenner
                "%.4f".format(java.util.Locale.US, lat),
                "%.4f".format(java.util.Locale.US, lon)
            )
            try {
                val body = get(url)
                val root = JSONObject(body)
                val daily = root.getJSONObject("daily")
                val code  = daily.getJSONArray("weathercode").optInt(0, 0)
                val max   = daily.getJSONArray("temperature_2m_max").optDouble(0, 0.0).toFloat()
                val min   = daily.getJSONArray("temperature_2m_min").optDouble(0, 0.0).toFloat()
                val prec  = daily.getJSONArray("precipitation_sum").optDouble(0, 0.0).toFloat()
                val (icon, desc) = wmoToIconAndDesc(code)
                Result.Success(WeatherForecast(max, min, prec, code, icon, desc))
            } catch (e: Exception) {
                Log.w(TAG, "Forecast failed", e)
                Result.Error(e.message ?: "Unknown error")
            }
        }

    /**
     * Resolve a city name to (lat, lon) via Open-Meteo geocoding.
     * Returns null on failure.
     */
    suspend fun geocode(city: String): Pair<Double, Double>? =
        withContext(Dispatchers.IO) {
            val url = GEO_URL.format(URLEncoder.encode(city, "UTF-8"))
            try {
                val body    = get(url)
                val results = JSONObject(body).optJSONArray("results") ?: return@withContext null
                if (results.length() == 0) return@withContext null
                val r   = results.getJSONObject(0)
                val lat = r.optDouble("latitude",  Double.NaN)
                val lon = r.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) null else lat to lon
            } catch (e: Exception) {
                Log.w(TAG, "Geocode failed", e)
                null
            }
        }

    // ── WMO code → icon + short description ──────────────────────────────────

    private fun wmoToIconAndDesc(code: Int): Pair<String, String> = when (code) {
        0            -> "☀️" to "Klar"
        1            -> "🌤" to "Meist klar"
        2            -> "⛅" to "Teils bewölkt"
        3            -> "☁️" to "Bedeckt"
        45, 48       -> "🌫" to "Nebel"
        51, 53, 55   -> "🌦" to "Nieselregen"
        61, 63, 65   -> "🌧" to "Regen"
        71, 73, 75   -> "❄️" to "Schnee"
        77           -> "🌨" to "Schneekörner"
        80, 81, 82   -> "🌦" to "Regenschauer"
        85, 86       -> "🌨" to "Schneeschauer"
        95           -> "⛈" to "Gewitter"
        96, 99       -> "⛈" to "Gewitter + Hagel"
        else         -> "🌡" to "Unbekannt ($code)"
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private fun get(url: String): String {
        Log.d(TAG, "GET $url")
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT; readTimeout = TIMEOUT
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "HomematicLauncher/1.0")
        }
        return try {
            val code = con.responseCode
            if (code >= 400) error("HTTP $code")
            con.inputStream.bufferedReader().readText()
        } finally { con.disconnect() }
    }
}
