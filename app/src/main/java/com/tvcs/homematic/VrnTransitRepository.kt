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
import java.time.ZonedDateTime

/**
 * VrnTransitRepository
 *
 * Fetches journey data from EFA (VRN/RNN) and HAFAS REST (RMV).
 * All results are normalised into [DbTransitRepository.Departure] / [DbTransitRepository.Leg]
 * so the existing UI works unchanged.
 *
 * ── Providers ────────────────────────────────────────────────────────────────
 *  vrn / rnn  →  VRN EFA  (https://www.vrn.de/mngvrn, no token)
 *  rmv        →  RMV HAFAS REST (https://www.rmv.de/hapi, token required)
 */
object VrnTransitRepository {

    private const val TAG     = "VrnTransitRepo"
    private const val TIMEOUT = 15_000

    // ── Base URLs ─────────────────────────────────────────────────────────────

    const val VRN_BASE = "https://www.vrn.de/mngvrn"
    const val RNN_BASE = "https://www.vrn.de/mngvrn"   // RNN shares the VRN EFA instance
    const val RMV_BASE = "https://www.rmv.de/hapi"

    val PROVIDERS = mapOf(
        "vrn" to VRN_BASE,
        "rnn" to RNN_BASE,
        "rmv" to RMV_BASE
    )

    /** Providers that require an API token. */
    val REQUIRES_TOKEN = setOf("rmv")

    // Convenience wrappers so call-sites stay concise
    private fun <T> ok(v: T):    DbTransitRepository.Result<T> = DbTransitRepository.Result.Success(v)
    private fun <T> err(m: String): DbTransitRepository.Result<T> = DbTransitRepository.Result.Error(m)

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun searchStops(
        providerKey: String,
        baseUrl: String,
        query: String,
        token: String = ""
    ): DbTransitRepository.Result<List<DbTransitRepository.TransitStop>> = withContext(Dispatchers.IO) {
        when (providerKey) {
            "rmv" -> searchStopsRmv(baseUrl, query, token)
            else  -> searchStopsEfa(baseUrl, query)
        }
    }

    suspend fun getDepartures(
        providerKey: String,
        baseUrl: String,
        fromId: String,
        toId: String,
        watchedStationNames: List<String> = emptyList(),
        results: Int = 5,
        token: String = ""
    ): DbTransitRepository.Result<List<DbTransitRepository.Departure>> = withContext(Dispatchers.IO) {
        when (providerKey) {
            "rmv" -> getDeparturesRmv(baseUrl, fromId, toId, watchedStationNames, results, token)
            else  -> getDeparturesEfa(baseUrl, fromId, toId, watchedStationNames, results)
        }
    }

    // ── EFA (VRN / RNN) ───────────────────────────────────────────────────────

    private fun searchStopsEfa(baseUrl: String, query: String): DbTransitRepository.Result<List<DbTransitRepository.TransitStop>> {
        // EFA location request
        val url = "$baseUrl/XML_STOPFINDER_REQUEST" +
                  "?outputFormat=JSON" +
                  "&locationServerActive=1" +
                  "&type_sf=any" +
                  "&name_sf=${enc(query)}"
        val raw = getOrErr(url) ?: return err("Netzwerkfehler bei Stationssuche")
        return try {
            val root  = JSONObject(raw)
            val dm    = root.optJSONObject("stopFinder") ?: return ok(emptyList())
            val points = dm.optJSONArray("points") ?: JSONArray()
            val list   = mutableListOf<DbTransitRepository.TransitStop>()
            for (i in 0 until points.length()) {
                val p = points.getJSONObject(i)
                parseStop(p)?.let { list += it }
            }
            ok(list)
        } catch (e: Exception) {
            Log.w(TAG, "EFA stop parse error", e)
            err("Parse-Fehler: ${e.message}")
        }
    }

