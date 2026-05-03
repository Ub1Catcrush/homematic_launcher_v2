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
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object DbTransitRepository {

    private const val TAG         = "DbTransitRepo"
    const  val DEFAULT_BASE       = "https://v6.db.transport.rest"
    private const val TIMEOUT     = 15_000
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY = 1_500L

    // ── Data classes ──────────────────────────────────────────────────────────

    data class TransitStop(val id: String, val name: String)

    /**
     * One real transit leg (train / bus / tram — NOT walking / transfer).
     * Used both for Col-4 display and for the detail bottom sheet.
     *
     * [origin]      departure station name
     * [destination] arrival station name
     * [depPlanned]  planned departure  "HH:mm"
     * [depRealtime] realtime departure "HH:mm", null if same as planned
     * [arrPlanned]  planned arrival    "HH:mm"
     * [arrRealtime] realtime arrival   "HH:mm", null if same as planned
     * [depDelay]    departure delay in minutes, null if unknown
     * [arrDelay]    arrival delay in minutes,   null if unknown
     */
    data class Leg(
        val lineName:    String,
        val origin:      String,
        val destination: String,
        val depPlanned:  String,
        val depRealtime: String?,
        val arrPlanned:  String,
        val arrRealtime: String?,
        val depDelay:    Int?,
        val arrDelay:    Int?,
        val cancelled:   Boolean
    )

    /**
     * One journey (= one result row in the departure list).
     *
     * [line]        first leg line name
     * [transfers]   number of real transit-leg transfers (walking excluded)
     * [plannedTime] departure of first leg, planned
     * [realtimeTime] departure of first leg, realtime (null if same)
     * [delayMinutes] departure delay of first leg
     * [cancelled]   first leg cancelled
     * [legs]        all non-walking transit legs in order
     */
    data class TransferInfo(
        val stationName:  String,
        val arrivalTime:  String,
        val delayMinutes: Int?
    )

    data class Departure(
        val line:         String,
        val transfers:    Int,
        val plannedTime:  String,
        val realtimeTime: String?,
        val delayMinutes: Int?,
        val cancelled:    Boolean,
        val legs:         List<Leg>,
        val transferInfo: TransferInfo? = null
    ) {
        /** Final destination name. */
        val direction: String get() = legs.lastOrNull()?.destination ?: "?"
        /** Origin name. */
        val origin: String get() = legs.firstOrNull()?.origin ?: "?"
    }

    sealed class Result<out T> {
        data class Success<T>(val data: T) : Result<T>()
        data class Error(val message: String) : Result<Nothing>()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun searchStops(baseUrl: String, query: String): Result<List<TransitStop>> =
        withContext(Dispatchers.IO) {
            val enc = encodeParam(query)
            val url = "$baseUrl/locations?query=$enc&results=10&stops=true&addresses=false&poi=false"
            when (val raw = getWithRetry(url)) {
                is RawResult.Ok -> try {
                    val arr  = JSONArray(raw.body)
                    val list = (0 until arr.length())
                        .map { arr.getJSONObject(it) }
                        .filter { it.optString("type") in setOf("stop", "station") }
                        .map { TransitStop(it.getString("id"), it.getString("name")) }
                    Result.Success(list)
                } catch (e: Exception) { Result.Error("Parse error: ${e.message}") }
                is RawResult.Err -> Result.Error(raw.message)
            }
        }

    suspend fun getDepartures(
        baseUrl: String,
        fromId: String,
        toId: String,
        watchedStationNames: List<String> = emptyList(),
        results: Int = 5   // fetch more so we have room after filtering stale ones
    ): Result<List<Departure>> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/journeys?from=${encodeParam(fromId)}&to=${encodeParam(toId)}" +
                      "&results=$results&language=de&stopovers=false&pretty=false"
            when (val raw = getWithRetry(url)) {
                is RawResult.Ok -> try {
                    val fmt      = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
                    val journeys = JSONObject(raw.body).optJSONArray("journeys") ?: JSONArray()
                    val deps     = mutableListOf<Departure>()
                    for (i in 0 until journeys.length()) {
                        val legsJson = journeys.getJSONObject(i).optJSONArray("legs") ?: continue
                        // Only keep real transit legs (exclude walking/transfer)
                        val transitLegs = parseTransitLegs(legsJson, fmt)
                        if (transitLegs.isEmpty()) continue
                        val first     = transitLegs.first()
                        val transfers = transitLegs.size - 1
                        deps += Departure(
                            line         = first.lineName,
                            transfers    = transfers,
                            plannedTime  = first.depPlanned,
                            realtimeTime = first.depRealtime,
                            delayMinutes = first.depDelay,
                            cancelled    = first.cancelled,
                            legs         = transitLegs,
                            transferInfo = findWatchedTransfer(transitLegs, watchedStationNames)
                        )
                    }
                    // Filter out departures whose realtime (or planned) departure
                    // time has already passed, taking delay into account.
                    val now = LocalTime.now()
                    val fmtParse = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
                    val filtered = deps.filter { dep ->
                        val timeStr = dep.realtimeTime ?: dep.plannedTime
                        if (timeStr.isBlank()) return@filter true
                        try {
                            val depTime = LocalTime.parse(timeStr, fmtParse)
                            // Handle midnight wrap: if dep is >6h behind now it's from yesterday
                            val minutesAgo = java.time.Duration.between(depTime, now).toMinutes()
                            minutesAgo < 0 || minutesAgo <= (dep.delayMinutes ?: 0)
                        } catch (_: Exception) { true }
                    }.take(3)
                    Result.Success(filtered)
                } catch (e: Exception) {
                    Log.w(TAG, "Parse error", e)
                    Result.Error("Parse error: ${e.message}")
                }
                is RawResult.Err -> Result.Error(raw.message)
            }
        }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private fun parseTransitLegs(legsJson: JSONArray, fmt: DateTimeFormatter): List<Leg> {
        val result = mutableListOf<Leg>()
        for (li in 0 until legsJson.length()) {
            val leg  = legsJson.getJSONObject(li)
            val mode = leg.optString("mode", "")
            // Skip pure walking/transfer legs
            if (mode == "walking" || mode == "transfer" || leg.optBoolean("walking", false)) continue

            val lineName    = leg.optJSONObject("line")?.optString("name")
                              ?: leg.optString("mode", "?")
            val origin      = leg.optJSONObject("origin")?.optString("name") ?: "?"
            val destination = leg.optJSONObject("destination")?.optString("name") ?: "?"

            val depPlanned  = parseTime(leg.optString("plannedDeparture", ""), fmt)
            val depRealRaw  = parseTime(leg.optString("departure", ""), fmt)
            val depReal     = if (depRealRaw != depPlanned) depRealRaw else null

            val arrPlanned  = parseTime(leg.optString("plannedArrival", ""), fmt)
            val arrRealRaw  = parseTime(leg.optString("arrival", ""), fmt)
            val arrReal     = if (arrRealRaw != arrPlanned) arrRealRaw else null

            val depDelay = if (leg.isNull("departureDelay")) null else leg.optInt("departureDelay") / 60
            val arrDelay = if (leg.isNull("arrivalDelay"))   null else leg.optInt("arrivalDelay")   / 60

            result += Leg(
                lineName    = lineName,
                origin      = origin,
                destination = destination,
                depPlanned  = depPlanned,
                depRealtime = depReal,
                arrPlanned  = arrPlanned,
                arrRealtime = arrReal,
                depDelay    = depDelay,
                arrDelay    = arrDelay,
                cancelled   = leg.optBoolean("cancelled", false)
            )
        }
        return result
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun findWatchedTransfer(legs: List<Leg>, watchedNames: List<String>): TransferInfo? {
        if (watchedNames.isEmpty()) return null
        for (leg in legs) {
            // Check origin and destination of each leg
            for (stationName in listOf(leg.origin, leg.destination)) {
                if (watchedNames.any { stationName.contains(it.trim(), ignoreCase = true) }) {
                    val isOrigin = stationName == leg.origin
                    return TransferInfo(
                        stationName  = stationName,
                        arrivalTime  = if (isOrigin) leg.depRealtime ?: leg.depPlanned
                                       else           leg.arrRealtime ?: leg.arrPlanned,
                        delayMinutes = if (isOrigin) leg.depDelay else leg.arrDelay
                    )
                }
            }
        }
        return null
    }

    private fun encodeParam(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private sealed class RawResult {
        data class Ok(val body: String)     : RawResult()
        data class Err(val message: String) : RawResult()
    }

    private fun getWithRetry(url: String): RawResult {
        var lastError = ""
        repeat(MAX_RETRIES) { attempt ->
            if (attempt > 0) Thread.sleep(RETRY_DELAY * attempt)
            try { return RawResult.Ok(get(url)) }
            catch (e: Exception) {
                lastError = e.message ?: "Unknown"
                Log.w(TAG, "Attempt ${attempt + 1}: $lastError")
            }
        }
        return RawResult.Err(lastError)
    }

    private fun parseTime(iso: String, fmt: DateTimeFormatter): String =
        if (iso.isBlank()) "" else
        try { fmt.format(Instant.parse(iso)) } catch (_: Exception) { iso.take(5) }

    private fun get(url: String): String {
        Log.d(TAG, "GET $url")
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT; readTimeout = TIMEOUT
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "HomematicLauncher/1.0")
        }
        return try {
            val code = con.responseCode
            if (code >= 500) error("HTTP $code (Server error)")
            if (code >= 400) error("HTTP $code")
            con.inputStream.bufferedReader().readText()
        } finally { con.disconnect() }
    }
}
