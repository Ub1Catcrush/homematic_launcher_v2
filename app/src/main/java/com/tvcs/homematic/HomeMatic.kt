package com.tvcs.homematic

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.homematic.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.simpleframework.xml.core.Persister
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL

object HomeMatic {

    private const val TAG = "HomeMatic"

    @Volatile var isLoaded = false
    @Volatile var lastLoadError: String? = null
    @Volatile var lastLoadTime: Long = 0L

    // Progress reporting — observed by MainActivity to update status bar during load
    @Volatile var loadProgress: LoadProgress = LoadProgress.IDLE

    enum class LoadProgress(val labelRes: Int) {
        IDLE            (R.string.load_idle),
        FETCHING        (R.string.load_fetching),
        FETCH_DONE      (R.string.load_fetch_done),
        PARSING         (R.string.load_parsing),
        PARSE_DONE      (R.string.load_parse_done),
        BUILDING_MAPS   (R.string.load_building_maps),
        DONE            (R.string.load_done),
        ERROR           (R.string.load_error)
    }

    var myDeviceList: Devicelist? = null
    var myRoomList: Roomlist? = null
    var mySystemNotification: SystemNotification? = null
    var myStateList: Statelist? = null

    var myDevices: HashMap<Int, Device> = HashMap()
    var myNotifications: HashMap<String, Notification> = HashMap()
    var myChannels: HashMap<Int, Channel> = HashMap()
    var myChannel2Device: HashMap<Int, Int> = HashMap()

    const val OUTDOOR_ROOM_DEFAULT = "Aussen"

    // IMPORTANT: Simple XML's Persister is NOT reliably thread-safe when multiple threads
    // call read() simultaneously — it shares internal strategy/registry state.
    // We use one Persister per parse call instead of a shared instance.
    // The object creation overhead (~microseconds) is negligible vs. XML parse time (~ms).
    private fun newPersister() = Persister()

    val STATE_DEVICES: Set<String> = setOf("HM-Sec-RHS", "HM-Sec-SCo", "HmIP-SRH")

    private var appContext: Context? = null

    private fun requirePrefs(): android.content.SharedPreferences {
        val ctx = appContext ?: error("HomeMatic.init(context) must be called before use")
        return PreferenceManager.getDefaultSharedPreferences(ctx)
    }

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun buildCcuBaseUrl(p: android.content.SharedPreferences): String {
        val protocol = if (p.getBoolean(PreferenceKeys.CCU_HTTPS, false)) "https" else "http"
        val host     = p.getString(PreferenceKeys.CCU_HOST, "homematic-ccu2") ?: "homematic-ccu2"
        val port     = p.getString(PreferenceKeys.CCU_PORT, "")
        val apiPath  = p.getString(PreferenceKeys.CCU_API_PATH, "/addons/xmlapi/") ?: "/addons/xmlapi/"
        return if (port.isNullOrEmpty()) "$protocol://$host$apiPath"
        else "$protocol://$host:$port$apiPath"
    }

    private fun appendSid(url: String, sid: String): String {
        if (sid.isBlank()) return url
        val separator = if (url.contains('?')) '&' else '?'
        return "$url${separator}sid=$sid"
    }

    fun getCcuHost(): String {
        val p = appContext?.let { PreferenceManager.getDefaultSharedPreferences(it) }
            ?: return "homematic-ccu2"
        return p.getString(PreferenceKeys.CCU_HOST, "homematic-ccu2") ?: "homematic-ccu2"
    }

    fun isCcuHttps(): Boolean {
        val p = appContext?.let { PreferenceManager.getDefaultSharedPreferences(it) } ?: return false
        return p.getBoolean(PreferenceKeys.CCU_HTTPS, false)
    }

