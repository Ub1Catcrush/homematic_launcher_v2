package com.tvcs.homematic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Thin wrapper around the db-rest / db-vendo-client REST API.
 *
 * Default base URL: https://v6.db.transport.rest
 * Can be overridden via [baseUrl] to point at a self-hosted instance.
 *
 * The public instance has a low rate limit and occasionally returns 503.
 * We retry up to [MAX_RETRIES] times with exponential back-off before giving up.
 *
 * Endpoints used:
 *   GET /locations?query=…&results=10&stops=true&addresses=false&poi=false
 *   GET /journeys?from={id}&to={id}&results=3&stopovers=true&language=de
 */
object DbTransitRepository {

    private const val TAG         = "DbTransitRepo"
    const  val DEFAULT_BASE       = "https://v6.db.transport.rest"
    private const val TIMEOUT     = 15_000     // ms
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY = 1_500L     // ms between retries

    /** Override to point at a self-hosted instance. */
    var baseUrl: String = DEFAULT_BASE

    // ── Data classes ──────────────────────────────────────────────────────────

    data class TransitStop(val id: String, val name: String)

    data class TransferInfo(
        val stationName:  String,
        val arrivalTime:  String,
        val delayMinutes: Int?
    )

    data class Departure(
        val line:         String,
        val direction:    String,
        val plannedTime:  String,
        val realtimeTime: String?,
        val delayMinutes: Int?,
        val cancelled:    Boolean,
        val transferInfo: TransferInfo?
    )

    sealed class Result<out T> {
        data class Success<T>(val data: T) : Result<T>()
        data class Error(val message: String) : Result<Nothing>()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun searchStops(query: String): Result<List<TransitStop>> =
        withContext(Dispatchers.IO) {
            val enc = encodeParam(query)
            val url = "$baseUrl/locations?query=$enc&results=10&stops=true&addresses=false&poi=false"
            when (val  raw = getWithRetry(url)) {
                is RawResult.Ok -> try {
                    val arr  = JSONArray(raw.body)
                    val allowedTypes = setOf("stop", "station")
                    val list = (0 until arr.length()).map { arr.getJSONObject(it) }
                        .filter { it.optString("type") in allowedTypes }
                        .map { TransitStop(it.getString("id"), it.getString("name")) }
                    Result.Success(list)
                } catch (e: Exception) {
                    Result.Error("Parse error: ${e.message}")
                }
                is RawResult.Err -> Result.Error(raw.message)
            }
        }

    suspend fun getDepartures(
        fromId: String,
        toId: String,
        watchedStationNames: List<String> = emptyList(),
        results: Int = 3
    ): Result<List<Departure>> =
        withContext(Dispatchers.IO) {
            val from = encodeParam(fromId)
            val to   = encodeParam(toId)
            val url  = "$baseUrl/journeys?from=$from&to=$to&results=$results" +
                       "&language=de&stopovers=true&pretty=false"
            when (val raw = getWithRetry(url)) {
                is RawResult.Ok -> try {
                    val fmt      = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
                    val journeys = JSONObject(raw.body).optJSONArray("journeys") ?: JSONArray()
                    val deps     = mutableListOf<Departure>()

                    for (i in 0 until journeys.length()) {
                        val legs     = journeys.getJSONObject(i).optJSONArray("legs") ?: continue
                        val firstLeg = legs.getJSONObject(0)
                        val lastLeg  = legs.getJSONObject(legs.length() - 1)

                        val lineName  = firstLeg.optJSONObject("line")?.optString("name")
                                        ?: firstLeg.optString("mode", "?")
                        val direction = lastLeg.optJSONObject("destination")?.optString("name") ?: "?"
                        val cancelled = firstLeg.optBoolean("cancelled", false)
                        val transfers = legs.length() - 1

                        val plannedStr  = firstLeg.optString("plannedDeparture", "")
                        val realtimeStr = firstLeg.optString("departure", "")
                        val delay       = if (firstLeg.isNull("departureDelay")) null
                                          else firstLeg.optInt("departureDelay") / 60

                        deps += Departure(
                            line         = if (transfers > 0) "$lineName +$transfers" else lineName,
                            direction    = direction,
                            plannedTime  = parseTime(plannedStr, fmt),
                            realtimeTime = if (realtimeStr.isNotBlank() && realtimeStr != plannedStr)
                                               parseTime(realtimeStr, fmt) else null,
                            delayMinutes = delay,
                            cancelled    = cancelled,
                            transferInfo = findFirstWatchedTransfer(legs, watchedStationNames, fmt)
                        )
                    }
                    Result.Success(deps)
                } catch (e: Exception) {
                    Result.Error("Parse error: ${e.message}")
                }
                is RawResult.Err -> Result.Error(raw.message)
            }
        }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * URL-encodes a query parameter value using RFC 3986 (%20 for spaces).
     * Java's URLEncoder.encode() produces + for spaces, which the DB REST API
     * does NOT accept — it must be %20.
     */
    private fun encodeParam(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private sealed class RawResult {
        data class Ok(val body: String) : RawResult()
        data class Err(val message: String) : RawResult()
    }

    private fun getWithRetry(url: String): RawResult {
        var lastError = ""
        repeat(MAX_RETRIES) { attempt ->
            if (attempt > 0) {
                Thread.sleep(RETRY_DELAY * attempt)
                Log.d(TAG, "Retry $attempt for $url")
            }
            try {
                return RawResult.Ok(get(url))
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                Log.w(TAG, "Attempt ${attempt + 1} failed: $lastError")
            }
        }
        return RawResult.Err(lastError)
    }

    private fun findFirstWatchedTransfer(
        legs: JSONArray,
        watchedNames: List<String>,
        fmt: DateTimeFormatter
    ): TransferInfo? {
        if (watchedNames.isEmpty()) return null
        for (li in 0 until legs.length()) {
            val stopovers = legs.getJSONObject(li).optJSONArray("stopovers") ?: continue
            for (si in 0 until stopovers.length()) {
                val sv       = stopovers.getJSONObject(si)
                val stopName = sv.optJSONObject("stop")?.optString("name") ?: continue
                if (watchedNames.any { stopName.contains(it.trim(), ignoreCase = true) }) {
                    val planned  = sv.optString("plannedArrival", "")
                    val realtime = sv.optString("arrival", "")
                    val arrDelay = if (sv.isNull("arrivalDelay")) null
                                   else sv.optInt("arrivalDelay") / 60
                    return TransferInfo(
                        stationName  = stopName,
                        arrivalTime  = if (realtime.isNotBlank()) parseTime(realtime, fmt)
                                       else parseTime(planned, fmt),
                        delayMinutes = arrDelay
                    )
                }
            }
        }
        return null
    }

    private fun parseTime(iso: String, fmt: DateTimeFormatter): String =
        try { fmt.format(Instant.parse(iso)) } catch (_: Exception) { iso.take(5) }

    private fun get(url: String): String {
        Log.d(TAG, "GET $url")
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT
            readTimeout    = TIMEOUT
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "HomematicLauncher/1.0")
        }
        return try {
            val code = con.responseCode
            if (code >= 500) error("HTTP $code (Server error – API temporarily unavailable)")
            if (code >= 400) error("HTTP $code")
            con.inputStream.bufferedReader().readText()
        } finally {
            con.disconnect()
        }
    }
}