    private fun parseStop(p: JSONObject): DbTransitRepository.TransitStop? {
        val type = p.optString("type", "")
        if (type != "stop" && type != "station") return null

        val ref  = p.optJSONObject("ref") ?: p
        val rawId = ref.optString("id", "").let { if (it == "-1") "" else it }
        val id    = rawId.ifBlank { p.optString("stateless", "") }
        val name  = p.optString("name", "").ifBlank { p.optString("disassembledName", id) }
        if (id.isBlank()) return null
        return DbTransitRepository.TransitStop(id, name)
    }

    private fun getDeparturesEfa(
        baseUrl: String,
        fromId: String,
        toId: String,
        watchedStationNames: List<String>,
        results: Int
    ): DbTransitRepository.Result<List<DbTransitRepository.Departure>> {
        val url = "$baseUrl/XML_TRIP_REQUEST2" +
                  "?outputFormat=JSON" +
                  "&language=de" +
                  "&type_origin=stopID" +
                  "&name_origin=${enc(fromId)}" +
                  "&type_destination=stopID" +
                  "&name_destination=${enc(toId)}" +
                  "&calcNumberOfTrips=$results" +
                  "&itdDate=${todayStr()}" +
                  "&itdTime=${nowTimeStr()}" +
                  "&useRealtime=1" +
                  "&coordOutputFormat=WGS84"
        val raw = getOrErr(url) ?: return err("Netzwerkfehler bei Verbindungsanfrage")
        return try {
            val root  = JSONObject(raw)
            val trips = root.optJSONArray("trips") ?: JSONArray()
            val deps  = mutableListOf<DbTransitRepository.Departure>()
            for (i in 0 until trips.length()) {
                val legsJson = trips.getJSONObject(i).optJSONArray("legs") ?: continue
                val legs = parseEfaLegs(legsJson)
                if (legs.isEmpty()) continue
                val first = legs.firstOrNull { !it.isWalk } ?: continue
                val transitCount = legs.count { !it.isWalk }
                deps += DbTransitRepository.Departure(
                    line         = first.lineName,
                    transfers    = (transitCount - 1).coerceAtLeast(0),
                    plannedTime  = first.depPlanned,
                    realtimeTime = first.depRealtime,
                    delayMinutes = first.depDelay,
                    cancelled    = first.cancelled,
                    legs         = legs,
                    transferInfo = findWatchedTransfer(legs, watchedStationNames)
                )
            }
            ok(filterPastDepartures(deps))
        } catch (e: Exception) {
            Log.w(TAG, "EFA trip parse error", e)
            err("Parse-Fehler: ${e.message}")
        }
    }

    private fun parseEfaLegs(legsJson: JSONArray): List<DbTransitRepository.Leg> {
        val result = mutableListOf<DbTransitRepository.Leg>()
        for (i in 0 until legsJson.length()) {
            val leg  = legsJson.getJSONObject(i)
            val mode     = leg.optJSONObject("mode")
            val isWalk   = mode == null || mode.optString("type") == "100"
            val pointsArr = leg.optJSONArray("points")
            val originObj = pointsArr?.optJSONObject(0)
            val destObj   = pointsArr?.optJSONObject(1)
            val origin      = originObj?.optString("name")?.ifBlank { null } ?: "?"
            val destination = destObj?.optString("name")?.ifBlank { null }   ?: "?"

            if (isWalk) {
                val walkMins = leg.optJSONArray("footpath")
                    ?.optJSONObject(0)?.optString("duration")
                    ?.toIntOrNull()
                result += DbTransitRepository.Leg(
                    lineName    = "",
                    origin      = origin,
                    destination = destination,
                    depPlanned  = "",
                    depRealtime = null,
                    arrPlanned  = "",
                    arrRealtime = null,
                    depDelay    = null,
                    arrDelay    = null,
                    cancelled   = false,
                    isWalk      = true,
                    walkMinutes = walkMins
                )
                continue
            }

            val lineName = mode.optString("symbol").ifBlank { null }
                        ?: mode.optString("number").ifBlank { null }
                        ?: mode.optString("name").ifBlank { null }
                        ?: "?"

            val depDt       = originObj?.optJSONObject("dateTime")
            val depPlanned  = depDt?.optString("time") ?: ""
            val depRtRaw    = depDt?.optString("rtTime") ?: ""
            val depRealtime = if (depRtRaw.isNotBlank() && depRtRaw != depPlanned) depRtRaw else null

            val arrDt       = destObj?.optJSONObject("dateTime")
            val arrPlanned  = arrDt?.optString("time") ?: ""
            val arrRtRaw    = arrDt?.optString("rtTime") ?: ""
            val arrRealtime = if (arrRtRaw.isNotBlank() && arrRtRaw != arrPlanned) arrRtRaw else null

            val depDelay = originObj?.optJSONObject("ref")?.optString("depDelay")
                               ?.toIntOrNull()?.takeIf { it != 0 }?.let { it / 60 }
            val arrDelay = destObj?.optJSONObject("ref")?.optString("arrDelay")
                               ?.toIntOrNull()?.takeIf { it != 0 }?.let { it / 60 }

            result += DbTransitRepository.Leg(
                lineName    = lineName,
                origin      = origin,
                destination = destination,
                depPlanned  = depPlanned,
                depRealtime = depRealtime,
                arrPlanned  = arrPlanned,
                arrRealtime = arrRealtime,
                depDelay    = depDelay,
                arrDelay    = arrDelay,
                cancelled   = leg.optBoolean("isCancelled", false)
            )
        }
        return result
    }

