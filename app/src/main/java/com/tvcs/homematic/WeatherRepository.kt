package com.tvcs.homematic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * WeatherRepository — fetches forecasts from the Open-Meteo API.
 *
 * Supports multiple forecast horizons:
 *   - Hourly:  next 1 h, 3 h, 6 h  (aggregated from hourly data)
 *   - Daily:   today's full-day forecast
 *
 * Open-Meteo is free, open-source, no API key required.
 */
object WeatherRepository {

    private const val TAG = "WeatherRepo"

    /** Combined URL: hourly + daily in one request to save bandwidth. */
    private const val FORECAST_URL =
        "https://api.open-meteo.com/v1/forecast" +
        "?latitude=%s&longitude=%s" +
        "&hourly=temperature_2m,precipitation,weathercode,apparent_temperature" +
        "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weathercode" +
        "&timezone=auto&forecast_days=2"

    private const val GEO_URL =
        "https://geocoding-api.open-meteo.com/v1/search?name=%s&count=1&language=de&format=json"
    private const val TIMEOUT = 10_000

    // ── Data ──────────────────────────────────────────────────────────────────

    /** A single weather snapshot (used for any horizon). */
    data class WeatherForecast(
        val label:       String,   // e.g. "Nächste Stunde", "Heute"
        val tempAvg:     Float,    // °C — for hourly windows; for daily it is (max+min)/2
        val tempMax:     Float,
        val tempMin:     Float,
        val precipMm:    Float,
        val weatherCode: Int,
        val icon:        String,
        val description: String
    )

    /** All horizons bundled together. */
    data class AllForecasts(
        val next1h:  WeatherForecast?,
        val next3h:  WeatherForecast?,
        val next6h:  WeatherForecast?,
        val daily:   WeatherForecast?
    ) {
        /** Returns a list of only the non-null forecasts, in display order. */
        fun toList(): List<WeatherForecast> =
            listOfNotNull(next1h, next3h, next6h, daily)
    }

