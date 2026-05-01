package com.tvcs.homematic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Thin wrapper around https://v6.db.transport.rest/
 *
 * Only the two endpoints we need:
 *   GET /locations?query=…&results=10&stops=true&addresses=false&poi=false
 *   GET /stops/{id}/departures?direction={toId}&results=3&duration=120&language=de
 */
object DbTransitRepository {

    private const val TAG     = "DbTransitRepo"
    private const val BASE    = "https://v6.db.transport.rest"
    private const val TIMEOUT = 8_000

    // ── Data classes ──────────────────────────────────────────────────────────

    data class TransitStop(val id: String, val name: String)

    data class Departure(
        val line:           String,   // e.g. "RE 1"
        val direction:      String,   // final destination of the line
        val plannedTime:    String,   // "HH:mm"
        val realtimeTime:   String?,  // null if no realtime data
        val delayMinutes:   Int?,     // null if no realtime data
        val cancelled:      Boolean
    )

    sealed class Result<out T> {
        data class Success<T>(val data: T) : Result<T>()
        data class Error(val message: String) : Result<Nothing>()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun searchStops(query: String): Result<List<TransitStop>> =
        withContext(Dispatchers.IO) {
            try {
                val enc = URLEncoder.encode(query, "UTF-8")
                val json = get("$BASE/locations?query=$enc&results=10&stops=true&addresses=false&poi=false")
                val arr  = JSONArray(json)
                val stops = (0 until arr.length())
                    .mapNotNull { i ->
                        val obj = arr.getJSONObject(i)
                        if (obj.optString("type") != "stop") return@mapNotNull null
                        TransitStop(
                            id   = obj.getString("id"),
                            name = obj.getString("name")
                        )
                    }
                Result.Success(stops)
            } catch (e: Exception) {
                Log.w(TAG, "searchStops failed: ${e.message}")
                Result.Error(e.message ?: "Unknown error")
            }
        }

    suspend fun getDepartures(fromId: String, toId: String): Result<List<Departure>> =
        withContext(Dispatchers.IO) {
            try {
                // /journeys is the correct endpoint for a From→To query:
                // it returns complete connections including transfers, realtime data,
                // and departure times at the origin stop — exactly what we need.
                val from = URLEncoder.encode(fromId, "UTF-8")
                val to   = URLEncoder.encode(toId,   "UTF-8")
                val json = get(
                    "$BASE/journeys?from=$from&to=$to&results=3&language=de&pretty=false"
                )
                val obj      = JSONObject(json)
                val journeys = obj.optJSONArray("journeys") ?: JSONArray()

                val fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

                val deps = mutableListOf<Departure>()
                for (i in 0 until journeys.length()) {
                    val journey = journeys.getJSONObject(i)
                    val legs    = journey.optJSONArray("legs") ?: continue

                    // First leg = departure from our origin stop
                    val firstLeg  = legs.getJSONObject(0)
                    val lastLeg   = legs.getJSONObject(legs.length() - 1)

                    val line      = firstLeg.optJSONObject("line")?.optString("name")
                                    ?: firstLeg.optString("mode", "?")
                    // Show final destination of the whole journey
                    val direction = lastLeg.optJSONObject("destination")?.optString("name") ?: "?"
                    val cancelled = firstLeg.optBoolean("cancelled", false)

                    val plannedStr  = firstLeg.optString("plannedDeparture", "")
                    val realtimeStr = firstLeg.optString("departure", "")
                    val delay       = if (firstLeg.isNull("departureDelay")) null
                                      else firstLeg.optInt("departureDelay") / 60

                    // Flag journeys with >0 transfers
                    val transfers = legs.length() - 1

                    deps += Departure(
                        line         = if (transfers > 0) "$line +$transfers" else line,
                        direction    = direction,
                        plannedTime  = parseTime(plannedStr, fmt),
                        realtimeTime = if (realtimeStr.isNotBlank() && realtimeStr != plannedStr)
                                           parseTime(realtimeStr, fmt) else null,
                        delayMinutes = delay,
                        cancelled    = cancelled
                    )
                }
                Result.Success(deps)
            } catch (e: Exception) {
                Log.w(TAG, "getDepartures failed: ${e.message}")
                Result.Error(e.message ?: "Unknown error")
            }
        }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun parseTime(iso: String, fmt: DateTimeFormatter): String {
        return try { fmt.format(Instant.parse(iso)) } catch (_: Exception) { iso.take(5) }
    }

    private fun get(url: String): String {
        Log.d(TAG, "GET $url")
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT
            readTimeout    = TIMEOUT
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "HomematicLauncher/1.0")
        }
        return try {
            if (con.responseCode != HttpURLConnection.HTTP_OK)
                error("HTTP ${con.responseCode}")
            con.inputStream.bufferedReader().readText()
        } finally {
            con.disconnect()
        }
    }
}