    // ── RMV HAFAS REST ────────────────────────────────────────────────────────

    private fun searchStopsRmv(baseUrl: String, query: String, token: String): DbTransitRepository.Result<List<DbTransitRepository.TransitStop>> {
        if (token.isBlank()) return err("RMV API-Token fehlt. Bitte in den Einstellungen eintragen.")
        val url = "$baseUrl/location.name" +
                  "?accessId=${enc(token)}" +
                  "&input=${enc(query)}" +
                  "&type=S" +
                  "&maxNo=10" +
                  "&format=json"
        val raw = getOrErr(url) ?: return err("Netzwerkfehler bei RMV-Stationssuche")
        return try {
            val root  = JSONObject(raw)
            val stopsField = root.opt("stopLocationOrCoordLocation")
            val stops = when (stopsField) {
                is JSONArray -> stopsField
                is JSONObject -> JSONArray().put(stopsField)
                else -> JSONArray()
            }
            val list  = mutableListOf<DbTransitRepository.TransitStop>()
            for (i in 0 until stops.length()) {
                val sl = stops.getJSONObject(i).optJSONObject("StopLocation") ?: continue
                val id = sl.optString("extId", sl.optString("id", ""))
                val name = sl.optString("name", id)
                if (id.isNotBlank()) list += DbTransitRepository.TransitStop(id, name)
            }
            ok(list)
        } catch (e: Exception) {
            Log.w(TAG, "RMV stop parse error", e)
            err("Parse-Fehler: ${e.message}")
        }
    }

