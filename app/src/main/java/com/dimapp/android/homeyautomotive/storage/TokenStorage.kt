package com.dimapp.android.homeyautomotive.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

private const val PREFS_FILE_NAME = "homey_secure_prefs"

// Active hub and multi-hub tokens map
private const val KEY_HUB_TOKENS = "hub_tokens_json"
private const val KEY_ACTIVE_HOMEY_ID = "active_homey_id"
private const val KEY_ACTIVE_HOMEY_NAME = "active_homey_name"
private const val KEY_ACTIVE_HOMEY_API_URL = "active_homey_api_url"

// OAuth2 session state
private const val KEY_OAUTH_STATE = "oauth_state"

// User profile (display name only)
private const val KEY_USER_NAME = "user_name"

// UI preferences
private const val KEY_HOME_SOURCE       = "home_source"
private const val KEY_HOME_DASHBOARD_ID = "home_dashboard_id"
private const val KEY_SYNC_INTERVAL     = "homey_sync_interval"

// Geofence & Proactive alerts preferences
private const val KEY_HOME_LAT            = "home_lat"
private const val KEY_HOME_LNG            = "home_lng"
private const val KEY_GEOFENCE_ENABLED    = "geofence_enabled"
private const val KEY_GEOFENCE_AUTO_CLOSE = "geofence_auto_close"
private const val KEY_GEOFENCE_RADIUS     = "geofence_radius"
private const val KEY_LAST_FENCE_EVENT    = "last_fence_event"
private const val KEY_GEOFENCE_BARRIER_IDS = "geofence_barrier_ids"

// Demo mode
private const val KEY_IS_DEMO_MODE        = "is_demo_mode"

/**
 * Entry for a single authenticated Homey hub.
 */
data class HubTokenEntry(
    @SerializedName("sessionToken") val sessionToken: String? = null,
    @SerializedName("refreshSecret") val refreshSecret: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("apiUrl") val apiUrl: String? = null,
    @SerializedName("isReauthRequired") val isReauthRequired: Boolean = false,
    @SerializedName("reauthReason") val reauthReason: String? = null
)

/** Possible sources for the Home tab device list. */
enum class HomeSource { FAVORITES, DASHBOARD }

/**
 * Secure encrypted storage for all Homey authentication and configuration data.
 *
 * Wraps [EncryptedSharedPreferences] backed by AES-256-GCM for value encryption
 * and AES-256-SIV for key encryption, using the Android Keystore as the master key.
 *
 * Stores:
 * - OAuth2 access token, refresh token, and expiry timestamp
 * - Temporary OAuth2 `state` parameter for CSRF protection
 * - Selected Homey hub ID, name, and API URL
 * - UI preferences (home source, dashboard ID, sync interval)
 *
 * Usage:
 * ```
 * val storage = TokenStorage(context)
 * if (storage.isConfigured()) {
 *     val apiUrl = storage.getSelectedHomeyApiUrl()
 *     val token  = storage.getHubSessionToken()
 * }
 * ```
 *
 * @property context The [Context] used to build the encrypted preferences file.
 * @constructor Creates a new [TokenStorage] instance for the given [context].
 */
class TokenStorage(private val context: Context) {

    /** Lazily initialised encrypted preferences instance. */
    private val prefs: SharedPreferences by lazy { _buildEncryptedPrefs() }

    private val gson = Gson()

    // ── Public Methods — OAuth2 State ────────────────────────────────────────

    /**
     * Saves the temporary OAuth2 `state` parameter used for CSRF protection.
     * Must be cleared (via [clearOAuthState]) after successful validation.
     */
    fun saveOAuthState(state: String) {
        prefs.edit().putString(KEY_OAUTH_STATE, state).apply()
    }

    /** Retrieves the saved OAuth2 `state` parameter. */
    fun getOAuthState(): String? = prefs.getString(KEY_OAUTH_STATE, null)

    /** Removes the temporary OAuth2 `state` parameter after successful validation. */
    fun clearOAuthState() {
        prefs.edit().remove(KEY_OAUTH_STATE).apply()
    }

