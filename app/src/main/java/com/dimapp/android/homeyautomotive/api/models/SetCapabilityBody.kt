package com.dimapp.android.homeyautomotive.api.models

import com.google.gson.annotations.SerializedName

/**
 * Request body for `PUT /manager/devices/device/{deviceId}/capability/{capabilityId}`.
 *
 * Public data class.
 * Sent to Homey REST API to set a new capability value on a device.
 * The [value] type depends on the capability: Boolean for on/off switches,
 * Number for dimmers, String for enum capabilities.
 *
 * Example usage:
 * ```
 * // Open a garage door (set garagedoor_closed = false)
 * val body = SetCapabilityBody(value = false)
 *
 * // Turn on a light
 * val body = SetCapabilityBody(value = true)
 *
 * // Set a dimmer to 50%
 * val body = SetCapabilityBody(value = 0.5)
 * ```
 *
 * @property value The new value for the capability. Must match the capability's type.
 * @property opts Optional parameters for the operation (e.g. transactionId).
 */
data class SetCapabilityBody(
    @SerializedName("value") val value: Any,
    @SerializedName("opts") val opts: Map<String, Any>? = null
)
