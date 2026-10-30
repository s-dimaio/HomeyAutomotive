package com.dimapp.android.homeyautomotive.auth

import android.content.Context
import android.util.Log
import com.dimapp.android.homeyautomotive.BuildConfig
import com.dimapp.android.homeyautomotive.api.HomeyCompanionApiClient
import com.dimapp.android.homeyautomotive.api.RefreshAuthRequest
import com.dimapp.android.homeyautomotive.api.SanitizedHttpLogger
import com.dimapp.android.homeyautomotive.api.StartAuthRequest
import com.dimapp.android.homeyautomotive.api.CompanionToken
import com.dimapp.android.homeyautomotive.storage.TokenStorage
import com.google.gson.JsonParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "HomeyAuthRepository"

/**
 * Result of a companion authentication polling session.
 */
sealed class PollResult {
    /** Auth complete, tokens saved. */
    object Success : PollResult()
    /** Auth still in progress, keep polling. */
    object Pending : PollResult()
    /** Session expired on the server (Homey side). */
    object Expired : PollResult()
    /** Server-side logical error (e.g. failed token exchange). */
    object Error : PollResult()
    /** Network or communication error. */
    data class NetworkError(val message: String) : PollResult()
}


/**
 * Repository that orchestrates the entire Homey OAuth2 Authorization Code flow.
 *
 * Responsibilities:
 * - Initiating the auth session on the Homey AAOS Companion App via `POST /auth/start`
 * - Polling the Companion App for session state via `GET /auth/poll`
 * - Saving the received session token, user profile, and hub list to [TokenStorage] upon successful auth
 * - Refreshing the hub session token transparently when expired via Companion App
 * - Logout (clears all tokens from storage)
 *
 * All network calls use [HomeyCompanionApiClient] to communicate with the Companion App.
 * Tokens and metadata are persisted via [TokenStorage].
 *
 * @property context Application context used to instantiate [TokenStorage].
 * @constructor Creates a new [HomeyAuthRepository] for the given [context].
 *
 * @example
 * ```
 * val repo = HomeyAuthRepository(context)
 * val authUrl = repo.startCompanionAuth(homeyId = "abc123", sessionId = sessionId)
 * // Show authUrl as QR code, then poll:
 * val done = repo.pollCompanionAuth(homeyId = "abc123", sessionId = sessionId)
 * // done == true → token saved, navigate forward
 * ```
 */
