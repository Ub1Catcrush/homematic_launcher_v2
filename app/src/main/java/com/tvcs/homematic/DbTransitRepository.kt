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

object DbTransitRepository {

    private const val TAG         = "DbTransitRepo"
    const  val DEFAULT_BASE       = "https://v6.db.transport.rest"
    private const val TIMEOUT     = 15_000
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY = 1_500L

    var baseUrl: String = DEFAULT_BASE

    // ── Data classes ──────────────────────────────────────────────────────────

    data class TransitStop(val id: String, val name: String)

    data class TransferInfo(
        val stationName:  String,
        val arrivalTime:  String,
        val delayMinutes: Int?
    )

    /**
     * One stopover within a leg (origin, intermediate stop, or destination).
     * Both [plannedTime] and [realtimeTime] are "HH:mm" strings.
     * [isArrival] true = arrival time shown, false = departure time shown.
     */
    data class Stopover(
        val stationName:  String,
        val plannedTime:  String,   // planned arr or dep
        val realtimeTime: String?,  // null if same as planned or unavailable
        val delayMinutes: Int?,
        val cancelled:    Boolean
    )

    /**
     * One leg of a journey (e.g. S1 from A to B, then walk, then RE from B to C).
     */
    data class Leg(
        val lineName:    String,        // e.g. "S 1", "walk", "RE 10"
        val mode:        String,        // "train", "bus", "walking", …
        val origin:      String,
        val destination: String,
        val depPlanned:  String,
        val depRealtime: String?,
        val arrPlanned:  String,
        val arrRealtime: String?,
        val depDelay:    Int?,
        val arrDelay:    Int?,
        val cancelled:   Boolean,
        val stopovers:   List<Stopover> // includes origin + destination
    )

    data class Departure(
        // ── Summary row fields ─────────────────────────────────────────────
        val line:         String,   // first leg line name (+ "+N" if transfers)
        val plannedTime:  String,   // departure of first leg, planned
        val realtimeTime: String?,  // departure of first leg, realtime
        val delayMinutes: Int?,
        val cancelled:    Boolean,
        val transferInfo: TransferInfo?,  // first watched-station match for col4
        // ── Full journey for detail sheet ──────────────────────────────────
        val legs:         List<Leg>
    ) {
        /** Final destination name (last leg destination). */
        val direction: String get() = legs.lastOrNull()?.destination ?: "?"
        /** Origin name (first leg origin). */
        val origin: String get() = legs.firstOrNull()?.origin ?: "?"
    }

