package com.homematic

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.ElementList
import org.simpleframework.xml.Root

// Note: SimpleXML requires mutable (var) fields for reflective deserialization.
// These classes are effectively immutable after parsing — do not mutate them externally.

@Root(name = "datapoint", strict = false)
data class Datapoint(
    @field:Attribute(name = "ise_id", required = false) var ise_id: Int = 0,
    @field:Attribute(name = "name", required = false) var name: String = "",
    @field:Attribute(name = "type", required = false) var type: String = "",
    @field:Attribute(name = "value", required = false) var value: String = "",
    @field:Attribute(name = "valuetype", required = false) var valuetype: Int = 0,
    @field:Attribute(name = "valueunit", required = false) var valueunit: String = "",
    @field:Attribute(name = "timestamp", required = false) var timestamp: Long = 0
) {
    companion object {
        const val TYPE_SET_TEMPERATURE    = "SET_TEMPERATURE"
        const val TYPE_TEMPERATURE        = "TEMPERATURE"
        const val TYPE_ACTUAL_TEMPERATURE = "ACTUAL_TEMPERATURE"
        const val TYPE_HUMIDITY           = "HUMIDITY"
        const val TYPE_STATE              = "STATE"
        const val TYPE_LOWBAT             = "LOWBAT"
        const val TYPE_SABOTAGE           = "SABOTAGE"
        const val TYPE_FAULT_REPORTING    = "FAULT_REPORTING"
    }
}

@Root(name = "channel", strict = false)
data class Channel(
    @field:Attribute(name = "name", required = false) var name: String = "",
    @field:Attribute(name = "ise_id", required = false) var ise_id: Int = 0,
    @field:Attribute(name = "address", required = false) var address: String = "",
    @field:Attribute(name = "direction", required = false) var direction: String = "",
    @field:Attribute(name = "parent_device", required = false) var parent_device: Int = 0,
    @field:Attribute(name = "index", required = false) var index: Int = 0,
    @field:Attribute(name = "group_partner", required = false) var group_partner: String = "",
    @field:Attribute(name = "aes_available", required = false) var aes_available: Boolean = false,
    @field:Attribute(name = "transmission_mode", required = false) var transmission_mode: String = "",
    @field:Attribute(name = "visible", required = false) var visible: Boolean = false,
    @field:Attribute(name = "ready_config", required = false) var ready_config: Boolean = false,
    @field:Attribute(name = "operate", required = false) var operate: Boolean = false,
    @field:ElementList(inline = true, required = false, entry = "datapoint") var datapoints: MutableList<Datapoint> = mutableListOf()
)

@Root(name = "device", strict = false)
data class Device(
    @field:Attribute(name = "name", required = false) var name: String = "",
    @field:Attribute(name = "address", required = false) var address: String = "",
    @field:Attribute(name = "ise_id", required = false) var ise_id: Int = 0,
    @field:Attribute(name = "interface", required = false) var interfaceName: String = "",
    @field:Attribute(name = "device_type", required = false) var device_type: String = "",
    @field:Attribute(name = "ready_config", required = false) var ready_config: Boolean = false,
    @field:ElementList(inline = true, required = false, entry = "channel") var channels: MutableList<Channel> = mutableListOf()
)

@Root(name = "deviceList", strict = false)
data class Devicelist(
    @field:ElementList(inline = true, required = false, entry = "device") var devices: MutableList<Device> = mutableListOf()
)

@Root(name = "room", strict = false)
data class Room(
    @field:Attribute(name = "name", required = false) var name: String = "",
    @field:Attribute(name = "ise_id", required = false) var ise_id: Int = 0,
    @field:ElementList(inline = true, required = false, entry = "channel") var channels: MutableList<Channel> = mutableListOf()
)

@Root(name = "roomList", strict = false)
data class Roomlist(
    @field:ElementList(inline = true, required = false, entry = "room") var rooms: MutableList<Room> = mutableListOf()
)

@Root(name = "stateList", strict = false)
data class Statelist(
    @field:ElementList(inline = true, required = false, entry = "device") var devices: MutableList<Device> = mutableListOf()
)

@Root(name = "notification", strict = false)
data class Notification(
    @field:Attribute(name = "name", required = false) var name: String = "",
    @field:Attribute(name = "type", required = false) var type: String = "",
    @field:Attribute(name = "ise_id", required = false) var ise_id: Int = 0
)

@Root(name = "systemNotification", strict = false)
data class SystemNotification(
    @field:ElementList(inline = true, required = false, entry = "notification") var notifications: MutableList<Notification> = mutableListOf()
)

/**
 * Immutable snapshot of all parsed CCU data.
 * Replaced atomically via AtomicReference in HomeMatic — eliminates partial-update races
 * between the background load thread and the UI thread reading individual fields.
 *
 * All maps are read-only after construction; do not cast to mutable.
 */
data class HmState(
    val deviceList:         Devicelist,
    val roomList:           Roomlist,
    val stateList:          Statelist,
    val systemNotification: SystemNotification?,
    val devices:            Map<Int, Device>,
    val channels:           Map<Int, Channel>,
    val channel2Device:     Map<Int, Int>,
    /**
     * Notification map: key = device name.
     * Includes LOWBAT, SABOTAGE and FAULT_REPORTING entries.
     * Value holds the most-severe notification for that device.
     */
    val notifications:      Map<String, Notification>,
    val loadTime:           Long
)
