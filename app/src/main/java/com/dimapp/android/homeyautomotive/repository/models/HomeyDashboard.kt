package com.dimapp.android.homeyautomotive.repository.models

/**
 * Domain model for a Homey Dashboard, used in AAOS screens to let the user choose
 * the Home tab source.
 *
 * @property id Dashboard UUID.
 * @property name Human-readable dashboard name.
 */
data class HomeyDashboard(
    val id: String,
    val name: String
)
