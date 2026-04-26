package com.tvcs.homematic

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.preference.PreferenceManager
import java.util.Locale

/**
 * Wraps a [Context] with the user-selected app language.
 *
 * Usage: override `attachBaseContext` in every Activity:
 * ```kotlin
 * override fun attachBaseContext(base: Context) {
 *     super.attachBaseContext(LocaleHelper.wrap(base))
 * }
 * ```
 *
 * The selected locale is persisted in SharedPreferences under [PreferenceKeys.APP_LANGUAGE].
 * Values: "system" (default), "de", "en".
 */
object LocaleHelper {

    /**
     * Returns a context whose resources reflect the user's chosen language.
     * Call this in `attachBaseContext` before `super`.
     */
    fun wrap(context: Context): Context {
        val prefs    = PreferenceManager.getDefaultSharedPreferences(context)
        val langCode = prefs.getString(PreferenceKeys.APP_LANGUAGE, "system") ?: "system"
        if (langCode == "system") return context
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            applyLocale(context, Locale.of(langCode))
        } else {
            @Suppress("DEPRECATION")
            applyLocale(context, Locale(langCode))
        }
    }

    private fun applyLocale(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        return context.createConfigurationContext(config)
    }
}