    private fun getDeparturesRmv(
        baseUrl: String,
        fromId: String,
        toId: String,
        watchedStationNames: List<String>,
        results: Int,
        token: String
    ): DbTransitRepository.Result<List<DbTransitRepository.Departure>> {
        if (token.isBlank()) return err("RMV API-Token fehlt. Bitte in den Einstellungen eintragen.")
        val numF = results.coerceIn(1, 6)
        val url = "$baseUrl/trip" +
                  "?accessId=${enc(token)}" +
                  "&originId=${enc(fromId)}" +
                  "&destId=${enc(toId)}" +
                  "&numF=$numF" +
                  "&date=${rmvDateStr()}" +
                  "&time=${enc(rmvTimeStr())}" +
                  "&rtMode=REALTIME" +
                  "&passlist=0" +
                  "&format=json"
        val raw = getOrErr(url) ?: return err("Netzwerkfehler bei RMV-Verbindungsanfrage")
        return try {
            val root  = JSONObject(raw)
            val tripsField = root.opt("Trip")
            val trips = when (tripsField) {
                is JSONArray -> tripsField
                is JSONObject -> JSONArray().put(tripsField)
                else -> JSONArray()
            }
            val deps  = mutableListOf<DbTransitRepository.Departure>()
            for (i in 0 until trips.length()) {
                val trip     = trips.getJSONObject(i)
                val legListObj = trip.optJSONObject("LegList") ?: continue
                val legsField = legListObj.opt("Leg")
                val legsJson = when (legsField) {
                    is JSONArray -> legsField
                    is JSONObject -> JSONArray().put(legsField)
                    else -> null
                } ?: continue
                val legs = parseRmvLegs(legsJson)
                if (legs.isEmpty()) continue
                
                val firstLeg = legs.first()
                val firstTransit = legs.firstOrNull { !it.isWalk } ?: firstLeg
                val transitCount = legs.count { !it.isWalk }

                deps += DbTransitRepository.Departure(
                    line         = firstTransit.lineName,
                    transfers    = (transitCount - 1).coerceAtLeast(0),
                    plannedTime  = firstLeg.depPlanned,
                    realtimeTime = firstLeg.depRealtime,
                    delayMinutes = firstTransit.depDelay,
                    cancelled    = firstTransit.cancelled,
                    legs         = legs,
                    transferInfo = findWatchedTransfer(legs, watchedStationNames)
                )
            }
            ok(filterPastDepartures(deps))
        } catch (e: Exception) {
            Log.w(TAG, "RMV trip parse error", e)
            err("Parse-Fehler: ${e.message}")
        }
    }

