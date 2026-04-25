package com.tvcs.homematic

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            bindSummaries()
            setupActions()
        }

        private fun bindSummaries() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

            // EditText preferences: show current value as summary
            listOf(
                PreferenceKeys.CCU_HOST,
                PreferenceKeys.CCU_PORT,
                PreferenceKeys.CCU_API_PATH,
                PreferenceKeys.OUTDOOR_ROOM_NAME
            ).forEach { key ->
                findPreference<Preference>(key)?.let { pref ->
                    val current = prefs.getString(key, "") ?: ""
                    if (current.isNotEmpty()) pref.summary = current
                    pref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                        p.summary = v.toString()
                        true
                    }
                }
            }

            // ListPreferences: show the selected entry label (not the raw value) as summary
            listOf(
                PreferenceKeys.SYNC_FREQUENCY,
                PreferenceKeys.CONNECTION_TIMEOUT,
                PreferenceKeys.DISABLE_DISPLAY_PERIOD,
                PreferenceKeys.THEME_MODE
            ).forEach { key ->
                findPreference<ListPreference>(key)?.let { pref ->
                    // Set initial summary
                    val index = pref.findIndexOfValue(pref.value)
                    if (index >= 0) pref.summary = pref.entries[index]

                    pref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                        val lp = p as ListPreference
                        val i = lp.findIndexOfValue(v.toString())
                        p.summary = if (i >= 0) lp.entries[i] else v.toString()
                        true
                    }
                }
            }
        }

        private fun setupActions() {
            findPreference<Preference>("action_reload_now")?.setOnPreferenceClickListener {
                requireContext().sendBroadcast(Intent(MainActivity.ACTION_RELOAD_DATA))
                it.summary = "Ladevorgang gestartet…"
                true
            }

            findPreference<Preference>("action_check_network")?.setOnPreferenceClickListener {
                val status = NetworkUtils.getNetworkStatus(requireContext())
                it.summary = status.description
                true
            }

            findPreference<Preference>("action_check_ccu")?.setOnPreferenceClickListener { pref ->
                pref.summary = getString(R.string.pref_summary_checking)
                val host  = HomeMatic.getCcuHost()
                val https = HomeMatic.isCcuHttps()
                // viewLifecycleOwner.lifecycleScope starts on Main — no extra withContext(Main) needed
                viewLifecycleOwner.lifecycleScope.launch {
                    val reachable = NetworkUtils.isCcuReachable(host, https)
                    pref.summary = if (reachable)
                        getString(R.string.pref_summary_ccu_reachable, host)
                    else
                        getString(R.string.pref_summary_ccu_unreachable, host)
                }
                true
            }
        }
    }
}
