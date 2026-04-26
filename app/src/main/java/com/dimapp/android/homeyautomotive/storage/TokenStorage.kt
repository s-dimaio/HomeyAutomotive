package com.dimapp.android.homeyautomotive.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

import com.google.gson.Gson
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

/**
 * Entry for a single authenticated Homey hub.
 */
data class HubTokenEntry(
    val sessionToken: String,
    val refreshSecret: String,
    val name: String,
    val apiUrl: String
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
            gson.fromJson(json, type) ?: emptyMap()
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

        if (tokensMap.isEmpty()) {
            clearAllAuth()
            return false
        }

        val wasActive = getSelectedHomeyId() == homeyId
        val editor = prefs.edit().putString(KEY_HUB_TOKENS, gson.toJson(tokensMap))

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
     */
    fun saveHubToken(homeyId: String, name: String, sessionToken: String, refreshSecret: String, apiUrl: String, saveAsActive: Boolean = true) {
        val tokensMap = getAllHubTokens().toMutableMap()
        tokensMap[homeyId] = HubTokenEntry(sessionToken, refreshSecret, name, apiUrl)
        
        val editor = prefs.edit().putString(KEY_HUB_TOKENS, gson.toJson(tokensMap))
        
        if (saveAsActive) {
            editor.putString(KEY_ACTIVE_HOMEY_ID, homeyId)
                .putString(KEY_ACTIVE_HOMEY_NAME, name)
                .putString(KEY_ACTIVE_HOMEY_API_URL, apiUrl)
        }
        
        editor.apply()
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
     * Retrieves the session token for the currently active Homey hub.
     *
     * @public
     * @return The raw session token string, or `null` if not authenticated.
     */
    fun getHubSessionToken(): String? {
        val activeId = getSelectedHomeyId() ?: return null
        return getHubToken(activeId)?.sessionToken
    }

    fun getHubRefreshSecret(): String? {
        val activeId = getSelectedHomeyId() ?: return null
        return getHubToken(activeId)?.refreshSecret
    }

    /**
     * Overwrites the stored session token and refresh secret ONLY for the currently active hub.
     * Used exclusively during silent token rotation (`/auth/refresh`).
     */
    fun updateActiveHubTokens(newSessionToken: String, newRefreshSecret: String) {
        val activeId = getSelectedHomeyId() ?: return
        val entry = getHubToken(activeId) ?: return
        saveHubToken(
            homeyId = activeId,
            name = entry.name,
            sessionToken = newSessionToken,
            refreshSecret = newRefreshSecret,
            apiUrl = entry.apiUrl,
            saveAsActive = true
        )
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
        val raw = prefs.getString(KEY_HOME_SOURCE, null)
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
        prefs.edit().putString(KEY_HOME_SOURCE, source.name).apply()
    }

    /**
     * Returns the UUID of the dashboard selected as the Home source, or `null` if not set.
     *
     * @public
     * @return Dashboard UUID string, or `null`.
     * @example val id = storage.getHomeDashboardId()
     */
    fun getHomeDashboardId(): String? = prefs.getString(KEY_HOME_DASHBOARD_ID, null)

    /**
     * Persists the UUID of the dashboard chosen as the Home tab source.
     *
     * @public
     * @param dashboardId UUID of the selected dashboard.
     * @example storage.saveHomeDashboardId("f3959433-a516-4600-a510-26fbda1bcbd1")
     */
    fun saveHomeDashboardId(dashboardId: String) {
        prefs.edit().putString(KEY_HOME_DASHBOARD_ID, dashboardId).apply()
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
     * Retrieves the sync interval in seconds, defaulting to 5 if not set.
     *
     * @public
     * @return Sync interval in seconds.
     * @example val interval = storage.getSyncInterval()
     */
    fun getSyncInterval(): Int = prefs.getInt(KEY_SYNC_INTERVAL, 5)

    /**
     * Clears all stored data (full factory reset).
     *
     * @public
     * @example storage.clearAll()
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    // ── Private Methods ───────────────────────────────────────────────────────

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
