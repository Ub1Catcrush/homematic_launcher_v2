package com.tvcs.homematic

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * DeviceProfileActivity — Multi-select chip editor for device types and field names.
 *
 * Each profile row now shows:
 *   • A label
 *   • A ChipGroup with one Chip per known value — defaults pre-selected, tap to toggle
 *   • An EditText for custom/unknown values not in the chip list (comma-separated)
 *
 * The effective set written to SharedPreferences is:
 *   selectedChips ∪ customInput
 *
 * This means:
 *   - All default values are available as chips and pre-selected
 *   - User can deselect defaults they don't want
 *   - User can add completely unknown device_types via the text field
 *   - The DeviceProfile.get() pref/prefOverride logic reads back the combined set
 *
 * For device_type groups (window, thermostat, temp, humidity):
 *   stored = selected chips + custom text (merged with defaults in DeviceProfile.get())
 *   → We store the FULL selection (not just additions) so deselecting a default works.
 *
 * For field name groups (SET_TEMPERATURE etc.) which use prefOverride:
 *   stored = selected chips + custom text (replaces defaults in DeviceProfile.get())
 *   → Same storage format, consistent behaviour.
 */
class DeviceProfileActivity : AppCompatActivity() {

    override fun attachBaseContext(base: Context) =
        super.attachBaseContext(LocaleHelper.wrap(base))

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var profileIO: ProfileExportImport