    private fun parseRmvLegs(legsJson: JSONArray): List<DbTransitRepository.Leg> {
        val result = mutableListOf<DbTransitRepository.Leg>()
        for (i in 0 until legsJson.length()) {
            val leg  = legsJson.getJSONObject(i)
            val type = leg.optString("type", "")
            val isWalk = type == "WALK" || type == "TRANSFER"

            val productField = leg.opt("Product")
            val product = when (productField) {
                is JSONArray -> productField.optJSONObject(0)
                is JSONObject -> productField
                else -> null
            }

            val lineName = product?.optString("line")?.ifBlank { null }
                        ?: product?.optString("name")?.ifBlank { null }
                        ?: leg.optString("name").ifBlank { null }
                        ?: ""

            val originObj  = leg.optJSONObject("Origin")
            val destObj    = leg.optJSONObject("Destination")

            val origin      = originObj?.optString("name") ?: "?"
            val destination = destObj?.optString("name") ?: "?"

            val depPlanned  = hafasHhmm(originObj?.optString("time")?.ifBlank { null } 
                                     ?: originObj?.optString("depTime"))
            val depRealRaw  = hafasHhmm(originObj?.optString("rtTime")?.ifBlank { null } 
                                     ?: originObj?.optString("rtDepTime"))
            val depRealtime = if (depRealRaw.isNotBlank() && depRealRaw != depPlanned) depRealRaw else null

            val arrPlanned  = hafasHhmm(destObj?.optString("time")?.ifBlank { null } 
                                     ?: destObj?.optString("arrTime"))
            val arrRealRaw  = hafasHhmm(destObj?.optString("rtTime")?.ifBlank { null } 
                                     ?: destObj?.optString("rtArrTime"))
            val arrRealtime = if (arrRealRaw.isNotBlank() && arrRealRaw != arrPlanned) arrRealRaw else null

            val depDelay = if (depRealtime != null) minutesDiff(depPlanned, depRealtime) else null
            val arrDelay = if (arrRealtime != null) minutesDiff(arrPlanned, arrRealtime) else null

            val durStr = leg.optString("duration", "")
            val walkMins = if (durStr.startsWith("PT")) {
                val mMatch = Regex("(\\d+)M").find(durStr)
                val hMatch = Regex("(\\d+)H").find(durStr)
                val h = hMatch?.groupValues?.get(1)?.toInt() ?: 0
                val m = mMatch?.groupValues?.get(1)?.toInt() ?: 0
                h * 60 + m
            } else null

            result += DbTransitRepository.Leg(
                lineName    = lineName,
                origin      = origin,
                destination = destination,
                depPlanned  = depPlanned,
                depRealtime = depRealtime,
                arrPlanned  = arrPlanned,
                arrRealtime = arrRealtime,
                depDelay    = depDelay,
                arrDelay    = arrDelay,
                cancelled   = leg.optBoolean("cancelled", false)
                           || originObj?.optBoolean("cancelled", false) == true
                           || leg.optString("JourneyStatus") == "C",
                isWalk      = isWalk,
                walkMinutes = walkMins
            )
        }
        return result
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private fun filterPastDepartures(deps: List<DbTransitRepository.Departure>): List<DbTransitRepository.Departure> {
        val nowZdt    = ZonedDateTime.now(ZoneId.systemDefault())
        val todayDate = nowZdt.toLocalDate()
        val fmt       = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        return deps.filter { dep ->
            val timeStr = dep.realtimeTime ?: dep.plannedTime
            if (timeStr.isBlank()) return@filter true
            try {
                val depLocalTime = java.time.LocalTime.parse(timeStr, fmt)
                var depZdt = ZonedDateTime.of(todayDate, depLocalTime, nowZdt.zone)
                if (java.time.Duration.between(nowZdt, depZdt).toMinutes() > 12 * 60) {
                    depZdt = depZdt.minusDays(1)
                }
                val minutesAgo = java.time.Duration.between(depZdt, nowZdt).toMinutes()
                minutesAgo < 0 || minutesAgo <= (dep.delayMinutes ?: 0)
            } catch (_: Exception) { true }
        }
    }

    private fun findWatchedTransfer(legs: List<DbTransitRepository.Leg>, watchedNames: List<String>): DbTransitRepository.TransferInfo? {
        if (watchedNames.isEmpty()) return null
        for (leg in legs) {
            if (leg.isWalk) continue
            for (stationName in listOf(leg.origin, leg.destination)) {
                if (watchedNames.any { stationName.contains(it.trim(), ignoreCase = true) }) {
                    val isOrigin = stationName == leg.origin
                    return DbTransitRepository.TransferInfo(
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

    private fun todayStr(): String {
        val tz = java.util.TimeZone.getDefault()
        val c  = java.util.Calendar.getInstance(tz)
        return "%04d%02d%02d".format(
            c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private fun nowTimeStr(): String {
        val tz = java.util.TimeZone.getDefault()
        val c  = java.util.Calendar.getInstance(tz)
        return "%02d%02d".format(
            c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE)
        )
    }

    private fun rmvDateStr(): String {
        val tz = java.util.TimeZone.getDefault()
        val c  = java.util.Calendar.getInstance(tz)
        return "%04d-%02d-%02d".format(
            c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private fun rmvTimeStr(): String {
        val tz = java.util.TimeZone.getDefault()
        val c  = java.util.Calendar.getInstance(tz)
        return "%02d:%02d".format(
            c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE)
        )
    }

    private fun enc(v: String): String =
        URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    private fun hafasHhmm(s: String?): String {
        if (s.isNullOrBlank()) return ""
        return if (s.length >= 5) s.substring(0, 5) else s
    }

    private fun minutesDiff(a: String, b: String): Int? = try {
        val (ah, am) = a.split(":").map { it.toInt() }
        val (bh, bm) = b.split(":").map { it.toInt() }
        (bh * 60 + bm) - (ah * 60 + am)
    } catch (_: Exception) { null }

    private fun getOrErr(url: String): String? {
        Log.d(TAG, "GET $url")
        return try {
            val con = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT
                readTimeout    = TIMEOUT
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "HomematicLauncher/1.0")
            }
            try {
                val code = con.responseCode
                if (code >= 400) { Log.w(TAG, "HTTP $code for $url"); return null }
                con.inputStream.bufferedReader().readText()
            } finally { con.disconnect() }
        } catch (e: Exception) {
            Log.w(TAG, "Network error: ${e.message}")
            null
        }
    }
}