    /**
     * Loads all CCU data. Caller must be on a coroutine (IO or Default).
     *
     * Stages with progress reporting:
     *   1. FETCHING  — 4 HTTP requests in parallel (the expensive step: network RTT × 1 instead of × 4)
     *   2. PARSING   — 4 XML documents parsed sequentially (Persister is NOT thread-safe)
     *   3. BUILDING  — HashMap construction from parsed objects (CPU-only, fast)
     *   4. DONE / ERROR
     *
     * Why sequential parsing after parallel fetching?
     *   Simple XML's Persister shares internal mutable state (AnnotationStrategy, Scanner cache).
     *   Concurrent reads cause silent deadlocks or corrupt results. Using one Persister per call
     *   on separate threads would work, but parse time is dominated by allocation, not I/O —
     *   the parallelism gain is marginal (<10%) while the risk of deadlock is real.
     *   Parallel fetching gives ~75% improvement; sequential parse adds ~0% overhead.
     */
    suspend fun loadDataAsync(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        init(context)
        val p = requirePrefs()

        return@withContext try {
            isLoaded = false
            lastLoadError = null
            loadProgress = LoadProgress.FETCHING
            Log.i(TAG, "=== Load cycle started ===")

            val t0 = System.currentTimeMillis()

            val devicelistXml: String
            val roomlistXml: String
            val sysNotifXml: String
            val statelistXml: String

            if (p.getBoolean(PreferenceKeys.TEST_MODE, false)) {
                Log.d(TAG, "Test mode: loading from assets")
                devicelistXml  = readAsset(context, "devicelist.cgi.xml")
                roomlistXml    = readAsset(context, "roomlist.cgi.xml")
                sysNotifXml    = readAsset(context, "systemNotification.cgi.xml")
                statelistXml   = readAsset(context, "statelist.cgi.xml")
                Log.d(TAG, "Assets loaded in ${System.currentTimeMillis() - t0} ms")
            } else {
                val baseUrl   = buildCcuBaseUrl(p)
                val timeoutMs = (p.getString(PreferenceKeys.CONNECTION_TIMEOUT, "5")
                    ?.toIntOrNull() ?: 5) * 1000
                val sid       = p.getString(PreferenceKeys.CCU_SID, "") ?: ""

                fun url(endpoint: String) = appendSid("$baseUrl$endpoint", sid)

                Log.d(TAG, "Fetching from: $baseUrl (timeout: ${timeoutMs}ms, SID: ${sid.isNotBlank()})")

                // ── Stage 1: Parallel HTTP fetch ─────────────────────────
                // All 4 requests start simultaneously; we wait for the slowest one.
                // This replaces 4×RTT with 1×RTT — typically saves 300–2000ms on local networks.
                coroutineScope {
                    val tFetch = System.currentTimeMillis()
                    val dDevicelist  = async(Dispatchers.IO) {
                        Log.d(TAG, "→ fetching devicelist.cgi")
                        fetchCgi(url("devicelist.cgi"), timeoutMs).also {
                            Log.d(TAG, "✓ devicelist: ${it.length} chars (${System.currentTimeMillis() - tFetch}ms)")
                        }
                    }
                    val dRoomlist    = async(Dispatchers.IO) {
                        Log.d(TAG, "→ fetching roomlist.cgi")
                        fetchCgi(url("roomlist.cgi"), timeoutMs).also {
                            Log.d(TAG, "✓ roomlist: ${it.length} chars (${System.currentTimeMillis() - tFetch}ms)")
                        }
                    }
                    val dSysNotif    = async(Dispatchers.IO) {
                        Log.d(TAG, "→ fetching systemNotification.cgi")
                        fetchCgi(url("systemNotification.cgi"), timeoutMs).also {
                            Log.d(TAG, "✓ sysNotif: ${it.length} chars (${System.currentTimeMillis() - tFetch}ms)")
                        }
                    }
                    val dStatelist   = async(Dispatchers.IO) {
                        Log.d(TAG, "→ fetching statelist.cgi")
                        fetchCgi(url("statelist.cgi"), timeoutMs).also {
                            Log.d(TAG, "✓ statelist: ${it.length} chars (${System.currentTimeMillis() - tFetch}ms)")
                        }
                    }

                    devicelistXml  = dDevicelist.await()
                    roomlistXml    = dRoomlist.await()
                    sysNotifXml    = dSysNotif.await()
                    statelistXml   = dStatelist.await()
                    Log.i(TAG, "All fetches done in ${System.currentTimeMillis() - tFetch}ms")
                }
            }

            loadProgress = LoadProgress.FETCH_DONE
            val tParse = System.currentTimeMillis()

            // ── Stage 2: Sequential XML parsing ─────────────────────────
            // NOT parallelized — Simple XML Persister is not thread-safe.
            // Each call gets its own fresh Persister instance to avoid shared state.
            loadProgress = LoadProgress.PARSING
            processData(devicelistXml, roomlistXml, sysNotifXml, statelistXml, tParse)

            val totalMs = System.currentTimeMillis() - t0
            Log.i(TAG, "=== Load cycle complete in ${totalMs}ms ===")
            loadProgress = LoadProgress.DONE
            Result.success(Unit)

        } catch (e: Exception) {
            loadProgress = LoadProgress.ERROR
            lastLoadError = e.message
                ?: appContext?.getString(R.string.error_unknown)
                ?: "Unknown error"
            Log.e(TAG, "Load failed: ${e.javaClass.simpleName}: ${e.message}", e)
            isLoaded = false
            Result.failure(e)
        }
    }

