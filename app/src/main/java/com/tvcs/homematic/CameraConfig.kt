package com.tvcs.homematic

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Immutable configuration for one camera.
 *
 * ── Dual-resolution ────────────────────────────────────────────────────────────
 * Each camera can have a high-res and a low-res stream (RTSP + snapshot each).
 * [useHighRes] selects which pair is active at runtime.
 * Legacy single-URL configs set both hi/lo fields to the same value on migration.
 *
 * ── Engine stack ───────────────────────────────────────────────────────────────
 * [engineOrder] is an ordered list of engine names, each optionally prefixed
 * with "-" to indicate it is disabled:
 *   e.g. ["exo", "vlc", "snapshot"]       — all enabled, default order
 *        ["vlc", "-exo", "snapshot"]       — VLC first, EXO disabled
 *        ["exo", "-vlc", "-snapshot"]      — only EXO, no fallback
 *
 * The CameraViewController reads this list to build its failover sequence.
 * An empty list falls back to the built-in default: exo → vlc → snapshot.
 */
data class CameraConfig(
    val id:              String,
    val name:            String,

    // ── High-res stream ──────────────────────────────────────────────────────
    val rtspUrlHigh:     String  = "",
    val snapshotUrlHigh: String  = "",

    // ── Low-res stream ───────────────────────────────────────────────────────
    val rtspUrlLow:      String  = "",
    val snapshotUrlLow:  String  = "",

    // ── Active resolution ────────────────────────────────────────────────────
    /** true = use high-res streams; false = use low-res streams */
    val useHighRes:      Boolean = true,

    // ── Credentials (shared for both resolutions) ───────────────────────────
    val username:        String  = "",
    val password:        String  = "",

    // ── Engine stack ─────────────────────────────────────────────────────────
    /**
     * Ordered engine list. Each entry is "exo", "vlc", or "snapshot",
     * optionally prefixed with "-" to disable that engine.
     * Empty = default built-in order (exo → vlc → snapshot).
     */
    val engineOrder:     List<String> = emptyList(),

    // ── Timeouts / intervals ─────────────────────────────────────────────────
    val rtspTimeoutMs:       Long = 8_000L,
    val snapshotIntervalSec: Int  = 5
) {
    // ── Convenience accessors ─────────────────────────────────────────────────

    val rtspUrl:     String get() = if (useHighRes) rtspUrlHigh.ifBlank { rtspUrlLow }
                                    else            rtspUrlLow.ifBlank  { rtspUrlHigh }
    val snapshotUrl: String get() = if (useHighRes) snapshotUrlHigh.ifBlank { snapshotUrlLow }
                                    else            snapshotUrlLow.ifBlank  { snapshotUrlHigh }

    /** Effective ordered list of enabled engine names in failover order. */
    val enabledEngines: List<String> get() {
        val order = engineOrder.ifEmpty { listOf("exo", "vlc", "snapshot") }
        return order.filter { !it.startsWith("-") }
    }

    /** True if the given engine name is present and NOT prefixed with "-". */
    fun isEngineEnabled(name: String) = enabledEngines.contains(name)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id",              id)
        put("name",            name)
        put("rtspUrlHigh",     rtspUrlHigh)
        put("snapshotUrlHigh", snapshotUrlHigh)
        put("rtspUrlLow",      rtspUrlLow)
        put("snapshotUrlLow",  snapshotUrlLow)
        put("useHighRes",      useHighRes)
        put("username",        username)
        put("password",        password)
        put("engineOrder",     JSONArray().also { a -> engineOrder.forEach { a.put(it) } })
        put("rtspTimeoutMs",   rtspTimeoutMs)
        put("snapshotInterval",snapshotIntervalSec)
    }

    companion object {
        fun fromJson(o: JSONObject): CameraConfig {
            // Parse engineOrder array
            val orderArr = o.optJSONArray("engineOrder")
            val order = if (orderArr != null)
                List(orderArr.length()) { orderArr.getString(it) }
            else {
                // Legacy single rtspEngine field → convert to stack
                when (o.optString("rtspEngine", "auto")) {
                    "vlc"      -> listOf("vlc", "snapshot")
                    "snapshot" -> listOf("snapshot")
                    else       -> emptyList()
                }
            }

            // Legacy single-URL migration
            val rtspHigh = o.optString("rtspUrlHigh", "")
                .ifBlank { o.optString("rtspUrl", "") }
            val snapHigh = o.optString("snapshotUrlHigh", "")
                .ifBlank { o.optString("snapshotUrl", "") }

            return CameraConfig(
                id                  = o.optString("id",   java.util.UUID.randomUUID().toString()),
                name                = o.optString("name", "Kamera"),
                rtspUrlHigh         = rtspHigh,
                snapshotUrlHigh     = snapHigh,
                rtspUrlLow          = o.optString("rtspUrlLow",      ""),
                snapshotUrlLow      = o.optString("snapshotUrlLow",  ""),
                useHighRes          = o.optBoolean("useHighRes",      true),
                username            = o.optString("username",         ""),
                password            = o.optString("password",         ""),
                engineOrder         = order,
                rtspTimeoutMs       = o.optLong  ("rtspTimeoutMs",    8_000L),
                snapshotIntervalSec = o.optInt   ("snapshotInterval", 5)
            )
        }
    }
}

