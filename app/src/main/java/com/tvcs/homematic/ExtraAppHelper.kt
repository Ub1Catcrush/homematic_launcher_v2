package com.tvcs.homematic

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager

/**
 * ExtraAppHelper — startet eine beliebige zweite App über den Toolbar-Button.
 *
 * Anders als LauncherSwitchHelper wird hier jede installierte App unterstützt,
 * nicht nur HOME-Aktivitäten. Die App wird über getLaunchIntentForPackage()
 * gestartet, also über ihren normalen Einstiegspunkt.
 */
object ExtraAppHelper {

    private const val TAG = "ExtraAppHelper"

    fun launch(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val pkg   = prefs.getString(PreferenceKeys.EXTRA_APP_PACKAGE, "")?.trim() ?: ""
        if (pkg.isBlank()) return false
        return try {
            val intent = context.packageManager
                .getLaunchIntentForPackage(pkg)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                ?: run {
                    Log.w(TAG, "Package '$pkg' not found or has no launch intent")
                    return false
                }
            context.startActivity(intent)
            Log.i(TAG, "Launched extra app: $pkg")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch extra app: ${e.message}", e)
            false
        }
    }

    fun isEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(PreferenceKeys.EXTRA_APP_ENABLED, false)
    }

    /**
     * Alle Apps mit HOME-Kategorie (echte Launcher wie Apex, Nova, …).
     * Wird für den Launcher-Button-Picker verwendet.
     */
    fun getLauncherApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return resolveInfos
            .asSequence()
            .mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName) return@mapNotNull null
                Pair(pkg, ri.loadLabel(pm).toString())
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
            .toList()
    }

    /**
     * ALLE installierten Apps (außer dieser App selbst) mit einem startbaren
     * Launcher-Intent. Benötigt QUERY_ALL_PACKAGES (im Manifest deklariert).
     * Wird für den 2. App Button Picker verwendet.
     */
    fun getInstalledApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager

        // 1. Hole die Paketliste passend zur Android-Version (Behebt die SDK-Warnungen)
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }

        // 2. Filter- und Transformations-Pipeline
        return packages
            .asSequence()
            .filter { it.packageName != context.packageName }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .mapNotNull { info ->
                val appInfo = info.applicationInfo ?: return@mapNotNull null
                runCatching {
                    // Tauscht die Reihenfolge passend zu Pair(packageName, applicationLabel)
                    Pair(info.packageName, pm.getApplicationLabel(appInfo).toString())
                }.getOrNull()
            }
            // distinctBy ist nach dem Filtern auf Launch-Intents meist redundant,
            // bleibt aber zur Sicherheit für eindeutige Paketnamen aktiv
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
            .toList()
    }
}