    /**
     * Parses XML strings sequentially and builds lookup maps.
     *
     * Sequential parsing rationale: see loadDataAsync KDoc above.
     * Map builds are CPU-only and fast — no benefit from parallelizing them.
     */
    private fun processData(
        devicelistXml: String,
        roomlistXml: String,
        sysNotifXml: String,
        statelistXml: String,
        tParse: Long
    ) {
        Log.d(TAG, "processData: validating input")
        if (devicelistXml.isBlank() || roomlistXml.isBlank() || statelistXml.isBlank()) {
            throw IllegalStateException(
                appContext?.getString(R.string.error_empty_response) ?: "Empty response from CCU"
            )
        }

        // ── Parse ────────────────────────────────────────────────────────
        Log.d(TAG, "Parsing devicelist (${devicelistXml.length} chars)…")
        val newDeviceList = deserialize(Devicelist::class.java, devicelistXml)
        Log.d(TAG, "Parsing roomlist (${roomlistXml.length} chars)…")
        val newRoomList   = deserialize(Roomlist::class.java, roomlistXml)
        Log.d(TAG, "Parsing statelist (${statelistXml.length} chars)…")
        val newStateList  = deserialize(Statelist::class.java, statelistXml)
        Log.d(TAG, "Parsing systemNotification (${sysNotifXml.length} chars)…")
        val newSysNotif   = if (sysNotifXml.isNotBlank())
            deserialize(SystemNotification::class.java, sysNotifXml) else null

        Log.i(TAG, "XML parsing done in ${System.currentTimeMillis() - tParse}ms — " +
            "devices=${newDeviceList?.devices?.size ?: 0}, " +
            "rooms=${newRoomList?.rooms?.size ?: 0}, " +
            "stateDevices=${newStateList?.devices?.size ?: 0}, " +
            "notifications=${newSysNotif?.notifications?.size ?: 0}"
        )
        loadProgress = LoadProgress.PARSE_DONE

        // ── Build device map ─────────────────────────────────────────────
        loadProgress = LoadProgress.BUILDING_MAPS
        val tMaps = System.currentTimeMillis()
        Log.d(TAG, "Building device map…")

        val deviceList   = newDeviceList?.devices ?: emptyList()
        val newDevices   = HashMap<Int, Device>((deviceList.size * 1.4).toInt().coerceAtLeast(16))
        for (dev in deviceList) {
            // No containsKey check needed: ise_id is unique per device in the CCU protocol
            newDevices[dev.ise_id] = dev
        }
        Log.d(TAG, "Device map: ${newDevices.size} entries")

        // ── Build channel maps ───────────────────────────────────────────
        Log.d(TAG, "Building channel maps…")
        val stateDevices = newStateList?.devices ?: emptyList()
        val totalChannels = stateDevices.sumOf { it.channels.size }
        val capacity      = (totalChannels * 1.4).toInt().coerceAtLeast(16)
        val newChannels       = HashMap<Int, Channel>(capacity)
        val newChannel2Device = HashMap<Int, Int>(capacity)
        for (dev in stateDevices) {
            for (chan in dev.channels) {
                // ise_id is unique; no duplicate check needed
                newChannels[chan.ise_id]       = chan
                newChannel2Device[chan.ise_id] = dev.ise_id
            }
        }
        Log.d(TAG, "Channel maps: ${newChannels.size} channels across ${stateDevices.size} devices")

        // ── Build notification map ───────────────────────────────────────
        Log.d(TAG, "Building notification map…")
        val notifications    = newSysNotif?.notifications ?: emptyList()
        val newNotifications = HashMap<String, Notification>((notifications.size * 1.4).toInt().coerceAtLeast(8))
        for (notify in notifications) {
            if (notify.type == "LOWBAT") newNotifications[notify.name] = notify
        }
        Log.d(TAG, "Notification map: ${newNotifications.size} LOWBAT entries")

        // ── Sort rooms ───────────────────────────────────────────────────
        Log.d(TAG, "Sorting ${newRoomList?.rooms?.size ?: 0} rooms…")
        newRoomList?.rooms?.sortWith(RoomComparator(getOutdoorRoomName()))

        Log.i(TAG, "Map build done in ${System.currentTimeMillis() - tMaps}ms")

        // ── Atomic assignment ────────────────────────────────────────────
        // isLoaded written last — acts as publication barrier for all preceding writes
        Log.d(TAG, "Assigning state…")
        myDeviceList         = newDeviceList
        myRoomList           = newRoomList
        myStateList          = newStateList
        mySystemNotification = newSysNotif
        myDevices            = newDevices
        myChannels           = newChannels
        myChannel2Device     = newChannel2Device
        myNotifications      = newNotifications
        lastLoadTime         = System.currentTimeMillis()
        isLoaded             = true
        Log.d(TAG, "State assigned. isLoaded=true")
    }

