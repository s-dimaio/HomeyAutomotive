package com.dimapp.android.homeyautomotive.api

import com.google.gson.annotations.SerializedName
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
data class StartAuthRequest(
    @SerializedName("sessionId") val sessionId: String
)

/**
 * Response body from `POST /auth/start`.
 *
 * @property authUrl The Athom authorization URL (with `redirect_uri=callback.athom.com/...`)
 *                   to be displayed as a QR code on the car screen.
 */
data class StartAuthResponse(
    @SerializedName("authUrl") val authUrl: String
)

/**
 * Response body from `GET /auth/poll`.
 *
 * @property status   Current session state: `"pending"`, `"complete"`, or `"expired"`.
 * @property token    Token payload, present only when [status] is `"complete"`.
 */
data class PollAuthResponse(
    @SerializedName("status") val status: String,
    @SerializedName("token") val token: CompanionToken? = null,
)

/**
 * Geographical coordinates of the Homey hub.
 */
data class HomeLocationDto(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double
)

/**
 * Barrier device IDs configured for near-home proximity notifications.
 */
data class GeofenceConfigDto(
    @SerializedName("deviceIds") val deviceIds: List<String> = emptyList()
)

/**
 * Thermometer classification configuration mapping device ID to mode:
 * "indoor", "outdoor", or "ignored".
 */
data class ThermometersConfigDto(
    @SerializedName("config") val config: Map<String, String> = emptyMap()
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
 * @property user                The user profile payload.
 * @property location            Geographical coordinates of the Homey hub.
 */
data class CompanionToken(
    @SerializedName("session_token") val session_token: String,
    @SerializedName("homey_id") val homey_id: String?,
    @SerializedName("homey_name") val homey_name: String?,
    @SerializedName("homey_api_url") val homey_api_url: String?,
    @SerializedName("athom_refresh_token") val athom_refresh_token: String?,
    @SerializedName("user") val user: UserPayload?,
    @SerializedName("location") val location: HomeLocationDto? = null
)

/**
 * User profile payload from the companion app.
 */
data class UserPayload(
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("email") val email: String?
)

/**
 * Generic Homey hub payload used locally to represent saved hubs.
 */
data class HomeyPayload(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("api_url") val api_url: String
)

/**
 * Request body for `POST /auth/refresh`.
 *
 * @property athom_refresh_token The refresh token obtained previously from the Athom Cloud.
 */
data class RefreshAuthRequest(
    @SerializedName("athom_refresh_token") val athom_refresh_token: String
)

/**
 * Response body from `POST /auth/refresh`.
 *
 * @property session_token The new owner API token for the Hub.
 * @property athom_refresh_token The new refresh token to use for the next rotation.
 */
data class RefreshAuthResponse(
    @SerializedName("session_token") val session_token: String,
    @SerializedName("athom_refresh_token") val athom_refresh_token: String
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

    /**
     * Retrieves the home coordinates configured on the Homey Pro hub.
     *
     * @public
     * @return [HomeLocationDto] containing latitude and longitude.
     */
    @GET("location")
    suspend fun getLocation(): HomeLocationDto

    /**
     * Retrieves the list of barrier device IDs configured for proximity alerts.
     *
     * @public
     * @return [GeofenceConfigDto] containing configured device IDs.
     */
    @GET("geofence")
    suspend fun getGeofenceConfig(): GeofenceConfigDto

    /**
     * Retrieves the thermometer configuration mapping device IDs to classification modes.
     *
     * @public
     * @return [ThermometersConfigDto] containing map of device ID to mode ("indoor", "outdoor", "ignored").
     */
    @GET("thermometers")
    suspend fun getThermometersConfig(): ThermometersConfigDto
}
