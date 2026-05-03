package com.tvcs.homematic

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.launch

// ── Top-level helper so all fragments can use it ──────────────────────────────
private fun switchLabel(ctx: Context, on: Boolean) =
    ctx.getString(if (on) R.string.summary_enabled else R.string.summary_disabled)

// ── Universal summary helpers (call from any PreferenceFragmentCompat) ────────

/** Binds an EditTextPreference: shows current value as summary, updates on change. */
private fun PreferenceFragmentCompat.bindEditText(
    key: String,
    format: (String) -> String = { it }
) {
    findPreference<EditTextPreference>(key)?.apply {
        val cur = preferenceManager.sharedPreferences?.getString(key, "") ?: ""
        summary = format(cur).ifEmpty { summary ?: "" }
        onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
            p.summary = format(v.toString()).ifEmpty { p.summary ?: "" }; true
        }
    }
}

/** Binds a SwitchPreferenceCompat: shows Enabled/Disabled as summary. */
private fun PreferenceFragmentCompat.bindSwitch(key: String) {
    findPreference<SwitchPreferenceCompat>(key)?.apply {
        val cur = preferenceManager.sharedPreferences?.getBoolean(key, false) ?: false
        summary = switchLabel(requireContext(), cur)
        onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
            p.summary = switchLabel(requireContext(), v as Boolean); true
        }
    }
}

/** Binds a ListPreference: shows selected entry label as summary. */
private fun PreferenceFragmentCompat.bindList(key: String) {
    findPreference<ListPreference>(key)?.apply {
        val idx = findIndexOfValue(value)
        if (idx >= 0) summary = entries[idx]
        onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
            val lp = p as ListPreference
            val i  = lp.findIndexOfValue(v.toString())
            p.summary = if (i >= 0) lp.entries[i] else v.toString(); true
        }
    }
}

/** Binds an EditTextPreference that holds a numeric value with a unit suffix. */
private fun PreferenceFragmentCompat.bindNumber(key: String, unit: String = "") {
    bindEditText(key) { v -> if (v.isNotBlank()) "$v$unit" else "" }
}

// ─────────────────────────────────────────────────────────────────────────────

