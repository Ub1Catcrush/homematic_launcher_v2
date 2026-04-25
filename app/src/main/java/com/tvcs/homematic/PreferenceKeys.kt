package com.tvcs.homematic

/**
 * Central source of truth for all SharedPreferences keys.
 * Using these constants everywhere prevents silent typo-bugs.
 */
object PreferenceKeys {
    const val CCU_HOST            = "sync_ccu_name"
    const val CCU_PORT            = "ccu_port"
    const val CCU_HTTPS           = "ccu_https"
    const val CCU_API_PATH        = "ccu_api_path"
    const val CONNECTION_TIMEOUT  = "connection_timeout"
    const val SYNC_FREQUENCY      = "sync_frequency"
    const val AUTO_RELOAD_ON_RECONNECT = "auto_reload_on_reconnect"
    const val KEEP_SCREEN_ON      = "keep_screen_on"
    const val SHOW_STATUS_BAR     = "show_status_bar"
    const val DISABLE_DISPLAY     = "disable_display_switch"
    const val DISABLE_DISPLAY_PERIOD = "disable_display_period"
    const val SHOW_RELOAD_POPUPS  = "notifications_show_reload_popups"
    const val TEST_MODE           = "test_switch"
    const val OUTDOOR_ROOM_NAME   = "outdoor_room_name"
    const val CCU_SID             = "ccu_sid"
    const val THEME_MODE          = "theme_mode"
}
