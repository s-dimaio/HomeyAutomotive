package com.dimapp.android.homeyautomotive.api.models

import com.google.gson.annotations.SerializedName

/**
 * Data Transfer Object representing the user's profile and settings in Homey Cloud.
 *
 * Public data class.
 * Retrieved from `/manager/users/user/me`.
 */
data class UserMeDto(
    @SerializedName("id")
    val id: String?,
    @SerializedName("name")
    val name: String?,
    @SerializedName("properties")
    val properties: UserPropertiesDto?
)

/**
 * Nested properties of the user, containing custom dashboard items like favorites.
 *
 * Public data class.
 */
data class UserPropertiesDto(
    @SerializedName("favoriteDevices")
    val favoriteDevices: List<String>?
)
