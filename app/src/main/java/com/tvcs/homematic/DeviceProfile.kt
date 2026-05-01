package com.tvcs.homematic

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * DeviceProfile — runtime-configurable mapping of device types and datapoint field names.
 *
 * HomeMatic device_type strings and datapoint type names vary between firmware versions,
 * third-party sensors, and local naming conventions. This class replaces every hardcoded
 * string with user-editable preferences so the app works with any CCU setup.
 *
 * ── How it works ────────────────────────────────────────────────────────────
 * Each logical sensor category (window/door, temperature, humidity, thermostat) has:
 *   • A DEFAULT set of known device_type or datapoint-type strings (comma-separated)
 *   • A SharedPreference key that lets the user override/extend that list
 *
 * Matching is always case-insensitive prefix/exact match against the stored set.
 *
 * ── Usage ───────────────────────────────────────────────────────────────────
 *   val profile = DeviceProfile.get(context)
 *   if (dev.device_type in profile.windowDeviceTypes) { … }
 *   if (dp.type in profile.actualTempFields) { … }
 *
 * The singleton is re-read from prefs on every CCU load cycle so changes in
 * Settings take effect on the next sync without a restart.
 */
data class DeviceProfile(
    /** device_type values that represent window/door contact sensors */
    val windowDeviceTypes: Set<String>,
    /** device_type values that carry SET_TEMPERATURE (thermostat actuators) */
    val thermostatDeviceTypes: Set<String>,
    /** device_type values that carry ACTUAL_TEMPERATURE (climate sensors) */
    val tempDeviceTypes: Set<String>,
    /** device_type values that carry HUMIDITY */
    val humidityDeviceTypes: Set<String>,

    /** Datapoint type name(s) → SET_TEMPERATURE */
    val setTempFields: Set<String>,
    /** Datapoint type name(s) → ACTUAL / current temperature */
    val actualTempFields: Set<String>,
    /** Datapoint type name(s) → HUMIDITY */
    val humidityFields: Set<String>,
    /** Datapoint type name(s) → window/door STATE (open/closed/tilted) */
    val stateFields: Set<String>,
    /** Datapoint type name(s) → LOWBAT */
    val lowbatFields: Set<String>,
    /** Datapoint type name(s) → SABOTAGE */
    val sabotageFields: Set<String>,
    /** Datapoint type name(s) → FAULT_REPORTING */
    val faultFields: Set<String>,

    /** Raw string values that mean "closed/false" for STATE datapoints */
    val stateClosedValues: Set<String>,
    /** Raw string values that mean "tilted" for STATE datapoints */
    val stateTiltedValues: Set<String>,
    /** Raw string values that mean "open/true" for STATE datapoints */
    val stateOpenValues: Set<String>,
) {
    companion object {

        // ── Defaults ─────────────────────────────────────────────────────────
        // These reflect real HomeMatic/HomematicIP device_type and datapoint names.
        // Users can extend or replace them via Settings → Device-Profil.

        val DEFAULT_WINDOW_DEVICE_TYPES = setOf(
            "HM-Sec-RHS", "HM-Sec-SCo", "HmIP-SRH",
            "HmIP-SWDO", "HmIP-SWDO-I", "HmIP-SWDO-PL",
            "HM-Sec-Key", "HM-Sec-WDS", "HM-Sec-WDS-2"
        )
        val DEFAULT_THERMOSTAT_DEVICE_TYPES = setOf(
            "HM-CC-RT-DN", "HM-CC-RT-DN-BoM",
            "HmIP-eTRV", "HmIP-eTRV-2", "HmIP-eTRV-3",
            "HmIP-eTRV-B", "HmIP-eTRV-B2", "HmIP-eTRV-C",
            "HmIP-HEATING", "HM-TC-IT-WM-W-EU"
        )
        val DEFAULT_TEMP_DEVICE_TYPES = setOf(
            "HM-WDS10-TH-O", "HM-WDS40-TH-I", "HM-WDS100-C6-O",
            "HmIP-STH", "HmIP-STHD", "HmIP-WTH", "HmIP-WTH-2",
            "HmIP-STHO", "HmIP-STHO-A"
        )
        val DEFAULT_HUMIDITY_DEVICE_TYPES = setOf(
            "HM-WDS10-TH-O", "HM-WDS40-TH-I", "HM-WDS100-C6-O",
            "HmIP-STH", "HmIP-STHD", "HmIP-WTH", "HmIP-WTH-2",
            "HmIP-STHO", "HmIP-STHO-A"
        )

        val DEFAULT_SET_TEMP_FIELDS    = setOf("SET_TEMPERATURE", "SET_POINT_TEMPERATURE")
        val DEFAULT_ACTUAL_TEMP_FIELDS = setOf("ACTUAL_TEMPERATURE", "TEMPERATURE")
        val DEFAULT_HUMIDITY_FIELDS    = setOf("HUMIDITY")
        val DEFAULT_STATE_FIELDS       = setOf("STATE")
        val DEFAULT_LOWBAT_FIELDS      = setOf("LOWBAT", "LOW_BAT")
        val DEFAULT_SABOTAGE_FIELDS    = setOf("SABOTAGE", "ERROR_SABOTAGE")
        val DEFAULT_FAULT_FIELDS       = setOf("FAULT_REPORTING", "ERROR_CODE", "STICKY_UNREACH")

        val DEFAULT_STATE_CLOSED_VALUES = setOf("0", "false")
        val DEFAULT_STATE_TILTED_VALUES = setOf("1")
        val DEFAULT_STATE_OPEN_VALUES   = setOf("2", "true")

        /**
         * Loads the current profile from SharedPreferences.
         * Falls back to defaults for any key not yet set by the user.
         * Call once per load cycle — do not cache across reloads.
         */
        fun get(context: Context): DeviceProfile {
            val p = PreferenceManager.getDefaultSharedPreferences(context)

            /**
             * Reads a profile preference and returns the effective Set<String>.
             *
             * The chip UI stores the COMPLETE selected set (defaults + extras – deselected).
             * The legacy text field stored only ADDITIONS to the defaults.
             *
             * Distinguishing rule:
             *   • Pref empty          → return [default]  (first run, nothing stored)
             *   • Pref non-empty      → use exactly as stored (chip UI owns the full set)
             *
             * Both device_type groups and field name groups now use the same rule:
             * the stored value is always the complete intended set.
             */
            fun pref(key: String, default: Set<String>): Set<String> =
                p.getString(key, "")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.toSet()
                    ?: default

            // prefOverride is now identical to pref — kept as alias for readability
            fun prefOverride(key: String, default: Set<String>): Set<String> =
                pref(key, default)

            return DeviceProfile(
                windowDeviceTypes    = pref(PreferenceKeys.PROFILE_WINDOW_DEVICE_TYPES,    DEFAULT_WINDOW_DEVICE_TYPES),
                thermostatDeviceTypes= pref(PreferenceKeys.PROFILE_THERMOSTAT_DEVICE_TYPES,DEFAULT_THERMOSTAT_DEVICE_TYPES),
                tempDeviceTypes      = pref(PreferenceKeys.PROFILE_TEMP_DEVICE_TYPES,      DEFAULT_TEMP_DEVICE_TYPES),
                humidityDeviceTypes  = pref(PreferenceKeys.PROFILE_HUMIDITY_DEVICE_TYPES,  DEFAULT_HUMIDITY_DEVICE_TYPES),

                setTempFields    = prefOverride(PreferenceKeys.PROFILE_SET_TEMP_FIELDS,    DEFAULT_SET_TEMP_FIELDS),
                actualTempFields = prefOverride(PreferenceKeys.PROFILE_ACTUAL_TEMP_FIELDS, DEFAULT_ACTUAL_TEMP_FIELDS),
                humidityFields   = prefOverride(PreferenceKeys.PROFILE_HUMIDITY_FIELDS,    DEFAULT_HUMIDITY_FIELDS),
                stateFields      = prefOverride(PreferenceKeys.PROFILE_STATE_FIELDS,       DEFAULT_STATE_FIELDS),
                lowbatFields     = prefOverride(PreferenceKeys.PROFILE_LOWBAT_FIELDS,      DEFAULT_LOWBAT_FIELDS),
                sabotageFields   = prefOverride(PreferenceKeys.PROFILE_SABOTAGE_FIELDS,    DEFAULT_SABOTAGE_FIELDS),
                faultFields      = prefOverride(PreferenceKeys.PROFILE_FAULT_FIELDS,       DEFAULT_FAULT_FIELDS),

                stateClosedValues = prefOverride(PreferenceKeys.PROFILE_STATE_CLOSED, DEFAULT_STATE_CLOSED_VALUES),
                stateTiltedValues = prefOverride(PreferenceKeys.PROFILE_STATE_TILTED, DEFAULT_STATE_TILTED_VALUES),
                stateOpenValues   = prefOverride(PreferenceKeys.PROFILE_STATE_OPEN,   DEFAULT_STATE_OPEN_VALUES),
            )
        }

        /** Serialises a Set back to comma-separated string for display in Settings. */
        fun setToString(set: Set<String>): String = set.joinToString(", ")
    }
}
