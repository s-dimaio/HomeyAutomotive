package com.dimapp.android.homeyautomotive.auth

import android.content.Context
import android.util.Log
import com.dimapp.android.homeyautomotive.BuildConfig
import com.dimapp.android.homeyautomotive.api.HomeyCompanionApiClient
import com.dimapp.android.homeyautomotive.api.RefreshAuthRequest
import com.dimapp.android.homeyautomotive.api.StartAuthRequest
import com.dimapp.android.homeyautomotive.api.CompanionToken
import com.dimapp.android.homeyautomotive.storage.TokenStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
     * @return A valid `"Bearer <token>"` value, or `null` if not available.
     */
    suspend fun getValidDelegationToken(): String? {
        val sessionToken = storage.getHubSessionToken()
        if (sessionToken.isNullOrBlank()) {
            Log.w(TAG, "No Hub session token found — Hub API calls will return 401.")
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
     * @return `true` if the refresh was successful (or already done by a concurrent request), `false` otherwise.
     */
    suspend fun refreshHubSessionToken(failedToken: String? = null): Boolean = globalRefreshMutex.withLock {
        // First check if the token was already refreshed by another concurrent thread
        val currentTokenString = getValidDelegationToken()
        if (failedToken != null && failedToken != currentTokenString && currentTokenString != null) {
            Log.i(TAG, "Token was already refreshed by another concurrent request, skipping network call.")
            return@withLock true
        }

        val homeyId = storage.getSelectedHomeyId()
        val refreshSecret = storage.getHubRefreshSecret()

        if (homeyId.isNullOrBlank() || refreshSecret.isNullOrBlank()) {
            Log.w(TAG, "Cannot refresh Hub session: missing homeyId or refreshSecret")
            return@withLock false
        }

        return@withLock try {
            val service = HomeyCompanionApiClient.create(homeyId, BuildConfig.DEBUG)
            val response = service.refreshAuth(RefreshAuthRequest(refreshSecret))

            if (!response.session_token.isNullOrBlank() && !response.athom_refresh_token.isNullOrBlank()) {
                storage.updateActiveHubTokens(response.session_token, response.athom_refresh_token)
                Log.i(TAG, "Hub session token refreshed successfully.")
                true
            } else {
                Log.e(TAG, "Hub session token refresh failed: invalid response payload.")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Hub session token refresh failed with exception: ${e.message}")
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


                    Log.d(TAG, "Companion auth complete — tokens saved for hub: ${token.homey_id}")
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

        // 1. Resolve hub name from token data (Companion App is Hub-centric, no multi-hub list)
        val resolvedName = token.homey_name ?: "Homey"

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
            Log.d(TAG, "Home location saved for hub $homeyId: (lat=***, lng=***)")
        }

        Log.d(TAG, "Auth data successfully persisted for hub: $homeyId (Name: $resolvedName)")
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
            Log.d(TAG, "Refreshed home location for $targetId: (lat=***, lng=***)")

            try {
                val geofenceConfig = service.getGeofenceConfig()
                storage.saveGeofenceDeviceIds(geofenceConfig.deviceIds, targetId)
                Log.d(TAG, "Refreshed geofence barrier devices for $targetId: ${geofenceConfig.deviceIds}")
            } catch (cfgErr: Exception) {
                Log.w(TAG, "Could not fetch geofence config: ${cfgErr.message}")
            }

            Pair(loc.latitude, loc.longitude)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh home location: ${e.message}", e)
            null
        }
    }
}
