package com.tvcs.homematic

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager

/**
 * DefaultLauncherPromptHelper
 *
 * Einmalige Anfrage, ob die App als Standard-Launcher gesetzt werden soll.
 *
 * Logik:
 *  1. Beim Start / onResume: aktuellen Standard-Launcher ermitteln.
 *  2. Hat der Standard-Launcher gewechselt? → "schon gefragt"-Flag zurücksetzen.
 *     So wird nach jedem Wechsel erneut gefragt.
 *  3. Sind wir bereits Standard-Launcher? → nichts tun.
 *  4. Haben wir in dieser "Session" noch nicht gefragt? → Dialog zeigen.
 *     • Ja  → System-Picker / RoleManager öffnen.
 *     • Nein / Abbruch → Flag setzen, nicht mehr fragen bis sich der Standard ändert.
 */
object DefaultLauncherPromptHelper {

    private const val TAG = "DefaultLauncherPrompt"

    /** Anfrage-Code für onActivityResult (RoleManager-Flow unter API 29+). */
    const val REQUEST_CODE = 4711

    /**
     * In Activity.onResume() aufrufen.
     * Ist die Activity am Beenden, passiert nichts.
     */
    fun checkAndPromptIfNeeded(activity: Activity) {
        if (activity.isFinishing) return

        val prefs        = PreferenceManager.getDefaultSharedPreferences(activity)
        val currentPkg   = currentDefaultLauncherPackage(activity)
        val lastKnown    = prefs.getString(PreferenceKeys.DEFAULT_LAUNCHER_LAST_KNOWN, null)
        val ownPkg       = activity.packageName

        // ── 1. Hat der Standard-Launcher gewechselt? ────────────────────────
        if (currentPkg != null && currentPkg != lastKnown) {
            Log.d(TAG, "Standard-Launcher gewechselt: $lastKnown → $currentPkg – frage erneut.")
            prefs.edit()
                .putString(PreferenceKeys.DEFAULT_LAUNCHER_LAST_KNOWN, currentPkg)
                .putBoolean(PreferenceKeys.DEFAULT_LAUNCHER_ASKED, false)
                .apply()
        }

        // ── 2. Sind wir bereits Standard? ───────────────────────────────────
        if (currentPkg == ownPkg) {
            Log.d(TAG, "Wir sind bereits Standard-Launcher.")
            return
        }

        // ── 3. Haben wir schon gefragt? ─────────────────────────────────────
        if (prefs.getBoolean(PreferenceKeys.DEFAULT_LAUNCHER_ASKED, false)) {
            Log.d(TAG, "Bereits gefragt für diese Session.")
            return
        }

        // ── 4. Dialog anzeigen ───────────────────────────────────────────────
        Log.d(TAG, "Zeige Standard-Launcher-Dialog.")
        prefs.edit().putBoolean(PreferenceKeys.DEFAULT_LAUNCHER_ASKED, true).apply()

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.prompt_default_launcher_title))
            .setMessage(activity.getString(R.string.prompt_default_launcher_message))
            .setPositiveButton(activity.getString(R.string.prompt_default_launcher_yes)) { _, _ ->
                requestDefaultLauncher(activity)
            }
            .setNegativeButton(activity.getString(R.string.prompt_default_launcher_no), null)
            .show()
    }

    // ── Hilfsmethoden ────────────────────────────────────────────────────────

    /** Gibt das Paket des aktuellen System-Standard-Launchers zurück, oder null. */
    private fun currentDefaultLauncherPackage(activity: Activity): String? = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        activity.packageManager
            .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    } catch (e: Exception) {
        Log.w(TAG, "Kann Standard-Launcher nicht ermitteln: ${e.message}")
        null
    }

    /**
     * Öffnet den System-Flow zum Setzen des Standard-Launchers.
     * Android 10+ (Q): RoleManager mit dediziertem Dialog.
     * Ältere Versionen: vorhandene Auswahl löschen → System-Auswahldialog erscheint.
     */
    private fun requestDefaultLauncher(activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = activity.getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    activity.startActivityForResult(
                        rm.createRequestRoleIntent(RoleManager.ROLE_HOME),
                        REQUEST_CODE
                    )
                    return
                }
            }
            // Fallback (< API 29): Home-Intent starten — Android zeigt den Auswahlbildschirm,
            // sobald die aktuelle Standard-App nicht mehr klar ist. Wir können die Voreinstellung
            // auf neueren Geräten nicht programmatisch löschen (clearPackagePreferredActivities
            // ist seit API 23 für Drittanbieter-Apps wirkungslos). Der Nutzer muss den Standard
            // ggf. manuell unter Einstellungen → Apps → Standard-Apps zurücksetzen.
            activity.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Konnte Standard-Launcher-Flow nicht öffnen: ${e.message}", e)
        }
    }
}
