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
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.*

object HomeMatic {

    private const val TAG = "HomeMatic"

    @Volatile var isLoaded      = false
    @Volatile var lastLoadError: String? = null
    @Volatile var loadProgress: LoadProgress = LoadProgress.IDLE

    enum class LoadProgress(val labelRes: Int) {
        IDLE          (R.string.load_idle),
        FETCHING      (R.string.load_fetching),
        FETCH_DONE    (R.string.load_fetch_done),
        PARSING       (R.string.load_parsing),
        PARSE_DONE    (R.string.load_parse_done),
        BUILDING_MAPS (R.string.load_building_maps),
        DONE          (R.string.load_done),
        ERROR         (R.string.load_error)
    }

    private val stateRef = AtomicReference<HmState?>(null)
    val state: HmState? get() = stateRef.get()

    // ── Legacy accessors ─────────────────────────────────────────────────────
    val myDeviceList:         Devicelist?              get() = state?.deviceList
    val myRoomList:           Roomlist?                get() = state?.roomList
    val mySystemNotification: SystemNotification?      get() = state?.systemNotification
    val myStateList:          Statelist?               get() = state?.stateList
    val myDevices:            Map<Int, Device>         get() = state?.devices        ?: emptyMap()
    val myChannels:           Map<Int, Channel>        get() = state?.channels       ?: emptyMap()
    val myChannel2Device:     Map<Int, Int>            get() = state?.channel2Device ?: emptyMap()
    val myNotifications:      Map<String, Notification>get() = state?.notifications  ?: emptyMap()
    val lastLoadTime:         Long                     get() = state?.loadTime       ?: 0L

    const val OUTDOOR_ROOM_DEFAULT          = "Aussen"
    const val MAX_WINDOW_INDICATORS_DEFAULT = 5

    /**
     * The active device profile for the current load cycle.
     * Replaced atomically alongside stateRef so RoomAdapter always reads
     * a profile consistent with the data it's rendering.
     */
    @Volatile var profile: DeviceProfile = DeviceProfile(
        windowDeviceTypes     = DeviceProfile.DEFAULT_WINDOW_DEVICE_TYPES,
        thermostatDeviceTypes = DeviceProfile.DEFAULT_THERMOSTAT_DEVICE_TYPES,
        tempDeviceTypes       = DeviceProfile.DEFAULT_TEMP_DEVICE_TYPES,
        humidityDeviceTypes   = DeviceProfile.DEFAULT_HUMIDITY_DEVICE_TYPES,
        setTempFields         = DeviceProfile.DEFAULT_SET_TEMP_FIELDS,
        actualTempFields      = DeviceProfile.DEFAULT_ACTUAL_TEMP_FIELDS,
        humidityFields        = DeviceProfile.DEFAULT_HUMIDITY_FIELDS,
        stateFields           = DeviceProfile.DEFAULT_STATE_FIELDS,
        lowbatFields          = DeviceProfile.DEFAULT_LOWBAT_FIELDS,
        sabotageFields        = DeviceProfile.DEFAULT_SABOTAGE_FIELDS,
        faultFields           = DeviceProfile.DEFAULT_FAULT_FIELDS,
        stateClosedValues     = DeviceProfile.DEFAULT_STATE_CLOSED_VALUES,
        stateTiltedValues     = DeviceProfile.DEFAULT_STATE_TILTED_VALUES,
        stateOpenValues       = DeviceProfile.DEFAULT_STATE_OPEN_VALUES,
    )

    // ── Legacy compatibility — RoomAdapter still references STATE_DEVICES ─────
    // Delegates to the live profile so old call sites work without changes.
    val STATE_DEVICES: Set<String> get() = profile.windowDeviceTypes

    private fun newPersister() = Persister()
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun requirePrefs(): android.content.SharedPreferences {
        val ctx = appContext ?: error("HomeMatic.init(context) must be called before use")
        return PreferenceManager.getDefaultSharedPreferences(ctx)
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
        val sep = if (url.contains('?')) '&' else '?'
        return "$url${sep}sid=$sid"
    }

    fun getCcuHost(): String =
        appContext?.let { PreferenceManager.getDefaultSharedPreferences(it) }
            ?.getString(PreferenceKeys.CCU_HOST, "homematic-ccu2") ?: "homematic-ccu2"

    fun isCcuHttps(): Boolean =
        appContext?.let { PreferenceManager.getDefaultSharedPreferences(it) }
            ?.getBoolean(PreferenceKeys.CCU_HTTPS, false) ?: false

