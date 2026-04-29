package com.tvcs.homematic

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

class DeviceProfileActivity : AppCompatActivity() {

    override fun attachBaseContext(base: Context) =
        super.attachBaseContext(LocaleHelper.wrap(base))

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var profileIO: ProfileExportImport

    private data class ProfileRow(val key: String, val inputView: EditText)
    private val rows = mutableListOf<ProfileRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_profile)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_device_profile)

        prefs     = PreferenceManager.getDefaultSharedPreferences(this)
        profileIO = ProfileExportImport(this)   // must be in onCreate

        buildRows()
        setupButtons()
    }

    override fun onOptionsItemSelected(item: MenuItem) =
        if (item.itemId == android.R.id.home) { finish(); true } else super.onOptionsItemSelected(item)

    private fun buildRows() {
        val definitions = listOf(
            Triple(PreferenceKeys.PROFILE_WINDOW_DEVICE_TYPES,
                R.string.profile_label_window_device_types,
                DeviceProfile.setToString(DeviceProfile.DEFAULT_WINDOW_DEVICE_TYPES)),
            Triple(PreferenceKeys.PROFILE_THERMOSTAT_DEVICE_TYPES,
                R.string.profile_label_thermostat_device_types,
                DeviceProfile.setToString(DeviceProfile.DEFAULT_THERMOSTAT_DEVICE_TYPES)),
            Triple(PreferenceKeys.PROFILE_TEMP_DEVICE_TYPES,
                R.string.profile_label_temp_device_types,
                DeviceProfile.setToString(DeviceProfile.DEFAULT_TEMP_DEVICE_TYPES)),
            Triple(PreferenceKeys.PROFILE_HUMIDITY_DEVICE_TYPES,
                R.string.profile_label_humidity_device_types,
                DeviceProfile.setToString(DeviceProfile.DEFAULT_HUMIDITY_DEVICE_TYPES)),
            Triple(PreferenceKeys.PROFILE_SET_TEMP_FIELDS,
                R.string.profile_label_set_temp_fields,
                DeviceProfile.setToString(DeviceProfile.DEFAULT_SET_TEMP_FIELDS)),
            Triple(PreferenceKeys.PROFILE_ACTUAL_TEMP_FIELDS,
                R.string.profile_label_actual_temp_fields,
                DeviceProfile.setToString(DeviceProfile.DEFAULT_ACTUAL_TEMP_FIELDS)),
            Triple(PreferenceKeys.PROFILE_HUMIDITY_FIELDS,
                R.string.profile_label_humidity_fields,
                DeviceProfile.setToString(DeviceProfile.DEFAULT_HUMIDITY_FIELDS)),
            Triple(PreferenceKeys.PROFILE_STATE_FIELDS,
                R.string.profile_label_state_fields,
                DeviceProfile.setToString(DeviceProfile.DEFAULT_STATE_FIELDS)),
            Triple(PreferenceKeys.PROFILE_LOWBAT_FIELDS,
                R.string.profile_label_lowbat_fields,
                DeviceProfile.setToString(DeviceProfile.DEFAULT_LOWBAT_FIELDS)),
            Triple(PreferenceKeys.PROFILE_SABOTAGE_FIELDS,
                R.string.profile_label_sabotage_fields,
                DeviceProfile.setToString(DeviceProfile.DEFAULT_SABOTAGE_FIELDS)),
            Triple(PreferenceKeys.PROFILE_FAULT_FIELDS,
                R.string.profile_label_fault_fields,
                DeviceProfile.setToString(DeviceProfile.DEFAULT_FAULT_FIELDS)),
            Triple(PreferenceKeys.PROFILE_STATE_CLOSED,
                R.string.profile_label_state_closed,
                DeviceProfile.setToString(DeviceProfile.DEFAULT_STATE_CLOSED_VALUES)),
            Triple(PreferenceKeys.PROFILE_STATE_TILTED,
                R.string.profile_label_state_tilted,
                DeviceProfile.setToString(DeviceProfile.DEFAULT_STATE_TILTED_VALUES)),
            Triple(PreferenceKeys.PROFILE_STATE_OPEN,
                R.string.profile_label_state_open,
                DeviceProfile.setToString(DeviceProfile.DEFAULT_STATE_OPEN_VALUES)),
        )

        val container = findViewById<LinearLayout>(R.id.profile_rows_container)

        for ((key, labelRes, defaultHint) in definitions) {
            val rowView = layoutInflater.inflate(R.layout.item_profile_row, container, false)
            rowView.findViewById<TextView>(R.id.profile_row_label).text = getString(labelRes)
            rowView.findViewById<TextView>(R.id.profile_row_hint).text  =
                getString(R.string.profile_defaults_prefix, defaultHint)

            val input = rowView.findViewById<EditText>(R.id.profile_row_input).apply {
                hint = getString(R.string.profile_input_hint)
                setText(prefs.getString(key, ""))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int)     = Unit
                    override fun afterTextChanged(s: Editable?) {
                        prefs.edit().putString(key, s?.toString()?.trim() ?: "").apply()
                    }
                })
            }
            rows.add(ProfileRow(key, input))
            container.addView(rowView)
        }
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btn_profile_reset).setOnClickListener {
            val editor = prefs.edit()
            rows.forEach { row -> editor.remove(row.key); row.inputView.setText("") }
            editor.apply()
            Toast.makeText(this, getString(R.string.profile_reset_done), Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_profile_reload).setOnClickListener {
            sendBroadcast(
                android.content.Intent(MainActivity.ACTION_RELOAD_DATA).setPackage(packageName)
            )
            finish()
        }

        // #15 — Export
        findViewById<Button>(R.id.btn_profile_export).setOnClickListener {
            profileIO.export()
        }

        // #15 — Import
        findViewById<Button>(R.id.btn_profile_import).setOnClickListener {
            profileIO.import { success, message ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                if (success) {
                    // Reload EditTexts with imported values
                    rows.forEach { row ->
                        row.inputView.setText(prefs.getString(row.key, ""))
                    }
                }
            }
        }
    }
}
