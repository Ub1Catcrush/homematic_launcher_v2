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
                "nav_weather"       to WeatherFragment::class.java.name,
                "nav_ha"            to HaFragment::class.java.name
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
                    if (!isResumed) return@launch
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
            if (!isResumed) return
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
            bindSwitch(PreferenceKeys.MOTION_WEBCAM_ENABLED)
            bindNumber(PreferenceKeys.MOTION_WEBCAM_SENSITIVITY, "")
            bindSwitch(PreferenceKeys.MOTION_LOCAL_ENABLED)
            bindList(PreferenceKeys.MOTION_LOCAL_FACING)
            bindNumber(PreferenceKeys.MOTION_LOCAL_SENSITIVITY, "")
            bindNumber(PreferenceKeys.MOTION_WAKE_TIMEOUT_SEC, " s")
            bindSwitch(PreferenceKeys.NIGHT_DIM_ENABLED)
            bindNumber(PreferenceKeys.NIGHT_DIM_START, "")
            bindNumber(PreferenceKeys.NIGHT_DIM_END, "")
            bindNumber(PreferenceKeys.NIGHT_DIM_BRIGHTNESS, " %")
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
            if (!isResumed) return
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
                        if (!isResumed) return@launch
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
                if (!isResumed) return@setOnPreferenceClickListener true
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
            bindGridColumns()
            bindFonts()
            bindColors()
            findPreference<Preference>("action_reset_appearance")?.setOnPreferenceClickListener {
                resetAppearance(); true
            }
        }

        private fun bindGridColumns() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            listOf(
                PreferenceKeys.GRID_COLUMNS_PORTRAIT  to requireContext().getString(R.string.pref_hint_grid_columns_portrait),
                PreferenceKeys.GRID_COLUMNS_LANDSCAPE to requireContext().getString(R.string.pref_hint_grid_columns_landscape)
            ).forEach { (key, hint) ->
                findPreference<EditTextPreference>(key)?.apply {
                    val cur = prefs.getString(key, "") ?: ""
                    summary = if (cur.isNotEmpty()) cur else hint
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                        p.summary = if (v.toString().isNotEmpty()) v.toString() else hint
                        true
                    }
                }
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
                PreferenceKeys.GRID_COLUMNS_PORTRAIT, PreferenceKeys.GRID_COLUMNS_LANDSCAPE,
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
            bindGridColumns(); bindFonts(); bindColors()
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
    // Home Assistant
    // ══════════════════════════════════════════════════════════════════════════

    class HaFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_ha, rootKey)
            bindSwitch(PreferenceKeys.HA_TILE_ENABLED)
            listOf(PreferenceKeys.HA_WS_URL).forEach { bindEditText(it) }

            // Token: show placeholder instead of the actual value
            findPreference<EditTextPreference>(PreferenceKeys.HA_TOKEN)?.apply {
                val cur = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getString(PreferenceKeys.HA_TOKEN, "") ?: ""
                summary = if (cur.isNotEmpty()) getString(R.string.pref_summary_ha_token_set)
                          else getString(R.string.pref_summary_ha_token)
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                    p.summary = if (v.toString().isNotEmpty()) getString(R.string.pref_summary_ha_token_set)
                                else getString(R.string.pref_summary_ha_token)
                    true
                }
            }

            // ── Multi-tile manager ─────────────────────────────────────────────
            findPreference<Preference>("action_ha_tiles")?.setOnPreferenceClickListener {
                showTilesManagerDialog()
                true
            }
            updateTilesSummary()

            // Connection test
            findPreference<Preference>("action_ha_test")?.setOnPreferenceClickListener { pref ->
                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                val url   = prefs.getString(PreferenceKeys.HA_WS_URL, "") ?: ""
                val tok   = prefs.getString(PreferenceKeys.HA_TOKEN,  "") ?: ""
                if (url.isBlank() || tok.isBlank()) {
                    pref.summary = getString(R.string.ha_test_missing_config)
                    return@setOnPreferenceClickListener true
                }
                pref.summary = getString(R.string.ha_test_connecting)
                val state = HaRepository.connState.value
                pref.summary = when (state) {
                    is HaRepository.ConnState.Connected      -> getString(R.string.ha_test_ok,
                        HaRepository.entityStates.value.size)
                    is HaRepository.ConnState.Error          -> getString(R.string.ha_test_error, state.message)
                    is HaRepository.ConnState.Connecting,
                    is HaRepository.ConnState.Authenticating -> getString(R.string.ha_test_connecting)
                    else -> getString(R.string.ha_test_disconnected)
                }
                true
            }
        }

        private fun updateTilesSummary() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val json  = prefs.getString(PreferenceKeys.HA_TILES_CONFIG, "") ?: ""
            val count = HaTileViewController.parseTiles(json).size
            findPreference<Preference>("action_ha_tiles")?.summary =
                if (count == 0) getString(R.string.ha_tiles_summary_empty)
                else            getString(R.string.ha_tiles_summary_count, count)
        }

        private fun updateEntitiesSummary() { updateTilesSummary() }

        // ── Tiles manager dialog ───────────────────────────────────────────────

        private fun showTilesManagerDialog() {
            if (!isResumed) return
            val prefs   = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val json    = prefs.getString(PreferenceKeys.HA_TILES_CONFIG, "") ?: ""
            val tiles   = HaTileViewController.parseTiles(json).toMutableList()
            val ctx     = requireContext()
            val dp      = ctx.resources.displayMetrics.density

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16*dp).toInt(), (8*dp).toInt(), (16*dp).toInt(), (8*dp).toInt())
            }

            val scrollView    = android.widget.ScrollView(ctx)
            val listContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            scrollView.addView(listContainer)
            root.addView(scrollView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (300*dp).toInt()))

            fun save() {
                prefs.edit()
                    .putString(PreferenceKeys.HA_TILES_CONFIG,
                        HaTileViewController.tilesToJson(tiles))
                    .apply()
                updateTilesSummary()
            }

            fun rebuildList() {
                listContainer.removeAllViews()
                if (tiles.isEmpty()) {
                    listContainer.addView(android.widget.TextView(ctx).apply {
                        text = getString(R.string.ha_tiles_empty)
                        setTextColor(0xFFAAAAAA.toInt()); textSize = 12f
                        setPadding(0, (8*dp).toInt(), 0, (8*dp).toInt())
                    })
                }
                tiles.forEachIndexed { idx, tile ->
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, (4*dp).toInt(), 0, (4*dp).toInt())
                    }
                    val tv = android.widget.TextView(ctx).apply {
                        text = "📊 ${tile.title}  (${tile.entities.size} Datenpunkte)"
                        setTextColor(android.graphics.Color.WHITE); textSize = 13f
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val btnEdit = android.widget.ImageButton(ctx).apply {
                        setImageResource(android.R.drawable.ic_menu_edit)
                        background = null; setColorFilter(0xFF88BBFF.toInt())
                        setOnClickListener {
                            showTileEditorDialog(tiles[idx]) { updated ->
                                tiles[idx] = updated; save(); rebuildList()
                            }
                        }
                    }
                    val btnUp = android.widget.ImageButton(ctx).apply {
                        setImageResource(android.R.drawable.arrow_up_float)
                        background = null; setColorFilter(0xFFCCCCCC.toInt())
                        setOnClickListener {
                            if (idx > 0) {
                                val tmp = tiles[idx-1]; tiles[idx-1] = tiles[idx]; tiles[idx] = tmp
                                save(); rebuildList()
                            }
                        }
                    }
                    val btnDown = android.widget.ImageButton(ctx).apply {
                        setImageResource(android.R.drawable.arrow_down_float)
                        background = null; setColorFilter(0xFFCCCCCC.toInt())
                        setOnClickListener {
                            if (idx < tiles.size - 1) {
                                val tmp = tiles[idx+1]; tiles[idx+1] = tiles[idx]; tiles[idx] = tmp
                                save(); rebuildList()
                            }
                        }
                    }
                    val btnDel = android.widget.ImageButton(ctx).apply {
                        setImageResource(android.R.drawable.ic_menu_delete)
                        background = null; setColorFilter(0xFFFF4444.toInt())
                        setOnClickListener { tiles.removeAt(idx); save(); rebuildList() }
                    }
                    row.addView(tv); row.addView(btnUp); row.addView(btnDown)
                    row.addView(btnEdit); row.addView(btnDel)
                    listContainer.addView(row)
                }
            }
            rebuildList()

            // Add new tile button
            val btnAddTile = android.widget.Button(ctx).apply {
                text = getString(R.string.ha_tile_add)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (8*dp).toInt()
                }
                setOnClickListener {
                    val newConfig = HaTileViewController.HaTileConfig(
                        id       = "tile_${System.currentTimeMillis()}",
                        title    = getString(R.string.ha_tile_default_title),
                        entities = emptyList()
                    )
                    showTileEditorDialog(newConfig) { created ->
                        tiles.add(created); save(); rebuildList()
                    }
                }
            }
            root.addView(btnAddTile)

            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.ha_tiles_manager_title))
                .setView(root)
                .setPositiveButton(getString(android.R.string.ok), null)
                .show()
        }

        /**
         * Editor dialog for a single HaTileConfig: set title + manage entity list.
         * Re-uses the existing showEntitiesDialog logic but for a specific tile.
         */
        private fun showTileEditorDialog(
            config: HaTileViewController.HaTileConfig,
            onSave: (HaTileViewController.HaTileConfig) -> Unit
        ) {
            if (!isResumed) return
            val ctx     = requireContext()
            val dp      = ctx.resources.displayMetrics.density
            val entities = config.entities.toMutableList()

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16*dp).toInt(), (8*dp).toInt(), (16*dp).toInt(), (8*dp).toInt())
            }

            // Title field
            root.addView(android.widget.TextView(ctx).apply {
                text = getString(R.string.ha_tile_title_label)
                setTextColor(0xFFCCCCCC.toInt()); textSize = 11f
            })
            val etTitle = android.widget.EditText(ctx).apply {
                setText(config.title)
                setTextColor(android.graphics.Color.WHITE)
                setHintTextColor(0xFF888888.toInt())
                hint = getString(R.string.ha_tile_default_title)
                textSize = 14f; setSingleLine()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            root.addView(etTitle)

            // Divider
            root.addView(android.view.View(ctx).apply {
                setBackgroundColor(0x33FFFFFF)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                    topMargin = (8*dp).toInt(); bottomMargin = (4*dp).toInt()
                }
            })

            // Entity list (re-use same pattern as showEntitiesDialog)
            val scrollView    = android.widget.ScrollView(ctx)
            val listContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            scrollView.addView(listContainer)
            root.addView(scrollView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (200*dp).toInt()))

            val knownEntities = HaRepository.entityStates.value.entries
                .sortedWith(compareBy({ it.key.substringBefore(".") }, { it.key }))
                .map { (id, state) ->
                    val name = state.attributes["friendly_name"]?.takeIf { it.isNotBlank() } ?: id
                    id to name
                }

            fun rebuildEntityList() {
                listContainer.removeAllViews()
                if (entities.isEmpty()) {
                    listContainer.addView(android.widget.TextView(ctx).apply {
                        text = getString(R.string.ha_entities_empty)
                        setTextColor(0xFFAAAAAA.toInt()); textSize = 12f
                    })
                }
                entities.forEachIndexed { idx, e ->
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, (3*dp).toInt(), 0, (3*dp).toInt())
                    }
                    val tv = android.widget.TextView(ctx).apply {
                        text = buildString {
                            if (e.icon.isNotBlank()) append("${e.icon} ")
                            append(e.label)
                            append(" (${e.entityId})")
                            if (e.unit.isNotBlank()) append(" [${e.unit}]")
                        }
                        setTextColor(android.graphics.Color.WHITE); textSize = 12f
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val btnEdit = android.widget.ImageButton(ctx).apply {
                        setImageResource(android.R.drawable.ic_menu_edit)
                        background = null; setColorFilter(0xFF88BBFF.toInt())
                        setOnClickListener {
                            showEntityEditor(ctx, dp, entities[idx]) { updated ->
                                entities[idx] = updated; rebuildEntityList()
                            }
                        }
                    }
                    val btnDel = android.widget.ImageButton(ctx).apply {
                        setImageResource(android.R.drawable.ic_menu_delete)
                        background = null; setColorFilter(0xFFFF4444.toInt())
                        setOnClickListener { entities.removeAt(idx); rebuildEntityList() }
                    }
                    row.addView(tv); row.addView(btnEdit); row.addView(btnDel)
                    listContainer.addView(row)
                }
            }
            rebuildEntityList()

            // Add entity section (autocomplete)
            val addSection = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (8*dp).toInt(), 0, 0)
            }
            val suggestionItems = knownEntities.map { (id, name) ->
                if (name == id) id else "$id  —  $name"
            }.toTypedArray()
            val etLabel = android.widget.EditText(ctx).apply {
                hint = getString(R.string.ha_field_label)
                setTextColor(android.graphics.Color.WHITE); setHintTextColor(0xFF888888.toInt())
                textSize = 12f; setSingleLine()
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val etUnit = android.widget.EditText(ctx).apply {
                hint = getString(R.string.ha_field_unit)
                setTextColor(android.graphics.Color.WHITE); setHintTextColor(0xFF888888.toInt())
                textSize = 12f; setSingleLine()
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val etIcon = android.widget.EditText(ctx).apply {
                hint = getString(R.string.ha_field_icon)
                setTextColor(android.graphics.Color.WHITE); setHintTextColor(0xFF888888.toInt())
                textSize = 12f; setSingleLine()
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val acEntityId = android.widget.AutoCompleteTextView(ctx).apply {
                hint = getString(R.string.ha_field_entity_id)
                setTextColor(android.graphics.Color.WHITE); setHintTextColor(0xFF888888.toInt())
                setDropDownBackgroundResource(android.R.color.background_dark)
                textSize = 12f; threshold = 1; setSingleLine()
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setAdapter(android.widget.ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, suggestionItems))
                setOnItemClickListener { parent, _, pos, _ ->
                    // Read the actual displayed string — pos is index in filtered list, not knownEntities
                    val selected = parent.getItemAtPosition(pos) as? String ?: return@setOnItemClickListener
                    val id = selected.substringBefore("  —  ").trim()
                    setText(id)
                    if (etLabel.text.isBlank()) {
                        val name = knownEntities.find { it.first == id }?.second
                        if (name != null && name != id) etLabel.setText(name)
                    }
                    if (etUnit.text.isBlank()) {
                        val unit = HaRepository.entityStates.value[id]
                            ?.attributes?.get("unit_of_measurement")?.takeIf { it.isNotBlank() }
                        if (unit != null) etUnit.setText(unit)
                    }
                }
            }
            val btnAdd = android.widget.Button(ctx).apply {
                text = getString(R.string.ha_add_entity)
                setOnClickListener {
                    val eid = acEntityId.text.toString().substringBefore("  —  ").trim()
                    if (eid.isEmpty()) { acEntityId.error = getString(R.string.ha_error_entity_id_required); return@setOnClickListener }
                    entities.add(HaTileViewController.EntityRow(
                        entityId = eid,
                        label    = etLabel.text.toString().trim().ifBlank { eid.substringAfter(".") },
                        unit     = etUnit.text.toString().trim(),
                        icon     = etIcon.text.toString().trim()
                    ))
                    listOf(acEntityId, etLabel, etUnit, etIcon).forEach { (it as android.widget.TextView).text = "" }
                    rebuildEntityList()
                }
            }
            if (knownEntities.isNotEmpty()) {
                addSection.addView(android.widget.TextView(ctx).apply {
                    text = getString(R.string.ha_entities_known, knownEntities.size)
                    setTextColor(0xFF88DD88.toInt()); textSize = 11f
                })
            }
            addSection.addView(acEntityId); addSection.addView(etLabel)
            addSection.addView(etIcon);     addSection.addView(etUnit)
            addSection.addView(btnAdd)
            root.addView(addSection)

            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.ha_tile_editor_title))
                .setView(root)
                .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                    val title = etTitle.text.toString().trim()
                        .ifBlank { getString(R.string.ha_tile_default_title) }
                    onSave(HaTileViewController.HaTileConfig(
                        id       = config.id,
                        title    = title,
                        entities = entities.toList()
                    ))
                }
                .setNegativeButton(getString(android.R.string.cancel), null)
                .show()
        }

        // ── Entity list editor ────────────────────────────────────────────────

        private fun showEntitiesDialog() {
            if (!isResumed) return
            val prefs     = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val jsonStr   = prefs.getString(PreferenceKeys.HA_ENTITIES, "") ?: ""
            val entities  = HaTileViewController.parseEntities(jsonStr).toMutableList()

            val ctx = requireContext()
            val dp  = ctx.resources.displayMetrics.density

            // ── Snapshot of known entities from live HA connection ─────────────
            // Map: entity_id → friendly_name (or entity_id if no name)
            val knownEntities: List<Pair<String, String>> = HaRepository.entityStates.value
                .entries
                .sortedWith(compareBy({ it.key.substringBefore(".") }, { it.key }))
                .map { (id, state) ->
                    val name = state.attributes["friendly_name"]?.takeIf { it.isNotBlank() } ?: id
                    id to name
                }

            // ── Root layout ───────────────────────────────────────────────────
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16*dp).toInt(), (8*dp).toInt(), (16*dp).toInt(), (8*dp).toInt())
            }

            // ── Current entity list ───────────────────────────────────────────
            val scrollView    = android.widget.ScrollView(ctx)
            val listContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            scrollView.addView(listContainer)
            root.addView(scrollView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (240*dp).toInt()))

            fun rebuildList() {
                listContainer.removeAllViews()
                entities.forEachIndexed { idx, entity ->
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, (4*dp).toInt(), 0, (4*dp).toInt())
                    }
                    val tv = android.widget.TextView(ctx).apply {
                        text = buildString {
                            if (entity.icon.isNotBlank()) append("${entity.icon}  ")
                            append(entity.label)
                            append("  (${entity.entityId})")
                            if (entity.unit.isNotEmpty()) append("  [${entity.unit}]")
                        }
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 13f
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val btnEdit = android.widget.ImageButton(ctx).apply {
                        setImageResource(android.R.drawable.ic_menu_edit)
                        background = null
                        setColorFilter(0xFF88BBFF.toInt())
                        setOnClickListener { showEntityEditor(ctx, dp, entity) { updated ->
                            entities[idx] = updated; rebuildList()
                        }}
                    }
                    val btnDel = android.widget.ImageButton(ctx).apply {
                        setImageResource(android.R.drawable.ic_menu_delete)
                        background = null
                        setColorFilter(0xFFFF4444.toInt())
                        setOnClickListener { entities.removeAt(idx); rebuildList() }
                    }
                    row.addView(tv); row.addView(btnEdit); row.addView(btnDel)
                    listContainer.addView(row)
                }
                if (entities.isEmpty()) {
                    listContainer.addView(android.widget.TextView(ctx).apply {
                        text = ctx.getString(R.string.ha_entities_empty)
                        setTextColor(0xFFAAAAAA.toInt())
                        textSize = 12f
                        setPadding(0, (8*dp).toInt(), 0, (8*dp).toInt())
                    })
                }
            }
            rebuildList()

            // ── Search / autocomplete bar ─────────────────────────────────────
            val searchHint = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (8*dp).toInt(), 0, 0)
            }

            // Label
            searchHint.addView(android.widget.TextView(ctx).apply {
                text = ctx.getString(R.string.ha_search_hint)
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 11f
            })

            // AutoCompleteTextView for entity_id search
            val acEntityId = android.widget.AutoCompleteTextView(ctx).apply {
                hint = ctx.getString(R.string.ha_field_entity_id)
                setTextColor(android.graphics.Color.WHITE)
                setHintTextColor(0xFF888888.toInt())
                setDropDownBackgroundResource(android.R.color.background_dark)
                textSize = 13f
                threshold = 1          // show suggestions after 1 character
                setSingleLine()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            // Adapter: shows "entity.id — Friendly Name"
            val suggestionItems = knownEntities.map { (id, name) ->
                if (name == id) id else "$id  —  $name"
            }.toTypedArray()
            val acAdapter = android.widget.ArrayAdapter(
                ctx, android.R.layout.simple_dropdown_item_1line, suggestionItems)
            acEntityId.setAdapter(acAdapter)

            // When user selects a suggestion, extract entity_id and auto-fill label
            val etLabel = android.widget.EditText(ctx).apply {
                hint = ctx.getString(R.string.ha_field_label)
                setTextColor(android.graphics.Color.WHITE)
                setHintTextColor(0xFF888888.toInt())
                textSize = 13f; setSingleLine()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val etUnit = android.widget.EditText(ctx).apply {
                hint = ctx.getString(R.string.ha_field_unit)
                setTextColor(android.graphics.Color.WHITE)
                setHintTextColor(0xFF888888.toInt())
                textSize = 13f; setSingleLine()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val etIcon = android.widget.EditText(ctx).apply {
                hint = ctx.getString(R.string.ha_field_icon)
                setTextColor(android.graphics.Color.WHITE)
                setHintTextColor(0xFF888888.toInt())
                textSize = 13f; setSingleLine()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            acEntityId.setOnItemClickListener { parent, _, position, _ ->
                val selected  = parent.getItemAtPosition(position) as? String ?: return@setOnItemClickListener
                val selectedId = selected.substringBefore("  —  ").trim()
                acEntityId.setText(selectedId)
                if (etLabel.text.isBlank()) {
                    val friendlyName = knownEntities.find { it.first == selectedId }?.second
                    if (friendlyName != null && friendlyName != selectedId) {
                        etLabel.setText(friendlyName)
                    }
                }
                if (etUnit.text.isBlank()) {
                    val unit = HaRepository.entityStates.value[selectedId]
                        ?.attributes?.get("unit_of_measurement")?.takeIf { it.isNotBlank() }
                    if (unit != null) etUnit.setText(unit)
                }
            }

            val btnAdd = android.widget.Button(ctx).apply {
                text = ctx.getString(R.string.ha_add_entity)
                setOnClickListener {
                    // Extract entity_id — strip the " — Name" suffix if user picked from dropdown
                    val raw = acEntityId.text.toString().trim()
                    val eid = raw.substringBefore("  —  ").trim()
                    if (eid.isEmpty()) {
                        acEntityId.error = ctx.getString(R.string.ha_error_entity_id_required)
                        return@setOnClickListener
                    }
                    entities.add(HaTileViewController.EntityRow(
                        entityId = eid,
                        label    = etLabel.text.toString().trim()
                                       .ifBlank { eid.substringAfter(".") },
                        unit     = etUnit.text.toString().trim(),
                        icon     = etIcon.text.toString().trim()
                    ))
                    listOf(acEntityId, etLabel, etUnit, etIcon).forEach {
                        (it as android.widget.TextView).text = ""
                    }
                    rebuildList()
                }
            }

            // Info label: how many entities known
            if (knownEntities.isNotEmpty()) {
                searchHint.addView(android.widget.TextView(ctx).apply {
                    text = ctx.getString(R.string.ha_entities_known, knownEntities.size)
                    setTextColor(0xFF88DD88.toInt())
                    textSize = 11f
                })
            } else {
                searchHint.addView(android.widget.TextView(ctx).apply {
                    text = ctx.getString(R.string.ha_entities_not_connected)
                    setTextColor(0xFFFFAA44.toInt())
                    textSize = 11f
                })
            }

            searchHint.addView(acEntityId)
            searchHint.addView(etLabel)
            searchHint.addView(etIcon)
            searchHint.addView(etUnit)
            searchHint.addView(btnAdd)
            root.addView(searchHint)

            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.pref_title_ha_entities))
                .setView(root)
                .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                    val arr = org.json.JSONArray()
                    entities.forEach { e ->
                        arr.put(org.json.JSONObject().apply {
                            put("entity_id", e.entityId)
                            put("label",     e.label)
                            put("unit",      e.unit)
                            put("icon",      e.icon)
                        })
                    }
                    prefs.edit().putString(PreferenceKeys.HA_ENTITIES, arr.toString()).apply()
                    updateEntitiesSummary()
                }
                .setNegativeButton(getString(android.R.string.cancel), null)
                .show()
        }

        /**
         * Shows an inline editor dialog for an existing entity row.
         * Uses AutoCompleteTextView for the entity_id field as well.
         */
        private fun showEntityEditor(
            ctx: android.content.Context,
            dp: Float,
            entity: HaTileViewController.EntityRow,
            onSave: (HaTileViewController.EntityRow) -> Unit
        ) {
            if (!isResumed) return
            val knownEntities = HaRepository.entityStates.value.entries
                .sortedBy { it.key }
                .map { (id, state) ->
                    val name = state.attributes["friendly_name"]?.takeIf { it.isNotBlank() } ?: id
                    id to name
                }

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16*dp).toInt(), (12*dp).toInt(), (16*dp).toInt(), (8*dp).toInt())
            }

            fun editField(hint: String, prefill: String) =
                android.widget.EditText(ctx).apply {
                    this.hint = hint
                    setText(prefill)
                    setTextColor(android.graphics.Color.WHITE)
                    setHintTextColor(0xFF888888.toInt())
                    textSize = 13f; setSingleLine()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT)
                }

            val acEntityId = android.widget.AutoCompleteTextView(ctx).apply {
                hint = ctx.getString(R.string.ha_field_entity_id)
                setText(entity.entityId)
                setTextColor(android.graphics.Color.WHITE)
                setHintTextColor(0xFF888888.toInt())
                setDropDownBackgroundResource(android.R.color.background_dark)
                textSize = 13f; threshold = 1; setSingleLine()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                val items = knownEntities.map { (id, name) ->
                    if (name == id) id else "$id  —  $name"
                }.toTypedArray()
                setAdapter(android.widget.ArrayAdapter(
                    ctx, android.R.layout.simple_dropdown_item_1line, items))
            }

            val etLabel = editField(ctx.getString(R.string.ha_field_label), entity.label)
            val etUnit  = editField(ctx.getString(R.string.ha_field_unit),  entity.unit)
            val etIcon  = editField(ctx.getString(R.string.ha_field_icon),  entity.icon)

            acEntityId.setOnItemClickListener { parent, _, position, _ ->
                val selected   = parent.getItemAtPosition(position) as? String ?: return@setOnItemClickListener
                val selectedId = selected.substringBefore("  —  ").trim()
                acEntityId.setText(selectedId)
                if (etLabel.text.isBlank()) {
                    val name = knownEntities.find { it.first == selectedId }?.second
                    if (name != null && name != selectedId) etLabel.setText(name)
                }
                if (etUnit.text.isBlank()) {
                    val unit = HaRepository.entityStates.value[selectedId]
                        ?.attributes?.get("unit_of_measurement")?.takeIf { it.isNotBlank() }
                    if (unit != null) etUnit.setText(unit)
                }
            }

            root.addView(acEntityId); root.addView(etLabel)
            root.addView(etIcon);     root.addView(etUnit)

            AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.ha_edit_entity))
                .setView(root)
                .setPositiveButton(ctx.getString(android.R.string.ok)) { _, _ ->
                    val eid = acEntityId.text.toString()
                        .substringBefore("  —  ").trim()
                    if (eid.isEmpty()) return@setPositiveButton
                    onSave(HaTileViewController.EntityRow(
                        entityId = eid,
                        label    = etLabel.text.toString().trim().ifBlank { eid.substringAfter(".") },
                        unit     = etUnit.text.toString().trim(),
                        icon     = etIcon.text.toString().trim()
                    ))
                }
                .setNegativeButton(ctx.getString(android.R.string.cancel), null)
                .show()
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