    sealed class Result<out T> {
        data class Success<T>(val data: T) : Result<T>()
        data class Error(val message: String) : Result<Nothing>()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun searchStops(query: String): Result<List<TransitStop>> =
        withContext(Dispatchers.IO) {
            val enc = encodeParam(query)
            val url = "$baseUrl/locations?query=$enc&results=10&stops=true&addresses=false&poi=false"
            when (val raw = getWithRetry(url)) {
                is RawResult.Ok -> try {
                    val arr  = JSONArray(raw.body)
                    val allowed = setOf("stop", "station")
                    val list = (0 until arr.length())
                        .map { arr.getJSONObject(it) }
                        .filter { it.optString("type") in allowed }
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
                        val legsJson = journeys.getJSONObject(i).optJSONArray("legs") ?: continue
                        val legs     = parselegs(legsJson, fmt)
                        if (legs.isEmpty()) continue

                        val first     = legs.first()
                        val transfers = legs.count { it.mode != "walking" } - 1
                        val lineName  = first.lineName
                        val transfers2= maxOf(0, transfers)

                        deps += Departure(
                            line         = if (transfers2 > 0) "$lineName +$transfers2" else lineName,
                            plannedTime  = first.depPlanned,
                            realtimeTime = first.depRealtime,
                            delayMinutes = first.depDelay,
                            cancelled    = first.cancelled,
                            transferInfo = findFirstWatchedTransfer(legs, watchedStationNames),
                            legs         = legs
                        )
                    }
                    Result.Success(deps)
                } catch (e: Exception) {
                    Log.w(TAG, "Parse error", e)
                    Result.Error("Parse error: ${e.message}")
                }
                is RawResult.Err -> Result.Error(raw.message)
            }
        }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private fun parselegs(legsJson: JSONArray, fmt: DateTimeFormatter): List<Leg> {
        val result = mutableListOf<Leg>()
        for (li in 0 until legsJson.length()) {
            val leg       = legsJson.getJSONObject(li)
            val lineObj   = leg.optJSONObject("line")
            val mode      = leg.optString("mode", "walking")
            val lineName  = lineObj?.optString("name") ?: when (mode) {
                "walking"  -> "🚶"
                "transfer" -> "⇄"
                else       -> mode
            }

            val origin      = leg.optJSONObject("origin")?.optString("name") ?: "?"
            val destination = leg.optJSONObject("destination")?.optString("name") ?: "?"

            val depPlanned  = parseTime(leg.optString("plannedDeparture", ""), fmt)
            val depReal     = leg.optString("departure", "").let {
                val t = parseTime(it, fmt); if (t != depPlanned) t else null
            }
            val arrPlanned  = parseTime(leg.optString("plannedArrival", ""), fmt)
            val arrReal     = leg.optString("arrival", "").let {
                val t = parseTime(it, fmt); if (t != arrPlanned) t else null
            }
            val depDelay    = if (leg.isNull("departureDelay")) null else leg.optInt("departureDelay") / 60
            val arrDelay    = if (leg.isNull("arrivalDelay"))   null else leg.optInt("arrivalDelay")   / 60
            val cancelled   = leg.optBoolean("cancelled", false)

            // Stopovers: parse if present, else synthesise origin + destination
            val stopovers = mutableListOf<Stopover>()
            val svsJson   = leg.optJSONArray("stopovers")
            if (svsJson != null && svsJson.length() > 0) {
                for (si in 0 until svsJson.length()) {
                    val sv      = svsJson.getJSONObject(si)
                    val name    = sv.optJSONObject("stop")?.optString("name") ?: continue
                    // Use arrival for intermediate/destination, departure for origin
                    val isFirst = si == 0
                    val pTime   = if (isFirst) parseTime(sv.optString("plannedDeparture", ""), fmt)
                                  else         parseTime(sv.optString("plannedArrival",   ""), fmt)
                    val rTimeRaw = if (isFirst) sv.optString("departure", "")
                                   else         sv.optString("arrival", "")
                    val rTime   = parseTime(rTimeRaw, fmt).let { if (it != pTime) it else null }
                    val delay   = if (isFirst) {
                        if (sv.isNull("departureDelay")) null else sv.optInt("departureDelay") / 60
                    } else {
                        if (sv.isNull("arrivalDelay")) null else sv.optInt("arrivalDelay") / 60
                    }
                    stopovers += Stopover(name, pTime, rTime, delay, sv.optBoolean("cancelled", false))
                }
            } else {
                // Fallback: just origin + destination
                stopovers += Stopover(origin, depPlanned, depReal, depDelay, cancelled)
                stopovers += Stopover(destination, arrPlanned, arrReal, arrDelay, cancelled)
            }

            result += Leg(lineName, mode, origin, destination,
                depPlanned, depReal, arrPlanned, arrReal,
                depDelay, arrDelay, cancelled, stopovers)
        }
        return result
    }

    private fun findFirstWatchedTransfer(
        legs: List<Leg>,
        watchedNames: List<String>
    ): TransferInfo? {
        if (watchedNames.isEmpty()) return null
        for (leg in legs) {
            for (sv in leg.stopovers) {
                if (watchedNames.any { sv.stationName.contains(it.trim(), ignoreCase = true) }) {
                    return TransferInfo(
                        stationName  = sv.stationName,
                        arrivalTime  = sv.realtimeTime ?: sv.plannedTime,
                        delayMinutes = sv.delayMinutes
                    )
                }
            }
        }
        return null
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun encodeParam(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private sealed class RawResult {
        data class Ok(val body: String)  : RawResult()
        data class Err(val message: String) : RawResult()
    }

    private fun getWithRetry(url: String): RawResult {
        var lastError = ""
        repeat(MAX_RETRIES) { attempt ->
            if (attempt > 0) Thread.sleep(RETRY_DELAY * attempt)
            try { return RawResult.Ok(get(url)) }
            catch (e: Exception) { lastError = e.message ?: "Unknown"; Log.w(TAG, "Attempt ${attempt+1}: $lastError") }
        }
        return RawResult.Err(lastError)
    }

    private fun parseTime(iso: String, fmt: DateTimeFormatter): String =
        if (iso.isBlank()) "" else try { fmt.format(Instant.parse(iso)) } catch (_: Exception) { iso.take(5) }

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
