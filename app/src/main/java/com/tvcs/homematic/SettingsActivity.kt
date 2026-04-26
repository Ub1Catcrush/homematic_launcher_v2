package com.tvcs.homematic

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.tvcs.homematic.MainActivity.Companion.PACKAGE_NAME
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            bindSummaries()
            setupActions()
        }

        // ── Summary binding ───────────────────────────────────────────────────

        private fun bindSummaries() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

            // EditText prefs: show current value as summary (non-empty only)
            listOf(
                PreferenceKeys.CCU_HOST,
                PreferenceKeys.CCU_API_PATH,
                PreferenceKeys.OUTDOOR_ROOM_NAME
            ).forEach { key ->
                findPreference<EditTextPreference>(key)?.apply {
                    val cur = prefs.getString(key, "") ?: ""
                    if (cur.isNotEmpty()) summary = cur
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                        p.summary = v.toString().ifEmpty { null }
                        true
                    }
                }
            }

            // CCU Port: show value or fallback string
            findPreference<EditTextPreference>(PreferenceKeys.CCU_PORT)?.apply {
                val cur = prefs.getString(PreferenceKeys.CCU_PORT, "") ?: ""
                summary = cur.ifEmpty { getString(R.string.pref_summary_ccu_port) }
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                    p.summary = v.toString().ifEmpty { getString(R.string.pref_summary_ccu_port) }
                    true
                }
            }

            // SID: show masked hint when set, or default summary
            findPreference<EditTextPreference>(PreferenceKeys.CCU_SID)?.apply {
                val cur = prefs.getString(PreferenceKeys.CCU_SID, "") ?: ""
                summary = if (cur.isNotEmpty())
                    "••••••  (${cur.length} ${getString(R.string.pref_chars_set)})"
                else
                    getString(R.string.pref_summary_ccu_sid)
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                    val s = v.toString()
                    p.summary = if (s.isNotEmpty())
                        "••••••  (${s.length} ${getString(R.string.pref_chars_set)})"
                    else
                        getString(R.string.pref_summary_ccu_sid)
                    true
                }
            }

            // ListPreferences: show the human-readable label as summary
            listOf(
                PreferenceKeys.SYNC_FREQUENCY,
                PreferenceKeys.CONNECTION_TIMEOUT,
                PreferenceKeys.DISABLE_DISPLAY_PERIOD,
                PreferenceKeys.THEME_MODE,
                PreferenceKeys.APP_LANGUAGE
            ).forEach { key ->
                findPreference<ListPreference>(key)?.apply {
                    val idx = findIndexOfValue(value)
                    if (idx >= 0) summary = entries[idx]
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                        val lp = p as ListPreference
                        val i = lp.findIndexOfValue(v.toString())
                        p.summary = if (i >= 0) lp.entries[i] else v.toString()
                        // Language change needs restart
                        if (key == PreferenceKeys.APP_LANGUAGE) {
                            showLanguageRestartDialog()
                        }
                        true
                    }
                }
            }

            // Switch prefs: show "Aktiv" / "Deaktiviert" as dynamic summary
            listOf(
                PreferenceKeys.CCU_HTTPS,
                PreferenceKeys.KEEP_SCREEN_ON,
                PreferenceKeys.SHOW_STATUS_BAR,
                PreferenceKeys.DISABLE_DISPLAY,
                PreferenceKeys.SHOW_RELOAD_POPUPS,
                PreferenceKeys.AUTO_RELOAD_ON_RECONNECT,
                PreferenceKeys.TEST_MODE
            ).forEach { key ->
                findPreference<SwitchPreferenceCompat>(key)?.apply {
                    summary = switchSummary(isChecked)
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                        p.summary = switchSummary(v as Boolean)
                        true
                    }
                }
            }
        }

        private fun switchSummary(checked: Boolean): String = getString(
            if (checked) R.string.summary_enabled else R.string.summary_disabled
        )

        // ── Action preferences ────────────────────────────────────────────────

        private fun setupActions() {
            // Reload now
            findPreference<Preference>("action_reload_now")?.setOnPreferenceClickListener {
                requireContext().sendBroadcast(
                    Intent(MainActivity.ACTION_RELOAD_DATA).setPackage(PACKAGE_NAME)
                )
                it.summary = getString(R.string.pref_summary_reload_started)
                true
            }

            // Network status check
            findPreference<Preference>("action_check_network")?.setOnPreferenceClickListener {
                val status = NetworkUtils.getNetworkStatus(requireContext())
                it.summary = status.description
                true
            }

            // CCU connection + auth test
            findPreference<Preference>("action_check_ccu")?.setOnPreferenceClickListener { pref ->
                pref.summary = getString(R.string.pref_summary_checking)
                val prefs   = PreferenceManager.getDefaultSharedPreferences(requireContext())
                val host    = HomeMatic.getCcuHost()
                val https   = HomeMatic.isCcuHttps()
                val port    = prefs.getString(PreferenceKeys.CCU_PORT, "") ?: ""
                val apiPath = prefs.getString(PreferenceKeys.CCU_API_PATH, "/addons/xmlapi/") ?: "/addons/xmlapi/"
                val sid     = prefs.getString(PreferenceKeys.CCU_SID, "") ?: ""
                val timeout = (prefs.getString(PreferenceKeys.CONNECTION_TIMEOUT, "5")?.toIntOrNull() ?: 5) * 1000

                viewLifecycleOwner.lifecycleScope.launch {
                    val result = NetworkUtils.testCcuConnection(host, https, port, apiPath, sid, timeout)
                    pref.summary = when {
                        !result.reachable ->
                            getString(R.string.pref_summary_ccu_unreachable, host)
                        result.authOk == null ->
                            getString(R.string.pref_summary_ccu_no_sid)
                        result.authOk ->
                            getString(R.string.pref_summary_ccu_auth_ok, host)
                        else ->
                            getString(R.string.pref_summary_ccu_auth_fail, host)
                    }
                }
                true
            }
        }

        // ── Language restart dialog ────────────────────────────────────────────

        private fun showLanguageRestartDialog() {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dialog_restart_title)
                .setMessage(R.string.dialog_language_restart_message)
                .setPositiveButton(R.string.dialog_btn_restart_now) { _, _ ->
                    restartApp()
                }
                .setNegativeButton(R.string.dialog_btn_later, null)
                .show()
        }

        private fun restartApp() {
            val ctx    = requireContext().applicationContext
            val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
            ctx.startActivity(intent)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
