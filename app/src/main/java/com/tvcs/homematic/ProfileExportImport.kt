package com.tvcs.homematic

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import org.json.JSONObject

/**
 * ProfileExportImport — full settings backup and restore.
 *
 * All user-configurable preferences are grouped into named categories.
 * On import the user can choose which categories to restore, which prevents
 * accidentally overwriting e.g. CCU connection data when restoring only the
 * appearance settings from another device.
 *
 * Export format (JSON, version 2):
 * {
 *   "_version": 2,
 *   "_app": "HomeMatic Launcher",
 *   "_exported": "2024-...",
 *   "ccu":        { "ccu_host": "...", ... },
 *   "appearance": { ... },
 *   "ha":         { ... },
 *   ...
 * }
 */
class ProfileExportImport(private val activity: AppCompatActivity) {

    companion object {
        private const val TAG     = "ProfileIO"
        private const val VERSION = 3
    }

    // ── Category definitions ──────────────────────────────────────────────────

    data class Category(val id: String, val labelRes: Int, val keys: List<String>)

    private val categories = listOf(
        Category("ccu", R.string.export_cat_ccu, listOf(
            PreferenceKeys.CCU_HOST, PreferenceKeys.CCU_PORT, PreferenceKeys.CCU_HTTPS,
            PreferenceKeys.CCU_API_PATH, PreferenceKeys.CCU_SID,
            PreferenceKeys.CCU_TRUST_SELF_SIGNED, PreferenceKeys.CONNECTION_TIMEOUT,
            PreferenceKeys.SYNC_FREQUENCY, PreferenceKeys.LAN_ONLY_MODE,
            PreferenceKeys.AUTO_RELOAD_ON_RECONNECT
        )),
        Category("profile", R.string.export_cat_profile, listOf(
            PreferenceKeys.PROFILE_WINDOW_DEVICE_TYPES,
            PreferenceKeys.PROFILE_THERMOSTAT_DEVICE_TYPES,
            PreferenceKeys.PROFILE_TEMP_DEVICE_TYPES,
            PreferenceKeys.PROFILE_HUMIDITY_DEVICE_TYPES,
            PreferenceKeys.PROFILE_SET_TEMP_FIELDS,
            PreferenceKeys.PROFILE_ACTUAL_TEMP_FIELDS,
            PreferenceKeys.PROFILE_HUMIDITY_FIELDS,
            PreferenceKeys.PROFILE_STATE_FIELDS,
            PreferenceKeys.PROFILE_LOWBAT_FIELDS,
            PreferenceKeys.PROFILE_SABOTAGE_FIELDS,
            PreferenceKeys.PROFILE_FAULT_FIELDS,
            PreferenceKeys.PROFILE_STATE_CLOSED,
            PreferenceKeys.PROFILE_STATE_TILTED,
            PreferenceKeys.PROFILE_STATE_OPEN,
            PreferenceKeys.OUTDOOR_ROOM_NAME,
            PreferenceKeys.MOLD_WARNING_RH, PreferenceKeys.MOLD_URGENT_RH,
            PreferenceKeys.MAX_WINDOW_INDICATORS
        )),
        Category("appearance", R.string.export_cat_appearance, listOf(
            PreferenceKeys.THEME_MODE, PreferenceKeys.APP_LANGUAGE,
            PreferenceKeys.FONT_ROOM_TITLE, PreferenceKeys.FONT_ROOM_DATA,
            PreferenceKeys.FONT_TRANSIT,
            PreferenceKeys.GRID_COLUMNS_PORTRAIT, PreferenceKeys.GRID_COLUMNS_LANDSCAPE,
            PreferenceKeys.COLOR_BG_HEADER, PreferenceKeys.COLOR_BG_STATUS,
            PreferenceKeys.COLOR_BG_CAMERA, PreferenceKeys.COLOR_BG_TRANSIT,
            PreferenceKeys.COLOR_BORDER_ROOM, PreferenceKeys.COLOR_TEXT_ROOM,
            PreferenceKeys.COLOR_TEXT_DIM,
            PreferenceKeys.SHOW_STATUS_BAR, PreferenceKeys.SHOW_NAV_BAR,
            PreferenceKeys.CONTENT_BELOW_STATUS_BAR, PreferenceKeys.CONTENT_BELOW_NAV_BAR,
            PreferenceKeys.KEEP_SCREEN_ON, PreferenceKeys.SHOW_RELOAD_POPUPS,
            PreferenceKeys.DISABLE_DISPLAY, PreferenceKeys.DISABLE_DISPLAY_PERIOD
        )),
        Category("camera", R.string.export_cat_camera, listOf(
            PreferenceKeys.CAMERA_ENABLED, PreferenceKeys.CAMERA_RTSP_URL,
            PreferenceKeys.CAMERA_SNAPSHOT_URL, PreferenceKeys.CAMERA_USERNAME,
            PreferenceKeys.CAMERA_PASSWORD, PreferenceKeys.CAMERA_VIEW_HEIGHT_DP,
            PreferenceKeys.CAMERA_RTSP_TIMEOUT_MS, PreferenceKeys.CAMERA_SNAPSHOT_INTERVAL,
            PreferenceKeys.CAMERA_OVERLAY_ALPHA, PreferenceKeys.CAMERA_SCALE_TYPE,
            PreferenceKeys.CAMERA_PANEL_PCT_PORTRAIT, PreferenceKeys.CAMERA_PANEL_PCT_LAND
        )),
        Category("motion", R.string.export_cat_motion, listOf(
            PreferenceKeys.MOTION_WEBCAM_ENABLED, PreferenceKeys.MOTION_WEBCAM_SENSITIVITY,
            PreferenceKeys.MOTION_LOCAL_ENABLED, PreferenceKeys.MOTION_LOCAL_SENSITIVITY,
            PreferenceKeys.MOTION_LOCAL_FACING, PreferenceKeys.MOTION_WAKE_TIMEOUT_SEC,
            PreferenceKeys.MOTION_LUMA_THRESHOLD, PreferenceKeys.MOTION_COOLDOWN_SEC,
            PreferenceKeys.MOTION_INTERVAL_SEC, PreferenceKeys.MOTION_ADAPTATION_RATE,
            PreferenceKeys.MOTION_ROI, PreferenceKeys.MOTION_TIME_START,
            PreferenceKeys.MOTION_TIME_END,
            PreferenceKeys.NIGHT_DIM_ENABLED, PreferenceKeys.NIGHT_DIM_START,
            PreferenceKeys.NIGHT_DIM_END, PreferenceKeys.NIGHT_DIM_BRIGHTNESS
        )),
        Category("ha", R.string.export_cat_ha, listOf(
            PreferenceKeys.HA_TILE_ENABLED, PreferenceKeys.HA_WS_URL,
            PreferenceKeys.HA_TOKEN, PreferenceKeys.HA_TILE_TITLE,
            PreferenceKeys.HA_ENTITIES, PreferenceKeys.HA_TILES_CONFIG
        )),
        Category("weather", R.string.export_cat_weather, listOf(
            PreferenceKeys.WEATHER_ENABLED, PreferenceKeys.WEATHER_CITY,
            PreferenceKeys.WEATHER_LAT, PreferenceKeys.WEATHER_LON,
            PreferenceKeys.WEATHER_DISPLAY_MODE, PreferenceKeys.WEATHER_REFRESH_MIN
        )),
        Category("transit", R.string.export_cat_transit, listOf(
            PreferenceKeys.TRANSIT_ENABLED, PreferenceKeys.TRANSIT_BASE_URL,
            PreferenceKeys.TRANSIT_FROM_ID, PreferenceKeys.TRANSIT_FROM_NAME,
            PreferenceKeys.TRANSIT_TO_ID, PreferenceKeys.TRANSIT_TO_NAME,
            PreferenceKeys.TRANSIT_ROW_COUNT, PreferenceKeys.TRANSIT_EXTRA_CONNECTIONS,
            PreferenceKeys.TRANSIT_REFRESH_INTERVAL, PreferenceKeys.TRANSIT_WATCHED_STATIONS
        )),
        Category("notifications", R.string.export_cat_notifications, listOf(
            PreferenceKeys.NOTIFY_WINDOW_OPEN, PreferenceKeys.NOTIFY_LOWBAT,
            PreferenceKeys.NOTIFY_SABOTAGE, PreferenceKeys.NOTIFY_FAULT,
            PreferenceKeys.NOTIFY_BACKGROUND
        ))
    )