    /**
     * Each profile row tracks both its chip group and custom input,
     * so we can combine them on save and reload them on import.
     */
    private data class ProfileRow(
        val key: String,
        val chipGroup: ChipGroup,
        val customInput: EditText,
        /** The full candidate set shown as chips (defaults + any extras seen in CCU) */
        val chipValues: List<String>
    )
    private val rows = mutableListOf<ProfileRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_profile)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_device_profile)

        prefs     = PreferenceManager.getDefaultSharedPreferences(this)
        profileIO = ProfileExportImport(this)

        buildRows()
        setupButtons()
    }

    override fun onOptionsItemSelected(item: MenuItem) =
        if (item.itemId == android.R.id.home) { finish(); true }
        else super.onOptionsItemSelected(item)

    // ── Row definitions ───────────────────────────────────────────────────────

    private data class RowDef(
        val key: String,
        val labelRes: Int,
        /** All values that can appear as chips (built-in defaults) */
        val knownValues: Set<String>,
        /** Hint shown below the chips for the free-text field */
        val customHintRes: Int = R.string.profile_custom_input_hint
    )

    private val rowDefs: List<RowDef> = listOf(
        // ── device_type groups ──────────────────────────────────────────────
        RowDef(PreferenceKeys.PROFILE_WINDOW_DEVICE_TYPES,
            R.string.profile_label_window_device_types,
            DeviceProfile.DEFAULT_WINDOW_DEVICE_TYPES),
        RowDef(PreferenceKeys.PROFILE_THERMOSTAT_DEVICE_TYPES,
            R.string.profile_label_thermostat_device_types,
            DeviceProfile.DEFAULT_THERMOSTAT_DEVICE_TYPES),
        RowDef(PreferenceKeys.PROFILE_TEMP_DEVICE_TYPES,
            R.string.profile_label_temp_device_types,
            DeviceProfile.DEFAULT_TEMP_DEVICE_TYPES),
        RowDef(PreferenceKeys.PROFILE_HUMIDITY_DEVICE_TYPES,
            R.string.profile_label_humidity_device_types,
            DeviceProfile.DEFAULT_HUMIDITY_DEVICE_TYPES),

        // ── datapoint field names ────────────────────────────────────────────
        RowDef(PreferenceKeys.PROFILE_SET_TEMP_FIELDS,
            R.string.profile_label_set_temp_fields,
            DeviceProfile.DEFAULT_SET_TEMP_FIELDS),
        RowDef(PreferenceKeys.PROFILE_ACTUAL_TEMP_FIELDS,
            R.string.profile_label_actual_temp_fields,
            DeviceProfile.DEFAULT_ACTUAL_TEMP_FIELDS),
        RowDef(PreferenceKeys.PROFILE_HUMIDITY_FIELDS,
            R.string.profile_label_humidity_fields,
            DeviceProfile.DEFAULT_HUMIDITY_FIELDS),
        RowDef(PreferenceKeys.PROFILE_STATE_FIELDS,
            R.string.profile_label_state_fields,
            DeviceProfile.DEFAULT_STATE_FIELDS),
        RowDef(PreferenceKeys.PROFILE_LOWBAT_FIELDS,
            R.string.profile_label_lowbat_fields,
            DeviceProfile.DEFAULT_LOWBAT_FIELDS),
        RowDef(PreferenceKeys.PROFILE_SABOTAGE_FIELDS,
            R.string.profile_label_sabotage_fields,
            DeviceProfile.DEFAULT_SABOTAGE_FIELDS),
        RowDef(PreferenceKeys.PROFILE_FAULT_FIELDS,
            R.string.profile_label_fault_fields,
            DeviceProfile.DEFAULT_FAULT_FIELDS),

        // ── state value interpretation ───────────────────────────────────────
        RowDef(PreferenceKeys.PROFILE_STATE_CLOSED,
            R.string.profile_label_state_closed,
            DeviceProfile.DEFAULT_STATE_CLOSED_VALUES),
        RowDef(PreferenceKeys.PROFILE_STATE_TILTED,
            R.string.profile_label_state_tilted,
            DeviceProfile.DEFAULT_STATE_TILTED_VALUES),
        RowDef(PreferenceKeys.PROFILE_STATE_OPEN,
            R.string.profile_label_state_open,
            DeviceProfile.DEFAULT_STATE_OPEN_VALUES),
    )

    // ── Build UI ──────────────────────────────────────────────────────────────

    private fun buildRows() {
        val container = findViewById<LinearLayout>(R.id.profile_rows_container)
        val inflater  = LayoutInflater.from(this)

        // Also include any extra values seen from the CCU (from DiagnosticsActivity state)
        // so the user can select newly-discovered device_types without typing them manually.
        val seenDevTypes = HomeMatic.state?.let { state ->
            state.deviceList.devices.map { it.device_type }.filter { it.isNotBlank() }.toSet() +
            state.stateList.devices.map  { it.device_type }.filter { it.isNotBlank() }.toSet()
        } ?: emptySet()

        val seenDpTypes = HomeMatic.state?.let { state ->
            state.stateList.devices.flatMap { dev ->
                dev.channels.flatMap { chan -> chan.datapoints.map { it.type } }
            }.filter { it.isNotBlank() }.toSet()
        } ?: emptySet()

        for (def in rowDefs) {
            val rowView = inflater.inflate(R.layout.item_profile_row_chips, container, false)

            rowView.findViewById<TextView>(R.id.profile_row_label).text = getString(def.labelRes)

            // Combine built-in defaults with CCU-discovered values for the chip list
            val isDeviceTypeRow = def.key in listOf(
                PreferenceKeys.PROFILE_WINDOW_DEVICE_TYPES,
                PreferenceKeys.PROFILE_THERMOSTAT_DEVICE_TYPES,
                PreferenceKeys.PROFILE_TEMP_DEVICE_TYPES,
                PreferenceKeys.PROFILE_HUMIDITY_DEVICE_TYPES,
            )
            val chipCandidates = if (isDeviceTypeRow)
                (def.knownValues + seenDevTypes).sorted()
            else
                (def.knownValues + seenDpTypes).sorted()

            // Read stored selection — if nothing stored yet, pre-select all defaults
            val storedRaw    = prefs.getString(def.key, "") ?: ""
            val storedValues = if (storedRaw.isBlank()) def.knownValues
                               else parseValues(storedRaw)

            val chipGroup = rowView.findViewById<ChipGroup>(R.id.profile_chip_group)
            chipGroup.isSingleSelection = false

            for (value in chipCandidates) {
                val chip = Chip(this).apply {
                    text       = value
                    isCheckable = true
                    isChecked   = value in storedValues
                    // Chips from CCU but not in defaults are visually distinguished
                    if (value !in def.knownValues) alpha = 0.7f
                    setOnCheckedChangeListener { _, _ -> saveRow(def.key, chipGroup, rowView) }
                }
                chipGroup.addView(chip)
            }

            // Custom free-text field for values not shown as chips
            val customChips  = storedValues - chipCandidates.toSet()
            val customInput  = rowView.findViewById<EditText>(R.id.profile_custom_input).apply {
                hint = getString(R.string.profile_custom_input_hint)
                setText(customChips.joinToString(", "))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int)     = Unit
                    override fun afterTextChanged(s: Editable?) { saveRow(def.key, chipGroup, rowView) }
                })
            }

            rows.add(ProfileRow(def.key, chipGroup, customInput, chipCandidates))
            container.addView(rowView)
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    /** Combines checked chips + custom text into a comma-separated pref value. */
    private fun saveRow(key: String, chipGroup: ChipGroup, rowView: View) {
        val checkedChips = (0 until chipGroup.childCount)
            .mapNotNull { chipGroup.getChildAt(it) as? Chip }
            .filter { it.isChecked }
            .map { it.text.toString() }

        val customField = rowView.findViewById<EditText>(R.id.profile_custom_input)
        val customValues = parseValues(customField.text?.toString() ?: "")

        val combined = (checkedChips + customValues).distinct().joinToString(",")
        prefs.edit().putString(key, combined).apply()
    }

    private fun parseValues(raw: String): Set<String> =
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    // ── Buttons ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        // Reset: clear pref, re-check all default chips, clear custom field
        findViewById<Button>(R.id.btn_profile_reset).setOnClickListener {
            val editor = prefs.edit()
            rows.forEach { row ->
                editor.remove(row.key)
                // Re-check only the default chips for this row
                val def = rowDefs.first { it.key == row.key }
                (0 until row.chipGroup.childCount)
                    .mapNotNull { row.chipGroup.getChildAt(it) as? Chip }
                    .forEach { chip -> chip.isChecked = chip.text.toString() in def.knownValues }
                row.customInput.setText("")
            }
            editor.apply()
            Toast.makeText(this, getString(R.string.profile_reset_done), Toast.LENGTH_SHORT).show()
        }

        // Reload CCU + close
        findViewById<Button>(R.id.btn_profile_reload).setOnClickListener {
            sendBroadcast(android.content.Intent(MainActivity.ACTION_RELOAD_DATA).setPackage(packageName))
            finish()
        }

        // Export
        findViewById<Button>(R.id.btn_profile_export).setOnClickListener { profileIO.export() }

        // Import — reload chip states from newly-written prefs
        findViewById<Button>(R.id.btn_profile_import).setOnClickListener {
            profileIO.import { success, message ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                if (success) reloadChipsFromPrefs()
            }
        }
    }

    /** After an import, update every chip's checked state and custom field from prefs. */
    private fun reloadChipsFromPrefs() {
        rows.forEach { row ->
            val stored = parseValues(prefs.getString(row.key, "") ?: "")
            val def    = rowDefs.first { it.key == row.key }
            // Update chips
            (0 until row.chipGroup.childCount)
                .mapNotNull { row.chipGroup.getChildAt(it) as? Chip }
                .forEach { chip -> chip.isChecked = chip.text.toString() in stored }
            // Update custom field — values not represented by chips
            val customValues = stored - row.chipValues.toSet()
            row.customInput.setText(customValues.joinToString(", "))
        }
    }
}