    fun clearAllAuth() {
        prefs.edit()
            .remove(KEY_HUB_TOKENS)
            .remove(KEY_ACTIVE_HOMEY_ID)
            .remove(KEY_ACTIVE_HOMEY_NAME)
            .remove(KEY_ACTIVE_HOMEY_API_URL)
            .remove(KEY_OAUTH_STATE)
            .remove(KEY_USER_NAME)
            .remove(KEY_IS_DEMO_MODE)
            .remove("${KEY_HOME_LAT}_demo")
            .remove("${KEY_HOME_LNG}_demo")
            .remove("${KEY_HOME_LAT}_default")
            .remove("${KEY_HOME_LNG}_default")
            .remove(KEY_HOME_LAT)
            .remove(KEY_HOME_LNG)
            .apply()
    }

    // ── Public Methods — Multi-Hub Token Management ───────────────────────────────

    /**
     * Retrieves the map of all authenticated hubs.
     */
    fun getAllHubTokens(): Map<String, HubTokenEntry> {
        val json = prefs.getString(KEY_HUB_TOKENS, null) ?: return emptyMap()
        val type = object : TypeToken<Map<String, HubTokenEntry>>() {}.type
        return try {
            val map: Map<String, HubTokenEntry>? = gson.fromJson(json, type)
            map?.filterValues { it.sessionToken != null && it.name != null && it.apiUrl != null } ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Retrieves the token entry for a specific hub.
     */
    fun getHubToken(homeyId: String): HubTokenEntry? {
        return getAllHubTokens()[homeyId]
    }

    /**
     * Returns true if the specified hub has tokens stored.
     */
    fun isHubAuthenticated(homeyId: String): Boolean {
        return getHubToken(homeyId) != null
    }

    /**
     * Removes a single hub's authentication data from storage.
     *
     * If the removed hub was the currently active one, automatically switches to the
     * first remaining hub in the map. If no hubs remain after removal, [clearAllAuth]
     * is called for a full reset.
     *
     * @public
     * @param homeyId The ID of the hub to disconnect.
     * @return `true` if at least one authenticated hub still exists after removal;
     *         `false` if all hubs have been removed (app must redirect to initial setup).
     * @example
     * ```
     * val hasMore = storage.removeHubToken(homeyId)
     * if (!hasMore) navigateToSetup() else invalidate()
     * ```
     */
    fun removeHubToken(homeyId: String): Boolean {
        val tokensMap = getAllHubTokens().toMutableMap()
        tokensMap.remove(homeyId)

        val editor = prefs.edit()
        editor.remove("${KEY_HOME_LAT}_$homeyId")
        editor.remove("${KEY_HOME_LNG}_$homeyId")
        editor.remove("${KEY_GEOFENCE_BARRIER_IDS}_$homeyId")
        editor.remove("${KEY_GEOFENCE_ENABLED}_$homeyId")
        editor.remove("${KEY_GEOFENCE_RADIUS}_$homeyId")
        editor.remove("${KEY_HOME_SOURCE}_$homeyId")
        editor.remove("${KEY_HOME_DASHBOARD_ID}_$homeyId")

        if (homeyId == "demo") {
            editor.remove(KEY_IS_DEMO_MODE)
        }

        if (tokensMap.isEmpty()) {
            editor.apply()
            clearAllAuth()
            return false
        }

        val wasActive = getSelectedHomeyId() == homeyId
        editor.putString(KEY_HUB_TOKENS, gson.toJson(tokensMap))

        if (wasActive) {
            // Auto-switch to the first remaining hub
            val (newId, newEntry) = tokensMap.entries.first()
            editor
                .putString(KEY_ACTIVE_HOMEY_ID, newId)
                .putString(KEY_ACTIVE_HOMEY_NAME, newEntry.name)
                .putString(KEY_ACTIVE_HOMEY_API_URL, newEntry.apiUrl)
        }

        editor.apply()
        return true
    }

    /**
     * Submits a fresh token for a hub, implicitly making it the active hub if saveAsActive is true.
     * Clears any active re-authentication flags for this hub.
     */
    fun saveHubToken(homeyId: String, name: String, sessionToken: String, refreshSecret: String, apiUrl: String, saveAsActive: Boolean = true) {
        val tokensMap = getAllHubTokens().toMutableMap()
        tokensMap[homeyId] = HubTokenEntry(
            sessionToken = sessionToken,
            refreshSecret = refreshSecret,
            name = name,
            apiUrl = apiUrl,
            isReauthRequired = false,
            reauthReason = null
        )
        
        val editor = prefs.edit().putString(KEY_HUB_TOKENS, gson.toJson(tokensMap))
        
        if (saveAsActive) {
            editor.putString(KEY_ACTIVE_HOMEY_ID, homeyId)
                .putString(KEY_ACTIVE_HOMEY_NAME, name)
                .putString(KEY_ACTIVE_HOMEY_API_URL, apiUrl)
        }
        
        editor.apply()
    }

    /**
     * Marks whether a specific hub requires user re-authentication, along with an optional reason.
     *
     * @public
     * @param homeyId The ID of the target hub.
     * @param required Whether re-authentication is required.
     * @param reason The diagnostic reason (e.g. "COMPANION_UPDATED" or "EXPIRED").
     */
    fun setReauthRequired(homeyId: String, required: Boolean, reason: String? = null) {
        val tokensMap = getAllHubTokens().toMutableMap()
        val entry = tokensMap[homeyId] ?: return
        tokensMap[homeyId] = entry.copy(isReauthRequired = required, reauthReason = if (required) reason else null)
        prefs.edit().putString(KEY_HUB_TOKENS, gson.toJson(tokensMap)).apply()
    }

    /**
     * Checks if re-authentication is required for the specified hub (or currently active hub).
     *
     * @public
     * @param hubId Optional target hub ID. Defaults to active hub.
     * @return `true` if re-authentication is needed, `false` otherwise.
     */
    fun isReauthRequired(hubId: String? = null): Boolean {
        val targetId = hubId ?: getSelectedHomeyId() ?: return false
        return getHubToken(targetId)?.isReauthRequired == true
    }

    /**
     * Retrieves the re-authentication reason for the specified hub (or currently active hub).
     *
     * @public
     * @param hubId Optional target hub ID. Defaults to active hub.
     * @return The reason string (e.g. "COMPANION_UPDATED"), or `null`.
     */
    fun getReauthReason(hubId: String? = null): String? {
        val targetId = hubId ?: getSelectedHomeyId() ?: return null
        return getHubToken(targetId)?.reauthReason
    }

    /**
     * Updates the user-defined name of an existing hub in storage.
     *
     * Public method.
     * Updates the entry in [KEY_HUB_TOKENS] and also updates [KEY_ACTIVE_HOMEY_NAME]
     * if the updated hub is currently the selected/active one.
     *
     * @param homeyId The ID of the hub to update.
     * @param newName The updated display name (e.g., "Veggia", "Modena").
     * @return `true` if the hub was found and its name updated; `false` if the hub does not exist or input is invalid.
     * @example
     * ```kotlin
     * val updated = storage.updateHubName("677c0a03023b9cf8cd156f27", "Veggia")
     * ```
     */
    fun updateHubName(homeyId: String, newName: String): Boolean {
        if (homeyId == "demo" || newName.isBlank()) return false
        val tokensMap = getAllHubTokens().toMutableMap()
        val entry = tokensMap[homeyId] ?: return false
        if (entry.name == newName) return false

        tokensMap[homeyId] = entry.copy(name = newName)
        val editor = prefs.edit().putString(KEY_HUB_TOKENS, gson.toJson(tokensMap))
        if (getSelectedHomeyId() == homeyId) {
            editor.putString(KEY_ACTIVE_HOMEY_NAME, newName)
        }
        editor.apply()
        return true
    }

    // ── Public Methods — Active Homey Selection ───────────────────────────────

    /**
     * Saves the active Homey selection details without requiring tokens yet.
     * Used during initial setup before OAuth2 is completed.
     */
    fun saveSelectedHomey(id: String, name: String, apiUrl: String) {
        prefs.edit()
            .putString(KEY_ACTIVE_HOMEY_ID, id)
            .putString(KEY_ACTIVE_HOMEY_NAME, name)
            .putString(KEY_ACTIVE_HOMEY_API_URL, apiUrl)
            .apply()
    }

    /**
     * Switches the active Homey hub. The hub MUST already have tokens stored.
     */
    fun switchActiveHomey(homeyId: String): Boolean {
        val entry = getHubToken(homeyId) ?: return false
        prefs.edit()
            .putString(KEY_ACTIVE_HOMEY_ID, homeyId)
            .putString(KEY_ACTIVE_HOMEY_NAME, entry.name)
            .putString(KEY_ACTIVE_HOMEY_API_URL, entry.apiUrl)
            .apply()
        return true
    }

    fun getSelectedHomeyId(): String? = prefs.getString(KEY_ACTIVE_HOMEY_ID, null)
    
    fun getSelectedHomeyName(): String? = prefs.getString(KEY_ACTIVE_HOMEY_NAME, null)
    
    fun getSelectedHomeyApiUrl(): String? = prefs.getString(KEY_ACTIVE_HOMEY_API_URL, null)

    fun isConfigured(): Boolean {
        val activeId = getSelectedHomeyId() ?: return false
        return isHubAuthenticated(activeId)
    }

    fun getBaseUrl(): String? = getSelectedHomeyApiUrl()

    // ── Public Methods — Active Hub Session Tokens ─────────────────────────────

    /**
     * Retrieves the session token for the specified Homey hub, or the currently active hub if hubId is null.
     *
     * @public
     * @param hubId Optional target hub ID. Defaults to active hub.
     * @return The raw session token string, or `null` if not authenticated.
     */
    fun getHubSessionToken(hubId: String? = null): String? {
        val targetId = hubId ?: getSelectedHomeyId() ?: return null
        return getHubToken(targetId)?.sessionToken
    }

    /**
     * Retrieves the refresh secret for the specified Homey hub, or the currently active hub if hubId is null.
     *
     * @public
     * @param hubId Optional target hub ID. Defaults to active hub.
     * @return The refresh secret string, or `null` if not available.
     */
    fun getHubRefreshSecret(hubId: String? = null): String? {
        val targetId = hubId ?: getSelectedHomeyId() ?: return null
        return getHubToken(targetId)?.refreshSecret
    }

    /**
     * Overwrites the stored session token and refresh secret for the specified hub (or active hub).
     * Used during silent token rotation (`/auth/refresh`).
     *
     * @public
     * @param homeyId The target hub ID.
     * @param newSessionToken The updated session token.
     * @param newRefreshSecret The updated refresh secret.
     */
    fun updateHubTokens(homeyId: String, newSessionToken: String, newRefreshSecret: String) {
        val entry = getHubToken(homeyId) ?: return
        val name = entry.name ?: (if (homeyId == getSelectedHomeyId()) getSelectedHomeyName() else null) ?: return
        val apiUrl = entry.apiUrl ?: (if (homeyId == getSelectedHomeyId()) getSelectedHomeyApiUrl() else null) ?: return
        val isCurrentlyActive = (homeyId == getSelectedHomeyId())
        saveHubToken(
            homeyId = homeyId,
            name = name,
            sessionToken = newSessionToken,
            refreshSecret = newRefreshSecret,
            apiUrl = apiUrl,
            saveAsActive = isCurrentlyActive
        )
    }

    /**
     * Overwrites the stored session token and refresh secret ONLY for the currently active hub.
     * Used exclusively during silent token rotation (`/auth/refresh`).
     */
    fun updateActiveHubTokens(newSessionToken: String, newRefreshSecret: String) {
        val activeId = getSelectedHomeyId() ?: return
        updateHubTokens(activeId, newSessionToken, newRefreshSecret)
    }

    // ── Public Methods — User and Homeys List (Persisted on first login) ──────

    /**
     * Saves the display name of the authenticated user.
     *
     * Only the user's display name is persisted. Email and user ID are not stored
     * as they are never displayed to the user in the current UI.
     *
     * @public
     * @param name The Athom account display name (e.g. "John Doe").
     */
    fun saveUserProfile(name: String?) {
        prefs.edit()
            .putString(KEY_USER_NAME, name ?: "Homey User")
            .apply()
    }

    fun getUserName(): String? = prefs.getString(KEY_USER_NAME, null)



    // ── Public Methods — UI Preferences ────────────────────────────────────

    /**
     * Returns the current Home tab source selection.
     * Defaults to [HomeSource.FAVORITES] if not yet configured.
     *
     * @public
     * @return [HomeSource.FAVORITES] or [HomeSource.DASHBOARD].
     * @example val source = storage.getHomeSource()
     */
    fun getHomeSource(): HomeSource {
        val raw = prefs.getString(_getScopedKey(KEY_HOME_SOURCE), null)
        return if (raw == HomeSource.DASHBOARD.name) HomeSource.DASHBOARD else HomeSource.FAVORITES
    }

    /**
     * Persists the Home tab source selection.
     *
     * @public
     * @param source [HomeSource.FAVORITES] or [HomeSource.DASHBOARD].
     * @example storage.saveHomeSource(HomeSource.DASHBOARD)
     */
    fun saveHomeSource(source: HomeSource) {
        prefs.edit().putString(_getScopedKey(KEY_HOME_SOURCE), source.name).apply()
    }

    /**
     * Returns the UUID of the dashboard selected as the Home source, or `null` if not set.
     *
     * @public
     * @return Dashboard UUID string, or `null`.
     * @example val id = storage.getHomeDashboardId()
     */
    fun getHomeDashboardId(): String? = prefs.getString(_getScopedKey(KEY_HOME_DASHBOARD_ID), null)

    /**
     * Persists the UUID of the dashboard chosen as the Home tab source.
     *
     * @public
     * @param dashboardId UUID of the selected dashboard.
     * @example storage.saveHomeDashboardId("f3959433-a516-4600-a510-26fbda1bcbd1")
     */
    fun saveHomeDashboardId(dashboardId: String) {
        prefs.edit().putString(_getScopedKey(KEY_HOME_DASHBOARD_ID), dashboardId).apply()
    }

    /**
     * Saves the sync interval in seconds.
     *
     * @public
     * @param seconds Sync interval in seconds (e.g. 5, 10, 30).
     * @example storage.saveSyncInterval(10)
     */
    fun saveSyncInterval(seconds: Int) {
        prefs.edit().putInt(KEY_SYNC_INTERVAL, seconds).apply()
    }

    /**
     * Retrieves the sync interval in seconds, defaulting to 15 if not set.
     * Automatically migrates legacy 5-second default to 15 seconds to prevent cloud network congestion.
     *
     * @public
     * @return Sync interval in seconds.
     * @example val interval = storage.getSyncInterval()
     */
    fun getSyncInterval(): Int {
        val saved = prefs.getInt(KEY_SYNC_INTERVAL, 15)
        return if (saved == 5) {
            saveSyncInterval(15)
            15
        } else {
            saved
        }
    }

    // ── Public Methods — Geofencing ──────────────────────────────────────────

    /**
     * Saves the Homey hub geographical coordinates for geofence registration.
     */
    fun saveHomeLocation(lat: Double, lng: Double, hubId: String? = null) {
        val targetHub = hubId ?: getSelectedHomeyId() ?: return
        if (targetHub == "demo") return
        prefs.edit()
            .putString("${KEY_HOME_LAT}_$targetHub", lat.toString())
            .putString("${KEY_HOME_LNG}_$targetHub", lng.toString())
            .apply()
    }

    /**
     * Retrieves the saved Homey hub geographical coordinates.
     * Always returns null for demo mode to ensure no real or mock home coordinates are used.
     */
    fun getHomeLocation(hubId: String? = null): Pair<Double, Double>? {
        if (hubId == "demo" || (hubId == null && isDemoMode())) return null
        val targetHub = hubId ?: getSelectedHomeyId() ?: return null
        if (targetHub == "demo") return null
        val latStr = prefs.getString("${KEY_HOME_LAT}_$targetHub", null)
        val lngStr = prefs.getString("${KEY_HOME_LNG}_$targetHub", null)
        val lat = latStr?.toDoubleOrNull() ?: return null
        val lng = lngStr?.toDoubleOrNull() ?: return null
        return Pair(lat, lng)
    }

    /**
     * Clears the saved Homey hub geographical coordinates, deactivating home location features.
     *
     * @public
     * @param hubId Optional target hub ID. Defaults to active hub.
     * @example storage.clearHomeLocation()
     */
    fun clearHomeLocation(hubId: String? = null) {
        val targetHub = hubId ?: getSelectedHomeyId() ?: return
        prefs.edit()
            .remove("${KEY_HOME_LAT}_$targetHub")
            .remove("${KEY_HOME_LNG}_$targetHub")
            .apply()
    }

    /**
     * Checks if geofencing alerts are enabled for the specified hub (or active hub).
     *
     * @public
     * @param hubId Optional target hub ID. Defaults to active hub.
     * @return True if geofencing is enabled.
     */
    fun isGeofenceEnabled(hubId: String? = null): Boolean {
        val target = hubId ?: getSelectedHomeyId() ?: return false
        return prefs.getBoolean("${KEY_GEOFENCE_ENABLED}_$target", prefs.getBoolean(KEY_GEOFENCE_ENABLED, true))
    }

    /**
     * Sets whether geofencing alerts are enabled for the specified hub (or active hub).
     *
     * @public
     * @param enabled True to enable geofencing alerts.
     * @param hubId Optional target hub ID. Defaults to active hub.
     */
    fun setGeofenceEnabled(enabled: Boolean, hubId: String? = null) {
        val target = hubId ?: getSelectedHomeyId()
        val editor = prefs.edit().putBoolean(KEY_GEOFENCE_ENABLED, enabled)
        if (target != null) {
            editor.putBoolean("${KEY_GEOFENCE_ENABLED}_$target", enabled)
        }
        editor.apply()
    }

    /**
     * Checks if automatic closure of barriers on departure is enabled.
     * Currently forced to false as automated actions will be handled via Homey Flow triggers.
     */
    fun isGeofenceAutoCloseEnabled(): Boolean = false

    /**
     * Sets whether automatic closure of barriers on departure is enabled.
     */
    fun setGeofenceAutoCloseEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GEOFENCE_AUTO_CLOSE, enabled).apply()
    }

