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
 * @property broken Whether the advanced flow has broken cards or connections.
 * @property folder Optional folder UUID from Homey.
 * @property folderName Resolved human-readable folder name for UI display.
 * @property isAdvanced Whether the flow is an Advanced Flow.
 */
data class FlowDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("enabled") val enabled: Boolean? = true,
    @SerializedName("triggerable") val triggerable: Boolean? = false,
    @SerializedName("broken") val broken: Boolean? = false,
    @SerializedName("folder") val folder: String? = null,
    val folderName: String? = null,
    val isAdvanced: Boolean = false
)
