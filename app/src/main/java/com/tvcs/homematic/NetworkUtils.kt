package com.tvcs.homematic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object NetworkUtils {

    enum class ConnectionType { NONE, WIFI, CELLULAR, ETHERNET, OTHER }

    data class NetworkStatus(
        val isConnected: Boolean,
        val connectionType: ConnectionType,
        val wifiSsid: String? = null,
        val signalStrength: Int = -1,   // 0–4 bars; -1 = unknown
        val description: String
    )

    fun getNetworkStatus(context: Context): NetworkStatus {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return NetworkStatus(
            isConnected = false, connectionType = ConnectionType.NONE,
            description = context.getString(R.string.network_status_none)
        )
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkStatus(
            isConnected = false, connectionType = ConnectionType.NONE,
            description = context.getString(R.string.network_status_unavailable)
        )

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val (ssid, bars) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // API 29+: signal strength via NetworkCapabilities — no deprecated WifiInfo needed
                    val rssi = caps.signalStrength
                    val bars = when {
                        rssi == Int.MIN_VALUE -> -1
                        rssi >= -55 -> 4
                        rssi >= -66 -> 3
                        rssi >= -77 -> 2
                        rssi >= -88 -> 1
                        else        -> 0
                    }
                    // SSID still needs WifiManager; returns "<unknown ssid>" without ACCESS_FINE_LOCATION
                    val wm = context.applicationContext
                        .getSystemService(Context.WIFI_SERVICE) as WifiManager
                    @Suppress("DEPRECATION")
                    val ssid = wm.connectionInfo?.ssid?.replace("\"", "") ?: "WLAN"
                    Pair(ssid, bars)
                } else {
                    val wm = context.applicationContext
                        .getSystemService(Context.WIFI_SERVICE) as WifiManager
                    @Suppress("DEPRECATION")
                    val info = wm.connectionInfo
                    @Suppress("DEPRECATION")
                    val ssid = info?.ssid?.replace("\"", "") ?: "Unbekannt"
                    @Suppress("DEPRECATION")
                    val bars = WifiManager.calculateSignalLevel(info?.rssi ?: -100, 5)
                    Pair(ssid, bars)
                }
                NetworkStatus(
                    isConnected = true,
                    connectionType = ConnectionType.WIFI,
                    wifiSsid = ssid,
                    signalStrength = bars,
                    description = if (bars >= 0)
                        context.getString(R.string.network_status_wifi_signal, ssid, bars)
                    else
                        context.getString(R.string.network_status_wifi, ssid)
                )
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkStatus(
                isConnected = true, connectionType = ConnectionType.ETHERNET,
                description = context.getString(R.string.network_status_ethernet)
            )
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkStatus(
                isConnected = true, connectionType = ConnectionType.CELLULAR,
                description = context.getString(R.string.network_status_cellular)
            )
            else -> NetworkStatus(
                isConnected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                connectionType = ConnectionType.OTHER,
                description = context.getString(R.string.network_status_other)
            )
        }
    }

    fun isConnected(context: Context): Boolean = getNetworkStatus(context).isConnected

    /**
     * Checks CCU reachability via TCP socket.
     * Uses the correct port based on the HTTPS setting (80 or 443).
     * Runs on IO dispatcher — safe to call from any coroutine context.
     */
    suspend fun isCcuReachable(
        host: String,
        https: Boolean = false,
        timeoutMs: Int = 3000
    ): Boolean = withContext(Dispatchers.IO) {
        val port = if (https) 443 else 80
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Result of a CCU connection + optional SID authentication test.
     */
    data class CcuTestResult(
        val reachable: Boolean,
        /** null = no SID configured, true = SID valid, false = SID rejected */
        val authOk: Boolean?,
        val host: String
    )

    /**
     * Tests TCP reachability AND — when a SID is configured — validates it against the
     * CCU XML-API by fetching `systemNotification.cgi?sid=…`.
     *
     * Auth is considered successful when the HTTP response is 200 and the body contains
     * a valid XML root element (not an error/empty document).
     * A 200 with `<not_authenticated/>` or an empty body counts as auth failure.
     *
     * Runs on IO dispatcher — safe to call from any coroutine context.
     */
    suspend fun testCcuConnection(
        host: String,
        https: Boolean,
        port: String?,
        apiPath: String,
        sid: String,
        timeoutMs: Int = 5000
    ): CcuTestResult = withContext(Dispatchers.IO) {
        // Step 1: TCP reachability
        val tcpPort = when {
            !port.isNullOrBlank() -> port.toIntOrNull() ?: (if (https) 443 else 80)
            else                  -> if (https) 443 else 80
        }
        val reachable = try {
            Socket().use { it.connect(InetSocketAddress(host, tcpPort), timeoutMs); true }
        } catch (e: Exception) { false }

        if (!reachable) return@withContext CcuTestResult(false, null, host)

        // Step 2: If no SID configured, skip auth check
        if (sid.isBlank()) return@withContext CcuTestResult(true, null, host)

        // Step 3: Authenticate SID against XML-API
        val protocol = if (https) "https" else "http"
        val portPart = if (port.isNullOrBlank()) "" else ":$port"
        val url = "$protocol://$host$portPart${apiPath}systemNotification.cgi?sid=$sid"
        val authOk = try {
            val con = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout    = timeoutMs
                requestMethod  = "GET"
            }
            val code = con.responseCode
            if (code != java.net.HttpURLConnection.HTTP_OK) {
                false
            } else {
                val body = con.inputStream.bufferedReader().readText().trim()
                // Auth failure: empty body or CCU returns <not_authenticated/> or similar error root
                body.isNotEmpty()
                    && !body.contains("<not_authenticated", ignoreCase = true)
                    && !body.contains("<error", ignoreCase = true)
                    && body.startsWith("<")
            }
        } catch (e: Exception) { false }

        CcuTestResult(true, authOk, host)
    }

    /**
     * Returns true when the app is allowed to make network requests.
     *
     * If LAN_ONLY mode is enabled, every configured endpoint is probed via TCP
     * before allowing a sync.  The idea: on mobile data the endpoints are not
     * reachable (private IPs / VPN required), so we gate on reachability rather
     * than transport type.
     *
     * Endpoints probed (when configured):
     *   • CCU  — host from prefs, port 80 or 443
     *   • Camera snapshot URL — host + port
     *   • DB transit base URL — host, port 443
     *
     * At least ONE endpoint must be reachable for the gate to open.
     */
    suspend fun isNetworkAllowed(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            val lanOnly = prefs.getBoolean(PreferenceKeys.LAN_ONLY_MODE, false)
            if (!lanOnly) return@withContext true          // feature disabled — always allow

            // Collect endpoints to probe
            val endpoints = mutableListOf<Pair<String, Int>>()

            // CCU
            val ccuHost = prefs.getString(PreferenceKeys.CCU_HOST, "") ?: ""
            if (ccuHost.isNotBlank()) {
                val https    = prefs.getBoolean(PreferenceKeys.CCU_HTTPS, false)
                val portStr  = prefs.getString(PreferenceKeys.CCU_PORT, "") ?: ""
                val port     = portStr.toIntOrNull() ?: if (https) 443 else 80
                endpoints += ccuHost to port
            }

            // Camera
            val camUrl = prefs.getString(PreferenceKeys.CAMERA_SNAPSHOT_URL, "") ?: ""
            if (camUrl.isNotBlank()) {
                try {
                    val u    = java.net.URL(camUrl)
                    val port = if (u.port > 0) u.port else if (u.protocol == "https") 443 else 80
                    endpoints += u.host to port
                } catch (_: Exception) {}
            }

            // DB transit
            val baseUrl = prefs.getString(PreferenceKeys.TRANSIT_BASE_URL,
                DbTransitRepository.DEFAULT_BASE) ?: DbTransitRepository.DEFAULT_BASE
            if (prefs.getBoolean(PreferenceKeys.TRANSIT_ENABLED, false) && baseUrl.isNotBlank()) {
                try {
                    val u    = java.net.URL(baseUrl)
                    val port = if (u.port > 0) u.port else if (u.protocol == "https") 443 else 80
                    endpoints += u.host to port
                } catch (_: Exception) {}
            }

            if (endpoints.isEmpty()) return@withContext true  // nothing to probe → allow

            // Return true if ANY endpoint is reachable within 2 s
            endpoints.any { (host, port) ->
                try {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(host, port), 2_000)
                        true
                    }
                } catch (_: Exception) { false }
            }
        }

}