    /**
     * Retrieves the configured geofence radius in meters for the specified hub (or active hub).
     * Defaults to 300m if not configured.
     *
     * @public
     * @param hubId Optional target hub ID. Defaults to active hub.
     * @return Radius in meters.
     */
    fun getGeofenceRadius(hubId: String? = null): Float {
        val target = hubId ?: getSelectedHomeyId() ?: return 300f
        return prefs.getFloat("${KEY_GEOFENCE_RADIUS}_$target", prefs.getFloat(KEY_GEOFENCE_RADIUS, 300f))
    }

    /**
     * Sets the geofence radius in meters for the specified hub (or active hub).
     *
     * @public
     * @param radius Radius in meters (e.g. 100f, 200f, 300f).
     * @param hubId Optional target hub ID. Defaults to active hub.
     */
    fun setGeofenceRadius(radius: Float, hubId: String? = null) {
        val target = hubId ?: getSelectedHomeyId()
        val editor = prefs.edit().putFloat(KEY_GEOFENCE_RADIUS, radius)
        if (target != null) {
            editor.putFloat("${KEY_GEOFENCE_RADIUS}_$target", radius)
        }
        editor.apply()
    }

    /**
     * Records a log stamp for the last detected geofence event.
     */
    fun setLastFenceEvent(eventDescription: String) {
        prefs.edit().putString(KEY_LAST_FENCE_EVENT, eventDescription).apply()
    }