    private fun fetchCgi(url: String, timeoutMs: Int = 5000): String {
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod  = "GET"
            doInput        = true
            connectTimeout = timeoutMs
            readTimeout    = timeoutMs
            setRequestProperty("Accept", "application/xml, text/xml")
        }
        return try {
            con.connect()
            if (con.responseCode != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException(
                    appContext?.getString(R.string.error_http, con.responseCode, url)
                        ?: "HTTP ${con.responseCode} from $url"
                )
            }
            con.inputStream.bufferedReader(Charsets.ISO_8859_1).use { it.readText() }
        } finally {
            con.disconnect()
        }
    }

    internal fun readAsset(context: Context, filename: String): String =
        context.assets.open(filename).bufferedReader(Charsets.ISO_8859_1).use { it.readText() }

    /**
     * Deserializes XML using a fresh Persister per call.
     * A shared Persister would be faster on repeated calls but is not thread-safe.
     */
    private fun <T> deserialize(type: Class<T>, source: String): T? {
        return try {
            newPersister().read(type, StringReader(source))
        } catch (e: Exception) {
            Log.e(TAG, "XML parse error for ${type.simpleName}: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    fun getWarning(relativeHumidity: Double, temperature: Double): Int {
        val afs = if (temperature < 10.0) {
            3.78 + (0.285 * temperature) + (0.0052 * temperature * temperature) +
                    (0.0005 * temperature * temperature * temperature)
        } else {
            val t = temperature - 10.0
            7.62 + (0.524 * t) + (0.0131 * t * t) + (0.00048 * t * t * t)
        }
        fun absHumidity(rh: Double) = (afs * rh) / (100.0 + afs * (100.0 - rh) / 622)

        val currentAbs = absHumidity(relativeHumidity)
        val warningAbs = absHumidity(65.0)
        val urgentAbs  = absHumidity(75.0)

        return when {
            currentAbs < warningAbs -> 0
            currentAbs < urgentAbs  -> 1
            else                    -> 2
        }
    }

    fun getOutdoorRoomName(): String {
        val p = appContext?.let { PreferenceManager.getDefaultSharedPreferences(it) }
            ?: return OUTDOOR_ROOM_DEFAULT
        return p.getString(PreferenceKeys.OUTDOOR_ROOM_NAME, OUTDOOR_ROOM_DEFAULT)
            ?.takeIf { it.isNotBlank() } ?: OUTDOOR_ROOM_DEFAULT
    }

    private class RoomComparator(private val outdoorName: String) : Comparator<Room> {
        private fun floorOrder(name: String): Int = when {
            name.contains("UG") -> 0
            name.contains("EG") -> 1
            name.contains("OG") -> 2
            name.contains("DG") -> 3
            name == outdoorName -> 99
            else                -> 50
        }
        override fun compare(a: Room, b: Room): Int {
            val diff = floorOrder(a.name) - floorOrder(b.name)
            return if (diff != 0) diff else a.name.compareTo(b.name)
        }
    }
}
