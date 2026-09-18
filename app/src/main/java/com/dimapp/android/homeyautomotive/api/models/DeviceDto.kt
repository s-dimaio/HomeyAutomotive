package com.dimapp.android.homeyautomotive.api.models

import com.google.gson.annotations.SerializedName

/**
 * DTO for a Homey device returned by `GET /manager/devices/device`.
 *
 * Public data class.
 * Mirrors the structure exposed by HomeyAPIV3 REST endpoints.
 * The [capabilitiesObj] map contains each capability keyed by its ID.
 *
 * @property id Unique device UUID.
 * @property name Human-readable device name.
 * @property driverClass Homey device class (e.g. `"garagedoor"`, `"light"`, `"lock"`).
 * @property virtualClass Optional override class set by the user in Homey.
 * @property zone Zone UUID the device belongs to (resolved by [ZoneDto]).
 * @property available Whether the device is reachable/online.
 * @property capabilitiesObj Map of capability ID → [CapabilityDto] for this device.
 * @property iconObj Dynamic icon object; its [IconObjDto.url] points to the driver's default icon.
 * @property iconOverride Name of a Homey built-in icon chosen by the user (e.g. `"garage-door"`).
 *   When non-null, the user-selected icon should be fetched from
 *   `https://my.homey.app/img/devices/{iconOverride}.svg` (no auth required).
 *   Takes priority over [iconObj].
 */
data class DeviceDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("class") val driverClass: String?,
    @SerializedName("virtualClass") val virtualClass: String?,
    @SerializedName("zone") val zone: String?,
    @SerializedName("available") val available: Boolean?,
    @SerializedName("capabilitiesObj") val capabilitiesObj: Map<String, CapabilityDto>?,
    @SerializedName("iconObj") val iconObj: IconObjDto?,
    @SerializedName("iconOverride") val iconOverride: String? = null,
    @SerializedName("hidden") val hidden: Boolean? = false,
    @SerializedName("settings") val settings: SettingsDto? = null
) {
    /**
     * Returns the effective device class, preferring [virtualClass] over [driverClass].
     * This mirrors the logic in `HomeyMCPAdapter.js`.
     *
     * Public property.
     *
     * @return The effective class string, or `null` if neither is set.
     * @example
     * val cls = device.effectiveClass
     */
    val effectiveClass: String?
        get() = virtualClass ?: driverClass
}

/**
 * DTO for the device icon object.
 *
 * Public data class.
 *
 * @property url Relative URL to the icon image (usually SVG).
 */
data class IconObjDto(
    @SerializedName("url") val url: String?
)

/**
 * DTO for a single device capability value.
 *
 * Public data class.
 *
 * @property value Current capability value (can be Boolean, Number, or String).
 * @property type Capability type identifier (e.g. `"boolean"`, `"number"`, `"enum"`).
 * @property title Human-readable capability title (optional, locale-dependent).
 * @property setable Whether this capability supports write operations.
 */
data class CapabilityDto(
    @SerializedName("value") val value: Any?,
    @SerializedName("type") val type: String?,
    @SerializedName("title") val title: Any?,
    @SerializedName("setable") val setable: Boolean?
)

/**
 * DTO for the device settings object.
 *
 * Public data class.
 * Contains configuration specific to the device or app (e.g. `deviceIds` for grouped devices, `climate_exclude`).
 *
 * @property deviceIds Array of child device IDs if this device is a group.
 * @property climateExclude Whether this device is excluded from Homey climate calculations.
 */
data class SettingsDto(
    @SerializedName("deviceIds") val deviceIds: List<String>? = null,
    @SerializedName("climate_exclude") val climateExclude: Boolean? = false
)
