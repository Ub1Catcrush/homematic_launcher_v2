package com.tvcs.homematic

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * HaRepository
 *
 * Manages a single persistent WebSocket connection to a Home Assistant instance.
 * Uses the HA WebSocket API (https://developers.home-assistant.io/docs/api/websocket).
 *
 * Connection lifecycle:
 *   connect()   → auth handshake → subscribe_events(state_changed) → live updates
 *   disconnect() → closes socket, stops reconnect attempts
 *
 * Entity states are exposed via [entityStates] StateFlow:
 *   Map<entity_id, EntityState>
 *
 * Reconnect: exponential back-off 2s → 4s → 8s … capped at 60s.
 */
object HaRepository {

    private const val TAG = "HaRepository"

    /**
     * Maximum byte length we will attempt to parse as JSON in handleMessage.
     * HA's get_states response can be several MB on large installations.
     * Messages exceeding this limit are dropped with a warning — the relevant
     * data arrives via filtered subscribe_entities / subscribe_events anyway.
     */
    private const val MAX_MESSAGE_BYTES = 2 * 1024 * 1024   // 2 MB

    data class EntityState(
        val entityId: String,
        val state:    String,
        /** Raw attributes map — may contain unit_of_measurement, friendly_name, etc. */
        val attributes: Map<String, String> = emptyMap()
    )

    sealed class ConnState {
        object Disconnected : ConnState()
        object Connecting   : ConnState()
        object Authenticating : ConnState()
        object Connected    : ConnState()
        data class Error(val message: String) : ConnState()
    }

    // ── Public state ──────────────────────────────────────────────────────────

    private val _entityStates = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    val entityStates: StateFlow<Map<String, EntityState>> = _entityStates

    private val _connState = MutableStateFlow<ConnState>(ConnState.Disconnected)
    val connState: StateFlow<ConnState> = _connState

    // ── Private fields ────────────────────────────────────────────────────────

    private val msgId     = AtomicInteger(1)
    private var socket:   WebSocket? = null
    private var token:    String     = ""
    private var wsUrl:    String     = ""
    private var active:   Boolean    = false
    private var retryDelayMs: Long   = 2_000L

    /**
     * Entity IDs to watch. When non-empty, [connect] uses subscribe_entities
     * (filtered) instead of get_states (all entities) to avoid OOM on large
     * HA installations. Set this before calling connect().
     */
    var watchedEntityIds: Set<String> = emptySet()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0,  TimeUnit.SECONDS)   // no read timeout — persistent connection
        .pingInterval(30, TimeUnit.SECONDS)  // keep-alive
        .build()

    // ── API ───────────────────────────────────────────────────────────────────

    /** Start (or restart) the WebSocket connection. Safe to call multiple times. */
    fun connect(wsUrl: String, token: String) {
        // Already connected or in-flight with the same credentials → nothing to do.
        // Checking Connecting/Authenticating prevents the three HaTileViewControllers
        // from each opening their own simultaneous WebSocket to the same server.
        val sameCredentials = this.wsUrl == wsUrl && this.token == token
        if (sameCredentials && active) {
            when (_connState.value) {
                is ConnState.Connected,
                is ConnState.Connecting,
                is ConnState.Authenticating -> return
                else -> { /* Disconnected or Error — fall through and reconnect */ }
            }
        }

        this.wsUrl = wsUrl
        this.token = token
        active     = true
        retryDelayMs = 2_000L
        doConnect()
    }

    /** Close the WebSocket and stop reconnect attempts. */
    fun disconnect() {
        active = false
        socket?.close(1000, "disconnect")
        socket = null
        _connState.value = ConnState.Disconnected
        _entityStates.value = emptyMap()
    }

    /** Force-reconnect (e.g. after settings change). */
    fun reconnect(wsUrl: String, token: String) {
        disconnect()
        connect(wsUrl, token)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun doConnect() {
        if (!active) return
        // Close any existing socket before opening a new one — prevents leaked
        // connections when doConnect() is called while a previous attempt is still
        // in-flight (e.g. scheduleReconnect firing after a manual reconnect).
        socket?.let { try { it.close(1000, "reconnect") } catch (_: Exception) {} }
        socket = null
        _connState.value = ConnState.Connecting
        Log.d(TAG, "Connecting to $wsUrl")

        val request = try {
            Request.Builder().url(wsUrl).build()
        } catch (e: Exception) {
            Log.e(TAG, "Invalid URL: $wsUrl")
            _connState.value = ConnState.Error("Ungültige URL: $wsUrl")
            return
        }

        socket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
                _connState.value = ConnState.Authenticating
                // HA sends auth_required first — we wait for onMessage
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(webSocket, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: ${t.message}")
                _connState.value = ConnState.Error(t.message ?: "Verbindungsfehler")
                scheduleReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed ($code): $reason")
                if (active) {
                    _connState.value = ConnState.Error("Verbindung getrennt ($code)")
                    scheduleReconnect()
                }
            }
        })
    }

    private fun handleMessage(ws: WebSocket, text: String) {
        if (text.length > MAX_MESSAGE_BYTES) {
            Log.w(TAG, "handleMessage: message too large (${text.length} bytes) — skipping to avoid OOM")
            return
        }
        // ── Diagnostic raw-message logging (first 800 chars per message) ──────
        // Remove this block once HA entity values are confirmed working.
        try {
            val preview = text.take(800)
            val msgType = try { org.json.JSONObject(text).optString("type", "?") } catch (_: Exception) { "?" }
            if (msgType !in setOf("auth_required", "auth_ok", "auth_invalid")) {
                Log.d(TAG, "WS_RAW type=$msgType len=${text.length}: $preview")
            }
        } catch (_: Exception) {}
        // ─────────────────────────────────────────────────────────────────────
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {

                // ── Auth flow ─────────────────────────────────────────────────
                "auth_required" -> {
                    ws.send(JSONObject().apply {
                        put("type", "auth")
                        put("access_token", token)
                    }.toString())
                }

                "auth_ok" -> {
                    Log.d(TAG, "Auth OK")
                    _connState.value = ConnState.Connected
                    retryDelayMs = 2_000L
                    val ids = watchedEntityIds
                    if (ids.isNotEmpty()) {
                        // Use subscribe_entities with explicit filter — avoids loading
                        // all entities into memory (OOM on large HA installations).
                        Log.d(TAG, "subscribe_entities for ${ids.size} entity IDs")
                        sendSubscribeEntities(ws, ids)
                    } else {
                        // Fallback: no filter configured — load all states.
                        // Risk of OOM on large installations; prefer configuring
                        // watchedEntityIds before connecting.
                        Log.w(TAG, "watchedEntityIds empty — falling back to get_states (may OOM)")
                        sendGetStates(ws)
                        sendSubscribeStateChanged(ws)
                    }
                }

                "auth_invalid" -> {
                    Log.e(TAG, "Auth invalid")
                    _connState.value = ConnState.Error("Authentifizierung fehlgeschlagen – Token prüfen")
                    active = false   // don't retry, user must fix token
                    socket?.close(1000, null)
                }

                // ── Result of get_states OR subscribe_entities initial snapshot ────
                // get_states returns:         result: [ {entity_id, state, attributes}, … ]  (array)
                // subscribe_entities returns: result: { "entity_id": {state, attributes}, … } (object)
                // Both must populate _entityStates.
                "result" -> {
                    if (json.optBoolean("success", false)) {
                        val map = (_entityStates.value).toMutableMap()
                        val resultArr = json.optJSONArray("result")
                        val resultObj = if (resultArr == null) json.optJSONObject("result") else null
                        when {
                            resultArr != null -> {
                                // get_states array format
                                for (i in 0 until resultArr.length()) {
                                    val s  = resultArr.getJSONObject(i)
                                    val es = parseEntityState(s)
                                    map[es.entityId] = es
                                }
                            }
                            resultObj != null -> {
                                // subscribe_entities object format: keys are entity_ids
                                resultObj.keys().forEach { eid ->
                                    runCatching {
                                        val s = resultObj.getJSONObject(eid)
                                        if (!s.has("entity_id")) s.put("entity_id", eid)
                                        map[eid] = parseEntityState(s)
                                    }
                                }
                            }
                            // result is null or neither — nothing to do
                        }
                        if (map.isNotEmpty()) _entityStates.value = map
                    }
                }

                // ── Real-time events (state_changed + subscribe_entities updates) ────
                "event" -> {
                    val event = json.optJSONObject("event") ?: return
                    when (event.optString("event_type")) {
                        "state_changed" -> {
                            // Legacy subscribe_events — data.new_state
                            val data     = event.optJSONObject("data") ?: return
                            val newState = data.optJSONObject("new_state") ?: return
                            val es = parseEntityState(newState)
                            _entityStates.value = _entityStates.value + (es.entityId to es)
                        }
                        else -> {
                            // subscribe_entities — a/c/r sit directly on event, NOT under "data"
                            // {"event": {"a": {"sensor.x": {"s":"1","a":{...}}}, "c": {...}, "r": []}}
                            val map = (_entityStates.value).toMutableMap()
                            // Added (initial snapshot on first event, or new entities later)
                            event.optJSONObject("a")?.let { added ->
                                added.keys().forEach { eid ->
                                    runCatching {
                                        val s = added.getJSONObject(eid)
                                        if (!s.has("entity_id")) s.put("entity_id", eid)
                                        map[eid] = parseEntityState(s)
                                    }
                                }
                            }
                            // Changed — {entity_id: {"+": {"s": newState, "a": attrDelta}}}
                            event.optJSONObject("c")?.let { changed ->
                                changed.keys().forEach { eid ->
                                    runCatching {
                                        val diff = changed.getJSONObject(eid)
                                            .optJSONObject("+") ?: return@forEach
                                        val existing = map[eid]
                                        val newState = diff.optString("s", "")
                                            .ifBlank { diff.optString("state", "") }
                                            .takeIf { it.isNotBlank() } ?: existing?.state ?: ""
                                        val attrs = existing?.attributes?.toMutableMap() ?: mutableMapOf()
                                        val attrDelta = diff.optJSONObject("a") ?: diff.optJSONObject("attributes")
                                        attrDelta?.keys()?.forEach { k ->
                                            attrs[k] = attrDelta.opt(k)?.toString() ?: ""
                                        }
                                        map[eid] = EntityState(eid, newState, attrs)
                                    }
                                }
                            }
                            // Removed
                            event.optJSONArray("r")?.let { removed ->
                                for (i in 0 until removed.length()) map.remove(removed.optString(i))
                            }
                            if (map.isNotEmpty()) _entityStates.value = map
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleMessage error: ${e.message}")
        }
    }

    private fun sendSubscribeEntities(ws: WebSocket, entityIds: Set<String>) {
        ws.send(JSONObject().apply {
            put("id",   msgId.getAndIncrement())
            put("type", "subscribe_entities")
            put("entity_ids", org.json.JSONArray(entityIds.toList()))
        }.toString())
    }

    private fun sendGetStates(ws: WebSocket) {
        ws.send(JSONObject().apply {
            put("id",   msgId.getAndIncrement())
            put("type", "get_states")
        }.toString())
    }

    private fun sendSubscribeStateChanged(ws: WebSocket) {
        ws.send(JSONObject().apply {
            put("id",   msgId.getAndIncrement())
            put("type", "subscribe_events")
            put("event_type", "state_changed")
        }.toString())
    }

    private fun parseEntityState(json: JSONObject): EntityState {
        val entityId = json.optString("entity_id", "")
        // subscribe_entities uses compact keys: "s" = state, "a" = attributes
        // get_states / state_changed use full keys: "state", "attributes"
        val state    = json.optString("s", "").ifBlank { json.optString("state", "") }
        val attrJson = json.optJSONObject("a") ?: json.optJSONObject("attributes")
        val attrs    = mutableMapOf<String, String>()
        attrJson?.keys()?.forEach { k ->
            attrs[k] = attrJson.opt(k)?.toString() ?: ""
        }
        return EntityState(entityId, state, attrs)
    }

    private fun scheduleReconnect() {
        if (!active) return
        val delay = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
        Log.d(TAG, "Reconnecting in ${delay}ms")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { doConnect() } catch (e: Exception) { Log.e(TAG, "doConnect failed: ${e.message}", e) }
        }, delay)
    }
}
