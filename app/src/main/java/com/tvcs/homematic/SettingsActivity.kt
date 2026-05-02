package com.tvcs.homematic

import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
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
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    override fun attachBaseContext(base: Context) =
        super.attachBaseContext(LocaleHelper.wrap(base))

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

    override fun onOptionsItemSelected(item: MenuItem) =
        if (item.itemId == android.R.id.home) { finish(); true } else super.onOptionsItemSelected(item)

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            bindSummaries()
            setupActions()
        }

        // ── Summary binding ───────────────────────────────────────────────────

        private fun bindSummaries() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

            // EditText preferences — show current value as summary
            listOf(
                PreferenceKeys.CCU_HOST,
                PreferenceKeys.CCU_API_PATH,
                PreferenceKeys.OUTDOOR_ROOM_NAME,
                PreferenceKeys.MOLD_WARNING_RH,
                PreferenceKeys.MOLD_URGENT_RH,
                PreferenceKeys.MAX_WINDOW_INDICATORS,
                PreferenceKeys.CAMERA_RTSP_URL,
                PreferenceKeys.CAMERA_SNAPSHOT_URL,
                PreferenceKeys.CAMERA_USERNAME,
                PreferenceKeys.CAMERA_VIEW_HEIGHT_DP,
                PreferenceKeys.CAMERA_RTSP_TIMEOUT_MS,
                PreferenceKeys.CAMERA_SNAPSHOT_INTERVAL,
                PreferenceKeys.ALT_LAUNCHER_PACKAGE,
                PreferenceKeys.TRANSIT_FROM_NAME,
                PreferenceKeys.TRANSIT_TO_NAME
            ).forEach { key ->
                findPreference<EditTextPreference>(key)?.apply {
                    val cur = prefs.getString(key, "") ?: ""
                    if (cur.isNotEmpty()) summary = cur
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                        p.summary = v.toString().ifEmpty { null }; true
                    }
                }
            }

            findPreference<EditTextPreference>(PreferenceKeys.CCU_PORT)?.apply {
                summary = (prefs.getString(PreferenceKeys.CCU_PORT, "") ?: "")
                    .ifEmpty { getString(R.string.pref_summary_ccu_port) }
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                    p.summary = v.toString().ifEmpty { getString(R.string.pref_summary_ccu_port) }; true
                }
            }

            findPreference<EditTextPreference>(PreferenceKeys.CAMERA_PASSWORD)?.apply {
                val cur = prefs.getString(PreferenceKeys.CAMERA_PASSWORD, "") ?: ""
                summary = if (cur.isNotEmpty()) "••••••" else getString(R.string.pref_summary_camera_password_unset)
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                    p.summary = if (v.toString().isNotEmpty()) "••••••"
                    else getString(R.string.pref_summary_camera_password_unset)
                    true
                }
            }

            findPreference<EditTextPreference>(PreferenceKeys.CCU_SID)?.apply {
                val cur = prefs.getString(PreferenceKeys.CCU_SID, "") ?: ""
                summary = sidSummary(cur)
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                    p.summary = sidSummary(v.toString()); true
                }
            }

            // List preferences — show selected entry label
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
                        val i  = lp.findIndexOfValue(v.toString())
                        p.summary = if (i >= 0) lp.entries[i] else v.toString()
                        if (key == PreferenceKeys.APP_LANGUAGE) showLanguageRestartDialog()
                        true
                    }
                }
            }

            // Switch preferences — enabled / disabled summary
            listOf(
                PreferenceKeys.CCU_HTTPS,
                PreferenceKeys.CCU_TRUST_SELF_SIGNED,
                PreferenceKeys.KEEP_SCREEN_ON,
                PreferenceKeys.SHOW_STATUS_BAR,
                PreferenceKeys.DISABLE_DISPLAY,
                PreferenceKeys.SHOW_RELOAD_POPUPS,
                PreferenceKeys.AUTO_RELOAD_ON_RECONNECT,
                PreferenceKeys.NOTIFY_BACKGROUND,
                PreferenceKeys.NOTIFY_LOWBAT,
                PreferenceKeys.NOTIFY_SABOTAGE,
                PreferenceKeys.NOTIFY_FAULT,
                PreferenceKeys.NOTIFY_WINDOW_OPEN,
                PreferenceKeys.TEST_MODE,
                PreferenceKeys.CONTENT_BELOW_STATUS_BAR,
                PreferenceKeys.CAMERA_ENABLED,
                PreferenceKeys.ALT_LAUNCHER_ENABLED,
                PreferenceKeys.TRANSIT_ENABLED
            ).forEach { key ->
                findPreference<SwitchPreferenceCompat>(key)?.apply {
                    summary = switchSummary(isChecked)
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                        p.summary = switchSummary(v as Boolean)
                        if (key == PreferenceKeys.NOTIFY_BACKGROUND)
                            CcuNotificationWorker.schedule(requireContext(), v)
                        true
                    }
                }
            }
        }

        private fun sidSummary(s: String) =
            if (s.isNotEmpty()) "••••••  (${s.length} ${getString(R.string.pref_chars_set)})"
            else getString(R.string.pref_summary_ccu_sid)

        private fun switchSummary(on: Boolean) =
            getString(if (on) R.string.summary_enabled else R.string.summary_disabled)

        // ── Action preferences ────────────────────────────────────────────────

        private fun setupActions() {
            findPreference<Preference>("action_reload_now")?.setOnPreferenceClickListener {
                requireContext().sendBroadcast(
                    Intent(MainActivity.ACTION_RELOAD_DATA)
                        .setPackage(MainActivity.PACKAGE_NAME)
                )
                it.summary = getString(R.string.pref_summary_reload_started); true
            }

            findPreference<Preference>("action_check_network")?.setOnPreferenceClickListener {
                it.summary = NetworkUtils.getNetworkStatus(requireContext()).description; true
            }

            findPreference<Preference>("action_check_ccu")?.setOnPreferenceClickListener { pref ->
                pref.summary = getString(R.string.pref_summary_checking)
                val ctx     = requireContext()
                val prefs   = PreferenceManager.getDefaultSharedPreferences(ctx)
                val host    = HomeMatic.getCcuHost()
                val https   = HomeMatic.isCcuHttps()
                val port    = prefs.getString(PreferenceKeys.CCU_PORT, "") ?: ""
                val apiPath = prefs.getString(PreferenceKeys.CCU_API_PATH, "/addons/xmlapi/") ?: "/addons/xmlapi/"
                val sid     = prefs.getString(PreferenceKeys.CCU_SID, "") ?: ""
                val timeout = (prefs.getString(PreferenceKeys.CONNECTION_TIMEOUT, "5")?.toIntOrNull() ?: 5) * 1000
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = NetworkUtils.testCcuConnection(host, https, port, apiPath, sid, timeout)
                    pref.summary = when {
                        !result.reachable     -> getString(R.string.pref_summary_ccu_unreachable, host)
                        result.authOk == null -> getString(R.string.pref_summary_ccu_no_sid)
                        result.authOk         -> getString(R.string.pref_summary_ccu_auth_ok,    host)
                        else                  -> getString(R.string.pref_summary_ccu_auth_fail,  host)
                    }
                }
                true
            }

            // Open DeviceProfileActivity
            findPreference<Preference>("action_open_device_profile")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), DeviceProfileActivity::class.java))
                true
            }

            // Open DiagnosticsActivity
            findPreference<Preference>("action_open_diagnostics")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), DiagnosticsActivity::class.java))
                true
            }

            // Test RTSP stream
            findPreference<Preference>("action_transit_search_from")?.setOnPreferenceClickListener { pref ->
                runTransitStopSearch(
                    idKey    = PreferenceKeys.TRANSIT_FROM_ID,
                    nameKey  = PreferenceKeys.TRANSIT_FROM_NAME,
                    titleRes = R.string.pref_title_transit_search_from,
                    pref     = pref
                )
                true
            }

            findPreference<Preference>("action_transit_search_to")?.setOnPreferenceClickListener { pref ->
                runTransitStopSearch(
                    idKey    = PreferenceKeys.TRANSIT_TO_ID,
                    nameKey  = PreferenceKeys.TRANSIT_TO_NAME,
                    titleRes = R.string.pref_title_transit_search_to,
                    pref     = pref
                )
                true
            }

            // ── Zwischenstationen (Spalte 4) ──────────────────────────────────
            fun refreshWatchedSummary() {
                val current = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getString(PreferenceKeys.TRANSIT_WATCHED_STATIONS, "") ?: ""
                val addPref   = findPreference<Preference>("action_transit_watched_add")
                val clearPref = findPreference<Preference>("action_transit_watched_clear")
                if (current.isBlank()) {
                    addPref?.summary   = getString(R.string.transit_watched_stations_empty)
                    clearPref?.isVisible = false
                } else {
                    addPref?.summary   = getString(R.string.transit_watched_stations_current, current)
                    clearPref?.isVisible = true
                    clearPref?.summary = getString(R.string.transit_watched_stations_current, current)
                }
            }
            refreshWatchedSummary()

            findPreference<Preference>("action_transit_watched_add")?.setOnPreferenceClickListener { pref ->
                showStopSearchDialog(getString(R.string.pref_title_transit_watched_stations), "") { stop ->
                    val ctx   = requireContext()
                    val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                    val current = prefs.getString(PreferenceKeys.TRANSIT_WATCHED_STATIONS, "") ?: ""
                    val names = current.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                    if (!names.contains(stop.name)) names.add(stop.name)
                    prefs.edit().putString(PreferenceKeys.TRANSIT_WATCHED_STATIONS, names.joinToString(", ")).apply()
                    refreshWatchedSummary()
                    pref.summary = getString(R.string.transit_watched_station_added, stop.name)
                }
                true
            }

            findPreference<Preference>("action_transit_watched_clear")?.setOnPreferenceClickListener { _ ->
                PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit().remove(PreferenceKeys.TRANSIT_WATCHED_STATIONS).apply()
                refreshWatchedSummary()
                true
            }

            // Extra connections 2–4 (stored in TRANSIT_EXTRA_CONNECTIONS as JSON array)
            listOf(
                Triple("action_transit_conn2_search_from", "conn2_from", true),
                Triple("action_transit_conn2_search_to",   "conn2_to",   false),
                Triple("action_transit_conn3_search_from", "conn3_from", true),
                Triple("action_transit_conn3_search_to",   "conn3_to",   false),
                Triple("action_transit_conn4_search_from", "conn4_from", true),
                Triple("action_transit_conn4_search_to",   "conn4_to",   false)
            ).forEach { (actionKey, connKey, isFrom) ->
                findPreference<Preference>(actionKey)?.setOnPreferenceClickListener { pref ->
                    val connIdx = connKey[4].digitToInt() - 2  // "conn2" → 0, "conn3" → 1, "conn4" → 2
                    runExtraConnectionStopSearch(connIdx, isFrom, pref)
                    true
                }
            }

            // Sync summary for extra connection name fields
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            listOf(
                "transit_conn2_from_name", "transit_conn2_to_name",
                "transit_conn3_from_name", "transit_conn3_to_name",
                "transit_conn4_from_name", "transit_conn4_to_name"
            ).forEach { key ->
                findPreference<EditTextPreference>(key)?.apply {
                    val cur = prefs.getString(key, "") ?: ""
                    if (cur.isNotEmpty()) summary = cur
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                        p.summary = v as? String ?: ""
                        true
                    }
                }
            }

            findPreference<Preference>("action_test_rtsp")?.setOnPreferenceClickListener { pref ->
                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                val url   = prefs.getString(PreferenceKeys.CAMERA_RTSP_URL, "") ?: ""
                if (url.isBlank()) {
                    pref.summary = getString(R.string.pref_summary_camera_test_no_url); return@setOnPreferenceClickListener true
                }
                pref.summary = getString(R.string.pref_summary_camera_test_rtsp_starting)
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val socket = java.net.Socket()
                            val uri    = android.net.Uri.parse(url)
                            val host   = uri.host ?: error("No host in URL")
                            val port   = if (uri.port > 0) uri.port else 554
                            socket.connect(java.net.InetSocketAddress(host, port), 4_000)
                            socket.close()
                            host to port
                        }
                    }
                    pref.summary = result.fold(
                        onSuccess = { (h, p) -> getString(R.string.pref_summary_camera_test_rtsp_ok, h, p) },
                        onFailure = { e     -> getString(R.string.pref_summary_camera_test_rtsp_fail, e.message ?: "?") }
                    )
                }
                true
            }

            // Test snapshot URL
            findPreference<Preference>("action_test_snapshot")?.setOnPreferenceClickListener { pref ->
                val prefs       = PreferenceManager.getDefaultSharedPreferences(requireContext())
                val snapshotUrl = prefs.getString(PreferenceKeys.CAMERA_SNAPSHOT_URL, "") ?: ""
                val user        = prefs.getString(PreferenceKeys.CAMERA_USERNAME, "") ?: ""
                val pass        = prefs.getString(PreferenceKeys.CAMERA_PASSWORD, "") ?: ""
                if (snapshotUrl.isBlank()) {
                    pref.summary = getString(R.string.pref_summary_camera_test_no_url); return@setOnPreferenceClickListener true
                }
                pref.summary = getString(R.string.pref_summary_camera_test_snapshot_starting)
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val fullUrl = if (user.isNotBlank()) {
                                val sep = if (snapshotUrl.contains("?")) "&" else "?"
                                "${snapshotUrl}${sep}user=$user&password=$pass"
                            } else snapshotUrl
                            val con = (java.net.URL(fullUrl).openConnection() as java.net.HttpURLConnection).apply {
                                connectTimeout = 5_000; readTimeout = 8_000; connect()
                            }
                            val code        = con.responseCode
                            val contentType = con.contentType ?: ""
                            con.disconnect()
                            code to contentType
                        }
                    }
                    pref.summary = result.fold(
                        onSuccess = { (code, ct) ->
                            if (code == 200) getString(R.string.pref_summary_camera_test_snapshot_ok, ct)
                            else             getString(R.string.pref_summary_camera_test_snapshot_http, code)
                        },
                        onFailure = { e -> getString(R.string.pref_summary_camera_test_snapshot_fail, e.message ?: "?") }
                    )
                }
                true
            }

            // Detect installed launchers — shows a chooser dialog and saves the package name
            findPreference<Preference>("action_detect_launchers")?.setOnPreferenceClickListener { pref ->
                val launchers = LauncherSwitchHelper.getInstalledLaunchers(requireContext())
                if (launchers.isEmpty()) {
                    pref.summary = getString(R.string.pref_summary_no_launchers_found)
                    return@setOnPreferenceClickListener true
                }
                val labels  = launchers.map { it.second }.toTypedArray()
                val pkgs    = launchers.map { it.first }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.dialog_select_launcher_title))
                    .setItems(labels) { _, idx ->
                        val chosen = pkgs[idx]
                        PreferenceManager.getDefaultSharedPreferences(requireContext())
                            .edit().putString(PreferenceKeys.ALT_LAUNCHER_PACKAGE, chosen).apply()
                        findPreference<EditTextPreference>(PreferenceKeys.ALT_LAUNCHER_PACKAGE)
                            ?.apply { text = chosen; summary = chosen }
                        pref.summary = getString(R.string.pref_summary_launcher_selected, labels[idx])
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }

        /**
         * Unified station search dialog — one dialog, live search, results inline.
         * Replaces the old two-step (input dialog → results dialog) approach.
         *
         * On selection, calls [onStopSelected] with the chosen stop.
         */
        private fun showStopSearchDialog(
            title: String,
            prefill: String,
            onStopSelected: (DbTransitRepository.TransitStop) -> Unit
        ) {
            val ctx = requireContext()
            val dp  = resources.displayMetrics.density

            // ── Views ──────────────────────────────────────────────────────────
            val input = EditText(ctx).apply {
                hint      = getString(R.string.transit_search_hint)
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                setText(prefill)
                selectAll()
            }

            val statusText = android.widget.TextView(ctx).apply {
                textSize = 12f
                setPadding(0, (4 * dp).toInt(), 0, 0)
                visibility = android.view.View.GONE
            }

            val listView = android.widget.ListView(ctx)

            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (16 * dp).toInt()
                setPadding(pad, (8 * dp).toInt(), pad, 0)
                addView(input)
                addView(statusText)
                addView(listView)
            }

            val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .create()

            // ── Live search logic ──────────────────────────────────────────────
            var searchJob: kotlinx.coroutines.Job? = null
            var currentStops: List<DbTransitRepository.TransitStop> = emptyList()

            fun updateList(stops: List<DbTransitRepository.TransitStop>) {
                currentStops = stops
                val adapter = android.widget.ArrayAdapter(
                    ctx,
                    android.R.layout.simple_list_item_1,
                    stops.map { it.name }
                )
                listView.adapter = adapter
                listView.setOnItemClickListener { _, _, idx, _ ->
                    onStopSelected(currentStops[idx])
                    dialog.dismiss()
                }
            }

            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val query = s?.toString()?.trim() ?: ""
                    searchJob?.cancel()
                    if (query.length < 2) {
                        statusText.visibility = android.view.View.GONE
                        updateList(emptyList())
                        return
                    }
                    statusText.text = getString(R.string.pref_summary_transit_searching, query)
                    statusText.visibility = android.view.View.VISIBLE
                    searchJob = viewLifecycleOwner.lifecycleScope.launch {
                        kotlinx.coroutines.delay(350) // debounce
                        when (val result = DbTransitRepository.searchStops(query)) {
                            is DbTransitRepository.Result.Error -> {
                                statusText.text = getString(R.string.pref_summary_transit_search_error, result.message)
                            }
                            is DbTransitRepository.Result.Success -> {
                                statusText.visibility = android.view.View.GONE
                                if (result.data.isEmpty()) {
                                    statusText.text = getString(R.string.pref_summary_transit_no_results, query)
                                    statusText.visibility = android.view.View.VISIBLE
                                }
                                updateList(result.data)
                            }
                        }
                    }
                }
            })

            dialog.show()
            // Trigger search immediately if prefill text is long enough
            if (prefill.length >= 2) input.text = input.text
        }

        // ── Helpers that call showStopSearchDialog ────────────────────────────

        private fun runTransitStopSearch(
            idKey: String, nameKey: String, titleRes: Int, pref: Preference
        ) {
            val ctx    = requireContext()
            val prefs  = PreferenceManager.getDefaultSharedPreferences(ctx)
            val prefill = prefs.getString(nameKey, "") ?: ""
            showStopSearchDialog(getString(titleRes), prefill) { stop ->
                prefs.edit()
                    .putString(idKey,   stop.id)
                    .putString(nameKey, stop.name)
                    .apply()
                findPreference<androidx.preference.EditTextPreference>(nameKey)
                    ?.apply { text = stop.name; summary = stop.name }
                pref.summary = getString(R.string.pref_summary_transit_station_set, stop.name, stop.id)
            }
        }

        /**
         * Like runTransitStopSearch but stores into TRANSIT_EXTRA_CONNECTIONS JSON array.
         */
        private fun runExtraConnectionStopSearch(connIdx: Int, isFrom: Boolean, pref: Preference) {
            val ctx    = requireContext()
            val prefs  = PreferenceManager.getDefaultSharedPreferences(ctx)
            val nameKey = if (isFrom) "transit_conn${connIdx + 2}_from_name"
                          else        "transit_conn${connIdx + 2}_to_name"
            val prefill = prefs.getString(nameKey, "") ?: ""
            val titleRes = if (isFrom) R.string.pref_title_transit_search_from
                           else        R.string.pref_title_transit_search_to
            showStopSearchDialog(getString(titleRes), prefill) { stop ->
                prefs.edit().putString(nameKey, stop.name).apply()
                findPreference<EditTextPreference>(nameKey)
                    ?.apply { text = stop.name; summary = stop.name }
                updateExtraConnection(prefs, connIdx, isFrom, stop)
                pref.summary = getString(R.string.pref_summary_transit_station_set, stop.name, stop.id)
            }
        }

        private fun updateExtraConnection(
            prefs: android.content.SharedPreferences,
            connIdx: Int,
            isFrom: Boolean,
            stop: DbTransitRepository.TransitStop
        ) {
            val json = prefs.getString(PreferenceKeys.TRANSIT_EXTRA_CONNECTIONS, "") ?: ""
            val arr  = if (json.isBlank()) org.json.JSONArray() else org.json.JSONArray(json)
            // Ensure the array is large enough
            while (arr.length() <= connIdx) arr.put(org.json.JSONObject())
            val obj = arr.getJSONObject(connIdx)
            if (isFrom) {
                obj.put("fromId",   stop.id)
                obj.put("fromName", stop.name)
            } else {
                obj.put("toId",   stop.id)
                obj.put("toName", stop.name)
            }
            prefs.edit().putString(PreferenceKeys.TRANSIT_EXTRA_CONNECTIONS, arr.toString()).apply()
        }

        private fun showLanguageRestartDialog() {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dialog_restart_title)
                .setMessage(R.string.dialog_language_restart_message)
                .setPositiveButton(R.string.dialog_btn_restart_now) { _, _ -> restartApp() }
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
