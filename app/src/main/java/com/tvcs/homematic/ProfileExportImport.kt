package com.tvcs.homematic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import org.json.JSONObject

/**
 * ProfileExportImport — JSON backup and restore for DeviceProfile preferences.
 *
 * Export: serialises all PROFILE_* keys from SharedPreferences into a JSON object
 *         and shares it via the system share sheet (any app — Files, Drive, Email…).
 *
 * Import: reads a previously exported JSON file via the system file picker,
 *         validates the keys, and writes them back to SharedPreferences.
 *         Unknown keys in the file are silently ignored (forward-compatible).
 *
 * ── Usage in an Activity ────────────────────────────────────────────────────
 *   private lateinit var profileIO: ProfileExportImport
 *
 *   override fun onCreate(…) {
 *       profileIO = ProfileExportImport(this)           // registers launchers
 *   }
 *
 *   // Export:
 *   profileIO.export()
 *
 *   // Import:
 *   profileIO.import { success, message -> showToast(message) }
 */
class ProfileExportImport(private val activity: AppCompatActivity) {

    private val TAG = "ProfileIO"

    // All preference keys that belong to the device profile
    private val profileKeys = listOf(
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
    )

    private var importCallback: ((success: Boolean, message: String) -> Unit)? = null

    private val importLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) return@registerForActivityResult
            val cb = importCallback ?: return@registerForActivityResult
            importCallback = null
            val result = runCatching { readAndApply(uri) }
            if (result.isSuccess) {
                cb(true, activity.getString(R.string.profile_import_ok))
            } else {
                Log.e(TAG, "Import failed", result.exceptionOrNull())
                cb(false, activity.getString(R.string.profile_import_error,
                    result.exceptionOrNull()?.message ?: "?"))
            }
        }

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Serialises all profile preferences to JSON and opens the system share sheet.
     * The file is written to the app's cache dir so no WRITE_EXTERNAL_STORAGE needed.
     */
    fun export() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val json  = JSONObject()
        json.put("_version", 1)
        json.put("_app",     "HomeMatic Launcher")
        for (key in profileKeys) {
            json.put(key, prefs.getString(key, "") ?: "")
        }

        val fileName = "hm_profile_${System.currentTimeMillis()}.json"
        val file     = activity.cacheDir.resolve(fileName)
        file.writeText(json.toString(2))   // pretty-print

        val uri = androidx.core.content.FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            file
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.profile_export_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(shareIntent,
            activity.getString(R.string.profile_export_chooser_title)))
        Log.i(TAG, "Exported profile to $fileName (${file.length()} bytes)")
    }

    // ── Import ────────────────────────────────────────────────────────────────

    /**
     * Opens the system file picker filtered to JSON files.
     * [callback] is invoked on the main thread with (success, message).
     */
    fun import(callback: (success: Boolean, message: String) -> Unit) {
        importCallback = callback
        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    private fun readAndApply(uri: Uri) {
        val raw  = activity.contentResolver.openInputStream(uri)
            ?.bufferedReader()?.readText()
            ?: error("Could not read file")

        val json    = JSONObject(raw)
        val version = json.optInt("_version", 1)
        Log.d(TAG, "Importing profile v$version")

        val prefs  = PreferenceManager.getDefaultSharedPreferences(activity)
        val editor = prefs.edit()
        var applied = 0

        for (key in profileKeys) {
            if (json.has(key)) {
                editor.putString(key, json.getString(key))
                applied++
            }
        }
        editor.apply()
        Log.i(TAG, "Applied $applied / ${profileKeys.size} profile keys from import")

        if (applied == 0) error(activity.getString(R.string.profile_import_no_keys))
    }
}
