package com.dimapp.android.homeyautomotive.repository.models

/**
 * Device classes considered "door/gate/lock" devices.
 */
val DOOR_CLASSES = setOf(
    "garagedoor",
    "lock",
    "doorbell"
)

/** Device classes considered "light" devices. */
val LIGHT_CLASSES = setOf(
    "light"
)

/**
 * Capability used to open/close garage doors and gates.
 * `false` = open, `true` = closed.
 */
const val CAP_GARAGEDOOR = "garagedoor_closed"

/**
 * Fallback capability for devices that use a simple on/off toggle.
 */
const val CAP_ONOFF = "onoff"

/**
 * Domain model for a smart device (Lock/Door/Light), ready for display in AAOS screens.
 *
 * @property id Homey device UUID.
 * @property name Human-readable name (e.g. "Cancello Giardino").
 * @property zoneName Name of the zone/room the device is in.
 * @property isActive For doors: `true` if open. For lights: `true` if on.
 * @property isAvailable Whether the device is currently reachable.
 * @property primaryCapability The capability ID used to toggle this device.
 * @property deviceClass Effective device class.
 * @property iconUrl URL of the icon to be displayed.
 * @property isFavorite `true` if this device is marked as a user favorite.
 * @property isLight `true` if this device is considered a Light.
 * @property isHidden `true` if the device shouldn't be shown.
 * @property isGroupMember `true` if this device is part of a larger group.
 * @property zoneOrder Order index based on depth-first search of zones.
 * @property zoneId Optional UUID of the zone/room the device belongs to.
 * @property isZoneActive Whether the device's zone is currently active (e.g. motion/occupancy).
 * @property temperature Optional temperature reading if the device reports `measure_temperature`.
 */
data class HomeyDevice(
    val id: String,
    val name: String,
    val zoneName: String,
    val isActive: Boolean,
    val isAvailable: Boolean,
    val primaryCapability: String,
    val deviceClass: String?,
    val iconUrl: String?,
    val isFavorite: Boolean,
    val isLight: Boolean,
    val isHidden: Boolean,
    val isGroupMember: Boolean,
    val zoneOrder: Int,
    val zoneId: String? = null,
    val isZoneActive: Boolean = false,
    val temperature: Double? = null
)