class HomeyAuthRepository(
    private val context: Context,
    private val storage: TokenStorage
) {

    companion object {
        private val globalRefreshMutex = Mutex()
    }

    // ── Public Methods ─────────────────────────────────────────────────────────
    /**
     * Retrieves the Hub session token (ownerApiToken) received from the Companion App,
     * formatted as a `"Bearer <token>"` header value.
     *
     * This token is obtained during the OAuth2 flow via the Companion App
     * and persisted in [TokenStorage].
     *
     * @public
     * @param hubId Optional target hub ID. Defaults to the currently active hub.
     * @return A valid `"Bearer <token>"` value, or `null` if not available.
     */
    suspend fun getValidDelegationToken(hubId: String? = null): String? {
        val sessionToken = storage.getHubSessionToken(hubId)
        if (sessionToken.isNullOrBlank()) {
            val target = hubId ?: storage.getSelectedHomeyId()
            Log.w(TAG, "No Hub session token found for hub ${SanitizedHttpLogger.maskId(target)} — Hub API calls will return 401.")
            return null
        }
        return "Bearer $sessionToken"
    }

    /**
     * Silently refreshes the Hub session token using the Companion app's `/auth/refresh` endpoint.
     * Uses the Token Rotation pattern: sends the current `refresh_secret` and receives a new
     * session token along with a new `refresh_secret`.
     *
     * @public
     * @param failedToken The token that failed with HTTP 401, if available. Used to prevent double-refreshes.
     * @param hubId Optional target hub ID. Defaults to the currently active hub.
     * @return `true` if the refresh was successful (or already done by a concurrent request), `false` otherwise.
     */
    suspend fun refreshHubSessionToken(failedToken: String? = null, hubId: String? = null): Boolean = globalRefreshMutex.withLock {
        val targetId = hubId ?: storage.getSelectedHomeyId()
        // First check if the token was already refreshed by another concurrent thread
        val currentTokenString = getValidDelegationToken(targetId)
        if (failedToken != null && failedToken != currentTokenString && currentTokenString != null) {
            Log.i(TAG, "Token for hub ${SanitizedHttpLogger.maskId(targetId)} was already refreshed by another concurrent request, skipping network call.")
            return@withLock true
        }

        val refreshSecret = storage.getHubRefreshSecret(targetId)

        if (targetId.isNullOrBlank() || refreshSecret.isNullOrBlank()) {
            Log.w(TAG, "Cannot refresh Hub session: missing targetId or refreshSecret for ${SanitizedHttpLogger.maskId(targetId)}")
            return@withLock false
        }

        return@withLock try {
            val service = HomeyCompanionApiClient.create(targetId, BuildConfig.DEBUG)
            val response = service.refreshAuth(RefreshAuthRequest(refreshSecret))

            if (!response.session_token.isNullOrBlank() && !response.athom_refresh_token.isNullOrBlank()) {
                storage.updateHubTokens(targetId, response.session_token, response.athom_refresh_token)
                storage.setReauthRequired(targetId, false)
                Log.i(TAG, "Hub session token refreshed successfully for ${SanitizedHttpLogger.maskId(targetId)}.")
                true
            } else {
                Log.e(TAG, "Hub session token refresh failed for ${SanitizedHttpLogger.maskId(targetId)}: invalid response payload.")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Hub session token refresh failed for ${SanitizedHttpLogger.maskId(targetId)} with exception: ${e.message}")
            if (e is retrofit2.HttpException && e.code() == 401) {
                val errorBody = try { e.response()?.errorBody()?.string().orEmpty() } catch (_: Exception) { "" }
                val isCompanionUpdated = errorBody.contains("COMPANION_UPDATED") || errorBody.contains("REAUTH_REQUIRED")
                Log.w(TAG, "Auth refresh rejected with HTTP 401 (isCompanionUpdated=$isCompanionUpdated) for ${SanitizedHttpLogger.maskId(targetId)}.")
                storage.setReauthRequired(targetId, true, if (isCompanionUpdated) "COMPANION_UPDATED" else "EXPIRED")
            }
            false
        }
    }



    /**
     * Returns `true` if the user is authenticated (access token exists and Homey is selected).
     *
     * @public
     * @return `true` if the app is ready to make API calls to the selected Homey.
     * @example
     * ```
     * if (authRepo.isAuthenticated()) { MainTabScreen() } else { OAuthSignInScreen() }
     * ```
     */
    fun isAuthenticated(): Boolean = storage.isConfigured()

    /**
     * Returns `true` if the user has selected an active Homey hub.
     *
     * @public
     * @return `true` if a Homey hub ID and API URL are stored.
     */
    fun hasSelectedHomey(): Boolean =
        !storage.getSelectedHomeyId().isNullOrBlank()

    /**
     * Initiates an OAuth2 auth session via the Homey AAOS Companion App.
     *
     * Calls `POST /auth/start` on the Companion App (public endpoint — no token required).
     * The Companion App creates an OAuth2 callback via `createOAuth2Callback` internally
     * and returns the Athom authorization URL to be displayed as a QR code.
     *
     * @public
     * @param homeyId   The Homey hub ID used to construct the Companion App base URL.
     * @param sessionId A 64-character hex string generated by the AAOS app with [java.security.SecureRandom].
     * @return The authorization URL string (to render as QR code), or `null` on error.
     * @example
     * ```
     * val url = authRepo.startCompanionAuth(homeyId = "abc123", sessionId = sessionId)
     * if (url != null) showQrCode(url)
     * ```
     */
    suspend fun startCompanionAuth(homeyId: String, sessionId: String): String? {
        return try {
            val service = HomeyCompanionApiClient.create(
                homeyId = homeyId,
                debug   = BuildConfig.DEBUG
            )
            val response = service.startAuth(StartAuthRequest(sessionId = sessionId))
            Log.d(TAG, "Companion auth session started — authUrl received.")
            response.authUrl
        } catch (e: Exception) {
            Log.e(TAG, "startCompanionAuth failed: ${e.message}", e)
            null
        }
    }

    /**
     * Polls the Homey AAOS Companion App to check if the user has completed authorization.
     *
     * Calls `GET /auth/poll` on the Companion App (public endpoint — no token required).
     * When the status is `"complete"`, the received tokens are saved to [TokenStorage]
     * and this method returns `true`. Should be called every ~3 seconds.
     *
     * @public
     * @param homeyId   The Homey hub ID matching the session started with [startCompanionAuth].
     * @param sessionId The session identifier matching the [startCompanionAuth] call.
     * @return [PollResult] representing the current session state or network error.
     * @example
     * ```
     * while (true) {
     *     delay(3_000)
     *     val res = authRepo.pollCompanionAuth(homeyId, sessionId)
     *     if (res is PollResult.Success) break
     *     if (res is PollResult.Expired) showError()
     * }
     * ```
     */
    suspend fun pollCompanionAuth(homeyId: String, sessionId: String): PollResult {
        return try {
            val service = HomeyCompanionApiClient.create(
                homeyId = homeyId,
                debug   = BuildConfig.DEBUG
            )
            val response = service.pollAuth(session = sessionId)
            when (response.status) {
                "complete" -> {
                    val token = response.token ?: run {
                        Log.e(TAG, "Poll returned complete but token is null.")
                        return PollResult.Error
                    }

                    if (token.homey_id == null || token.homey_api_url == null) {
                        Log.e(TAG, "Companion App did not return homey_id or API URL.")
                        return PollResult.Error
                    }
                    if (token.athom_refresh_token == null) {
                        Log.e(TAG, "Companion App did not return a refresh token.")
                        return PollResult.Error
                    }

                    // Centralized processing and saving of all received authentication data
                    _saveAuthData(token)


                    Log.d(TAG, "Companion auth complete — tokens saved for hub: ${SanitizedHttpLogger.maskId(token.homey_id)}")
                    PollResult.Success
                }
                "expired" -> {
                    Log.w(TAG, "Companion auth session expired on server.")
                    PollResult.Expired
                }
                "error" -> {
                    Log.e(TAG, "Companion auth session reported error status.")
                    PollResult.Error
                }
                else -> {
                    Log.d(TAG, "Companion auth status: ${response.status}")
                    PollResult.Pending
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.d(TAG, "pollCompanionAuth cancelled.")
            throw e // Always re-throw CancellationException in coroutines
        } catch (e: Exception) {
            Log.e(TAG, "pollCompanionAuth failed (network): ${e.message}", e)
            PollResult.NetworkError(e.message ?: "Unknown network error")
        }
    }




    /**
     * Logs out the user by clearing all OAuth2 tokens and Homey selection from storage.
     *
     * After logout, [isAuthenticated] will return `false` and the app should redirect
     * to [com.dimapp.android.homeyautomotive.screens.OAuthSignInScreen].
     *
     * @public
     * @example
     * ```
     * authRepo.logout()
     * screenManager.push(OAuthSignInScreen(carContext))
     * ```
     */
    fun logout() {
        storage.clearAllAuth()
        Log.d(TAG, "User logged out (all multi-hub tokens cleared).")
    }

    // ── Private Methods ───────────────────────────────────────────────────────

    /**
     * Processes and persists all authentication data received from the Companion App.
     *
     * This method centralizes:
     * 1. Hub name resolution (using homey_name from the token, with a safe fallback).
     * 2. Hub-specific token and URL persistence.
     * 3. User profile information saving.
     *
     * Note: The Companion App is Hub-centric and cannot provide the list of other Athom account
     * hubs. Additional hubs are managed exclusively via manual ID entry.
     *
     * @private
     * @param token The [CompanionToken] payload received after successful OAuth2 exchange.
     */
    private fun _saveAuthData(token: CompanionToken) {
        val homeyId = token.homey_id ?: return
        val apiUrl = token.homey_api_url ?: return
        val sessionToken = token.session_token ?: run {
            Log.e(TAG, "[HomeyAuthRepository:_saveAuthData] Failed: session_token is null in payload.")
            return
        }
        val refreshSecret = token.athom_refresh_token ?: return

        // 1. Resolve hub name from token data, or query the REST API if generic/missing
        var resolvedName = token.homey_name
        if (resolvedName.isNullOrBlank() || resolvedName.equals("Homey", ignoreCase = true)) {
            val liveName = fetchHubSystemName(apiUrl, sessionToken)
            resolvedName = if (!liveName.isNullOrBlank()) liveName else "Homey"
        }

        // 2. Save/Update hub token entry and mark as active
        storage.saveHubToken(
            homeyId = homeyId,
            name = resolvedName,
            sessionToken = sessionToken,
            refreshSecret = refreshSecret,
            apiUrl = apiUrl,
            saveAsActive = true
        )

        // 3. Persist user profile
        token.user?.let {
            storage.saveUserProfile(it.name)
        }

        // 4. Persist home location if provided
        token.location?.let { loc ->
            storage.saveHomeLocation(loc.latitude, loc.longitude, homeyId)
            Log.d(TAG, "Home location saved for hub ${SanitizedHttpLogger.maskId(homeyId)}: (lat=***, lng=***)")
        }

        Log.d(TAG, "Auth data successfully persisted for hub: ${SanitizedHttpLogger.maskId(homeyId)} (Name: $resolvedName)")
    }

    /**
     * Fetches the system name for a specific Homey hub from the REST API.
     *
     * Public method.
     * Queries `GET /manager/system/name` and falls back to `GET /manager/system` to resolve
     * the user-defined name configured in Homey (e.g., "Veggia", "Modena").
     *
     * @param apiUrl The base API URL for the target Homey hub.
     * @param sessionToken The OAuth2/Session Bearer token for authentication.
     * @return The resolved user-configured system name, or `null` if unreachable.
     * @example
     * ```kotlin
     * val name = authRepo.fetchHubSystemName("https://677c0a03023b9cf8cd156f27.connect.athom.com/api/", token)
     * ```
     */
    fun fetchHubSystemName(apiUrl: String, sessionToken: String): String? {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        val baseUrl = apiUrl.trimEnd('/')

        // Primary attempt: GET /manager/system/name
        try {
            val req1 = Request.Builder()
                .url("$baseUrl/manager/system/name")
                .header("Authorization", "Bearer $sessionToken")
                .get()
                .build()

            client.newCall(req1).execute().use { res ->
                if (res.isSuccessful) {
                    val raw = res.body?.string()?.trim()
                    if (!raw.isNullOrBlank()) {
                        val parsed = JsonParser.parseString(raw)
                        if (parsed.isJsonPrimitive) {
                            val str = parsed.asString.trim()
                            if (str.isNotBlank()) return str
                        } else if (parsed.isJsonObject) {
                            val obj = parsed.asJsonObject
                            val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim()
                                ?: obj.get("value")?.takeIf { !it.isJsonNull }?.asString?.trim()
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "[HomeyAuthRepository:fetchHubSystemName] /manager/system/name failed: ${e.message}")
        }

        // Secondary fallback: GET /manager/system
        try {
            val req2 = Request.Builder()
                .url("$baseUrl/manager/system")
                .header("Authorization", "Bearer $sessionToken")
                .get()
                .build()

            client.newCall(req2).execute().use { res ->
                if (res.isSuccessful) {
                    val raw = res.body?.string()?.trim()
                    if (!raw.isNullOrBlank()) {
                        val parsed = JsonParser.parseString(raw)
                        if (parsed.isJsonObject) {
                            val name = parsed.asJsonObject.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim()
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "[HomeyAuthRepository:fetchHubSystemName] /manager/system failed: ${e.message}")
        }

        return null
    }

    /**
     * Fetches and refreshes the home location coordinates and geofence barrier configuration from the active Homey Companion App.
     *
     * @public
     * @param homeyId Optional target hub ID. If null, the currently active hub is used.
     * @return [Pair] of latitude and longitude, or null if retrieval fails.
     */
    suspend fun refreshHomeLocation(homeyId: String? = null): Pair<Double, Double>? {
        val targetId = homeyId ?: storage.getSelectedHomeyId() ?: return null
        if (targetId == "demo" || storage.isDemoMode()) {
            Log.d(TAG, "[HomeyAuthRepository:refreshHomeLocation] Skipping location refresh for demo mode.")
            return null
        }
        return try {
            val service = HomeyCompanionApiClient.create(homeyId = targetId, debug = BuildConfig.DEBUG)
            val loc = service.getLocation()
            storage.saveHomeLocation(loc.latitude, loc.longitude, targetId)
            Log.d(TAG, "[HomeyAuthRepository:refreshHomeLocation] Refreshed home location for ${SanitizedHttpLogger.maskId(targetId)}: (lat=***, lng=***)")

            try {
                val geofenceConfig = service.getGeofenceConfig()
                storage.saveGeofenceDeviceIds(geofenceConfig.deviceIds, targetId)
                Log.d(TAG, "[HomeyAuthRepository:refreshHomeLocation] Refreshed geofence barrier devices for ${SanitizedHttpLogger.maskId(targetId)}: ${geofenceConfig.deviceIds}")
            } catch (cfgErr: Exception) {
                Log.w(TAG, "[HomeyAuthRepository:refreshHomeLocation] Could not fetch geofence config: ${cfgErr.message}")
            }

            Pair(loc.latitude, loc.longitude)
        } catch (e: Exception) {
            Log.e(TAG, "[HomeyAuthRepository:refreshHomeLocation] Failed to refresh home location: ${e.message}", e)
            null
        }
    }
}
