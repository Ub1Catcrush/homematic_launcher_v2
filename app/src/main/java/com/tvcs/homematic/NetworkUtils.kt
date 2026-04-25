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
}
