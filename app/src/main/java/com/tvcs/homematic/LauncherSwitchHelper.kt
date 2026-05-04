package com.tvcs.homematic

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager

/**
 * LauncherSwitchHelper — launches an alternative launcher when this app is the default.
 *
 * ── Why this exists ───────────────────────────────────────────────────────────
 * When HomeMatic Launcher is set as the default HOME app (kiosk mode), pressing
 * the home button always returns here. This helper lets the user tap a FAB to
 * deliberately leave and open another launcher (e.g. the OEM launcher, Nova, etc.).
 *
 * ── How it works ─────────────────────────────────────────────────────────────
 * We query the package manager for the alternative launcher package set in Settings,
 * resolve its main LAUNCHER activity, and start it directly. This bypasses the
 * "which app to use?" resolver dialog — the user has already chosen in Settings.
 *
 * ── Configuration ────────────────────────────────────────────────────────────
 * Settings → Launcher-Wechsel:
 *   • Enabled toggle
 *   • Target package name (e.g. "com.google.android.apps.nexuslauncher")
 *   • A "Detect installed launchers" action that fills the package name automatically
 */
object LauncherSwitchHelper {

    private const val TAG = "LauncherSwitch"

    /**
     * Attempts to start the configured alternative launcher.
     * Returns true on success, false if the package is not found or not a launcher.
     */
    fun switchToAltLauncher(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val pkg = prefs.getString(PreferenceKeys.ALT_LAUNCHER_PACKAGE, "")?.trim() ?: ""

        if (pkg.isBlank()) return false

        return try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                setPackage(pkg) // Suche nur innerhalb des Ziel-Pakets
            }

            // Finde die konkrete Launcher-Aktivität des Pakets
            val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }.firstOrNull()

            if (resolveInfo == null) {
                Log.w("Launcher", "Keine HOME-Aktivität für Paket '$pkg' gefunden")
                return false
            }

            // Erstelle den expliziten Intent
            val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                setClassName(pkg, resolveInfo.activityInfo.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(launchIntent)
            true
        } catch (e: Exception) {
            Log.e("Launcher", "Fehler beim Wechsel: ${e.message}")
            false
        }
    }
/*
    fun switchToAltLauncher(context: Context): Boolean {
        val prefs   = PreferenceManager.getDefaultSharedPreferences(context)
        val pkg     = prefs.getString(PreferenceKeys.ALT_LAUNCHER_PACKAGE, "")?.trim() ?: ""

        if (pkg.isBlank()) {
            Log.w(TAG, "No alternative launcher package configured")
            return false
        }

        return try {
            val intent = context.packageManager
                .getLaunchIntentForPackage(pkg)
                ?.apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ?: run {
                    Log.w(TAG, "Package '$pkg' not found or has no launch intent")
                    return false
                }
            context.startActivity(intent)
            Log.i(TAG, "Switched to alt launcher: $pkg")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch launcher: ${e.message}", e)
            false
        }
    }
*/
    /**
     * Returns all installed launcher packages, excluding this app itself.
     * Used in Settings to populate a chooser for the alternative launcher.
     *
     * Each entry is Pair(packageName, appLabel).
     */
    fun getInstalledLaunchers(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }

        // Modernisierte Flags-Abfrage für API 33+ und Fallback für ältere Versionen
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

        return resolveInfos
            .asSequence()
            .mapNotNull { ri ->
                val activityInfo = ri.activityInfo
                val pkg = activityInfo.packageName

                // Filter-Logik
                when {
                    pkg == context.packageName -> null
                    pkg == "android" -> null
                    activityInfo.name.contains("ResolverActivity", true) -> null
                    activityInfo.name.contains("ChooserActivity", true) -> null
                    else -> {
                        // Nutze loadLabel() statt getApplicationInfo(), das ist performanter
                        // und benötigt keinen extra try-catch Block für die App-Suche.
                        val label = ri.loadLabel(pm).toString()
                        Pair(pkg, label)
                    }
                }
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() } // Case-insensitive Sortierung
            .toList()
    }
/*
    fun getInstalledLaunchers(context: Context): List<Pair<String, String>> {
        val pm     = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }

        // MATCH_DEFAULT_ONLY returns only the currently-set default, which is this app itself
        // in kiosk mode — so it would yield nothing useful. Use 0 to get ALL HOME activities.
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                when {
                    pkg == context.packageName -> null  // exclude ourselves
                    // Exclude the AOSP resolver / chooser — it is not a real launcher
                    pkg == "android"           -> null
                    ri.activityInfo.name.contains("ResolverActivity",  ignoreCase = true) -> null
                    ri.activityInfo.name.contains("ChooserActivity",   ignoreCase = true) -> null
                    else -> {
                        val label = try {
                            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                        } catch (_: Exception) { pkg }
                        Pair(pkg, label)
                    }
                }
            }
            .distinctBy { it.first }   // guard against duplicate entries from multi-activity packages
            .sortedBy  { it.second }
            .toList()
    }
*/

    fun isEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(PreferenceKeys.ALT_LAUNCHER_ENABLED, false)
    }
}
