package com.dimapp.android.homeyautomotive.api.models

import com.google.gson.annotations.SerializedName

/**
 * DTO for a Homey zone returned by `GET /manager/zones/zone`.
 *
 * Public data class.
 *
 * @property id Unique zone UUID.
 * @property name Human-readable zone name (e.g. "Living Room", "Garage").
 * @property parent UUID of the parent zone, or `null` for top-level zones.
 * @property order Display order index within its parent zone.
 */
data class ZoneDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("parent") val parent: String?,
    @SerializedName("sortIndex") val sortIndex: Int?
)
