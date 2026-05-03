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
    const val CONTENT_BELOW_STATUS_BAR  = "content_below_status_bar"
    const val SHOW_NAV_BAR              = "show_nav_bar"
    const val CONTENT_BELOW_NAV_BAR     = "content_below_nav_bar"

    // Panel heights as % of usable screen height (1–50), portrait and landscape independent
    const val CAMERA_PANEL_PCT_PORTRAIT  = "camera_panel_pct_portrait"   // default 20
    const val TRANSIT_PANEL_PCT_PORTRAIT = "transit_panel_pct_portrait"  // default 20
    const val CAMERA_PANEL_PCT_LAND      = "camera_panel_pct_land"       // default 20
    const val TRANSIT_PANEL_PCT_LAND     = "transit_panel_pct_land"      // default 20

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
    // ── Camera live view ──────────────────────────────────────────────────────
    const val CAMERA_ENABLED            = "camera_enabled"
    const val CAMERA_RTSP_URL           = "camera_rtsp_url"
    const val CAMERA_SNAPSHOT_URL       = "camera_snapshot_url"
    const val CAMERA_USERNAME           = "camera_username"
    const val CAMERA_PASSWORD           = "camera_password"
    const val CAMERA_VIEW_HEIGHT_DP     = "camera_view_height_dp"
    /** How long (ms) to wait for RTSP before falling back to MJPEG snapshots. */
    const val CAMERA_RTSP_TIMEOUT_MS    = "camera_rtsp_timeout_ms"
    /** Snapshot polling interval in seconds when in MJPEG fallback mode. */
    const val CAMERA_SNAPSHOT_INTERVAL  = "camera_snapshot_interval"

    // ── DB Transit ───────────────────────────────────────────────────────────
    const val TRANSIT_ENABLED   = "transit_enabled"
    const val TRANSIT_FROM_ID   = "transit_from_id"
    const val TRANSIT_FROM_NAME = "transit_from_name"
    const val TRANSIT_TO_ID     = "transit_to_id"
    const val TRANSIT_TO_NAME   = "transit_to_name"

    /**
     * Comma-separated list of station name substrings (case-insensitive) that
     * should be shown as transfer info in Spalte 4.
     * Example: "Hamburg Hbf, Bremen Hbf"
     */
    const val TRANSIT_BASE_URL           = "transit_base_url"
    const val TRANSIT_WATCHED_STATIONS   = "transit_watched_stations"

    /**
     * Additional connections beyond the primary one, stored as a JSON array string.
     * Each element: {"fromId":"…","fromName":"…","toId":"…","toName":"…"}
     * Up to 4 extra connections are supported.
     */
    const val TRANSIT_EXTRA_CONNECTIONS  = "transit_extra_connections"

    // ── Launcher switch ───────────────────────────────────────────────────────
    /** Package name of the alternative launcher to launch when tapping the FAB. */
    const val ALT_LAUNCHER_PACKAGE      = "alt_launcher_package"
    const val ALT_LAUNCHER_ENABLED      = "alt_launcher_enabled"

    // ── DB Transit refresh interval ───────────────────────────────────────────
    /** Refresh interval in seconds (default 120). */
    const val TRANSIT_REFRESH_INTERVAL   = "transit_refresh_interval"

    // ── LAN / VPN only mode ───────────────────────────────────────────────────
    /** When true, only sync if at least one configured endpoint is reachable. */
    const val LAN_ONLY_MODE              = "lan_only_mode"

    // ── Colours – base keys (append "_dark" for dark-theme variant) ───────────
    const val COLOR_BG_STATUS            = "color_bg_status"
    const val COLOR_BG_HEADER            = "color_bg_header"
    const val COLOR_BG_TRANSIT           = "color_bg_transit"
    const val COLOR_BG_CAMERA            = "color_bg_camera"
    const val COLOR_BORDER_ROOM          = "color_border_room"
    const val COLOR_TEXT_ROOM            = "color_text_room"
    const val COLOR_TEXT_DIM             = "color_text_dim"

    // ── Font sizes ────────────────────────────────────────────────────────────
    /** Room tile title font size in sp */
    const val FONT_ROOM_TITLE            = "font_room_title"
    /** Room tile data row font size in sp */
    const val FONT_ROOM_DATA             = "font_room_data"
    /** Transit row font size in sp */
    const val FONT_TRANSIT               = "font_transit"

    // ── Weather ───────────────────────────────────────────────────────────────
    const val WEATHER_ENABLED            = "weather_enabled"
    /** "room" or "overlay" */
    const val WEATHER_DISPLAY_MODE       = "weather_display_mode"
    const val WEATHER_CITY               = "weather_city"
    const val WEATHER_LAT                = "weather_lat"
    const val WEATHER_LON                = "weather_lon"
    /** Refresh interval in minutes (default 30) */
    const val WEATHER_REFRESH_MIN        = "weather_refresh_min"
}