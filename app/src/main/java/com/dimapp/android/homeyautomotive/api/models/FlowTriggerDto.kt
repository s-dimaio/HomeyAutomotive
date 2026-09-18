package com.dimapp.android.homeyautomotive.api.models

import com.google.gson.annotations.SerializedName

/**
 * Request body for triggering a Homey flow via the companion proxy endpoint.
 *
 * @property id The UUID of the flow to trigger.
 * @property isAdvanced True if the flow is an Advanced Flow, false for standard.
 */
data class FlowTriggerRequestDto(
    @SerializedName("id") val id: String,
    @SerializedName("isAdvanced") val isAdvanced: Boolean = false
)

/**
 * Response payload from the companion flow trigger endpoint.
 *
 * @property success Whether the flow execution was triggered successfully.
 * @property message Status message from the companion app.
 * @property error Error code or description if execution failed.
 */
data class FlowTriggerResponseDto(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("message") val message: String? = null,
    @SerializedName("error") val error: String? = null
)