    /**
     * Retrieves the last recorded geofence event description.
     */
    fun getLastFenceEvent(): String? = prefs.getString(KEY_LAST_FENCE_EVENT, null)

    /**
     * Saves the list of barrier device IDs configured for proximity alerts.
     *
     * @public
     * @param ids List of device IDs selected by the user in companion app.
     * @param hubId Optional target hub ID, defaults to currently active hub.
     */
    fun saveGeofenceDeviceIds(ids: List<String>, hubId: String? = null) {
        val targetHub = hubId ?: getSelectedHomeyId() ?: return
        prefs.edit()
            .putStringSet("${KEY_GEOFENCE_BARRIER_IDS}_$targetHub", ids.toSet())
            .apply()
    }

    /**
     * Retrieves the list of barrier device IDs configured for proximity alerts.
     *
     * @public
     * @param hubId Optional target hub ID, defaults to currently active hub.
     * @return List of configured barrier device IDs, or empty list if none selected.
     */
    fun getGeofenceDeviceIds(hubId: String? = null): List<String> {
        val targetHub = hubId ?: getSelectedHomeyId() ?: return emptyList()
        val set = prefs.getStringSet("${KEY_GEOFENCE_BARRIER_IDS}_$targetHub", null)
        return set?.toList() ?: emptyList()
    }

