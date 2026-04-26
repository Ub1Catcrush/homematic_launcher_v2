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
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    companion object {
        const val PACKAGE_NAME  = "com.tvcs.homematic"
        const val ACTION_UPDATED_DATA = "com.tvcs.homematic.updated_data"
        const val ACTION_RELOAD_DATA  = "com.tvcs.homematic.reload_data"

        // Keys for onSaveInstanceState
        private const val KEY_DISPLAY_ON        = "display_on"
        private const val KEY_OLD_SYNC_INTERVAL = "old_sync_interval"
        private const val KEY_TIMEOUT_SHOWN     = "timeout_shown"
    }

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var toolbar: Toolbar
    private lateinit var gridView: RecyclerView
    private lateinit var statusTextView: TextView
    private lateinit var loadingIndicator: ProgressBar

    private val mRooms = ArrayList<Room>()
    private lateinit var mAdapter: RoomAdapter

    private var isPaused = false
    /** True between onStart() and onStop() — guards network callback from triggering
     *  the initial load before the BroadcastReceiver is registered. */
    private var isStarted = false
    private var reloadJob: Job? = null
    private var timeJob: Job? = null
    // Guard against concurrent loads (timer + network callback firing simultaneously)
    private var loadJob: Job? = null

    private var oldSyncInterval = -1
    private var mDisplayOn = 0L
    private var displayTimeoutToastShown = false

    // Must be a field — SharedPreferences holds only a WeakReference to the listener
    private lateinit var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener

    // DateTimeFormatter is thread-safe; cached as val — never allocate in hot paths
    // Locale.GERMANY: intentionally hardcoded so weekday names are always German on any device
    private val clockFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE dd.MM.yyyy HH:mm:ss  ", Locale.GERMANY)

    // Cached formatter for sync timestamp — called every second via updateNetworkStatus
    private val syncTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss")

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val broadcastReceiver = object : BroadcastReceiver() {
        // onReceive is always called on the main thread — no runOnUiThread needed
        override fun onReceive(context: Context, intent: Intent) {
            val showPopups = sharedPreferences.getBoolean(PreferenceKeys.SHOW_RELOAD_POPUPS, true)
            when (intent.action) {
                ACTION_UPDATED_DATA -> {
                    setLoading(false)
                    mRooms.clear()
                    HomeMatic.myRoomList?.rooms?.let { mRooms.addAll(it) }
                    mAdapter.notifyDataSetChanged()
                    updateNetworkStatus()
                    if (showPopups) showToast(getString(R.string.toast_ui_updated))
                }
                ACTION_RELOAD_DATA -> {
                    if (showPopups) showToast(getString(R.string.toast_loading))
                    loadCcuData()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        HomeMatic.init(this)

        // Apply user-configured day/night theme before setContentView
        applyThemeMode(sharedPreferences.getString(PreferenceKeys.THEME_MODE, "system") ?: "system")

        // Restore instance state before touching UI
        savedInstanceState?.let {
            mDisplayOn             = it.getLong(KEY_DISPLAY_ON, 0L)
            oldSyncInterval        = it.getInt(KEY_OLD_SYNC_INTERVAL, -1)
            displayTimeoutToastShown = it.getBoolean(KEY_TIMEOUT_SHOWN, false)
        }

        // Keep screen on must be set BEFORE setContentView
        if (sharedPreferences.getBoolean(PreferenceKeys.KEEP_SCREEN_ON, true)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // FLAG_FULLSCREEN (API <30) must be set before setContentView
        if (!sharedPreferences.getBoolean(PreferenceKeys.SHOW_STATUS_BAR, false)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
                )
            }
        }

        setContentView(R.layout.activity_main)

        // API 30+: insetsController only available after setContentView
        hideStatusBarIfNeeded()

        toolbar = findViewById(R.id.toolbar)
        toolbar.setTitleMargin(0, 0, 0, 0)
        toolbar.setContentInsetsAbsolute(6, 6)
        setSupportActionBar(toolbar)

        statusTextView  = findViewById(R.id.status_text)
        loadingIndicator = findViewById(R.id.loading_indicator)
        gridView        = findViewById(R.id.grid_view)
        mAdapter        = RoomAdapter(this, mRooms)
        gridView.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        gridView.adapter = mAdapter

        // Show cached data immediately if available, then reload in background
        if (HomeMatic.isLoaded) {
            mRooms.clear()
            HomeMatic.myRoomList?.rooms?.let { mRooms.addAll(it) }
            mAdapter.notifyDataSetChanged()
        }

        // Mini settings gear FAB — compact and always accessible
        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fab_settings)
            .setOnClickListener {
                startActivity(Intent(this, SettingsActivity::class.java))
            }

        setupNetworkCallback()
        setupPreferenceListener()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_DISPLAY_ON, mDisplayOn)
        outState.putInt(KEY_OLD_SYNC_INTERVAL, oldSyncInterval)
        outState.putBoolean(KEY_TIMEOUT_SHOWN, displayTimeoutToastShown)
    }

    private fun hideStatusBarIfNeeded() {
        if (sharedPreferences.getBoolean(PreferenceKeys.SHOW_STATUS_BAR, false)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        // Pre-R: handled via FLAG_FULLSCREEN before setContentView
    }

    private fun setupNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    updateNetworkStatus()
                    // Only auto-reload on RE-connect (not on initial startup).
                    // The initial load is triggered from onStart() after the
                    // BroadcastReceiver is registered, so ACTION_UPDATED_DATA
                    // won't be lost.
                    if (isStarted && sharedPreferences.getBoolean(PreferenceKeys.AUTO_RELOAD_ON_RECONNECT, true)) {
                        loadCcuData()
                    }
                }
            }
            override fun onLost(network: Network) {
                runOnUiThread {
                    setLoading(false)
                    updateNetworkStatus()
                    showSnackbar(getString(R.string.snackbar_network_lost), Snackbar.LENGTH_LONG)
                }
            }
        }
        cm.registerNetworkCallback(request, networkCallback!!)
    }

    private fun updateNetworkStatus() {
        val status = NetworkUtils.getNetworkStatus(this)
        val progress = HomeMatic.loadProgress

        // During active loading: show current stage instead of generic "connecting"
        val isLoading = loadJob?.isActive == true
        if (isLoading && progress != HomeMatic.LoadProgress.DONE &&
            progress != HomeMatic.LoadProgress.ERROR
        ) {
            statusTextView.setTextColor(Color.CYAN)
            statusTextView.text = getString(progress.labelRes)
            return
        }

        val syncInfo = if (HomeMatic.isLoaded && HomeMatic.lastLoadTime > 0L) {
            val timeStr = syncTimeFormatter.format(
                java.time.Instant.ofEpochMilli(HomeMatic.lastLoadTime)
                    .atZone(java.time.ZoneId.systemDefault())
            )
            " · ${getString(R.string.status_sync_at, timeStr)}"
        } else ""

        val (color, text) = when {
            !status.isConnected ->
                Pair(Color.RED,    getString(R.string.status_offline))
            HomeMatic.lastLoadError != null ->
                Pair(Color.YELLOW, getString(R.string.status_error, HomeMatic.lastLoadError))
            HomeMatic.isLoaded ->
                Pair(Color.GREEN,  getString(R.string.status_ok, status.description, syncInfo))
            else ->
                Pair(Color.YELLOW, getString(R.string.status_connecting, status.description))
        }
        statusTextView.setTextColor(color)
        statusTextView.text = text
    }

    private fun setLoading(loading: Boolean) {
        loadingIndicator.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun setupPreferenceListener() {
        preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                PreferenceKeys.SYNC_FREQUENCY -> {
                    val interval = sharedPreferences
                        .getString(PreferenceKeys.SYNC_FREQUENCY, "30")?.toIntOrNull() ?: 30
                    if (interval != oldSyncInterval) {
                        scheduleReloadData(interval)
                    }
                }
                PreferenceKeys.KEEP_SCREEN_ON -> {
                    if (sharedPreferences.getBoolean(PreferenceKeys.KEEP_SCREEN_ON, true)) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                PreferenceKeys.SHOW_STATUS_BAR -> {
                    // Only show the dialog when the Activity is actually visible
                    // (listener fires even when Activity is stopped)
                    if (!isPaused) {
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.dialog_restart_title))
                            .setMessage(getString(R.string.dialog_restart_message))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
                PreferenceKeys.THEME_MODE -> {
                    val mode = sharedPreferences.getString(PreferenceKeys.THEME_MODE, "system") ?: "system"
                    applyThemeMode(mode)
                    // recreate so the new theme is applied to already-inflated views
                    recreate()
                }
            }
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    /**
     * Applies the day/night mode based on user preference.
     * Call before setContentView on first launch; recreate() when changed at runtime.
     */
    private fun applyThemeMode(mode: String) {
        val nightMode = when (mode) {
            "light"  -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            "dark"   -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else     -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    override fun onStart() {
        super.onStart()
        isStarted = true
        val filter = IntentFilter().apply {
            addAction(ACTION_UPDATED_DATA)
            addAction(ACTION_RELOAD_DATA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(broadcastReceiver, filter)
        }

        // Read interval once — pass to scheduleReloadData to avoid double Prefs read
        val syncInterval = sharedPreferences
            .getString(PreferenceKeys.SYNC_FREQUENCY, "30")?.toIntOrNull() ?: 30
        if (syncInterval != oldSyncInterval) {
            scheduleReloadData(syncInterval)
        }
        // Trigger an immediate load now that the BroadcastReceiver is registered.
        // This ensures ACTION_UPDATED_DATA is never sent to an unregistered receiver.
        loadCcuData()
        scheduleTimeUpdate()
        updateNetworkStatus()
    }

    override fun onStop() {
        super.onStop()
        isStarted = false
        unregisterReceiver(broadcastReceiver)
        reloadJob?.cancel()
        timeJob?.cancel()
    }

    override fun onPause() {
        super.onPause()
        isPaused = true
    }

    override fun onResume() {
        super.onResume()
        isPaused = false
    }

    override fun onDestroy() {
        super.onDestroy()
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        networkCallback?.let {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(it)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        listOf(R.id.action_settings, R.id.action_refresh).forEach { id ->
            menu.findItem(id)?.icon?.mutate()?.let { icon ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    icon.colorFilter = BlendModeColorFilter(Color.WHITE, BlendMode.SRC_ATOP)
                } else {
                    @Suppress("DEPRECATION")
                    icon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP)
                }
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_refresh -> {
                loadCcuData()
                showToast(getString(R.string.toast_loading))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadCcuData() {
        if (loadJob?.isActive == true) return
        setLoading(true)
        loadJob = lifecycleScope.launch {
            // Poll HomeMatic.loadProgress every 200ms so the status bar shows live stage updates
            val progressJob = launch {
                while (isActive) {
                    updateNetworkStatus()
                    delay(200L)
                }
            }

            val result = HomeMatic.loadDataAsync(this@MainActivity)

            progressJob.cancel()  // stop polling once load is complete

            result.onSuccess {
                sendBroadcast(Intent(ACTION_UPDATED_DATA).setPackage(PACKAGE_NAME))
            }.onFailure { e ->
                setLoading(false)
                updateNetworkStatus()
                if (sharedPreferences.getBoolean(PreferenceKeys.SHOW_RELOAD_POPUPS, true)) {
                    showSnackbar(
                        getString(R.string.snackbar_load_error, e.message),
                        Snackbar.LENGTH_LONG
                    )
                }
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
                if (!isPaused) {
                    toolbar.title = clockFormatter.format(LocalDateTime.now())
                    checkDisplayTimeout()
                }
                delay(1000L)
            }
        }
    }

    private fun checkDisplayTimeout() {
        if (!sharedPreferences.getBoolean(PreferenceKeys.DISABLE_DISPLAY, false)) return

        val isOn = isDisplayOn()
        when {
            isOn && mDisplayOn == 0L -> {
                mDisplayOn = System.currentTimeMillis()
                displayTimeoutToastShown = false
            }
            isOn && mDisplayOn > 0L -> {
                val period = sharedPreferences
                    .getString(PreferenceKeys.DISABLE_DISPLAY_PERIOD, "120")
                    ?.toLongOrNull() ?: 120L
                if (!displayTimeoutToastShown &&
                    mDisplayOn < System.currentTimeMillis() - period * 1000
                ) {
                    displayTimeoutToastShown = true
                    showToast(getString(R.string.toast_display_timeout))
                }
            }
            !isOn && mDisplayOn != 0L -> {
                mDisplayOn = 0L
                displayTimeoutToastShown = false
            }
        }
    }

    private fun isDisplayOn(): Boolean {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.displays.any { it.state != Display.STATE_OFF }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showSnackbar(message: String, duration: Int = Snackbar.LENGTH_SHORT) {
        val view = window.decorView.findViewById<View>(android.R.id.content)
        Snackbar.make(view, message, duration).show()
    }
}