    fun getMaxWindowIndicators(): Int =
        appContext?.let { PreferenceManager.getDefaultSharedPreferences(it) }
            ?.getString(PreferenceKeys.MAX_WINDOW_INDICATORS, "$MAX_WINDOW_INDICATORS_DEFAULT")
            ?.toIntOrNull()?.coerceIn(1, 10) ?: MAX_WINDOW_INDICATORS_DEFAULT

    fun getOutdoorRoomName(): String =
        appContext?.let { PreferenceManager.getDefaultSharedPreferences(it) }
            ?.getString(PreferenceKeys.OUTDOOR_ROOM_NAME, OUTDOOR_ROOM_DEFAULT)
            ?.takeIf { it.isNotBlank() } ?: OUTDOOR_ROOM_DEFAULT

    // ── SSL ──────────────────────────────────────────────────────────────────

    private fun buildTrustAllSsl(): Pair<SSLSocketFactory, HostnameVerifier> {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sc = SSLContext.getInstance("TLS")
        sc.init(null, trustAll, java.security.SecureRandom())
        return sc.socketFactory to HostnameVerifier { _, _ -> true }
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    suspend fun loadDataAsync(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        init(context)
        val p = requirePrefs()

        return@withContext try {
            isLoaded      = false
            lastLoadError = null
            loadProgress  = LoadProgress.FETCHING
            Log.i(TAG, "=== Load cycle started ===")

            // Reload device profile at the start of every cycle
            val newProfile = DeviceProfile.get(context)
            profile = newProfile
            Log.d(TAG, "Profile loaded: " +
                "windowTypes=${newProfile.windowDeviceTypes.size}, " +
                "setTempFields=${newProfile.setTempFields}, " +
                "actualTempFields=${newProfile.actualTempFields}, " +
                "stateFields=${newProfile.stateFields}"
            )

            val t0 = System.currentTimeMillis()

            val devicelistXml: String
            val roomlistXml:   String
            val sysNotifXml:   String
            val statelistXml:  String

            if (p.getBoolean(PreferenceKeys.TEST_MODE, false)) {
                devicelistXml = readAsset(context, "devicelist.cgi.xml")
                roomlistXml   = readAsset(context, "roomlist.cgi.xml")
                sysNotifXml   = readAsset(context, "systemNotification.cgi.xml")
                statelistXml  = readAsset(context, "statelist.cgi.xml")
                Log.d(TAG, "Assets loaded in ${System.currentTimeMillis() - t0} ms")
            } else {
                val baseUrl   = buildCcuBaseUrl(p)
                val timeoutMs = (p.getString(PreferenceKeys.CONNECTION_TIMEOUT, "5")?.toIntOrNull() ?: 5) * 1000
                val sid       = p.getString(PreferenceKeys.CCU_SID, "") ?: ""
                val trustSelf = p.getBoolean(PreferenceKeys.CCU_TRUST_SELF_SIGNED, false)
                fun url(ep: String) = appendSid("$baseUrl$ep", sid)

                Log.d(TAG, "Fetching: $baseUrl (timeout=${timeoutMs}ms, SID=${sid.isNotBlank()}, trustSelf=$trustSelf)")

                coroutineScope {
                    val tF = System.currentTimeMillis()
                    val dDev  = async(Dispatchers.IO) { fetchCgi(url("devicelist.cgi"),         timeoutMs, trustSelf) }
                    val dRoom = async(Dispatchers.IO) { fetchCgi(url("roomlist.cgi"),            timeoutMs, trustSelf) }
                    val dSys  = async(Dispatchers.IO) { fetchCgi(url("systemNotification.cgi"), timeoutMs, trustSelf) }
                    val dSt   = async(Dispatchers.IO) { fetchCgi(url("statelist.cgi"),           timeoutMs, trustSelf) }
                    devicelistXml = dDev.await()
                    roomlistXml   = dRoom.await()
                    sysNotifXml   = dSys.await()
                    statelistXml  = dSt.await()
                    Log.i(TAG, "All fetches done in ${System.currentTimeMillis() - tF}ms")
                }
            }

            loadProgress = LoadProgress.FETCH_DONE
            val tParse = System.currentTimeMillis()
            loadProgress = LoadProgress.PARSING
            processData(devicelistXml, roomlistXml, sysNotifXml, statelistXml, newProfile, tParse)

            Log.i(TAG, "=== Load cycle complete in ${System.currentTimeMillis() - t0}ms ===")
            loadProgress = LoadProgress.DONE
            Result.success(Unit)

        } catch (e: Exception) {
            loadProgress  = LoadProgress.ERROR
            lastLoadError = e.message ?: appContext?.getString(R.string.error_unknown) ?: "Unknown error"
            Log.e(TAG, "Load failed: ${e.javaClass.simpleName}: ${e.message}", e)
            isLoaded = false
            Result.failure(e)
        }
    }

    private fun processData(
        devicelistXml: String,
        roomlistXml:   String,
        sysNotifXml:   String,
        statelistXml:  String,
        prof:          DeviceProfile,
        tParse:        Long
    ) {
        if (devicelistXml.isBlank() || roomlistXml.isBlank() || statelistXml.isBlank()) {
            throw IllegalStateException(
                appContext?.getString(R.string.error_empty_response) ?: "Empty response from CCU"
            )
        }

        val newDeviceList = deserialize(Devicelist::class.java, devicelistXml)
        val newRoomList   = deserialize(Roomlist::class.java,   roomlistXml)
        val newStateList  = deserialize(Statelist::class.java,  statelistXml)
        val newSysNotif   = if (sysNotifXml.isNotBlank()) deserialize(SystemNotification::class.java, sysNotifXml) else null

        Log.i(TAG, "XML parsed in ${System.currentTimeMillis() - tParse}ms — " +
            "devices=${newDeviceList?.devices?.size ?: 0}, rooms=${newRoomList?.rooms?.size ?: 0}, " +
            "stateDevices=${newStateList?.devices?.size ?: 0}, notifs=${newSysNotif?.notifications?.size ?: 0}")
        loadProgress = LoadProgress.PARSE_DONE
        loadProgress = LoadProgress.BUILDING_MAPS
        val tMaps = System.currentTimeMillis()

        // ── Device map ───────────────────────────────────────────────────────
        val deviceList = newDeviceList?.devices ?: emptyList()
        val newDevices = HashMap<Int, Device>((deviceList.size * 1.4).toInt().coerceAtLeast(16))
        for (dev in deviceList) newDevices[dev.ise_id] = dev

        // ── Channel map — built from statelist ───────────────────────────────
        val stateDevices  = newStateList?.devices ?: emptyList()
        val totalChannels = stateDevices.sumOf { it.channels.size }
        val cap           = (totalChannels * 1.4).toInt().coerceAtLeast(16)
        val newChannels       = HashMap<Int, Channel>(cap)
        val newChannel2Device = HashMap<Int, Int>(cap)
        for (dev in stateDevices) {
            for (chan in dev.channels) {
                newChannels[chan.ise_id]       = chan
                newChannel2Device[chan.ise_id] = dev.ise_id
            }
        }

        // ── Notification map — uses profile field names ───────────────────────
        val notifList     = newSysNotif?.notifications ?: emptyList()
        val newNotifications = HashMap<String, Notification>((notifList.size * 1.4).toInt().coerceAtLeast(8))
        for (n in notifList) {
            val isKnown = n.type in prof.lowbatFields ||
                          n.type in prof.sabotageFields ||
                          n.type in prof.faultFields
            if (!isKnown) continue
            val existing = newNotifications[n.name]
            if (existing == null || notificationSeverity(n.type, prof) > notificationSeverity(existing.type, prof)) {
                newNotifications[n.name] = n
            }
        }
        Log.d(TAG, "Notifications: ${newNotifications.size} entries " +
            "(lowbat=${newNotifications.values.count { it.type in prof.lowbatFields }}, " +
            "sabotage=${newNotifications.values.count { it.type in prof.sabotageFields }}, " +
            "fault=${newNotifications.values.count { it.type in prof.faultFields }})")

        // ── Sort rooms ───────────────────────────────────────────────────────
        newRoomList?.rooms?.sortWith(RoomComparator(getOutdoorRoomName()))
        Log.i(TAG, "Maps built in ${System.currentTimeMillis() - tMaps}ms")

        stateRef.set(HmState(
            deviceList         = newDeviceList ?: Devicelist(),
            roomList           = newRoomList   ?: Roomlist(),
            stateList          = newStateList  ?: Statelist(),
            systemNotification = newSysNotif,
            devices            = newDevices,
            channels           = newChannels,
            channel2Device     = newChannel2Device,
            notifications      = newNotifications,
            loadTime           = System.currentTimeMillis()
        ))
        isLoaded = true
        Log.d(TAG, "State published atomically. isLoaded=true")
    }

    /**
     * Severity ranking using the live profile field sets.
     * sabotageFields > faultFields > lowbatFields > unknown
     */
    fun notificationSeverity(type: String, prof: DeviceProfile = profile): Int = when {
        type in prof.sabotageFields -> 3
        type in prof.faultFields    -> 2
        type in prof.lowbatFields   -> 1
        else                        -> 0
    }

    // ── Mold warning ─────────────────────────────────────────────────────────

    fun getWarning(relativeHumidity: Double, temperature: Double): Int {
        val afs = if (temperature < 10.0)
            3.78 + 0.285 * temperature + 0.0052 * temperature * temperature + 0.0005 * temperature * temperature * temperature
        else {
            val t = temperature - 10.0
            7.62 + 0.524 * t + 0.0131 * t * t + 0.00048 * t * t * t
        }
        fun absHumidity(rh: Double) = (afs * rh) / (100.0 + afs * (100.0 - rh) / 622)

        val p         = appContext?.let { PreferenceManager.getDefaultSharedPreferences(it) }
        val warningRh = p?.getString(PreferenceKeys.MOLD_WARNING_RH, "65")?.toDoubleOrNull() ?: 65.0
        val urgentRh  = p?.getString(PreferenceKeys.MOLD_URGENT_RH,  "75")?.toDoubleOrNull() ?: 75.0

        val cur     = absHumidity(relativeHumidity)
        val warnAbs = absHumidity(warningRh)
        val urgAbs  = absHumidity(urgentRh)

        return when {
            cur < warnAbs -> 0
            cur < urgAbs  -> 1
            else          -> 2
        }
    }

    // ── HTTP / Assets ─────────────────────────────────────────────────────────

    private fun fetchCgi(url: String, timeoutMs: Int = 5000, trustSelf: Boolean = false): String {
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod  = "GET"
            doInput        = true
            connectTimeout = timeoutMs
            readTimeout    = timeoutMs
            setRequestProperty("Accept", "application/xml, text/xml")
            if (this is HttpsURLConnection && trustSelf) {
                val (factory, verifier) = buildTrustAllSsl()
                sslSocketFactory = factory
                hostnameVerifier = verifier
            }
        }
        return try {
            con.connect()
            if (con.responseCode != HttpURLConnection.HTTP_OK)
                throw java.io.IOException(
                    appContext?.getString(R.string.error_http, con.responseCode, url)
                        ?: "HTTP ${con.responseCode} from $url"
                )
            con.inputStream.bufferedReader(Charsets.ISO_8859_1).use { it.readText() }
        } finally {
            con.disconnect()
        }
    }

