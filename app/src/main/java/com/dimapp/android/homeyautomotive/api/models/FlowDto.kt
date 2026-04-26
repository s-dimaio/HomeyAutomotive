package com.dimapp.android.homeyautomotive.api.models

import com.google.gson.annotations.SerializedName

/**
 * DTO for a Homey Flow returned by `GET /manager/flow/flow`.
 *
 * Public data class.
 *
 * @property id Unique flow UUID.
 * @property name Human-readable flow name.
 * @property enabled Whether the flow is currently enabled.
 * @property triggerable Whether the flow can be manually triggered via the API.
 *   Only flows with a "This flow is started" trigger card return `true`.
 * @property folder Optional folder name for organisation.
 */
data class FlowDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("enabled") val enabled: Boolean?,
    @SerializedName("triggerable") val triggerable: Boolean?,
    @SerializedName("folder") val folder: String?
)