class SettingsActivity : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    override fun attachBaseContext(base: Context) =
        super.attachBaseContext(LocaleHelper.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, MainFragment())
                .commit()
        }
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0)
                supportActionBar?.title = getString(R.string.title_activity_settings)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            if (supportFragmentManager.backStackEntryCount > 0)
                supportFragmentManager.popBackStack()
            else
                finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** Called when a Preference with android:fragment set is tapped. */
    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ): Boolean {
        val fragment = supportFragmentManager.fragmentFactory
            .instantiate(classLoader, pref.fragment ?: return false)
        fragment.arguments = pref.extras
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, fragment)
            .addToBackStack(null)
            .commit()
        supportActionBar?.title = pref.title
        return true
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Main screen
    // ══════════════════════════════════════════════════════════════════════════

    class MainFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_main, rootKey)
            // Wire each nav entry to its fragment class
            mapOf(
                "nav_ccu"           to CcuFragment::class.java.name,
                "nav_display"       to DisplayFragment::class.java.name,
                "nav_notifications" to NotificationsFragment::class.java.name,
                "nav_camera"        to CameraFragment::class.java.name,
                "nav_transit"       to TransitFragment::class.java.name,
                "nav_advanced"      to AdvancedFragment::class.java.name,
                "nav_appearance"    to AppearanceFragment::class.java.name,
                "nav_weather"       to WeatherFragment::class.java.name
            ).forEach { (key, cls) ->
                findPreference<Preference>(key)?.fragment = cls
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CCU & Sync
    // ══════════════════════════════════════════════════════════════════════════

    class CcuFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_ccu, rootKey)
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

            // EditText summaries
            bindEditText(PreferenceKeys.CCU_HOST)
            bindEditText(PreferenceKeys.CCU_API_PATH)
            findPreference<EditTextPreference>(PreferenceKeys.CCU_PORT)?.apply {
                summary = (prefs.getString(PreferenceKeys.CCU_PORT, "") ?: "")
                    .ifEmpty { getString(R.string.pref_summary_ccu_port) }
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                    p.summary = v.toString().ifEmpty { getString(R.string.pref_summary_ccu_port) }; true
                }
            }
            findPreference<EditTextPreference>(PreferenceKeys.CCU_SID)?.apply {
                val cur = prefs.getString(PreferenceKeys.CCU_SID, "") ?: ""
                summary = sidSummary(cur)
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                    p.summary = sidSummary(v.toString()); true
                }
            }

            // List summaries
            bindList(PreferenceKeys.SYNC_FREQUENCY)
            bindList(PreferenceKeys.CONNECTION_TIMEOUT)

            // Switch summaries
            bindSwitch(PreferenceKeys.CCU_HTTPS)
            bindSwitch(PreferenceKeys.CCU_TRUST_SELF_SIGNED)
            bindSwitch(PreferenceKeys.AUTO_RELOAD_ON_RECONNECT)

            // Actions
            findPreference<Preference>("action_reload_now")?.setOnPreferenceClickListener {
                requireContext().sendBroadcast(
                    Intent(MainActivity.ACTION_RELOAD_DATA).setPackage(MainActivity.PACKAGE_NAME))
                it.summary = getString(R.string.pref_summary_reload_started); true
            }
            findPreference<Preference>("action_check_network")?.setOnPreferenceClickListener {
                it.summary = NetworkUtils.getNetworkStatus(requireContext()).description; true
            }
            findPreference<Preference>("action_check_ccu")?.setOnPreferenceClickListener { pref ->
                pref.summary = getString(R.string.pref_summary_checking)
                val p       = PreferenceManager.getDefaultSharedPreferences(requireContext())
                val host    = HomeMatic.getCcuHost()
                val https   = HomeMatic.isCcuHttps()
                val port    = p.getString(PreferenceKeys.CCU_PORT, "") ?: ""
                val apiPath = p.getString(PreferenceKeys.CCU_API_PATH, "/addons/xmlapi/") ?: "/addons/xmlapi/"
                val sid     = p.getString(PreferenceKeys.CCU_SID, "") ?: ""
                val timeout = (p.getString(PreferenceKeys.CONNECTION_TIMEOUT, "5")?.toIntOrNull() ?: 5) * 1000
                viewLifecycleOwner.lifecycleScope.launch {
                    val r = NetworkUtils.testCcuConnection(host, https, port, apiPath, sid, timeout)
                    pref.summary = when {
                        !r.reachable     -> getString(R.string.pref_summary_ccu_unreachable, host)
                        r.authOk == null -> getString(R.string.pref_summary_ccu_no_sid)
                        r.authOk         -> getString(R.string.pref_summary_ccu_auth_ok, host)
                        else             -> getString(R.string.pref_summary_ccu_auth_fail, host)
                    }
                }
                true
            }
        }

        private fun sidSummary(s: String) =
            if (s.isNotEmpty()) "••••••  (${s.length} ${getString(R.string.pref_chars_set)})"
            else getString(R.string.pref_summary_ccu_sid)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Display
    // ══════════════════════════════════════════════════════════════════════════

    class DisplayFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_display, rootKey)
            bindList(PreferenceKeys.DISABLE_DISPLAY_PERIOD)
            bindList(PreferenceKeys.THEME_MODE)
            findPreference<ListPreference>(PreferenceKeys.APP_LANGUAGE)?.apply {
                val idx = findIndexOfValue(value)
                if (idx >= 0) summary = entries[idx]
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                    val lp = p as ListPreference
                    val i  = lp.findIndexOfValue(v.toString())
                    p.summary = if (i >= 0) lp.entries[i] else v.toString()
                    showLanguageRestartDialog()
                    true
                }
            }
            bindSwitch(PreferenceKeys.KEEP_SCREEN_ON)
            bindSwitch(PreferenceKeys.SHOW_STATUS_BAR)
            bindSwitch(PreferenceKeys.CONTENT_BELOW_STATUS_BAR)
            bindSwitch(PreferenceKeys.SHOW_NAV_BAR)
            bindSwitch(PreferenceKeys.CONTENT_BELOW_NAV_BAR)
            bindSwitch(PreferenceKeys.DISABLE_DISPLAY)
            bindNumber(PreferenceKeys.MAX_WINDOW_INDICATORS)
            bindNumber(PreferenceKeys.MOLD_WARNING_RH, " %")
            bindNumber(PreferenceKeys.MOLD_URGENT_RH, " %")
        }

        private fun showLanguageRestartDialog() {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dialog_restart_title)
                .setMessage(R.string.dialog_language_restart_message)
                .setPositiveButton(R.string.dialog_btn_restart_now) { _, _ ->
                    val ctx    = requireContext().applicationContext
                    val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                        ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
                    ctx.startActivity(intent)
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
                .setNegativeButton(R.string.dialog_btn_later, null)
                .show()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Notifications
    // ══════════════════════════════════════════════════════════════════════════

    class NotificationsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_notifications, rootKey)
            listOf(
                PreferenceKeys.SHOW_RELOAD_POPUPS,
                PreferenceKeys.NOTIFY_LOWBAT,
                PreferenceKeys.NOTIFY_SABOTAGE,
                PreferenceKeys.NOTIFY_FAULT,
                PreferenceKeys.NOTIFY_WINDOW_OPEN
            ).forEach { bindSwitch(it) }
            findPreference<SwitchPreferenceCompat>(PreferenceKeys.NOTIFY_BACKGROUND)?.apply {
                summary = switchLabel(requireContext(), isChecked)
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                    p.summary = switchLabel(requireContext(), v as Boolean)
                    CcuNotificationWorker.schedule(requireContext(), v)
                    true
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Camera
    // ══════════════════════════════════════════════════════════════════════════

    class CameraFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_camera, rootKey)
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            bindEditText(PreferenceKeys.CAMERA_RTSP_URL)
            bindEditText(PreferenceKeys.CAMERA_SNAPSHOT_URL)
            bindEditText(PreferenceKeys.CAMERA_USERNAME)
            bindNumber(PreferenceKeys.CAMERA_OVERLAY_ALPHA, " %")
            bindNumber(PreferenceKeys.CAMERA_RTSP_TIMEOUT_MS, " ms")
            bindNumber(PreferenceKeys.CAMERA_SNAPSHOT_INTERVAL, " s")
            bindNumber(PreferenceKeys.CAMERA_PANEL_PCT_PORTRAIT, " %")
            bindNumber(PreferenceKeys.CAMERA_PANEL_PCT_LAND, " %")
            bindNumber(PreferenceKeys.TRANSIT_PANEL_PCT_PORTRAIT, " %")
            bindNumber(PreferenceKeys.TRANSIT_PANEL_PCT_LAND, " %")
            bindList(PreferenceKeys.CAMERA_SCALE_TYPE)
            bindSwitch(PreferenceKeys.CAMERA_ENABLED)
            findPreference<EditTextPreference>(PreferenceKeys.CAMERA_PASSWORD)?.apply {
                val cur = prefs.getString(PreferenceKeys.CAMERA_PASSWORD, "") ?: ""
                summary = if (cur.isNotEmpty()) "••••••" else getString(R.string.pref_summary_camera_password_unset)
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                    p.summary = if (v.toString().isNotEmpty()) "••••••"
                    else getString(R.string.pref_summary_camera_password_unset)
                    true
                }
            }
            findPreference<Preference>("action_test_rtsp")?.setOnPreferenceClickListener { pref ->
                val url = prefs.getString(PreferenceKeys.CAMERA_RTSP_URL, "") ?: ""
                if (url.isBlank()) { pref.summary = getString(R.string.pref_summary_camera_test_no_url); return@setOnPreferenceClickListener true }
                pref.summary = getString(R.string.pref_summary_camera_test_rtsp_starting)
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val socket = java.net.Socket()
                            val uri    = android.net.Uri.parse(url)
                            val host   = uri.host ?: error("No host")
                            val port   = if (uri.port > 0) uri.port else 554
                            socket.connect(java.net.InetSocketAddress(host, port), 4_000); socket.close()
                            host to port
                        }
                    }
                    pref.summary = result.fold(
                        onSuccess = { (h, p) -> getString(R.string.pref_summary_camera_test_rtsp_ok, h, p) },
                        onFailure = { e -> getString(R.string.pref_summary_camera_test_rtsp_fail, e.message ?: "?") }
                    )
                }
                true
            }
            findPreference<Preference>("action_test_snapshot")?.setOnPreferenceClickListener { pref ->
                val url  = prefs.getString(PreferenceKeys.CAMERA_SNAPSHOT_URL, "") ?: ""
                val user = prefs.getString(PreferenceKeys.CAMERA_USERNAME, "") ?: ""
                val pass = prefs.getString(PreferenceKeys.CAMERA_PASSWORD, "") ?: ""
                if (url.isBlank()) { pref.summary = getString(R.string.pref_summary_camera_test_no_url); return@setOnPreferenceClickListener true }
                pref.summary = getString(R.string.pref_summary_camera_test_snapshot_starting)
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val full = if (user.isNotBlank()) "$url${if (url.contains("?")) "&" else "?"}user=$user&password=$pass" else url
                            val con  = (java.net.URL(full).openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 5_000; readTimeout = 8_000; connect() }
                            val code = con.responseCode; val ct = con.contentType ?: ""; con.disconnect(); code to ct
                        }
                    }
                    pref.summary = result.fold(
                        onSuccess = { (code, ct) -> if (code == 200) getString(R.string.pref_summary_camera_test_snapshot_ok, ct) else getString(R.string.pref_summary_camera_test_snapshot_http, code) },
                        onFailure = { e -> getString(R.string.pref_summary_camera_test_snapshot_fail, e.message ?: "?") }
                    )
                }
                true
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Transit
    // ══════════════════════════════════════════════════════════════════════════

    class TransitFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_transit, rootKey)
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            bindSwitch(PreferenceKeys.TRANSIT_ENABLED)
            bindList(PreferenceKeys.TRANSIT_REFRESH_INTERVAL)
            bindNumber(PreferenceKeys.TRANSIT_ROW_COUNT, context?.getString(R.string.unit_rows) ?: " Zeilen")
            bindEditText(PreferenceKeys.TRANSIT_FROM_NAME)
            bindEditText(PreferenceKeys.TRANSIT_TO_NAME)

            // Extra connection name fields
            listOf(
                "transit_conn2_from_name", "transit_conn2_to_name",
                "transit_conn3_from_name", "transit_conn3_to_name",
                "transit_conn4_from_name", "transit_conn4_to_name"
            ).forEach { key ->
                findPreference<EditTextPreference>(key)?.apply {
                    val cur = prefs.getString(key, "") ?: ""
                    if (cur.isNotEmpty()) summary = cur
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v -> p.summary = v as? String ?: ""; true }
                }
            }

            // Stop search actions
            findPreference<Preference>("action_transit_search_from")?.setOnPreferenceClickListener { pref ->
                runStopSearch(PreferenceKeys.TRANSIT_FROM_ID, PreferenceKeys.TRANSIT_FROM_NAME, R.string.pref_title_transit_search_from, pref); true
            }
            findPreference<Preference>("action_transit_search_to")?.setOnPreferenceClickListener { pref ->
                runStopSearch(PreferenceKeys.TRANSIT_TO_ID, PreferenceKeys.TRANSIT_TO_NAME, R.string.pref_title_transit_search_to, pref); true
            }

            // Watched stations
            fun refreshWatched() {
                val cur = prefs.getString(PreferenceKeys.TRANSIT_WATCHED_STATIONS, "") ?: ""
                findPreference<Preference>("action_transit_watched_add")?.summary =
                    if (cur.isBlank()) getString(R.string.transit_watched_stations_empty)
                    else getString(R.string.transit_watched_stations_current, cur)
                findPreference<Preference>("action_transit_watched_clear")?.isVisible = cur.isNotBlank()
            }
            refreshWatched()
            findPreference<Preference>("action_transit_watched_add")?.setOnPreferenceClickListener { pref ->
                showStopSearch(getString(R.string.pref_title_transit_watched_stations), "") { stop ->
                    val cur   = prefs.getString(PreferenceKeys.TRANSIT_WATCHED_STATIONS, "") ?: ""
                    val names = cur.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                    if (!names.contains(stop.name)) names.add(stop.name)
                    prefs.edit().putString(PreferenceKeys.TRANSIT_WATCHED_STATIONS, names.joinToString(", ")).apply()
                    refreshWatched()
                }
                true
            }
            findPreference<Preference>("action_transit_watched_clear")?.setOnPreferenceClickListener {
                prefs.edit().remove(PreferenceKeys.TRANSIT_WATCHED_STATIONS).apply(); refreshWatched(); true
            }

            // Extra connection search actions
            listOf(
                Triple("action_transit_conn2_search_from", 0, true),
                Triple("action_transit_conn2_search_to",   0, false),
                Triple("action_transit_conn3_search_from", 1, true),
                Triple("action_transit_conn3_search_to",   1, false),
                Triple("action_transit_conn4_search_from", 2, true),
                Triple("action_transit_conn4_search_to",   2, false)
            ).forEach { (key, idx, isFrom) ->
                findPreference<Preference>(key)?.setOnPreferenceClickListener { pref ->
                    runExtraSearch(idx, isFrom, pref); true
                }
            }
        }

        private fun runStopSearch(idKey: String, nameKey: String, titleRes: Int, pref: Preference) {
            val prefs  = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val prefill = prefs.getString(nameKey, "") ?: ""
            showStopSearch(getString(titleRes), prefill) { stop ->
                prefs.edit().putString(idKey, stop.id).putString(nameKey, stop.name).apply()
                findPreference<EditTextPreference>(nameKey)?.apply { text = stop.name; summary = stop.name }
                pref.summary = getString(R.string.pref_summary_transit_station_set, stop.name, stop.id)
            }
        }

        private fun runExtraSearch(connIdx: Int, isFrom: Boolean, pref: Preference) {
            val prefs   = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val nameKey = "transit_conn${connIdx + 2}_${if (isFrom) "from" else "to"}_name"
            val titleRes = if (isFrom) R.string.pref_title_transit_search_from else R.string.pref_title_transit_search_to
            showStopSearch(getString(titleRes), prefs.getString(nameKey, "") ?: "") { stop ->
                prefs.edit().putString(nameKey, stop.name).apply()
                findPreference<EditTextPreference>(nameKey)?.apply { text = stop.name; summary = stop.name }
                // Persist into extra connections JSON
                val json = prefs.getString(PreferenceKeys.TRANSIT_EXTRA_CONNECTIONS, "") ?: ""
                val arr  = if (json.isBlank()) org.json.JSONArray() else org.json.JSONArray(json)
                while (arr.length() <= connIdx) arr.put(org.json.JSONObject())
                arr.getJSONObject(connIdx).apply {
                    if (isFrom) { put("fromId", stop.id); put("fromName", stop.name) }
                    else        { put("toId",   stop.id); put("toName",   stop.name) }
                }
                prefs.edit().putString(PreferenceKeys.TRANSIT_EXTRA_CONNECTIONS, arr.toString()).apply()
                pref.summary = getString(R.string.pref_summary_transit_station_set, stop.name, stop.id)
            }
        }

        private fun showStopSearch(title: String, prefill: String, onSelected: (DbTransitRepository.TransitStop) -> Unit) {
            val ctx = requireContext()
            val dp  = resources.displayMetrics.density
            val input      = EditText(ctx).apply { hint = getString(R.string.transit_search_hint); inputType = android.text.InputType.TYPE_CLASS_TEXT; setText(prefill); selectAll() }
            val statusText = android.widget.TextView(ctx).apply { textSize = 12f; setPadding(0, (4*dp).toInt(), 0, 0); visibility = android.view.View.GONE }
            val listView   = android.widget.ListView(ctx)
            val container  = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (16*dp).toInt(); setPadding(pad, (8*dp).toInt(), pad, 0)
                addView(input); addView(statusText); addView(listView)
            }
            val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(title).setView(container).setNegativeButton(android.R.string.cancel, null).create()

            var searchJob: kotlinx.coroutines.Job? = null
            var stops: List<DbTransitRepository.TransitStop> = emptyList()

            fun updateList(s: List<DbTransitRepository.TransitStop>) {
                stops = s
                listView.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_list_item_1, s.map { it.name })
                listView.setOnItemClickListener { _, _, i, _ -> onSelected(stops[i]); dialog.dismiss() }
            }

            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    val rawUrl = prefs.getString(PreferenceKeys.TRANSIT_BASE_URL, DbTransitRepository.DEFAULT_BASE)
                    val baseUrl = rawUrl?.trimEnd('/')?.ifBlank { DbTransitRepository.DEFAULT_BASE }
                        ?: DbTransitRepository.DEFAULT_BASE

                    val q = s?.toString()?.trim() ?: ""
                    searchJob?.cancel()
                    if (q.length < 2) { statusText.visibility = android.view.View.GONE; updateList(emptyList()); return }
                    statusText.text = getString(R.string.pref_summary_transit_searching, q); statusText.visibility = android.view.View.VISIBLE
                    searchJob = viewLifecycleOwner.lifecycleScope.launch {
                        kotlinx.coroutines.delay(350)
                        when (val r = DbTransitRepository.searchStops(baseUrl, q)) {
                            is DbTransitRepository.Result.Error   -> statusText.text = getString(R.string.pref_summary_transit_search_error, r.message)
                            is DbTransitRepository.Result.Success -> {
                                statusText.visibility = android.view.View.GONE
                                if (r.data.isEmpty()) { statusText.text = getString(R.string.pref_summary_transit_no_results, q); statusText.visibility = android.view.View.VISIBLE }
                                updateList(r.data)
                            }
                        }
                    }
                }
            })
            dialog.show()
            if (prefill.length >= 2) input.text = input.text
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Advanced
    // ══════════════════════════════════════════════════════════════════════════

    class AdvancedFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_advanced, rootKey)
            bindEditText(PreferenceKeys.OUTDOOR_ROOM_NAME)
            bindEditText(PreferenceKeys.ALT_LAUNCHER_PACKAGE)
            bindSwitch(PreferenceKeys.TEST_MODE)
            bindSwitch(PreferenceKeys.ALT_LAUNCHER_ENABLED)
            bindSwitch(PreferenceKeys.LAN_ONLY_MODE)

            findPreference<Preference>("action_open_device_profile")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), DeviceProfileActivity::class.java)); true
            }
            findPreference<Preference>("action_open_diagnostics")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), DiagnosticsActivity::class.java)); true
            }
            findPreference<Preference>("action_detect_launchers")?.setOnPreferenceClickListener { pref ->
                val launchers = LauncherSwitchHelper.getInstalledLaunchers(requireContext())
                if (launchers.isEmpty()) { pref.summary = getString(R.string.pref_summary_no_launchers_found); return@setOnPreferenceClickListener true }
                val labels = launchers.map { it.second }.toTypedArray()
                val pkgs   = launchers.map { it.first }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.dialog_select_launcher_title))
                    .setItems(labels) { _, i ->
                        val chosen = pkgs[i]
                        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putString(PreferenceKeys.ALT_LAUNCHER_PACKAGE, chosen).apply()
                        findPreference<EditTextPreference>(PreferenceKeys.ALT_LAUNCHER_PACKAGE)?.apply { text = chosen; summary = chosen }
                        pref.summary = getString(R.string.pref_summary_launcher_selected, labels[i])
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
                true
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Appearance (colours + font sizes)
    // ══════════════════════════════════════════════════════════════════════════

    class AppearanceFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_appearance, rootKey)
            bindFonts()
            bindColors()
            findPreference<Preference>("action_reset_appearance")?.setOnPreferenceClickListener {
                resetAppearance(); true
            }
        }

        private fun bindFonts() {
            listOf(PreferenceKeys.FONT_ROOM_TITLE, PreferenceKeys.FONT_ROOM_DATA, PreferenceKeys.FONT_TRANSIT).forEach { key ->
                findPreference<EditTextPreference>(key)?.apply {
                    val cur = PreferenceManager.getDefaultSharedPreferences(requireContext()).getString(key, "") ?: ""
                    if (cur.isNotEmpty()) summary = "$cur sp"
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v -> p.summary = "$v sp"; true }
                }
            }
        }

        private fun bindColors() {
            val bases = listOf(
                PreferenceKeys.COLOR_BG_STATUS, PreferenceKeys.COLOR_BG_HEADER,
                PreferenceKeys.COLOR_BG_TRANSIT, PreferenceKeys.COLOR_BG_CAMERA,
                PreferenceKeys.COLOR_BORDER_ROOM, PreferenceKeys.COLOR_TEXT_ROOM, PreferenceKeys.COLOR_TEXT_DIM
            )
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            bases.forEach { base ->
                listOf(base, "${base}_dark").forEach { key ->
                    findPreference<EditTextPreference>(key)?.apply {
                        val cur = prefs.getString(key, "") ?: ""
                        if (cur.isNotEmpty()) summary = cur
                        onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v -> p.summary = v.toString(); true }
                    }
                }
            }
        }

        private fun resetAppearance() {
            val keys = listOf(
                PreferenceKeys.FONT_ROOM_TITLE, PreferenceKeys.FONT_ROOM_DATA, PreferenceKeys.FONT_TRANSIT,
                PreferenceKeys.COLOR_BG_STATUS,   "${PreferenceKeys.COLOR_BG_STATUS}_dark",
                PreferenceKeys.COLOR_BG_HEADER,   "${PreferenceKeys.COLOR_BG_HEADER}_dark",
                PreferenceKeys.COLOR_BG_TRANSIT,  "${PreferenceKeys.COLOR_BG_TRANSIT}_dark",
                PreferenceKeys.COLOR_BG_CAMERA,   "${PreferenceKeys.COLOR_BG_CAMERA}_dark",
                PreferenceKeys.COLOR_BORDER_ROOM, "${PreferenceKeys.COLOR_BORDER_ROOM}_dark",
                PreferenceKeys.COLOR_TEXT_ROOM,   "${PreferenceKeys.COLOR_TEXT_ROOM}_dark",
                PreferenceKeys.COLOR_TEXT_DIM,    "${PreferenceKeys.COLOR_TEXT_DIM}_dark"
            )
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit().also { ed -> keys.forEach { ed.remove(it) } }.apply()
            setPreferencesFromResource(R.xml.preferences_appearance, null)
            bindFonts(); bindColors()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Weather
    // ══════════════════════════════════════════════════════════════════════════

    class WeatherFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_weather, rootKey)
            bindSwitch(PreferenceKeys.WEATHER_ENABLED)
            bindList(PreferenceKeys.WEATHER_DISPLAY_MODE)
            listOf(PreferenceKeys.WEATHER_CITY, PreferenceKeys.WEATHER_LAT,
                   PreferenceKeys.WEATHER_LON,  PreferenceKeys.WEATHER_REFRESH_MIN).forEach { key ->
                findPreference<EditTextPreference>(key)?.apply {
                    val cur = PreferenceManager.getDefaultSharedPreferences(requireContext()).getString(key, "") ?: ""
                    if (cur.isNotEmpty()) summary = cur
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                        if (key == PreferenceKeys.WEATHER_CITY) {
                            PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                                .remove(PreferenceKeys.WEATHER_LAT).remove(PreferenceKeys.WEATHER_LON).apply()
                        }
                        p.summary = v.toString().ifEmpty { null }; true
                    }
                }
            }
            findPreference<Preference>("action_weather_test")?.setOnPreferenceClickListener { pref ->
                pref.summary = getString(R.string.pref_summary_checking)
                viewLifecycleOwner.lifecycleScope.launch {
                    val p    = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    val city = p.getString(PreferenceKeys.WEATHER_CITY, "") ?: ""
                    val lat  = p.getString(PreferenceKeys.WEATHER_LAT, "")?.toDoubleOrNull()
                    val lon  = p.getString(PreferenceKeys.WEATHER_LON, "")?.toDoubleOrNull()
                    val coords = if (lat != null && lon != null) lat to lon
                                 else if (city.isNotBlank()) WeatherRepository.geocode(city) else null
                    if (coords == null) { pref.summary = "Kein Ort oder Koordinaten konfiguriert"; return@launch }
                    when (val r = WeatherRepository.getForecast(coords.first, coords.second)) {
                        is WeatherRepository.Result.Success -> {
                            val fc = r.data
                            pref.summary = "${fc.icon} ${fc.description}  ▲${"%.1f".format(fc.tempMax)}° ▼${"%.1f".format(fc.tempMin)}°" +
                                if (fc.precipMm > 0f) "  💧${"%.1f".format(fc.precipMm)}mm" else ""
                        }
                        is WeatherRepository.Result.Error -> pref.summary = "Fehler: ${r.message}"
                    }
                }
                true
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Shared fragment extension helpers
    // ══════════════════════════════════════════════════════════════════════════

    companion object {
        /** Bind an EditTextPreference to show its current value as summary. */
        fun PreferenceFragmentCompat.bindEditText(key: String) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            findPreference<EditTextPreference>(key)?.apply {
                val cur = prefs.getString(key, "") ?: ""
                if (cur.isNotEmpty()) summary = cur
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                    p.summary = v.toString().ifEmpty { null }; true
                }
            }
        }

        /** Bind a ListPreference to show the selected entry label as summary. */
        fun PreferenceFragmentCompat.bindList(key: String) {
            findPreference<ListPreference>(key)?.apply {
                val idx = findIndexOfValue(value)
                if (idx >= 0) summary = entries[idx]
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                    val lp = p as ListPreference
                    val i  = lp.findIndexOfValue(v.toString())
                    p.summary = if (i >= 0) lp.entries[i] else v.toString()
                    true
                }
            }
        }

        /** Bind a SwitchPreferenceCompat to show enabled/disabled as summary. */
        fun PreferenceFragmentCompat.bindSwitch(key: String) {
            findPreference<SwitchPreferenceCompat>(key)?.apply {
                summary = switchLabel(requireContext(), isChecked)
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                    p.summary = switchLabel(requireContext(), v as Boolean); true
                }
            }
        }
    }
}
