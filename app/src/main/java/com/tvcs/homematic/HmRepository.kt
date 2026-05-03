package com.tvcs.homematic

import android.content.Context
import com.homematic.*

/**
 * HmRepository — abstracts all CCU data access behind an interface.
 *
 * This decouples UI components (MainActivity, RoomAdapter, Widget) from the
 * concrete HomeMatic singleton, making them unit-testable without a real CCU
 * or Android device.
 *
 * Production code uses [HomeMatic] as the implementation (via [HomeMatic.asRepository]).
 * Tests inject a [FakeHmRepository] or a Mockito mock.
 *
 * ── Pattern ──────────────────────────────────────────────────────────────────
 *   class MyViewModel(private val repo: HmRepository) { … }
 *
 *   // In production Activity:
 *   val vm = MyViewModel(HomeMatic.asRepository())
 *
 *   // In unit test:
 *   val vm = MyViewModel(FakeHmRepository(state = testState))
 */
interface HmRepository {

    val isLoaded: Boolean
    val lastLoadError: String?
    val loadProgress: HomeMatic.LoadProgress
    val profile: DeviceProfile

    val state: HmState?
    val myRoomList: Roomlist?
    val myDevices: Map<Int, Device>
    val myChannels: Map<Int, Channel>
    val myChannel2Device: Map<Int, Int>
    val myNotifications: Map<String, Notification>
    val lastLoadTime: Long

    fun getOutdoorRoomName(): String
    fun getMaxWindowIndicators(): Int
    fun getWarning(relativeHumidity: Double, temperature: Double): Int
    fun notificationSeverity(type: String, prof: DeviceProfile = profile): Int

    suspend fun loadDataAsync(context: Context): Result<Unit>
    suspend fun setDatapointValue(iseId: Int, value: String): Result<Unit>
}

/**
 * Thin adapter that delegates every call to the [HomeMatic] singleton.
 * Zero overhead — all properties are direct references.
 */
private class HomematicRepositoryAdapter : HmRepository {
    override val isLoaded        get() = HomeMatic.isLoaded
    override val lastLoadError   get() = HomeMatic.lastLoadError
    override val loadProgress    get() = HomeMatic.loadProgress
    override val profile         get() = HomeMatic.profile
    override val state           get() = HomeMatic.state
    override val myRoomList      get() = HomeMatic.myRoomList
    override val myDevices       get() = HomeMatic.myDevices
    override val myChannels      get() = HomeMatic.myChannels
    override val myChannel2Device get() = HomeMatic.myChannel2Device
    override val myNotifications  get() = HomeMatic.myNotifications
    override val lastLoadTime     get() = HomeMatic.lastLoadTime

    override fun getOutdoorRoomName()   = HomeMatic.getOutdoorRoomName()
    override fun getMaxWindowIndicators() = HomeMatic.getMaxWindowIndicators()
    override fun getWarning(relativeHumidity: Double, temperature: Double) = HomeMatic.getWarning(relativeHumidity, temperature)
    override fun notificationSeverity(type: String, prof: DeviceProfile) =
        HomeMatic.notificationSeverity(type, prof)

    override suspend fun loadDataAsync(context: Context) = HomeMatic.loadDataAsync(context)
    override suspend fun setDatapointValue(iseId: Int, value: String) =
        HomeMatic.setDatapointValue(iseId, value)
}

/** Returns the production [HmRepository] backed by the [HomeMatic] singleton. */
fun HomeMatic.asRepository(): HmRepository = HomematicRepositoryAdapter()

// ── Test double — use in unit tests ──────────────────────────────────────────

/**
 * Simple fake repository for unit tests. Pre-load with any [HmState] you need.
 *
 * Example:
 *   val repo = FakeHmRepository(
 *       state  = buildTestState(rooms = listOf(testRoom)),
 *       loaded = true
 *   )
 *   // then inject into the class under test
 */
class FakeHmRepository(
    state: HmState? = null,
    override val isLoaded: Boolean = state != null,
    override val lastLoadError: String? = null,
    override val loadProgress: HomeMatic.LoadProgress = HomeMatic.LoadProgress.DONE,
    override val profile: DeviceProfile = DeviceProfile(
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
    ),
    private val outdoorName: String = "Aussen",
    private val maxIndicators: Int = 5,
    private val moldWarningResult: Int = 0,
) : HmRepository {

    private val _state = state
    override val state get() = _state
    override val myRoomList       get() = _state?.roomList
    override val myDevices        get() = _state?.devices        ?: emptyMap()
    override val myChannels       get() = _state?.channels       ?: emptyMap()
    override val myChannel2Device get() = _state?.channel2Device ?: emptyMap()
    override val myNotifications  get() = _state?.notifications  ?: emptyMap()
    override val lastLoadTime     get() = _state?.loadTime       ?: 0L

    var loadResult: Result<Unit> = Result.success(Unit)
    var setValueResult: Result<Unit> = Result.success(Unit)

    override fun getOutdoorRoomName() = outdoorName
    override fun getMaxWindowIndicators() = maxIndicators
    override fun getWarning(relativeHumidity: Double, temperature: Double) = moldWarningResult
    override fun notificationSeverity(type: String, prof: DeviceProfile): Int = when {
        type in prof.sabotageFields -> 3
        type in prof.faultFields    -> 2
        type in prof.lowbatFields   -> 1
        else                        -> 0
    }

    override suspend fun loadDataAsync(context: Context) = loadResult
    override suspend fun setDatapointValue(iseId: Int, value: String) = setValueResult
}