    // ── Public Methods — Demo Mode ───────────────────────────────────────────

    /**
     * Checks whether the app is currently running in mock Demo Mode.
     * Evaluates dynamically based on whether the active hub is "demo".
     *
     * @return True if in demo mode.
     */
    fun isDemoMode(): Boolean = getSelectedHomeyId() == "demo"

    /**
     * Activates Demo Mode, registering a mock hub with sample configuration.
     * Note: Home coordinates are intentionally NOT configured in demo mode.
     */
    fun enableDemoMode() {
        prefs.edit()
            .putBoolean(KEY_IS_DEMO_MODE, true)
            .remove("${KEY_HOME_LAT}_demo")
            .remove("${KEY_HOME_LNG}_demo")
            .remove("${KEY_HOME_LAT}_default")
            .remove("${KEY_HOME_LNG}_default")
            .remove(KEY_HOME_LAT)
            .remove(KEY_HOME_LNG)
            .apply()

        saveHubToken(
            homeyId = "demo",
            name = "Homey Demo",
            sessionToken = "demo_session_token",
            refreshSecret = "demo_refresh_secret",
            apiUrl = "https://demo.connect.athom.com",
            saveAsActive = true
        )
        saveGeofenceDeviceIds(listOf("demo_gate_1", "demo_garage_1", "demo_door_1"), "demo")
    }

    /**
     * Deactivates Demo Mode and removes the mock hub from storage.
     */
    fun clearDemoMode() {
        prefs.edit()
            .remove(KEY_IS_DEMO_MODE)
            .remove("${KEY_HOME_LAT}_demo")
            .remove("${KEY_HOME_LNG}_demo")
            .apply()
        removeHubToken("demo")
    }

    /**
     * Clears all stored data (full factory reset).
     *
     * @public
     * @example storage.clearAll()
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /**
     * Generates a preference key scoped to the currently active Homey hub.
     *
     * @private
     * @param baseKey The original setting key (e.g. "home_source").
     * @return A scoped key (e.g. "home_source_abc123") or the baseKey if no hub is active.
     */
    private fun _getScopedKey(baseKey: String): String {
        val activeId = getSelectedHomeyId() ?: return baseKey
        return "${baseKey}_$activeId"
    }

    /**
     * Builds and returns the [EncryptedSharedPreferences] instance.
     *
     * Creates the AES-256 master key in the Android Keystore on first call.
     *
     * @private
     * @return The [SharedPreferences] backed by encryption.
     */
    private fun _buildEncryptedPrefs(): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREFS_FILE_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
