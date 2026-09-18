package com.dimapp.android.homeyautomotive.api

import com.dimapp.android.homeyautomotive.api.models.DashboardDto
import com.dimapp.android.homeyautomotive.api.models.DeviceDto
import com.dimapp.android.homeyautomotive.api.models.FlowDto
import com.dimapp.android.homeyautomotive.api.models.FlowFolderDto
import com.dimapp.android.homeyautomotive.api.models.FlowTriggerRequestDto
import com.dimapp.android.homeyautomotive.api.models.FlowTriggerResponseDto
import com.dimapp.android.homeyautomotive.api.models.SetCapabilityBody
import com.dimapp.android.homeyautomotive.api.models.UserMeDto
import com.dimapp.android.homeyautomotive.api.models.ZoneDto
import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * Retrofit interface for the Homey Cloud REST API v3.
 *
 * Base URL: `https://{homeyId}.connect.athom.com/api/`
 *
 * Authorization is handled transparently by [com.dimapp.android.homeyautomotive.api.TokenInjectorInterceptor],
 * which injects the OAuth2 Bearer token (and refreshes it if expired) on every request.
 * No `Authorization` parameter is required on individual methods.
 *
 * @see <a href="https://athombv.github.io/node-homey-api/HomeyAPIV3Local.ManagerDevices">Homey API v3 Docs</a>
 */
interface HomeyApiService {

    /**
     * Retrieves all devices registered in Homey.
     *
     * Returns a flat map of device UUID → [DeviceDto]. The capability values
     * and zone references are embedded in each device object.
     *
     * @public
     * @return Map of device ID → [DeviceDto], or an error [Response].
     * @example
     * ```
     * val response = service.getDevices()
     * if (response.isSuccessful) {
     *     val devices = response.body() ?: emptyMap()
     * }
     * ```
     */
    @GET("manager/devices/device")
    suspend fun getDevices(): Response<Map<String, DeviceDto>>

    /**
     * Sets the value of a specific capability on a device.
     *
     * Corresponds to `PUT /manager/devices/device/{deviceId}/capability/{capabilityId}`.
     * Used to open/close doors, toggle switches, etc.
     *
     * @public
     * @param deviceId     UUID of the target device.
     * @param capabilityId Capability identifier (e.g. `"garagedoor_closed"`, `"onoff"`).
     * @param body         [SetCapabilityBody] containing the new value.
     * @return Empty [Response] on success (HTTP 200).
     * @example
     * ```
     * service.setCapability(deviceId, "garagedoor_closed", SetCapabilityBody(false))
     * ```
     */
    @PUT("manager/devices/device/{deviceId}/capability/{capabilityId}")
    suspend fun setCapability(
        @Path("deviceId")     deviceId: String,
        @Path("capabilityId") capabilityId: String,
        @Body body: SetCapabilityBody
    ): Response<Unit>

    /**
     * Retrieves all standard Flows defined in Homey.
     *
     * Returns a flat map of flow UUID → [FlowDto]. Only flows with
     * `triggerable = true` can be manually triggered via [triggerFlow].
     *
     * @public
     * @return Map of flow ID → [FlowDto], or an error [Response].
     */
    @GET("manager/flow/flow")
    suspend fun getFlows(): Response<Map<String, FlowDto>>

    /**
     * Triggers a Homey standard Flow by its UUID.
     *
     * Only flows with `triggerable = true` can be started this way.
     *
     * @public
     * @param flowId UUID of the flow to trigger.
     * @return Empty [Response] on success (HTTP 200).
     * @example
     * ```
     * service.triggerFlow("flow-uuid-here")
     * ```
     */
    @POST("manager/flow/flow/{flowId}/trigger")
    suspend fun triggerFlow(
        @Path("flowId") flowId: String
    ): Response<Unit>

    /**
     * Retrieves all Advanced Flows defined in Homey.
     *
     * Returns a flat map of advanced flow UUID → [FlowDto].
     *
     * @public
     * @return Map of advanced flow ID → [FlowDto], or an error [Response].
     */
    @GET("manager/flow/advancedflow")
    suspend fun getAdvancedFlows(): Response<Map<String, FlowDto>>

    /**
     * Triggers a Homey Advanced Flow by its UUID.
     *
     * @public
     * @param flowId UUID of the advanced flow to trigger.
     * @return Empty [Response] on success (HTTP 200).
     */
    @POST("manager/flow/advancedflow/{flowId}/trigger")
    suspend fun triggerAdvancedFlow(
        @Path("flowId") flowId: String
    ): Response<Unit>

    /**
     * Retrieves all flow folders defined in Homey.
     *
     * Returns a flat map of folder UUID → [FlowFolderDto].
     *
     * @public
     * @return Map of folder ID → [FlowFolderDto], or an error [Response].
     */
    @GET("manager/flow/flowfolder")
    suspend fun getFlowFolders(): Response<Map<String, FlowFolderDto>>

    /**
     * Retrieves all zones (rooms) defined in Homey.
     *
     * Returns a flat map of zone UUID → [ZoneDto]. Zones form a hierarchy
     * via the [ZoneDto.parent] reference.
     *
     * @public
     * @return Map of zone ID → [ZoneDto], or an error [Response].
     */
    @GET("manager/zones/zone")
    suspend fun getZones(): Response<Map<String, ZoneDto>>

    /**
     * Retrieves the current value object of a specific capability for a device.
     *
     * Used for lightweight polling of individual capability states.
     *
     * @public
     * @param deviceId     UUID of the target device.
     * @param capabilityId Capability identifier.
     * @return A map containing the capability properties, e.g. `{"value": true}`.
     */
    @GET("manager/devices/device/{deviceId}/capability/{capabilityId}")
    suspend fun getCapabilityObject(
        @Path("deviceId")     deviceId: String,
        @Path("capabilityId") capabilityId: String
    ): Response<Any>

    /**
     * Retrieves the current user profile, including favorite devices.
     *
     * Used primarily to extract the [UserMeDto.properties.favoriteDevices] list.
     *
     * @public
     * @return [UserMeDto] mapped response or an error [Response].
     */
    @GET("manager/users/user/me")
    suspend fun getUserMe(): Response<UserMeDto>

    /**
     * Retrieves all dashboards as structured data.
     *
     * Uses the confirmed schema: each dashboard has `columns → widgets → data.deviceIds`.
     *
     * @public
     * @return Map of dashboard ID → [DashboardDto].
     */
    @GET("app/com.dimapp.aaos/dashboards")
    suspend fun getDashboards(): Response<Map<String, DashboardDto>>

    /**
     * Triggers a Flow (standard or advanced) via the Companion App HomeyScript proxy.
     *
     * @public
     * @param body [FlowTriggerRequestDto] with flow ID and isAdvanced flag.
     * @return [Response] containing [FlowTriggerResponseDto].
     */
    @POST("app/com.dimapp.aaos/flow/trigger")
    suspend fun triggerCompanionFlow(@Body body: FlowTriggerRequestDto): Response<FlowTriggerResponseDto>
}