    private var importCallback: ((success: Boolean, message: String) -> Unit)? = null

    private val importLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val cb = importCallback ?: return@registerForActivityResult
            importCallback = null
            try {
                val json = readJson(uri)
                showImportCategoryDialog(json, cb)
            } catch (e: Exception) {
                Log.e(TAG, "Import read failed", e)
                cb(false, activity.getString(R.string.profile_import_error, e.message ?: "?"))
            }
        }

    // ── Export ────────────────────────────────────────────────────────────────

    fun export() {
        showExportCategoryDialog()
    }

    private fun showExportCategoryDialog() {
        val labels   = categories.map { activity.getString(it.labelRes) }.toTypedArray()
        val checked  = BooleanArray(categories.size) { true }

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.export_select_categories))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(activity.getString(R.string.export_action)) { _, _ ->
                val selected = categories.filterIndexed { i, _ -> checked[i] }
                doExport(selected)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doExport(selected: List<Category>) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            val all   = prefs.all          // Map<String, Any?> — safe read of all types
            val root  = JSONObject()
            root.put("_version",  VERSION)
            root.put("_app",      "HomeMatic Launcher")
            root.put("_exported", java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date()))

            for (cat in selected) {
                val obj = JSONObject()
                for (key in cat.keys) {
                    val v = all[key] ?: continue   // skip keys not yet set
                    // Store as {"t":"<type>","v":"<value>"} so import can restore the right type
                    val entry = JSONObject()
                    when (v) {
                        is Boolean    -> { entry.put("t", "bool");   entry.put("v", v.toString()) }
                        is Int        -> { entry.put("t", "int");    entry.put("v", v.toString()) }
                        is Long       -> { entry.put("t", "long");   entry.put("v", v.toString()) }
                        is Float      -> { entry.put("t", "float");  entry.put("v", v.toString()) }
                        is Set<*>     -> { entry.put("t", "strset"); entry.put("v",
                            org.json.JSONArray().also { a -> v.forEach { s -> a.put(s.toString()) } }) }
                        else          -> { entry.put("t", "str");    entry.put("v", v.toString()) }
                    }
                    obj.put(key, entry)
                }
                root.put(cat.id, obj)
            }

            val fileName = "hm_settings_${System.currentTimeMillis()}.json"
            val file     = activity.cacheDir.resolve(fileName)
            file.writeText(root.toString(2))

            val uri = androidx.core.content.FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", file)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT,
                    activity.getString(R.string.profile_export_subject))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(shareIntent,
                activity.getString(R.string.profile_export_chooser_title)))
            Log.i(TAG, "Exported ${selected.size} categories to $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}", e)
            Toast.makeText(activity,
                activity.getString(R.string.profile_export_error, e.message ?: "?"),
                Toast.LENGTH_LONG).show()
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    fun import(callback: (success: Boolean, message: String) -> Unit) {
        importCallback = callback
        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    private fun readJson(uri: Uri): JSONObject {
        val raw = activity.contentResolver.openInputStream(uri)
            ?.bufferedReader()?.readText()
            ?: error("Could not read file")
        return JSONObject(raw)
    }

    private fun showImportCategoryDialog(
        json: JSONObject,
        callback: (Boolean, String) -> Unit
    ) {
        val version  = json.optInt("_version", 1)
        val exported = json.optString("_exported", "?")

        // v1: flat JSON (no category objects) — treat all categories as present
        // v2/v3: category objects at root — only show categories actually in the file
        val present = if (version == 1) categories
                      else categories.filter { json.has(it.id) }
        if (present.isEmpty()) {
            callback(false, activity.getString(R.string.profile_import_no_keys))
            return
        }

        val labels  = present.map { activity.getString(it.labelRes) }.toTypedArray()
        val checked = BooleanArray(present.size) { true }

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.import_select_categories, exported))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(activity.getString(R.string.import_action)) { _, _ ->
                val selected = present.filterIndexed { i, _ -> checked[i] }
                val count    = doImport(json, selected, version)
                callback(true, activity.getString(R.string.profile_import_ok_count, count))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doImport(
        json: JSONObject,
        selected: List<Category>,
        version: Int
    ): Int {
        val prefs   = PreferenceManager.getDefaultSharedPreferences(activity)
        val editor  = prefs.edit()
        var applied = 0

        for (cat in selected) {
            if (version == 1) {
                // Legacy v1: flat JSON — all values were stored as raw strings
                for (key in cat.keys) {
                    if (json.has(key)) { editor.putString(key, json.getString(key)); applied++ }
                }
            } else {
                val obj = json.optJSONObject(cat.id) ?: continue
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key   = keys.next()
                    val entry = obj.optJSONObject(key)
                    if (entry != null) {
                        // New typed format: {"t":"<type>","v":"<value>"}
                        val type = entry.optString("t", "str")
                        val raw  = entry.optString("v", "")
                        try {
                            when (type) {
                                "bool"   -> editor.putBoolean(key, raw.toBoolean())
                                "int"    -> editor.putInt(key, raw.toInt())
                                "long"   -> editor.putLong(key, raw.toLong())
                                "float"  -> editor.putFloat(key, raw.toFloat())
                                "strset" -> {
                                    val arr = entry.optJSONArray("v")
                                    if (arr != null) {
                                        val set = mutableSetOf<String>()
                                        for (i in 0 until arr.length()) set.add(arr.getString(i))
                                        editor.putStringSet(key, set)
                                    }
                                }
                                else     -> editor.putString(key, raw)
                            }
                            applied++
                        } catch (e: Exception) {
                            // Fallback: store as string if type conversion fails
                            Log.w(TAG, "Type restore failed for $key ($type): ${e.message}")
                            editor.putString(key, raw)
                            applied++
                        }
                    } else {
                        // Fallback for partially-typed files
                        editor.putString(key, obj.optString(key, ""))
                        applied++
                    }
                }
            }
        }
        editor.apply()
        Log.i(TAG, "Imported $applied keys from ${selected.size} categories (v$version)")
        return applied
    }
}
