package com.tvcs.homematic

import android.content.Context
import android.graphics.Color
import androidx.preference.PreferenceManager

/**
 * Central helper for app-wide theming prefs.
 *
 * All colours are stored as hex strings ("#RRGGBB" or "#AARRGGBB").
 * Two sets exist: dark-mode and light-mode colours.
 * The active set is chosen automatically based on the current night-mode state.
 *
 * Font sizes are a single value (sp, float) regardless of theme.
 */
object AppThemeHelper {

    // ── Default colours – dark theme ──────────────────────────────────────────
    private const val DEF_DARK_BG_STATUS   = "#AA000000"   // status bar background
    private const val DEF_DARK_BG_HEADER   = "#FF1A237E"   // toolbar / header
    private const val DEF_DARK_BG_TRANSIT  = "#CC001122"   // transit panel background
    private const val DEF_DARK_BG_CAMERA   = "#CC000000"   // camera panel background
    private const val DEF_DARK_BORDER      = "#FF444444"   // room tile border
    private const val DEF_DARK_TEXT_ROOM   = "#FFFFFFFF"   // room text / values
    private const val DEF_DARK_TEXT_DIM    = "#BBFFFFFF"   // dimmed secondary text

    // ── Default colours – light theme ────────────────────────────────────────
    private const val DEF_LIGHT_BG_STATUS  = "#DDFFFFFF"
    private const val DEF_LIGHT_BG_HEADER  = "#FF3F51B5"
    private const val DEF_LIGHT_BG_TRANSIT = "#EEF5F5FF"
    private const val DEF_LIGHT_BG_CAMERA  = "#EE222222"
    private const val DEF_LIGHT_BORDER     = "#FFBBBBBB"
    private const val DEF_LIGHT_TEXT_ROOM  = "#FF111111"
    private const val DEF_LIGHT_TEXT_DIM   = "#AA111111"

    // ── Default font sizes ────────────────────────────────────────────────────
    private const val DEF_FONT_ROOM_TITLE  = 12f   // sp
    private const val DEF_FONT_ROOM_DATA   = 11.5f // sp  (portrait)
    private const val DEF_FONT_TRANSIT     = 11f   // sp

    // ─────────────────────────────────────────────────────────────────────────

    private fun isDark(context: Context): Boolean {
        val mode = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun prefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context)

    private fun color(context: Context, key: String, default: String): Int =
        try { Color.parseColor(prefs(context).getString(key, default) ?: default) }
        catch (_: Exception) { Color.parseColor(default) }

    // ── Theme keys ────────────────────────────────────────────────────────────
    // Light keys are the base; dark keys have "_dark" suffix.
    private fun key(base: String, dark: Boolean) = if (dark) "${base}_dark" else base

    // ── Colour accessors ─────────────────────────────────────────────────────

    fun bgStatus(context: Context): Int {
        val dark = isDark(context)
        return color(context, key(PreferenceKeys.COLOR_BG_STATUS, dark),
            if (dark) DEF_DARK_BG_STATUS else DEF_LIGHT_BG_STATUS)
    }

    fun bgHeader(context: Context): Int {
        val dark = isDark(context)
        return color(context, key(PreferenceKeys.COLOR_BG_HEADER, dark),
            if (dark) DEF_DARK_BG_HEADER else DEF_LIGHT_BG_HEADER)
    }

    fun bgTransit(context: Context): Int {
        val dark = isDark(context)
        return color(context, key(PreferenceKeys.COLOR_BG_TRANSIT, dark),
            if (dark) DEF_DARK_BG_TRANSIT else DEF_LIGHT_BG_TRANSIT)
    }

    fun bgCamera(context: Context): Int {
        val dark = isDark(context)
        return color(context, key(PreferenceKeys.COLOR_BG_CAMERA, dark),
            if (dark) DEF_DARK_BG_CAMERA else DEF_LIGHT_BG_CAMERA)
    }

    fun borderRoom(context: Context): Int {
        val dark = isDark(context)
        return color(context, key(PreferenceKeys.COLOR_BORDER_ROOM, dark),
            if (dark) DEF_DARK_BORDER else DEF_LIGHT_BORDER)
    }

    fun textRoom(context: Context): Int {
        val dark = isDark(context)
        return color(context, key(PreferenceKeys.COLOR_TEXT_ROOM, dark),
            if (dark) DEF_DARK_TEXT_ROOM else DEF_LIGHT_TEXT_ROOM)
    }

    fun textDim(context: Context): Int {
        val dark = isDark(context)
        return color(context, key(PreferenceKeys.COLOR_TEXT_DIM, dark),
            if (dark) DEF_DARK_TEXT_DIM else DEF_LIGHT_TEXT_DIM)
    }

    // ── Font size accessors ───────────────────────────────────────────────────

    fun fontRoomTitle(context: Context): Float =
        prefs(context).getString(PreferenceKeys.FONT_ROOM_TITLE, DEF_FONT_ROOM_TITLE.toString())
            ?.toFloatOrNull() ?: DEF_FONT_ROOM_TITLE

    fun fontRoomData(context: Context): Float =
        prefs(context).getString(PreferenceKeys.FONT_ROOM_DATA, DEF_FONT_ROOM_DATA.toString())
            ?.toFloatOrNull() ?: DEF_FONT_ROOM_DATA

    fun fontTransit(context: Context): Float =
        prefs(context).getString(PreferenceKeys.FONT_TRANSIT, DEF_FONT_TRANSIT.toString())
            ?.toFloatOrNull() ?: DEF_FONT_TRANSIT

    // ── Util: build colour pref key ───────────────────────────────────────────
    /** Used by settings to know the full key when saving a colour. */
    fun colorKey(base: String, dark: Boolean) = key(base, dark)

    // ── Window indicator colours ──────────────────────────────────────────────
    fun windowOpen(context: Context)   = android.graphics.Color.parseColor("#FFE53935") // red
    fun windowTilted(context: Context) = android.graphics.Color.parseColor("#FFFFA726") // orange
    fun windowClosed(context: Context) = android.graphics.Color.parseColor("#FF43A047") // green

}