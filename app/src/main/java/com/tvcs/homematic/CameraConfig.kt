package com.tvcs.homematic

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Immutable configuration for one camera.
 * All fields mirror the existing single-camera preference keys so that
 * CameraViewController can be constructed without modification.
 */
data class CameraConfig(
    val id:              String,   // stable UUID
    val name:           String,   // display name, e.g. "Eingang"
    val rtspUrl:        String,
    val snapshotUrl:    String,
    val username:       String,
    val password:       String,
    val rtspEngine:     String = "auto",   // "auto" | "exo" | "vlc" | "snapshot"
    val rtspTimeoutMs:  Long   = 8_000L,
    val snapshotIntervalSec: Int = 5
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id",               id)
        put("name",             name)
        put("rtspUrl",          rtspUrl)
        put("snapshotUrl",      snapshotUrl)
        put("username",         username)
        put("password",         password)
        put("rtspEngine",       rtspEngine)
        put("rtspTimeoutMs",    rtspTimeoutMs)
        put("snapshotInterval", snapshotIntervalSec)
    }

    companion object {
        fun fromJson(o: JSONObject) = CameraConfig(
            id                   = o.optString("id",   java.util.UUID.randomUUID().toString()),
            name                 = o.optString("name", "Kamera"),
            rtspUrl              = o.optString("rtspUrl",       ""),
            snapshotUrl          = o.optString("snapshotUrl",   ""),
            username             = o.optString("username",      ""),
            password             = o.optString("password",      ""),
            rtspEngine           = o.optString("rtspEngine",    "auto"),
            rtspTimeoutMs        = o.optLong  ("rtspTimeoutMs", 8_000L),
            snapshotIntervalSec  = o.optInt   ("snapshotInterval", 5)
        )
    }
}

/** Persists the ordered list of CameraConfig objects in SharedPreferences as JSON. */
object CameraConfigStore {

    private const val KEY_LIST     = "camera_configs_json"
    private const val KEY_ROTATION = "camera_rotation_sec"   // 0 = disabled

    // ── Read ──────────────────────────────────────────────────────────────────

    fun load(prefs: SharedPreferences): List<CameraConfig> {
        val json = prefs.getString(KEY_LIST, null) ?: return migrateLegacy(prefs)
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { CameraConfig.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { migrateLegacy(prefs) }
    }

    fun loadRotationSec(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_ROTATION, 0)

    // ── Write ─────────────────────────────────────────────────────────────────

    fun save(prefs: SharedPreferences, list: List<CameraConfig>) {
        val arr = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun saveRotationSec(prefs: SharedPreferences, seconds: Int) {
        prefs.edit().putInt(KEY_ROTATION, seconds).apply()
    }

    // ── Migration: single-cam legacy prefs → list with one entry ─────────────

    private fun migrateLegacy(prefs: SharedPreferences): List<CameraConfig> {
        val rtspUrl = prefs.getString(PreferenceKeys.CAMERA_RTSP_URL, "") ?: ""
        val snapUrl = prefs.getString(PreferenceKeys.CAMERA_SNAPSHOT_URL, "") ?: ""
        if (rtspUrl.isBlank() && snapUrl.isBlank()) return emptyList()
        val cfg = CameraConfig(
            id                  = java.util.UUID.randomUUID().toString(),
            name                = "Kamera",
            rtspUrl             = rtspUrl,
            snapshotUrl         = snapUrl,
            username            = prefs.getString(PreferenceKeys.CAMERA_USERNAME, "") ?: "",
            password            = prefs.getString(PreferenceKeys.CAMERA_PASSWORD, "") ?: "",
            rtspEngine          = prefs.getString(PreferenceKeys.CAMERA_RTSP_ENGINE, "auto") ?: "auto",
            rtspTimeoutMs       = prefs.getString(PreferenceKeys.CAMERA_RTSP_TIMEOUT_MS, "8000")
                                      ?.toLongOrNull() ?: 8_000L,
            snapshotIntervalSec = prefs.getString(PreferenceKeys.CAMERA_SNAPSHOT_INTERVAL, "5")
                                      ?.toIntOrNull() ?: 5
        )
        save(prefs, listOf(cfg))
        return listOf(cfg)
    }
}