/** Persists the ordered list of CameraConfig objects in SharedPreferences as JSON. */
object CameraConfigStore {

    private const val KEY_LIST     = "camera_configs_json"
    private const val KEY_ROTATION = "camera_rotation_sec"

    fun load(prefs: SharedPreferences): List<CameraConfig> {
        val json = prefs.getString(KEY_LIST, null) ?: return migrateLegacy(prefs)
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { CameraConfig.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { migrateLegacy(prefs) }
    }

    fun loadRotationSec(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_ROTATION, 0)

    fun save(prefs: SharedPreferences, list: List<CameraConfig>) {
        val arr = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun saveRotationSec(prefs: SharedPreferences, seconds: Int) {
        prefs.edit().putInt(KEY_ROTATION, seconds).apply()
    }

    private fun migrateLegacy(prefs: SharedPreferences): List<CameraConfig> {
        val rtspUrl = prefs.getString(PreferenceKeys.CAMERA_RTSP_URL, "") ?: ""
        val snapUrl = prefs.getString(PreferenceKeys.CAMERA_SNAPSHOT_URL, "") ?: ""
        if (rtspUrl.isBlank() && snapUrl.isBlank()) return emptyList()
        val engineStr = prefs.getString(PreferenceKeys.CAMERA_RTSP_ENGINE, "auto") ?: "auto"
        val order = when (engineStr) {
            "vlc"      -> listOf("vlc", "snapshot")
            "snapshot" -> listOf("snapshot")
            else       -> emptyList()
        }
        val cfg = CameraConfig(
            id                  = java.util.UUID.randomUUID().toString(),
            name                = "Kamera",
            rtspUrlHigh         = rtspUrl,
            snapshotUrlHigh     = snapUrl,
            rtspUrlLow          = rtspUrl,
            snapshotUrlLow      = snapUrl,
            useHighRes          = true,
            username            = prefs.getString(PreferenceKeys.CAMERA_USERNAME, "") ?: "",
            password            = prefs.getString(PreferenceKeys.CAMERA_PASSWORD, "") ?: "",
            engineOrder         = order,
            rtspTimeoutMs       = prefs.getString(PreferenceKeys.CAMERA_RTSP_TIMEOUT_MS, "8000")
                                      ?.toLongOrNull() ?: 8_000L,
            snapshotIntervalSec = prefs.getString(PreferenceKeys.CAMERA_SNAPSHOT_INTERVAL, "5")
                                      ?.toIntOrNull() ?: 5
        )
        save(prefs, listOf(cfg))
        return listOf(cfg)
    }
}
