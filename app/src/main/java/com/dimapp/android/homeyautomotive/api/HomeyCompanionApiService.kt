package com.dimapp.android.homeyautomotive.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

// ── Request / Response DTOs ───────────────────────────────────────────────────

/**
 * Request body for `POST /auth/start`.
 *
 * @property sessionId A 64-character hex string generated with [java.security.SecureRandom]
 *                     by the AAOS app. Used as a unique, unguessable session identifier.
 */
data class StartAuthRequest(val sessionId: String)

/**
 * Response body from `POST /auth/start`.
 *
 * @property authUrl The Athom authorization URL (with `redirect_uri=callback.athom.com/...`)
 *                   to be displayed as a QR code on the car screen.
 */
data class StartAuthResponse(val authUrl: String)

/**
 * Response body from `GET /auth/poll`.
 *
 * @property status   Current session state: `"pending"`, `"complete"`, or `"expired"`.
 * @property token    Token payload, present only when [status] is `"complete"`.
 */
data class PollAuthResponse(
    val status: String,
    val token: CompanionToken? = null,
)

/**
 * Token payload delivered by the Homey Companion App after successful authorization.
 *
 * Note: The Companion App is Hub-centric and does not have access to the list of other
 * Athom account hubs. Additional hubs must be added manually via Homey ID.
 *
 * @property session_token       The owner API token for the Hub.
 * @property homey_id            The Hub ID.
 * @property homey_name          The Hub display name.
 * @property homey_api_url       The Hub API URL.
 * @property athom_refresh_token The refresh secret for silent token rotation.
 * @property user                The user profile fetched during the OAuth2 exchange.
 */
data class CompanionToken(
    val session_token: String,
    val homey_id: String?,
    val homey_name: String?,
    val homey_api_url: String?,
    val athom_refresh_token: String?,
    val user: UserPayload?
)

/**
 * User profile payload from the companion app.
 */
data class UserPayload(
    val id: String?,
    val name: String?,
    val email: String?
)

/**
 * Generic Homey hub payload used locally to represent saved hubs.
 */
data class HomeyPayload(
    val id: String,
    val name: String,
    val api_url: String
)

/**
 * Request body for `POST /auth/refresh`.
 *
 * @property athom_refresh_token The refresh token obtained previously from the Athom Cloud.
 */
data class RefreshAuthRequest(val athom_refresh_token: String)

/**
 * Response body from `POST /auth/refresh`.
 *
 * @property session_token The new owner API token for the Hub.
 * @property athom_refresh_token The new refresh token to use for the next rotation.
 */
data class RefreshAuthResponse(
    val session_token: String,
    val athom_refresh_token: String
)

// ── Retrofit Service ──────────────────────────────────────────────────────────

/**
 * Retrofit interface for the two **public** endpoints exposed by the Homey AAOS Companion App.
 *
 * These endpoints do not require a Homey Web API bearer token (`"public": true`).
 * They are accessible at:
 * `https://{homeyId}.connect.athom.com/api/app/com.dimapp.aaos/`
 *
 * @see HomeyCompanionApiClient
 */
interface HomeyCompanionApiService {

    /**
     * Starts a new OAuth2 auth session on the Homey Companion App.
     *
     * The companion app calls `createOAuth2Callback` internally and returns the
     * Athom authorization URL (with `redirect_uri=callback.athom.com`).
     *
     * @public
     * @param body [StartAuthRequest] containing the AAOS-generated [StartAuthRequest.sessionId].
     * @return [StartAuthResponse] with the authorization URL to render as QR code.
     */
    @POST("auth/start")
    suspend fun startAuth(@Body body: StartAuthRequest): StartAuthResponse

    /**
     * Polls the Homey Companion App for the current status of an auth session.
     *
     * Should be called every ~3 seconds until [PollAuthResponse.status] is `"complete"`
     * or `"expired"`. When `"complete"`, the response includes [PollAuthResponse.token]
     * and the session is immediately deleted server-side (one-time delivery).
     *
     * @public
     * @param session The session identifier matching a previous [startAuth] call.
     * @return [PollAuthResponse] with the current session state.
     */
    @GET("auth/poll")
    suspend fun pollAuth(@Query("session") session: String): PollAuthResponse

    /**
     * Attempts to refresh the Hub session token using the stored refresh_secret.
     *
     * Enables silent token rotation without requiring user intervention.
     *
     * @public
     * @param body [RefreshAuthRequest] containing the valid refresh secret.
     * @return [RefreshAuthResponse] with the new session_token and new refresh_secret.
     */
    @POST("auth/refresh")
    suspend fun refreshAuth(@Body body: RefreshAuthRequest): RefreshAuthResponse
}
