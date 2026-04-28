package com.tvcs.homematic

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Color
import android.graphics.PorterDuff
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.homematic.Room
import kotlinx.coroutines.*
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(base: Context) =
        super.attachBaseContext(LocaleHelper.wrap(base))

    companion object {
        const val PACKAGE_NAME        = "com.tvcs.homematic"
        const val ACTION_UPDATED_DATA = "com.tvcs.homematic.updated_data"
        const val ACTION_RELOAD_DATA  = "com.tvcs.homematic.reload_data"

        private const val KEY_DISPLAY_ON    = "display_on"
        private const val KEY_SYNC_INTERVAL = "old_sync_interval"
        private const val KEY_TIMEOUT_SHOWN = "timeout_shown"

        private const val TEMP_STEP = 0.5
        private const val TEMP_MIN  = 5.0
        private const val TEMP_MAX  = 30.0
    }

    // ── Permission helper — must be initialised before the activity starts ────
    private lateinit var permissionHelper: PermissionHelper

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var toolbar: Toolbar
    private lateinit var gridView: RecyclerView
    private lateinit var statusTextView: TextView
    private lateinit var loadingIndicator: ProgressBar

    private val mRooms   = ArrayList<Room>()
    private lateinit var mAdapter: RoomAdapter

    private var isPaused  = false
    private var isStarted = false
    private var reloadJob: Job? = null
    private var timeJob:   Job? = null
    private var loadJob:   Job? = null

    private var oldSyncInterval          = -1
    private var mDisplayOn               = 0L
    private var displayTimeoutToastShown = false

    private lateinit var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Cached formatter for sync timestamp — called every second via updateNetworkStatus
    private val clockFormatter: DateTimeFormatter by lazy {
        val lang = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(PreferenceKeys.APP_LANGUAGE, "system") ?: "system"
        val locale = when (lang) {
            "de"  -> Locale.GERMANY
            "en"  -> Locale.UK
            else  -> Locale.getDefault().takeIf { it.language.isNotEmpty() } ?: Locale.GERMANY
        }
        DateTimeFormatter.ofPattern("EEEE dd.MM.yyyy HH:mm:ss  ", locale)
    }
    private val syncTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    // ── Broadcast receiver ────────────────────────────────────────────────────

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val showPopups = sharedPreferences.getBoolean(PreferenceKeys.SHOW_RELOAD_POPUPS, true)
            when (intent.action) {
                ACTION_UPDATED_DATA -> {
                    setLoading(false)
                    mAdapter.updateRooms(HomeMatic.myRoomList?.rooms ?: emptyList())
                    updateStatusBar()
                    if (showPopups) showToast(getString(R.string.toast_ui_updated))
                }
                ACTION_RELOAD_DATA -> {
                    if (showPopups) showToast(getString(R.string.toast_loading))
                    loadCcuData()
                }
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // PermissionHelper MUST be created in onCreate — it registers an ActivityResultLauncher
        permissionHelper = PermissionHelper(this)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        HomeMatic.init(this)
        CcuNotificationWorker.createChannels(this)
        applyThemeMode(sharedPreferences.getString(PreferenceKeys.THEME_MODE, "system") ?: "system")

        savedInstanceState?.let {
            mDisplayOn               = it.getLong(KEY_DISPLAY_ON, 0L)
            oldSyncInterval          = it.getInt(KEY_SYNC_INTERVAL, -1)
            displayTimeoutToastShown = it.getBoolean(KEY_TIMEOUT_SHOWN, false)
        }

        if (sharedPreferences.getBoolean(PreferenceKeys.KEEP_SCREEN_ON, true))
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (!sharedPreferences.getBoolean(PreferenceKeys.SHOW_STATUS_BAR, false) &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        setContentView(R.layout.activity_main)
        hideStatusBarIfNeeded()

        toolbar = findViewById(R.id.toolbar)
        toolbar.setContentInsetsAbsolute(6, 6)
        setSupportActionBar(toolbar)

        statusTextView   = findViewById(R.id.status_text)
        loadingIndicator = findViewById(R.id.loading_indicator)
        gridView         = findViewById(R.id.grid_view)
        mAdapter         = RoomAdapter(this, mRooms)
        mAdapter.onSetTemperatureRequest = { iseId, currentValue, roomName ->
            showThermostatDialog(iseId, currentValue, roomName)
        }

        gridView.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        gridView.adapter = mAdapter

        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fab_settings)
            ?.setOnClickListener {
                startActivity(Intent(this, SettingsActivity::class.java))
            }

        if (HomeMatic.isLoaded)
            mAdapter.updateRooms(HomeMatic.myRoomList?.rooms ?: emptyList())

        setupNetworkCallback()
        setupPreferenceListener()

        // Sync background-worker state with current preference
        CcuNotificationWorker.schedule(this,
            sharedPreferences.getBoolean(PreferenceKeys.NOTIFY_BACKGROUND, false))

        // Request POST_NOTIFICATIONS permission — non-blocking, shows rationale if needed
        permissionHelper.requestNotificationPermission()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putLong(KEY_DISPLAY_ON, mDisplayOn)
        out.putInt(KEY_SYNC_INTERVAL, oldSyncInterval)
        out.putBoolean(KEY_TIMEOUT_SHOWN, displayTimeoutToastShown)
    }

    override fun onStart() {
        super.onStart()
        isStarted = true
        val filter = IntentFilter().apply {
            addAction(ACTION_UPDATED_DATA); addAction(ACTION_RELOAD_DATA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(broadcastReceiver, filter)

        val interval = sharedPreferences.getString(PreferenceKeys.SYNC_FREQUENCY, "30")?.toIntOrNull() ?: 30
        if (interval != oldSyncInterval) scheduleReloadData(interval)
        loadCcuData()
        scheduleTimeUpdate()
        updateStatusBar()
    }

    override fun onStop() {
        super.onStop()
        isStarted = false
        unregisterReceiver(broadcastReceiver)
        reloadJob?.cancel()
        timeJob?.cancel()
    }

    override fun onPause()  { super.onPause();  isPaused = true  }
    override fun onResume() { super.onResume(); isPaused = false }

    override fun onDestroy() {
        super.onDestroy()
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        networkCallback?.let {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(it)
        }
    }

    // ── Status bar ────────────────────────────────────────────────────────────

    private fun hideStatusBarIfNeeded() {
        if (sharedPreferences.getBoolean(PreferenceKeys.SHOW_STATUS_BAR, false)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun updateStatusBar() {
        val status    = NetworkUtils.getNetworkStatus(this)
        val isLoading = loadJob?.isActive == true
        val progress  = HomeMatic.loadProgress

        if (isLoading && progress != HomeMatic.LoadProgress.DONE && progress != HomeMatic.LoadProgress.ERROR) {
            statusTextView.setTextColor(Color.CYAN)
            statusTextView.text = getString(progress.labelRes)
            return
        }
        val syncInfo = if (HomeMatic.isLoaded && HomeMatic.lastLoadTime > 0L)
            " · ${getString(R.string.status_sync_at, syncTimeFormatter.format(
                java.time.Instant.ofEpochMilli(HomeMatic.lastLoadTime).atZone(ZoneId.systemDefault())
            ))}"
        else ""

        val (color, text) = when {
            !status.isConnected         -> Color.RED    to getString(R.string.status_offline)
            HomeMatic.lastLoadError != null -> Color.YELLOW to getString(R.string.status_error, HomeMatic.lastLoadError)
            HomeMatic.isLoaded          -> Color.GREEN  to getString(R.string.status_ok, status.description, syncInfo)
            else                        -> Color.YELLOW to getString(R.string.status_connecting, status.description)
        }
        statusTextView.setTextColor(color)
        statusTextView.text = text
    }

    private fun setLoading(loading: Boolean) {
        loadingIndicator.visibility = if (loading) View.VISIBLE else View.GONE
    }

    // ── Preference listener ───────────────────────────────────────────────────

    private fun setupPreferenceListener() {
        preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                PreferenceKeys.SYNC_FREQUENCY -> {
                    val i = sharedPreferences.getString(key, "30")?.toIntOrNull() ?: 30
                    if (i != oldSyncInterval) scheduleReloadData(i)
                }
                PreferenceKeys.KEEP_SCREEN_ON ->
                    if (sharedPreferences.getBoolean(key, true))
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                PreferenceKeys.THEME_MODE -> {
                    applyThemeMode(sharedPreferences.getString(key, "system") ?: "system")
                    recreate()
                }
                PreferenceKeys.NOTIFY_BACKGROUND ->
                    CcuNotificationWorker.schedule(this, sharedPreferences.getBoolean(key, false))
                PreferenceKeys.SHOW_STATUS_BAR ->
                    if (!isPaused) showRestartDialog()
            }
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    private fun applyThemeMode(mode: String) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(when (mode) {
            "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            "dark"  -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else    -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        })
    }

    private fun showRestartDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_restart_title))
            .setMessage(getString(R.string.dialog_restart_message))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ── Network ───────────────────────────────────────────────────────────────

    private fun setupNetworkCallback() {
        val cm  = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    updateStatusBar()
                    if (isStarted && sharedPreferences.getBoolean(PreferenceKeys.AUTO_RELOAD_ON_RECONNECT, true))
                        loadCcuData()
                }
            }
            override fun onLost(network: Network) {
                runOnUiThread {
                    setLoading(false)
                    updateStatusBar()
                    showSnackbar(getString(R.string.snackbar_network_lost), Snackbar.LENGTH_LONG)
                }
            }
        }
        cm.registerNetworkCallback(req, networkCallback!!)
    }

    // ── CCU load ──────────────────────────────────────────────────────────────

    private fun loadCcuData() {
        if (loadJob?.isActive == true) return
        setLoading(true)
        loadJob = lifecycleScope.launch {
            val progressJob = launch { while (isActive) { updateStatusBar(); delay(200L) } }
            val result      = HomeMatic.loadDataAsync(this@MainActivity)
            progressJob.cancel()
            result
                .onSuccess { sendBroadcast(Intent(ACTION_UPDATED_DATA).setPackage(PACKAGE_NAME)) }
                .onFailure { e ->
                    setLoading(false); updateStatusBar()
                    if (sharedPreferences.getBoolean(PreferenceKeys.SHOW_RELOAD_POPUPS, true))
                        showSnackbar(getString(R.string.snackbar_load_error, e.message), Snackbar.LENGTH_LONG)
                }
        }
    }

    private fun scheduleReloadData(interval: Int) {
        oldSyncInterval = interval
        reloadJob?.cancel()
        if (interval < 0) return
        reloadJob = lifecycleScope.launch {
            delay(1000L)
            while (isActive) {
                if (!isPaused) sendBroadcast(Intent(ACTION_RELOAD_DATA).setPackage(PACKAGE_NAME))
                delay(interval * 1000L)
            }
        }
    }

    private fun scheduleTimeUpdate() {
        timeJob?.cancel()
        timeJob = lifecycleScope.launch {
            while (isActive) {
                if (!isPaused) { toolbar.title = clockFormatter.format(LocalDateTime.now()); checkDisplayTimeout() }
                delay(1000L)
            }
        }
    }

    // ── Thermostat dialog ─────────────────────────────────────────────────────

    private fun showThermostatDialog(iseId: Int, currentValue: Double, roomName: String) {
        val steps    = ((TEMP_MAX - TEMP_MIN) / TEMP_STEP).toInt()
        val initStep = ((currentValue - TEMP_MIN) / TEMP_STEP).toInt().coerceIn(0, steps)

        val dialogView = layoutInflater.inflate(R.layout.dialog_thermostat, null)
        val labelView  = dialogView.findViewById<TextView>(R.id.thermostat_value_label)
        val seekBar    = dialogView.findViewById<SeekBar>(R.id.thermostat_seekbar)
        seekBar.max      = steps
        seekBar.progress = initStep
        labelView.text   = getString(R.string.thermostat_label, currentValue)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                labelView.text = getString(R.string.thermostat_label, TEMP_MIN + p * TEMP_STEP)
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar)  = Unit
        })

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.thermostat_dialog_title, roomName))
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newTemp = TEMP_MIN + seekBar.progress * TEMP_STEP
                lifecycleScope.launch {
                    HomeMatic.setDatapointValue(iseId, "%.1f".format(newTemp))
                        .onSuccess { showToast(getString(R.string.thermostat_set_ok, newTemp)); delay(500L); loadCcuData() }
                        .onFailure { e -> showSnackbar(getString(R.string.thermostat_set_error, e.message), Snackbar.LENGTH_LONG) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Display timeout ───────────────────────────────────────────────────────

    private fun checkDisplayTimeout() {
        if (!sharedPreferences.getBoolean(PreferenceKeys.DISABLE_DISPLAY, false)) return
        val isOn = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .displays.any { it.state != Display.STATE_OFF }
        when {
            isOn  && mDisplayOn == 0L -> { mDisplayOn = System.currentTimeMillis(); displayTimeoutToastShown = false }
            isOn  && mDisplayOn  > 0L -> {
                val period = sharedPreferences.getString(PreferenceKeys.DISABLE_DISPLAY_PERIOD, "120")?.toLongOrNull() ?: 120L
                if (!displayTimeoutToastShown && mDisplayOn < System.currentTimeMillis() - period * 1000) {
                    displayTimeoutToastShown = true
                    showToast(getString(R.string.toast_display_timeout))
                }
            }
            !isOn && mDisplayOn != 0L -> { mDisplayOn = 0L; displayTimeoutToastShown = false }
        }
    }

    // ── Menu ──────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        listOf(R.id.action_settings, R.id.action_refresh).forEach { id ->
            menu.findItem(id)?.icon?.mutate()?.let { icon ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    icon.colorFilter = BlendModeColorFilter(Color.WHITE, BlendMode.SRC_ATOP)
                else
                    @Suppress("DEPRECATION") icon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP)
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        R.id.action_refresh  -> { loadCcuData(); showToast(getString(R.string.toast_loading)); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun showSnackbar(msg: String, dur: Int = Snackbar.LENGTH_SHORT) =
        Snackbar.make(window.decorView.findViewById(android.R.id.content), msg, dur).show()
}