    sealed class Result<out T> {
        data class Success<T>(val data: T) : Result<T>()
        data class Error(val message: String) : Result<Nothing>()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun getAllForecasts(lat: Double, lon: Double): Result<AllForecasts> =
        withContext(Dispatchers.IO) {
            val latStr = "%.4f".format(java.util.Locale.US, lat)
            val lonStr = "%.4f".format(java.util.Locale.US, lon)
            val url = FORECAST_URL.format(latStr, lonStr)
            try {
                val body = get(url)
                val root = JSONObject(body)
                val hourly = root.getJSONObject("hourly")
                val daily  = root.getJSONObject("daily")

                // ── Find current hour index in the hourly arrays ──────────
                val times = hourly.getJSONArray("time")
                val nowMs = System.currentTimeMillis()
                // ISO-8601 without seconds: "2024-06-01T14:00"
                // Set timezone BEFORE formatting — on Android 10 setting it after
                // has no effect, causing UTC time to be formatted instead of local time,
                // so nowHourStr never matches any entry in the hourly array.
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:00", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getDefault()
                val nowHourStr = sdf.format(java.util.Date(nowMs))

                var startIdx = 0
                var foundHour = false
                for (i in 0 until times.length()) {
                    if (times.getString(i) == nowHourStr) { startIdx = i; foundHour = true; break }
                }
                // Fallback: if exact hour not found (timezone edge case), find closest past hour
                if (!foundHour && times.length() > 0) {
                    val nowEpoch = nowMs / 1000
                    var bestDiff = Long.MAX_VALUE
                    for (i in 0 until times.length()) {
                        try {
                            val sdf2 = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US)
                            sdf2.timeZone = java.util.TimeZone.getDefault()
                            val t = sdf2.parse(times.getString(i) + ":00")?.time?.div(1000) ?: continue
                            val diff = nowEpoch - t
                            if (diff >= 0 && diff < bestDiff) { bestDiff = diff; startIdx = i }
                        } catch (_: Exception) {}
                    }
                    Log.d(TAG, "Hour fallback: startIdx=$startIdx (nowHourStr=$nowHourStr, entry=${times.optString(startIdx)})")
                }

                val hTemps   = hourly.getJSONArray("temperature_2m")
                val hPrec    = hourly.getJSONArray("precipitation")
                val hCodes   = hourly.getJSONArray("weathercode")

                fun hourWindow(hours: Int, label: String): WeatherForecast? {
                    val end = (startIdx + hours).coerceAtMost(times.length() - 1)
                    if (end <= startIdx) return null
                    var sumTemp = 0.0; var maxTemp = Double.MIN_VALUE; var minTemp = Double.MAX_VALUE
                    var sumPrec = 0.0; val codes = mutableListOf<Int>()
                    for (i in startIdx until end) {
                        val t = hTemps.optDouble(i, 0.0)
                        sumTemp += t
                        if (t > maxTemp) maxTemp = t
                        if (t < minTemp) minTemp = t
                        sumPrec += hPrec.optDouble(i, 0.0)
                        codes.add(hCodes.optInt(i, 0))
                    }
                    val count = end - startIdx
                    val dominantCode = codes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
                    val (icon, desc) = wmoToIconAndDesc(dominantCode)
                    return WeatherForecast(
                        label      = label,
                        tempAvg    = (sumTemp / count).toFloat(),
                        tempMax    = maxTemp.toFloat(),
                        tempMin    = minTemp.toFloat(),
                        precipMm   = sumPrec.toFloat(),
                        weatherCode = dominantCode,
                        icon       = icon,
                        description = desc
                    )
                }

                // ── Daily (index 0 = today) ───────────────────────────────
                val dCode = daily.getJSONArray("weathercode").optInt(0, 0)
                val dMax  = daily.getJSONArray("temperature_2m_max").optDouble(0, 0.0).toFloat()
                val dMin  = daily.getJSONArray("temperature_2m_min").optDouble(0, 0.0).toFloat()
                val dPrec = daily.getJSONArray("precipitation_sum").optDouble(0, 0.0).toFloat()
                val (dIcon, dDesc) = wmoToIconAndDesc(dCode)
                val dailyFc = WeatherForecast(
                    label       = "Heute",
                    tempAvg     = (dMax + dMin) / 2f,
                    tempMax     = dMax,
                    tempMin     = dMin,
                    precipMm    = dPrec,
                    weatherCode = dCode,
                    icon        = dIcon,
                    description = dDesc
                )

                Result.Success(
                    AllForecasts(
                        next1h = hourWindow(1,  "Nächste Stunde"),
                        next3h = hourWindow(3,  "Nächste 3 Std."),
                        next6h = hourWindow(6,  "Nächste 6 Std."),
                        daily  = dailyFc
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Forecast failed", e)
                Result.Error(e.message ?: "Unknown error")
            }
        }

    // ── Geocoding ─────────────────────────────────────────────────────────────

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

    fun wmoToIconAndDesc(code: Int): Pair<String, String> = when (code) {
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
        // Use OkHttp-style explicit TLS setup via HttpsURLConnection to ensure
        // TLS 1.2/1.3 works reliably on Android 10 (API 29).
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT
            readTimeout    = TIMEOUT
            setRequestProperty("Accept",     "application/json")
            setRequestProperty("User-Agent", "HomematicLauncher/1.0")
            // Force modern TLS on Android 10 where the default SSLContext may
            // not negotiate TLS 1.3 correctly with some CDN configurations.
            if (this is javax.net.ssl.HttpsURLConnection) {
                try {
                    val sc = javax.net.ssl.SSLContext.getInstance("TLSv1.2")
                    sc.init(null, null, null)
                    sslSocketFactory = sc.socketFactory
                } catch (_: Exception) { /* leave default if init fails */ }
            }
        }
        return try {
            val code = con.responseCode
            if (code >= 400) error("HTTP $code: ${con.responseMessage}")
            con.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally { con.disconnect() }
    }
}
