package com.dimapp.android.homeyautomotive.api.models

import com.google.gson.annotations.SerializedName

/**
 * DTO representing a Homey Flow Folder returned by `GET /manager/flow/folder`.
 *
 * Public data class.
 *
 * @property id Unique folder UUID.
 * @property title Human-readable folder title (if set by user).
 * @property name Human-readable folder name (fallback if title is absent).
 */
data class FlowFolderDto(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String? = null,
    @SerializedName("name") val name: String? = null
) {
    /**
     * Returns the best available human-readable folder name.
     *
     * Public property.
     *
     * @return The title, name, or the UUID fallback.
     */
    val effectiveName: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: id
}