    internal fun readAsset(context: Context, filename: String): String =
        context.assets.open(filename).bufferedReader(Charsets.ISO_8859_1).use { it.readText() }

    private fun <T> deserialize(type: Class<T>, source: String): T? = try {
        newPersister().read(type, StringReader(source))
    } catch (e: Exception) {
        Log.e(TAG, "XML parse error for ${type.simpleName}: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    // ── setValue.cgi ─────────────────────────────────────────────────────────

    suspend fun setDatapointValue(iseId: Int, value: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val p         = requirePrefs()
            val baseUrl   = buildCcuBaseUrl(p)
            val sid       = p.getString(PreferenceKeys.CCU_SID, "") ?: ""
            val timeoutMs = (p.getString(PreferenceKeys.CONNECTION_TIMEOUT, "5")?.toIntOrNull() ?: 5) * 1000
            val trustSelf = p.getBoolean(PreferenceKeys.CCU_TRUST_SELF_SIGNED, false)
            val url       = appendSid("${baseUrl}setValue.cgi?ise_id=$iseId&value=$value", sid)

            Log.d(TAG, "setValue: ise_id=$iseId value=$value")
            return@withContext try {
                fetchCgi(url, timeoutMs, trustSelf)
                Log.d(TAG, "setValue OK")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "setValue failed: ${e.message}", e)
                Result.failure(e)
            }
        }

    // ── Room sort ─────────────────────────────────────────────────────────────

    private class RoomComparator(private val outdoorName: String) : Comparator<Room> {
        private fun floorOrder(name: String): Int {
            val tokens = name.uppercase().split(Regex("\\s+"))
            for (token in tokens) {
                when {
                    token == "UG" || (token.startsWith("UG") && token.drop(2).all { it.isDigit() }) -> return 0
                    token == "EG" || (token.startsWith("EG") && token.drop(2).all { it.isDigit() }) -> return 1
                    token == "OG" || (token.startsWith("OG") && token.drop(2).all { it.isDigit() }) -> return 2 + (token.drop(2).toIntOrNull() ?: 0)
                    token == "DG" || (token.startsWith("DG") && token.drop(2).all { it.isDigit() }) -> return 20
                }
            }
            return if (name == outdoorName) 99 else 50
        }
        override fun compare(a: Room, b: Room): Int {
            val d = floorOrder(a.name) - floorOrder(b.name)
            return if (d != 0) d else a.name.compareTo(b.name)
        }
    }
}
