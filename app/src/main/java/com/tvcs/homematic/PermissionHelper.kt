package com.tvcs.homematic

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * PermissionHelper — centralized runtime permission management.
 *
 * Handles the full permission request lifecycle following Google's recommended pattern:
 *   1. Check if already granted → skip
 *   2. Show rationale dialog if shouldShowRequestPermissionRationale() → explain, then request
 *   3. Request via ActivityResultLauncher (modern API, no deprecated onRequestPermissionsResult)
 *   4. If permanently denied → direct user to app settings
 *
 * ── Usage ───────────────────────────────────────────────────────────────────
 * In your Activity.onCreate() (before the activity is started):
 *
 *   private lateinit var permHelper: PermissionHelper
 *
 *   override fun onCreate(savedInstanceState: Bundle?) {
 *       super.onCreate(savedInstanceState)
 *       permHelper = PermissionHelper(this)   // must be called in onCreate
 *       permHelper.requestNotificationPermission()
 *   }
 *
 * The helper self-registers with the ActivityResultRegistry — no manual
 * onRequestPermissionsResult() override needed anywhere.
 */
class PermissionHelper(private val activity: AppCompatActivity) {

    /** Result launcher — must be registered before the activity starts (i.e. in onCreate). */
    private val notificationPermLauncher: ActivityResultLauncher<String> =
        activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                // Permission granted — re-schedule worker so it can now post notifications
                val bgEnabled = androidx.preference.PreferenceManager
                    .getDefaultSharedPreferences(activity)
                    .getBoolean(PreferenceKeys.NOTIFY_BACKGROUND, false)
                if (bgEnabled) CcuNotificationWorker.schedule(activity, true)
            } else {
                // Check if permanently denied (never ask again)
                val permanentlyDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !activity.shouldShowRequestPermissionRationale(
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                if (permanentlyDenied) {
                    showPermanentlyDeniedDialog()
                }
                // If not permanent, user just tapped "Deny" once — we respect that silently.
            }
        }

    /**
     * Checks and requests POST_NOTIFICATIONS permission on Android 13+.
     * On older Android versions, notifications don't need a runtime permission — returns immediately.
     *
     * Call from MainActivity.onCreate() after the content view is set.
     */
    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return  // not needed pre-13

        when {
            isNotificationPermissionGranted(activity) -> return  // already have it

            activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) ->
                showRationaleDialog()

            else ->
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Returns true if POST_NOTIFICATIONS is granted (or not required on this OS version).
     */
    fun isNotificationPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ── Private dialogs ──────────────────────────────────────────────────────

    /**
     * Rationale dialog — shown when Android says we should explain why we need the permission
     * (i.e. after the user denied once but did NOT select "never ask again").
     */
    private fun showRationaleDialog() {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.perm_notif_rationale_title))
            .setMessage(activity.getString(R.string.perm_notif_rationale_message))
            .setPositiveButton(activity.getString(R.string.perm_btn_grant)) { _, _ ->
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .setNegativeButton(activity.getString(R.string.perm_btn_not_now), null)
            .show()
    }

    /**
     * "Permanently denied" dialog — shown when the user has selected "never ask again".
     * The only way forward is to open the system app settings.
     */
    private fun showPermanentlyDeniedDialog() {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.perm_notif_denied_title))
            .setMessage(activity.getString(R.string.perm_notif_denied_message))
            .setPositiveButton(activity.getString(R.string.perm_btn_open_settings)) { _, _ ->
                openAppSettings(activity)
            }
            .setNegativeButton(activity.getString(R.string.perm_btn_not_now), null)
            .show()
    }

    private fun openAppSettings(activity: Activity) {
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", activity.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
