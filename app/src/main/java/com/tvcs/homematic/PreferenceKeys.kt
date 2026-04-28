package com.tvcs.homematic

/**
 * Central source of truth for all SharedPreferences keys.
 * Using these constants everywhere prevents silent typo-bugs.
 */
object PreferenceKeys {

    // ── CCU Connection ────────────────────────────────────────────────────────
    const val CCU_HOST                  = "sync_ccu_name"
    const val CCU_PORT                  = "ccu_port"
    const val CCU_HTTPS                 = "ccu_https"
    const val CCU_API_PATH              = "ccu_api_path"
    const val CCU_SID                   = "ccu_sid"
    const val CCU_TRUST_SELF_SIGNED     = "ccu_trust_self_signed"
    const val CONNECTION_TIMEOUT        = "connection_timeout"

    // ── Sync ─────────────────────────────────────────────────────────────────
    const val SYNC_FREQUENCY            = "sync_frequency"
    const val AUTO_RELOAD_ON_RECONNECT  = "auto_reload_on_reconnect"

    // ── Display ───────────────────────────────────────────────────────────────
    const val KEEP_SCREEN_ON            = "keep_screen_on"
    const val SHOW_STATUS_BAR           = "show_status_bar"
    const val DISABLE_DISPLAY           = "disable_display_switch"
    const val DISABLE_DISPLAY_PERIOD    = "disable_display_period"
    const val THEME_MODE                = "theme_mode"
    const val APP_LANGUAGE              = "app_language"
    const val MAX_WINDOW_INDICATORS     = "max_window_indicators"

    // ── Notifications ─────────────────────────────────────────────────────────
    const val SHOW_RELOAD_POPUPS        = "notifications_show_reload_popups"
    const val NOTIFY_BACKGROUND         = "notify_background_enabled"
    const val NOTIFY_LOWBAT             = "notify_lowbat"
    const val NOTIFY_SABOTAGE           = "notify_sabotage"
    const val NOTIFY_FAULT              = "notify_fault"
    const val NOTIFY_WINDOW_OPEN        = "notify_window_open"

    // ── Mold warning thresholds ───────────────────────────────────────────────
    const val MOLD_WARNING_RH           = "mold_warning_rh"
    const val MOLD_URGENT_RH            = "mold_urgent_rh"

    // ── Developer / Test ──────────────────────────────────────────────────────
    const val TEST_MODE                 = "test_switch"
    const val OUTDOOR_ROOM_NAME         = "outdoor_room_name"

    // ── Device Profile: device_type sets ──────────────────────────────────────
    // Values: comma-separated strings appended to the built-in defaults.
    // Empty string → use defaults only.
    const val PROFILE_WINDOW_DEVICE_TYPES     = "profile_window_device_types"
    const val PROFILE_THERMOSTAT_DEVICE_TYPES = "profile_thermostat_device_types"
    const val PROFILE_TEMP_DEVICE_TYPES       = "profile_temp_device_types"
    const val PROFILE_HUMIDITY_DEVICE_TYPES   = "profile_humidity_device_types"

    // ── Device Profile: datapoint field name overrides ────────────────────────
    // Values: comma-separated strings that REPLACE the built-in defaults for that field.
    // Empty string → use built-in defaults.
    const val PROFILE_SET_TEMP_FIELDS    = "profile_set_temp_fields"
    const val PROFILE_ACTUAL_TEMP_FIELDS = "profile_actual_temp_fields"
    const val PROFILE_HUMIDITY_FIELDS    = "profile_humidity_fields"
    const val PROFILE_STATE_FIELDS       = "profile_state_fields"
    const val PROFILE_LOWBAT_FIELDS      = "profile_lowbat_fields"
    const val PROFILE_SABOTAGE_FIELDS    = "profile_sabotage_fields"
    const val PROFILE_FAULT_FIELDS       = "profile_fault_fields"

    // ── Device Profile: state value interpretation ────────────────────────────
    const val PROFILE_STATE_CLOSED = "profile_state_closed_values"
    const val PROFILE_STATE_TILTED = "profile_state_tilted_values"
    const val PROFILE_STATE_OPEN   = "profile_state_open_values"
}
