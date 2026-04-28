package com.tvcs.homematic

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

/**
 * DeviceProfileActivity — full-screen editor for device type and field name mappings.
 *
 * Each row shows:
 *   • A label describing what the field does
 *   • The built-in defaults (read-only hint text)
 *   • An EditText for the user's additions / overrides
 *
 * For device_type sets (window, thermostat, temp, humidity) the user's values are
 * MERGED with the defaults (union). For datapoint field name sets they OVERRIDE
 * the defaults when non-empty. This matches DeviceProfile.get() behaviour.
 *
 * A "Defaults wiederherstellen" button clears all user overrides.
 */
class DeviceProfileActivity : AppCompatActivity() {

    override fun attachBaseContext(base: Context) =
        super.attachBaseContext(LocaleHelper.wrap(base))

    private lateinit var prefs: android.content.SharedPreferences

    // EditTexts — indexed by their PreferenceKey constant
    private data class ProfileRow(
        val key: String,
        val inputView: EditText
    )
    private val rows = mutableListOf<ProfileRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_profile)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_device_profile)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        buildRows()
        setupButtons()
    }

    override fun onOptionsItemSelected(item: MenuItem) =
        if (item.itemId == android.R.id.home) { finish(); true } else super.onOptionsItemSelected(item)

    // ── Build rows from layout ────────────────────────────────────────────────

    private fun buildRows() {
        // Order matches the layout sections in activity_device_profile.xml
        val definitions = listOf(
            // ── Device type sets ─────────────────────────────────────────────
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

            // ── Datapoint field names ─────────────────────────────────────────
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

            // ── State value interpretation ────────────────────────────────────
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

        val container = findViewById<android.widget.LinearLayout>(R.id.profile_rows_container)
        val inflater  = layoutInflater

        for ((key, labelRes, defaultHint) in definitions) {
            val rowView = inflater.inflate(R.layout.item_profile_row, container, false)

            rowView.findViewById<TextView>(R.id.profile_row_label).text = getString(labelRes)
            rowView.findViewById<TextView>(R.id.profile_row_hint).text  =
                getString(R.string.profile_defaults_prefix, defaultHint)

            val input = rowView.findViewById<EditText>(R.id.profile_row_input).apply {
                hint = getString(R.string.profile_input_hint)
                setText(prefs.getString(key, ""))
                // Auto-save on every keystroke
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
            rows.forEach { row ->
                editor.remove(row.key)
                row.inputView.setText("")
            }
            editor.apply()
        }

        // "Jetzt neu laden" — triggers a full CCU reload so the new profile takes effect
        findViewById<Button>(R.id.btn_profile_reload).setOnClickListener {
            sendBroadcast(
                android.content.Intent(MainActivity.ACTION_RELOAD_DATA)
                    .setPackage(packageName)
            )
            finish()
        }
    }
}
