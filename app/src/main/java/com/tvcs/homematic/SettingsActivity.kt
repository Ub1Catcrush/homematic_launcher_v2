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
                .replace(R.id.settings_container, MainSettingsFragment())
                .commit()
        }
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                supportActionBar?.title = getString(R.string.title_activity_settings)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem) =
        if (item.itemId == android.R.id.home) {
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
            } else {
                finish()
            }
            true
        } else super.onOptionsItemSelected(item)

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

    // ── Main settings screen ──────────────────────────────────────────────────

    class MainSettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_main, rootKey)
            findPreference<Preference>("nav_ccu")?.fragment          = CcuSettingsFragment::class.java.name
            findPreference<Preference>("nav_display")?.fragment      = DisplaySettingsFragment::class.java.name
            findPreference<Preference>("nav_notifications")?.fragment = NotificationSettingsFragment::class.java.name
            findPreference<Preference>("nav_camera")?.fragment       = CameraSettingsFragment::class.java.name
            findPreference<Preference>("nav_transit")?.fragment      = TransitSettingsFragment::class.java.name
            findPreference<Preference>("nav_advanced")?.fragment     = AdvancedSettingsFragment::class.java.name
        }
    }

    // ── CCU-Verbindung & Sync ─────────────────────────────────────────────────

    class CcuSettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_ccu, rootKey)
            bindCcuSummaries()
            setupCcuActions()
        }

        private fun bindCcuSummaries() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

            listOf(PreferenceKeys.CCU_HOST, PreferenceKeys.CCU_API_PATH).forEach { key ->
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

            findPreference<EditTextPreference>(PreferenceKeys.CCU_SID)?.apply {
                val cur = prefs.getString(PreferenceKeys.CCU_SID, "") ?: ""
                summary = sidSummary(cur)
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                    p.summary = sidSummary(v.toString()); true
                }
            }

            listOf(PreferenceKeys.SYNC_FREQUENCY, PreferenceKeys.CONNECTION_TIMEOUT).forEach { key ->
                findPreference<ListPreference>(key)?.apply {
                    val idx = findIndexOfValue(value)
                    if (idx >= 0) summary = entries[idx]
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                        val lp = p as ListPreference
                        val i = lp.findIndexOfValue(v.toString())
                        p.summary = if (i >= 0) lp.entries[i] else v.toString()
                        true
                    }
                }
            }

            listOf(PreferenceKeys.CCU_HTTPS, PreferenceKeys.CCU_TRUST_SELF_SIGNED,
                   PreferenceKeys.AUTO_RELOAD_ON_RECONNECT).forEach { key ->
                findPreference<SwitchPreferenceCompat>(key)?.apply {
                    summary = switchSummary(isChecked)
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                        p.summary = switchSummary(v as Boolean); true
                    }
                }
            }
        }

        private fun setupCcuActions() {
            findPreference<Preference>("action_reload_now")?.setOnPreferenceClickListener {
                requireContext().sendBroadcast(
                    Intent(MainActivity.ACTION_RELOAD_DATA).setPackage(MainActivity.PACKAGE_NAME)
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
        }

        private fun sidSummary(s: String) =
            if (s.isNotEmpty()) "••••••  (${s.length} ${getString(R.string.pref_chars_set)})"
            else getString(R.string.pref_summary_ccu_sid)
    }

    // ── Anzeige & Thermostat ──────────────────────────────────────────────────

    class DisplaySettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_display, rootKey)
            bindDisplaySummaries()
        }

        private fun bindDisplaySummaries() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

            listOf(PreferenceKeys.MOLD_WARNING_RH, PreferenceKeys.MOLD_URGENT_RH,
                   PreferenceKeys.MAX_WINDOW_INDICATORS).forEach { key ->
                findPreference<EditTextPreference>(key)?.apply {
                    val cur = prefs.getString(key, "") ?: ""
                    if (cur.isNotEmpty()) summary = cur
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                        p.summary = v.toString().ifEmpty { null }; true
                    }
                }
            }

            listOf(PreferenceKeys.THEME_MODE, PreferenceKeys.APP_LANGUAGE,
                   PreferenceKeys.DISABLE_DISPLAY_PERIOD).forEach { key ->
                findPreference<ListPreference>(key)?.apply {
                    val idx = findIndexOfValue(value)
                    if (idx >= 0) summary = entries[idx]
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                        val lp = p as ListPreference
                        val i = lp.findIndexOfValue(v.toString())
                        p.summary = if (i >= 0) lp.entries[i] else v.toString()
                        if (key == PreferenceKeys.APP_LANGUAGE) showLanguageRestartDialog()
                        true
                    }
                }
            }

            listOf(PreferenceKeys.KEEP_SCREEN_ON, PreferenceKeys.SHOW_STATUS_BAR,
                   PreferenceKeys.CONTENT_BELOW_STATUS_BAR, PreferenceKeys.DISABLE_DISPLAY).forEach { key ->
                findPreference<SwitchPreferenceCompat>(key)?.apply {
                    summary = switchSummary(isChecked)
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                        p.summary = switchSummary(v as Boolean); true
                    }
                }
            }
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

    // ── Benachrichtigungen ────────────────────────────────────────────────────

    class NotificationSettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_notifications, rootKey)
            listOf(
                PreferenceKeys.SHOW_RELOAD_POPUPS, PreferenceKeys.NOTIFY_BACKGROUND,
                PreferenceKeys.NOTIFY_LOWBAT,       PreferenceKeys.NOTIFY_SABOTAGE,
                PreferenceKeys.NOTIFY_FAULT,        PreferenceKeys.NOTIFY_WINDOW_OPEN
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
    }

    // ── Kamera ────────────────────────────────────────────────────────────────

    class CameraSettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_camera, rootKey)
            bindCameraSummaries()
            setupCameraActions()
        }

        private fun bindCameraSummaries() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

            listOf(
                PreferenceKeys.CAMERA_RTSP_URL, PreferenceKeys.CAMERA_SNAPSHOT_URL,
                PreferenceKeys.CAMERA_USERNAME,  PreferenceKeys.CAMERA_VIEW_HEIGHT_DP,
                PreferenceKeys.CAMERA_RTSP_TIMEOUT_MS, PreferenceKeys.CAMERA_SNAPSHOT_INTERVAL
            ).forEach { key ->
                findPreference<EditTextPreference>(key)?.apply {
                    val cur = prefs.getString(key, "") ?: ""
                    if (cur.isNotEmpty()) summary = cur
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                        p.summary = v.toString().ifEmpty { null }; true
                    }
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

            findPreference<SwitchPreferenceCompat>(PreferenceKeys.CAMERA_ENABLED)?.apply {
                summary = switchSummary(isChecked)
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                    p.summary = switchSummary(v as Boolean); true
                }
            }
        }

        private fun setupCameraActions() {
            findPreference<Preference>("action_test_rtsp")?.setOnPreferenceClickListener { pref ->
                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                val url   = prefs.getString(PreferenceKeys.CAMERA_RTSP_URL, "") ?: ""
                if (url.isBlank()) { pref.summary = getString(R.string.pref_summary_camera_test_no_url); return@setOnPreferenceClickListener true }
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

            findPreference<Preference>("action_test_snapshot")?.setOnPreferenceClickListener { pref ->
                val prefs       = PreferenceManager.getDefaultSharedPreferences(requireContext())
                val snapshotUrl = prefs.getString(PreferenceKeys.CAMERA_SNAPSHOT_URL, "") ?: ""
                val user        = prefs.getString(PreferenceKeys.CAMERA_USERNAME, "") ?: ""
                val pass        = prefs.getString(PreferenceKeys.CAMERA_PASSWORD, "") ?: ""
                if (snapshotUrl.isBlank()) { pref.summary = getString(R.string.pref_summary_camera_test_no_url); return@setOnPreferenceClickListener true }
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
                            val code = con.responseCode; val ct = con.contentType ?: ""; con.disconnect()
                            code to ct
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
        }
    }

    // ── Abfahrtsanzeige ───────────────────────────────────────────────────────

    class TransitSettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_transit, rootKey)
            bindTransitSummaries()
            setupTransitActions()
        }

        private fun bindTransitSummaries() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            listOf(
                PreferenceKeys.TRANSIT_FROM_NAME, PreferenceKeys.TRANSIT_TO_NAME,
                "transit_conn2_from_name", "transit_conn2_to_name",
                "transit_conn3_from_name", "transit_conn3_to_name",
                "transit_conn4_from_name", "transit_conn4_to_name"
            ).forEach { key ->
                findPreference<EditTextPreference>(key)?.apply {
                    val cur = prefs.getString(key, "") ?: ""
                    if (cur.isNotEmpty()) summary = cur
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                        p.summary = v as? String ?: ""; true
                    }
                }
            }
            findPreference<SwitchPreferenceCompat>(PreferenceKeys.TRANSIT_ENABLED)?.apply {
                summary = switchSummary(isChecked)
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                    p.summary = switchSummary(v as Boolean); true
                }
            }
        }

        private fun setupTransitActions() {
            findPreference<Preference>("action_transit_search_from")?.setOnPreferenceClickListener { pref ->
                runTransitStopSearch(PreferenceKeys.TRANSIT_FROM_ID, PreferenceKeys.TRANSIT_FROM_NAME, R.string.pref_title_transit_search_from, pref); true
            }
            findPreference<Preference>("action_transit_search_to")?.setOnPreferenceClickListener { pref ->
                runTransitStopSearch(PreferenceKeys.TRANSIT_TO_ID, PreferenceKeys.TRANSIT_TO_NAME, R.string.pref_title_transit_search_to, pref); true
            }

            fun refreshWatchedSummary() {
                val current = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getString(PreferenceKeys.TRANSIT_WATCHED_STATIONS, "") ?: ""
                val addPref   = findPreference<Preference>("action_transit_watched_add")
                val clearPref = findPreference<Preference>("action_transit_watched_clear")
                if (current.isBlank()) {
                    addPref?.summary = getString(R.string.transit_watched_stations_empty)
                    clearPref?.isVisible = false
                } else {
                    addPref?.summary = getString(R.string.transit_watched_stations_current, current)
                    clearPref?.isVisible = true
                    clearPref?.summary = getString(R.string.transit_watched_stations_current, current)
                }
            }
            refreshWatchedSummary()

            findPreference<Preference>("action_transit_watched_add")?.setOnPreferenceClickListener { pref ->
                showStopSearchDialog(getString(R.string.pref_title_transit_watched_stations), "") { stop ->
                    val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
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
                refreshWatchedSummary(); true
            }

            listOf(
                Triple("action_transit_conn2_search_from", "conn2_from", true),
                Triple("action_transit_conn2_search_to",   "conn2_to",   false),
                Triple("action_transit_conn3_search_from", "conn3_from", true),
                Triple("action_transit_conn3_search_to",   "conn3_to",   false),
                Triple("action_transit_conn4_search_from", "conn4_from", true),
                Triple("action_transit_conn4_search_to",   "conn4_to",   false)
            ).forEach { (actionKey, connKey, isFrom) ->
                findPreference<Preference>(actionKey)?.setOnPreferenceClickListener { pref ->
                    runExtraConnectionStopSearch(connKey[4].digitToInt() - 2, isFrom, pref); true
                }
            }
        }

        private fun runTransitStopSearch(idKey: String, nameKey: String, titleRes: Int, pref: Preference) {
            val prefs  = PreferenceManager.getDefaultSharedPreferences(requireContext())
            showStopSearchDialog(getString(titleRes), prefs.getString(nameKey, "") ?: "") { stop ->
                prefs.edit().putString(idKey, stop.id).putString(nameKey, stop.name).apply()
                findPreference<EditTextPreference>(nameKey)?.apply { text = stop.name; summary = stop.name }
                pref.summary = getString(R.string.pref_summary_transit_station_set, stop.name, stop.id)
            }
        }

        private fun runExtraConnectionStopSearch(connIdx: Int, isFrom: Boolean, pref: Preference) {
            val prefs  = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val nameKey = if (isFrom) "transit_conn${connIdx + 2}_from_name" else "transit_conn${connIdx + 2}_to_name"
            val titleRes = if (isFrom) R.string.pref_title_transit_search_from else R.string.pref_title_transit_search_to
            showStopSearchDialog(getString(titleRes), prefs.getString(nameKey, "") ?: "") { stop ->
                prefs.edit().putString(nameKey, stop.name).apply()
                findPreference<EditTextPreference>(nameKey)?.apply { text = stop.name; summary = stop.name }
                updateExtraConnection(prefs, connIdx, isFrom, stop)
                pref.summary = getString(R.string.pref_summary_transit_station_set, stop.name, stop.id)
            }
        }

        private fun updateExtraConnection(prefs: android.content.SharedPreferences, connIdx: Int, isFrom: Boolean, stop: DbTransitRepository.TransitStop) {
            val json = prefs.getString(PreferenceKeys.TRANSIT_EXTRA_CONNECTIONS, "") ?: ""
            val arr  = if (json.isBlank()) org.json.JSONArray() else org.json.JSONArray(json)
            while (arr.length() <= connIdx) arr.put(org.json.JSONObject())
            val obj = arr.getJSONObject(connIdx)
            if (isFrom) { obj.put("fromId", stop.id); obj.put("fromName", stop.name) }
            else        { obj.put("toId",   stop.id); obj.put("toName",   stop.name) }
            prefs.edit().putString(PreferenceKeys.TRANSIT_EXTRA_CONNECTIONS, arr.toString()).apply()
        }

        private fun showStopSearchDialog(title: String, prefill: String, onStopSelected: (DbTransitRepository.TransitStop) -> Unit) {
            val ctx = requireContext()
            val dp  = resources.displayMetrics.density
            val input = EditText(ctx).apply {
                hint = getString(R.string.transit_search_hint)
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                setText(prefill); selectAll()
            }
            val statusText = android.widget.TextView(ctx).apply {
                textSize = 12f; setPadding(0, (4 * dp).toInt(), 0, 0); visibility = android.view.View.GONE
            }
            val listView = android.widget.ListView(ctx)
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (16 * dp).toInt()
                setPadding(pad, (8 * dp).toInt(), pad, 0)
                addView(input); addView(statusText); addView(listView)
            }
            val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(title).setView(container).setNegativeButton(android.R.string.cancel, null).create()

            var searchJob: kotlinx.coroutines.Job? = null
            var currentStops: List<DbTransitRepository.TransitStop> = emptyList()

            fun updateList(stops: List<DbTransitRepository.TransitStop>) {
                currentStops = stops
                listView.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_list_item_1, stops.map { it.name })
                listView.setOnItemClickListener { _, _, idx, _ -> onStopSelected(currentStops[idx]); dialog.dismiss() }
            }

            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val query = s?.toString()?.trim() ?: ""
                    searchJob?.cancel()
                    if (query.length < 2) { statusText.visibility = android.view.View.GONE; updateList(emptyList()); return }
                    statusText.text = getString(R.string.pref_summary_transit_searching, query)
                    statusText.visibility = android.view.View.VISIBLE
                    searchJob = viewLifecycleOwner.lifecycleScope.launch {
                        kotlinx.coroutines.delay(350)
                        when (val result = DbTransitRepository.searchStops(query)) {
                            is DbTransitRepository.Result.Error -> statusText.text = getString(R.string.pref_summary_transit_search_error, result.message)
                            is DbTransitRepository.Result.Success -> {
                                statusText.visibility = android.view.View.GONE
                                if (result.data.isEmpty()) { statusText.text = getString(R.string.pref_summary_transit_no_results, query); statusText.visibility = android.view.View.VISIBLE }
                                updateList(result.data)
                            }
                        }
                    }
                }
            })
            dialog.show()
            if (prefill.length >= 2) input.text = input.text
        }
    }

    // ── Erweitert ─────────────────────────────────────────────────────────────

    class AdvancedSettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_advanced, rootKey)
            bindAdvancedSummaries()
            setupAdvancedActions()
        }

        private fun bindAdvancedSummaries() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            listOf(PreferenceKeys.OUTDOOR_ROOM_NAME, PreferenceKeys.ALT_LAUNCHER_PACKAGE).forEach { key ->
                findPreference<EditTextPreference>(key)?.apply {
                    val cur = prefs.getString(key, "") ?: ""
                    if (cur.isNotEmpty()) summary = cur
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                        p.summary = v.toString().ifEmpty { null }; true
                    }
                }
            }
            listOf(PreferenceKeys.TEST_MODE, PreferenceKeys.ALT_LAUNCHER_ENABLED).forEach { key ->
                findPreference<SwitchPreferenceCompat>(key)?.apply {
                    summary = switchSummary(isChecked)
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { p, v ->
                        p.summary = switchSummary(v as Boolean); true
                    }
                }
            }
        }

        private fun setupAdvancedActions() {
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
                    .setItems(labels) { _, idx ->
                        val chosen = pkgs[idx]
                        PreferenceManager.getDefaultSharedPreferences(requireContext())
                            .edit().putString(PreferenceKeys.ALT_LAUNCHER_PACKAGE, chosen).apply()
                        findPreference<EditTextPreference>(PreferenceKeys.ALT_LAUNCHER_PACKAGE)
                            ?.apply { text = chosen; summary = chosen }
                        pref.summary = getString(R.string.pref_summary_launcher_selected, labels[idx])
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
                true
            }
        }
    }
}

private fun PreferenceFragmentCompat.switchSummary(on: Boolean) =
    getString(if (on) R.string.summary_enabled else R.string.summary_disabled)
