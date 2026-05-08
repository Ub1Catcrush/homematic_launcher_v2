package com.tvcs.homematic

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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

    // Built lazily on first use — OkHttpClient.build() loads SSL certs and can take
    // 200–800 ms; doing it eagerly on the Main Thread contributes to ANR.
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0,  TimeUnit.SECONDS)   // no read timeout — persistent connection
            .pingInterval(30, TimeUnit.SECONDS)  // keep-alive
            .build()
    }

    // IO dispatcher for all blocking network work
    private val ioScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob()
    )

    // ── API ───────────────────────────────────────────────────────────────────

    /** Start (or restart) the WebSocket connection. Safe to call multiple times. */
    fun connect(wsUrl: String, token: String) {
        if (this.wsUrl == wsUrl && this.token == token && active &&
            _connState.value is ConnState.Connected) return

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
        _connState.value = ConnState.Connecting
        Log.d(TAG, "Connecting to $wsUrl")

        // Dispatch to IO — URL parsing + OkHttpClient.newWebSocket() acquires SSL
        // context and does DNS lookups; blocking the Main Thread here causes ANR.
        ioScope.launch {
        val request = try {
            Request.Builder().url(wsUrl).build()
        } catch (e: Exception) {
            Log.e(TAG, "Invalid URL: $wsUrl")
            _connState.value = ConnState.Error("Ungültige URL: $wsUrl")
            return@launch
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
        } // end ioScope.launch
    }

    private fun handleMessage(ws: WebSocket, text: String) {
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
                    // Fetch all current states then subscribe to changes
                    sendGetStates(ws)
                    sendSubscribeStateChanged(ws)
                }

                "auth_invalid" -> {
                    Log.e(TAG, "Auth invalid")
                    _connState.value = ConnState.Error("Authentifizierung fehlgeschlagen – Token prüfen")
                    active = false   // don't retry, user must fix token
                    socket?.close(1000, null)
                }

                // ── Result of get_states ──────────────────────────────────────
                "result" -> {
                    if (json.optBoolean("success", false)) {
                        val result = json.optJSONArray("result") ?: return
                        val map = (_entityStates.value).toMutableMap()
                        for (i in 0 until result.length()) {
                            val s = result.getJSONObject(i)
                            val es = parseEntityState(s)
                            map[es.entityId] = es
                        }
                        _entityStates.value = map
                    }
                }

                // ── Real-time state_changed events ────────────────────────────
                "event" -> {
                    val event = json.optJSONObject("event") ?: return
                    if (event.optString("event_type") != "state_changed") return
                    val data     = event.optJSONObject("data") ?: return
                    val newState = data.optJSONObject("new_state") ?: return
                    val es       = parseEntityState(newState)
                    _entityStates.value = _entityStates.value + (es.entityId to es)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleMessage error: ${e.message}")
        }
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
        val state    = json.optString("state", "")
        val attrJson = json.optJSONObject("attributes")
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
        // Use coroutine delay on IO thread — Handler.postDelayed would put doConnect()
        // back on the Main Thread which is exactly what we are trying to avoid.
        ioScope.launch {
            delay(delay)
            if (active) {
                try { doConnect() } catch (e: Exception) {
                    Log.e(TAG, "doConnect failed: ${e.message}", e)
                }
            }
        }
    }
}
