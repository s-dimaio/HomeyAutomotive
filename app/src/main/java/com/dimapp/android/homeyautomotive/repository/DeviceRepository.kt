package com.dimapp.android.homeyautomotive.repository

import android.content.Context
import com.dimapp.android.homeyautomotive.BuildConfig
import com.dimapp.android.homeyautomotive.R
import com.dimapp.android.homeyautomotive.api.HomeyCompanionApiClient
import com.dimapp.android.homeyautomotive.api.models.DeviceDto
import com.dimapp.android.homeyautomotive.api.models.SetCapabilityBody
import com.dimapp.android.homeyautomotive.api.models.ZoneDto
import com.dimapp.android.homeyautomotive.repository.models.CAP_GARAGEDOOR
import com.dimapp.android.homeyautomotive.repository.models.CAP_ONOFF
import com.dimapp.android.homeyautomotive.repository.models.DOOR_CLASSES
import com.dimapp.android.homeyautomotive.repository.models.LIGHT_CLASSES
import com.dimapp.android.homeyautomotive.repository.models.HomeyDevice
import com.dimapp.android.homeyautomotive.repository.models.HomeyResult
import com.dimapp.android.homeyautomotive.auth.HomeyAuthRepository
import com.dimapp.android.homeyautomotive.storage.TokenStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log

/**
 * Repository handling device-related operations (fetching, toggling, syncing).
 */
class DeviceRepository(
    context: Context,
    storage: TokenStorage,
    authRepo: HomeyAuthRepository
) : HomeyBaseRepository(context, storage, authRepo) {
 
    private val TAG = "DeviceRepository"
    private val cacheMutex = Mutex()
    private var cachedDevices: List<HomeyDevice>? = null
    private var cachedZones: Map<String, ZoneDto>? = null
    private var cachedHubId: String? = null
    private var cachedZoneTemperatures: Map<String, Double> = emptyMap()
    private var cachedIndoorTemperature: Double? = null
    private var cachedOutdoorTemperature: Double? = null
    private var cachedThermometersConfig: Map<String, String>? = null
    private var demoDevices: MutableList<HomeyDevice>? = null

    /**
     * Returns the latest computed average temperatures mapped by zone name.
     *
     * Public method.
     *
     * @return Map of zone name to average temperature in degrees Celsius.
     * @example
     * val temps = repo.getZoneTemperatures()
     */
    fun getZoneTemperatures(): Map<String, Double> {
        return cachedZoneTemperatures
    }

    /**
     * Returns the latest computed average indoor temperature across indoor-configured thermometers.
     *
     * Public method.
     *
     * @return Average indoor temperature in degrees Celsius, or `null` if none available.
     * @example
     * val avgIndoor = repo.getAverageIndoorTemperature()
     */
    fun getAverageIndoorTemperature(): Double? {
        return cachedIndoorTemperature
    }

    /**
     * Returns the latest computed average outdoor temperature across outdoor-configured thermometers.
     *
     * Public method.
     *
     * @return Average outdoor temperature in degrees Celsius, or `null` if none available.
     * @example
     * val avgOutdoor = repo.getAverageOutdoorTemperature()
     */
    fun getAverageOutdoorTemperature(): Double? {
        return cachedOutdoorTemperature
    }

    /**
     * Returns the computed average home temperature.
     *
     * Public method.
     * Prioritizes indoor temperature, falling back to outdoor temperature if indoor is unavailable.
     *
     * @return Average temperature in degrees Celsius, or `null` if no temperature readings are available.
     * @example
     * val avgTemp = repo.getAverageHomeTemperature()
     */
    fun getAverageHomeTemperature(): Double? {
        return cachedIndoorTemperature ?: cachedOutdoorTemperature
    }

    /**
     * Silently fetches the latest thermometer classification configuration from the companion app.
     *
     * @private
     * @param hubId The ID of the currently selected Homey hub.
     */
    private suspend fun _syncThermometersConfig(hubId: String) {
        try {
            val companionService = HomeyCompanionApiClient.create(hubId, BuildConfig.DEBUG)
            cachedThermometersConfig = companionService.getThermometersConfig().config
            Log.d(TAG, "[DeviceRepository:_syncThermometersConfig] Loaded ${cachedThermometersConfig?.size ?: 0} thermometer configurations.")
        } catch (e: Exception) {
            Log.w(TAG, "[DeviceRepository:_syncThermometersConfig] Failed to fetch thermometer configuration: ${e.message}")
            if (cachedThermometersConfig == null) {
                cachedThermometersConfig = emptyMap()
            }
        }
    }

    /**
     * Calculates room temperatures and overall indoor/outdoor home averages from raw device DTOs,
     * respecting Homey's native climate exclusions and the companion app's thermometer classifications.
     *
     * Devices marked with `climate_exclude: true` or set to ignored are excluded from both room averages
     * and home indoor/outdoor calculations.
     *
     * @private
     * @param devices The map of raw [DeviceDto] objects.
     * @param zones The map of [ZoneDto] objects.
     */
    private fun _computeTemperatures(
        devices: Map<String, DeviceDto>,
        zones: Map<String, ZoneDto>
    ) {
        val zoneTempReadings = mutableMapOf<String, MutableList<Double>>()
        val indoorReadings = mutableListOf<Double>()
        val outdoorReadings = mutableListOf<Double>()

        for (dto in devices.values) {
            // Respect Homey native climate exclusion (advanced device settings or climate tab) and hidden devices
            if (dto.settings?.climateExclude == true || dto.hidden == true) {
                continue
            }

            val caps = dto.capabilitiesObj ?: continue
            val tempCap = caps["measure_temperature"]
            val tempVal = (tempCap?.value as? Number)?.toDouble() ?: continue
            if (tempVal.isNaN()) continue

            val mode = cachedThermometersConfig?.get(dto.id) ?: "indoor"
            when (mode) {
                "ignored" -> {
                    // Legacy setting: excluded from room average and from home indoor/outdoor
                }
                "outdoor" -> {
                    outdoorReadings.add(tempVal)
                    val zoneId = dto.zone
                    if (zoneId != null) {
                        zoneTempReadings.getOrPut(zoneId) { mutableListOf() }.add(tempVal)
                    }
                }
                else -> { // "indoor" or unconfigured default
                    indoorReadings.add(tempVal)
                    val zoneId = dto.zone
                    if (zoneId != null) {
                        zoneTempReadings.getOrPut(zoneId) { mutableListOf() }.add(tempVal)
                    }
                }
            }
        }

        val computedTemps = mutableMapOf<String, Double>()
        for ((zId, readings) in zoneTempReadings) {
            if (readings.isNotEmpty()) {
                val zonePathName = _buildZonePathName(zId, zones)
                computedTemps[zonePathName] = readings.average()
            }
        }
        cachedZoneTemperatures = computedTemps
        cachedIndoorTemperature = if (indoorReadings.isNotEmpty()) indoorReadings.average() else null
        cachedOutdoorTemperature = if (outdoorReadings.isNotEmpty()) outdoorReadings.average() else null
        Log.d(TAG, "[DeviceRepository:_computeTemperatures] Indoor: $cachedIndoorTemperature, Outdoor: $cachedOutdoorTemperature, Zones: ${computedTemps.size}")
    }


    /**
     * Returns all supported devices, using an in-memory cache for structure to improve performance.
     *
     * The cache is automatically invalidated if the active hub ID changes.
     * To force a network refresh, set [forceRefresh] to true.
     */
    suspend fun getDevices(forceRefresh: Boolean = false): HomeyResult<List<HomeyDevice>> = cacheMutex.withLock {
        // Handle Demo Mode directly without network interaction
        if (storage.isDemoMode()) {
            cachedZoneTemperatures = mapOf(
                context.getString(R.string.demo_zone_ground_floor) to 21.5,
                context.getString(R.string.demo_zone_outside) to 18.0
            )
            cachedIndoorTemperature = 21.5
            cachedOutdoorTemperature = 18.0
            if (demoDevices == null || forceRefresh) {
                demoDevices = _createDemoDevices().toMutableList()
                Log.d(TAG, "Initialized demo devices list with ${demoDevices?.size} mock devices.")
            }
            return HomeyResult.Success(demoDevices!!.toList())
        }

        val currentHubId = storage.getSelectedHomeyId() ?: return HomeyResult.Error(context.getString(R.string.repo_error_not_configured))
        
        // 1. Check if we have valid cached data for the current hub
        // If forceRefresh is true, we skip the cache and go straight to network.
        if (!forceRefresh && cachedHubId == currentHubId && cachedDevices != null) {
            Log.d(TAG, "Returning ${cachedDevices?.size} devices from in-memory cache.")
            return HomeyResult.Success(cachedDevices!!)
        }

        // 2. Fetch from network if cache is missing, stale, or forced
        val reason = when {
            forceRefresh -> "Forced refresh"
            cachedHubId != currentHubId -> "Hub changed ($cachedHubId -> $currentHubId)"
            else -> "Cache empty"
        }
        Log.d(TAG, "$reason. Fetching from network...")

        val service = _buildService()
        if (service == null) {
            Log.e(TAG, "Cannot fetch devices: HomeyApiService is null (check if Hub is configured).")
            return HomeyResult.Error(context.getString(R.string.repo_error_not_configured))
        }

        return try {
            val devicesResponse = service.getDevices()
            val zonesResponse = service.getZones()
            val userResponse = service.getUserMe()

            if (!devicesResponse.isSuccessful) {
                val errorBody = devicesResponse.errorBody()?.string()
                Log.e(TAG, "Devices fetch failed: HTTP ${devicesResponse.code()} - $errorBody")
                return HomeyResult.Error(context.getString(R.string.repo_error_devices_http, devicesResponse.code()))
            }
            if (!zonesResponse.isSuccessful) {
                val errorBody = zonesResponse.errorBody()?.string()
                Log.e(TAG, "Zones fetch failed: HTTP ${zonesResponse.code()} - $errorBody")
                return HomeyResult.Error(context.getString(R.string.repo_error_zones_http, zonesResponse.code()))
            }

            val devices = devicesResponse.body() ?: emptyMap()
            val zones = zonesResponse.body() ?: emptyMap()
            val favoritesList = userResponse.body()?.properties?.favoriteDevices ?: emptyList()

            // Fetch thermometer classifications from companion app and compute room / home temperatures
            if (forceRefresh || cachedThermometersConfig == null) {
                _syncThermometersConfig(currentHubId)
            }
            _computeTemperatures(devices, zones)

            val excludedIds = devices.values
                .flatMap { it.settings?.deviceIds ?: emptyList() }
                .toSet()

            val globalZoneOrder = _computeGlobalZoneOrder(zones)

            val mappedDevices = devices.values
                .filter { device -> _isSupportedDevice(device) }
                .map { device -> _toHomeyDevice(device, zones, favoritesList, excludedIds, globalZoneOrder) }
                .sortedBy { it.name }

            // 3. Update cache
            cachedDevices = mappedDevices
            cachedZones = zones
            cachedHubId = currentHubId
            
            Log.d(TAG, "[DeviceRepository:getDevices] Fetched ${mappedDevices.size} devices from network and saved to cache.")
            HomeyResult.Success(mappedDevices)
        } catch (e: Exception) {
            Log.e(TAG, "[DeviceRepository:getDevices] Exception during getDevices: ${e.message}", e)
            HomeyResult.Error(context.getString(R.string.repo_error_conn_failed, e.message ?: "Unknown error"))
        }
    }

    /**
     * Clears the in-memory device cache, forcing the next [getDevices] call to fetch from network.
     * Use this for manual refresh flows or after significant configuration changes.
     */
    fun invalidateCache() {
        Log.d(TAG, "[DeviceRepository:invalidateCache] Device cache invalidated.")
        cachedDevices = null
        cachedZones = null
        cachedHubId = null
        cachedZoneTemperatures = emptyMap()
        cachedIndoorTemperature = null
        cachedOutdoorTemperature = null
        cachedThermometersConfig = null
        demoDevices = null
    }

    /**
     * Checks if the in-memory cache is currently empty.
     */
    fun isCacheEmpty(): Boolean {
        return cachedDevices == null
    }



    /**
     * Toggles a device (door/light) by setting its primary capability.
     */
    suspend fun toggleDeviceState(
        deviceId: String,
        capabilityId: String,
        makeActive: Boolean
    ): HomeyResult<String> {
        // Handle Demo Mode optimistic state toggle
        if (storage.isDemoMode()) {
            val list = demoDevices ?: _createDemoDevices().toMutableList()
            val index = list.indexOfFirst { it.id == deviceId }
            if (index != -1) {
                list[index] = list[index].copy(isActive = makeActive)
                demoDevices = list
                Log.d(TAG, "Toggled demo device $deviceId to isActive=$makeActive")
            }
            return HomeyResult.Success(context.getString(R.string.repo_success_toggle))
        }

        val service = _buildService() ?: return HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

        val value: Any = when (capabilityId) {
            CAP_GARAGEDOOR -> !makeActive  
            "locked"       -> !makeActive  
            else           -> makeActive   
        }

        return try {
            val response = service.setCapability(
                deviceId = deviceId,
                capabilityId = capabilityId,
                body = SetCapabilityBody(value = value)
            )

            if (response.isSuccessful) {
                HomeyResult.Success(context.getString(R.string.repo_success_toggle))
            } else {
                HomeyResult.Error(context.getString(R.string.repo_error_toggle_http, response.code()))
            }
        } catch (e: Exception) {
            HomeyResult.Error(context.getString(R.string.repo_error_network, e.message ?: "Unknown error"))
        }
    }

    /**
     * Synchronizes device states using a single bulk request to Homey API (`GET /manager/devices/device`).
     *
     * Public method.
     * Efficiently fetches all device objects and capability values in a single HTTP call,
     * updates the in-memory cache, recalculates zone temperatures if zones are cached,
     * and returns the updated subset of [currentDevices].
     *
     * @public
     * @param currentDevices The list of [HomeyDevice] objects to update with fresh states.
     * @return [HomeyResult.Success] containing updated devices, or [HomeyResult.Error] on failure.
     * @example
     * val result = deviceRepository.syncDeviceStates(devices)
     */
    suspend fun syncDeviceStates(currentDevices: List<HomeyDevice>): HomeyResult<List<HomeyDevice>> {
        if (currentDevices.isEmpty()) return HomeyResult.Success(emptyList())

        // Handle Demo Mode
        if (storage.isDemoMode()) {
            val list = demoDevices ?: _createDemoDevices().toMutableList()
            demoDevices = list
            val demoMap = list.associateBy { it.id }
            val updated = currentDevices.map { demoMap[it.id] ?: it }
            return HomeyResult.Success(updated)
        }

        val service = _buildService() ?: return HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

        return try {
            val response = service.getDevices()
            if (!response.isSuccessful) {
                Log.e(TAG, "[DeviceRepository:syncDeviceStates] Bulk sync failed with HTTP ${response.code()}")
                return HomeyResult.Error(context.getString(R.string.repo_error_devices_http, response.code()))
            }

            val networkDevices = response.body() ?: emptyMap()

            // Update average room temperatures if cachedZones are available
            cachedZones?.let { zones ->
                val currentHubId = storage.getSelectedHomeyId()
                if (cachedThermometersConfig == null && currentHubId != null) {
                    _syncThermometersConfig(currentHubId)
                }
                _computeTemperatures(networkDevices, zones)
            }

            val updatedDevices = currentDevices.map { device ->
                val dto = networkDevices[device.id]
                if (dto != null) {
                    val caps = dto.capabilitiesObj ?: emptyMap()
                    val rawValue = caps[device.primaryCapability]?.value as? Boolean
                    val newIsActive = when (device.primaryCapability) {
                        CAP_GARAGEDOOR -> rawValue == false
                        "locked"       -> rawValue == false
                        else           -> rawValue == true
                    }
                    val isAvail = dto.available != false
                    device.copy(isActive = newIsActive, isAvailable = isAvail)
                } else {
                    device
                }
            }

            // Keep in-memory cache synchronized as well
            cacheMutex.withLock {
                cachedDevices = cachedDevices?.map { cached ->
                    val fresh = networkDevices[cached.id]
                    if (fresh != null) {
                        val caps = fresh.capabilitiesObj ?: emptyMap()
                        val rawValue = caps[cached.primaryCapability]?.value as? Boolean
                        val newIsActive = when (cached.primaryCapability) {
                            CAP_GARAGEDOOR -> rawValue == false
                            "locked"       -> rawValue == false
                            else           -> rawValue == true
                        }
                        cached.copy(isActive = newIsActive, isAvailable = fresh.available != false)
                    } else {
                        cached
                    }
                }
            }

            Log.d(TAG, "[DeviceRepository:syncDeviceStates] Bulk sync succeeded for ${updatedDevices.size} devices via single HTTP call.")
            HomeyResult.Success(updatedDevices)
        } catch (e: Exception) {
            Log.e(TAG, "[DeviceRepository:syncDeviceStates] Exception during bulk sync: ${e.message}", e)
            HomeyResult.Error(context.getString(R.string.repo_error_sync_failed, e.message ?: "Unknown error"))
        }
    }

    // ── Private Methods ───────────────────────────────────────────────────────

    private fun _isSupportedDevice(device: DeviceDto): Boolean {
        val clazz = device.effectiveClass?.lowercase()
        return clazz in DOOR_CLASSES || clazz in LIGHT_CLASSES
    }

    private fun _toHomeyDevice(
        device: DeviceDto,
        zones: Map<String, ZoneDto>,
        favoritesList: List<String>,
        excludedIds: Set<String>,
        globalZoneOrder: Map<String, Int>
    ): HomeyDevice {
        val caps = device.capabilitiesObj ?: emptyMap()

        val primaryCap = when {
            caps.containsKey(CAP_GARAGEDOOR) -> CAP_GARAGEDOOR
            caps.containsKey("locked")       -> "locked"
            else                             -> CAP_ONOFF
        }

        val rawValue = caps[primaryCap]?.value as? Boolean
        val isActive = when (primaryCap) {
            CAP_GARAGEDOOR -> rawValue == false  
            "locked"       -> rawValue == false  
            else           -> rawValue == true   
        }

        val zoneName = _buildZonePathName(device.zone, zones)
        val zoneOrder = device.zone?.let { globalZoneOrder[it] } ?: Int.MAX_VALUE

        val isZoneActive = device.zone?.let { zones[it]?.active } == true
        val devTemp = (caps["measure_temperature"]?.value as? Number)?.toDouble()

        return HomeyDevice(
            id = device.id,
            name = device.name,
            zoneName = zoneName,
            isActive = isActive,
            isAvailable = device.available != false,
            primaryCapability = primaryCap,
            deviceClass = device.effectiveClass,
            iconUrl = if (!device.iconOverride.isNullOrBlank()) {
                "https://my.homey.app/img/devices/${device.iconOverride}.svg"
            } else {
                device.iconObj?.url
            },
            isFavorite = favoritesList.contains(device.id),
            isLight = (device.effectiveClass?.lowercase() in LIGHT_CLASSES),
            isHidden = device.hidden == true,
            isGroupMember = excludedIds.contains(device.id),
            zoneOrder = zoneOrder,
            zoneId = device.zone,
            isZoneActive = isZoneActive,
            temperature = devTemp
        )
    }

    private fun _computeGlobalZoneOrder(zones: Map<String, ZoneDto>): Map<String, Int> {
        val childrenMap = mutableMapOf<String?, MutableList<ZoneDto>>()
        zones.values.forEach { zone ->
            val list = childrenMap.getOrPut(zone.parent) { mutableListOf() }
            list.add(zone)
        }

        childrenMap.values.forEach { list ->
            list.sortWith(
                compareBy<ZoneDto, Int?>(nullsLast()) { it.sortIndex }
                    .thenBy { it.name }
            )
        }

        val flattenedOrder = mutableMapOf<String, Int>()
        var currentIndex = 0

        fun dfs(parentId: String?) {
            val children = childrenMap[parentId] ?: return
            for (child in children) {
                flattenedOrder[child.id] = currentIndex++
                dfs(child.id)
            }
        }

        dfs(null)
        return flattenedOrder
    }

    private fun _buildZonePathName(zoneId: String?, zones: Map<String, ZoneDto>): String {
        if (zoneId == null) return context.getString(R.string.repo_zone_unknown)
        
        val path = mutableListOf<String>()
        var current: ZoneDto? = zones[zoneId]
        
        while (current != null) {
            if (current.parent != null) {
                path.add(current.name)
            }
            current = current.parent?.let { zones[it] }
        }
        
        if (path.isEmpty()) {
             return zones[zoneId]?.name ?: context.getString(R.string.repo_zone_unknown)
        }
        
        return path.reversed().joinToString(" - ")
    }

    /**
     * Creates a predefined list of mock devices for Demo Mode / Google Play Store review.
     *
     * @private
     * @return List of mock [HomeyDevice] instances.
     */
    private fun _createDemoDevices(): List<HomeyDevice> {
        return listOf(
            HomeyDevice(
                id = "demo_light_1",
                name = context.getString(R.string.demo_device_living_light),
                zoneName = context.getString(R.string.demo_zone_ground_floor),
                isActive = true,
                isAvailable = true,
                primaryCapability = CAP_ONOFF,
                deviceClass = "light",
                iconUrl = null,
                isFavorite = true,
                isLight = true,
                isHidden = false,
                isGroupMember = false,
                zoneOrder = 1,
                zoneId = "demo_zone_ground_floor",
                isZoneActive = true
            ),
            HomeyDevice(
                id = "demo_light_2",
                name = context.getString(R.string.demo_device_kitchen_light),
                zoneName = context.getString(R.string.demo_zone_ground_floor),
                isActive = false,
                isAvailable = true,
                primaryCapability = CAP_ONOFF,
                deviceClass = "light",
                iconUrl = null,
                isFavorite = true,
                isLight = true,
                isHidden = false,
                isGroupMember = false,
                zoneOrder = 1,
                zoneId = "demo_zone_ground_floor",
                isZoneActive = true
            ),
            HomeyDevice(
                id = "demo_light_3",
                name = context.getString(R.string.demo_device_garden_light),
                zoneName = context.getString(R.string.demo_zone_outside),
                isActive = false,
                isAvailable = true,
                primaryCapability = CAP_ONOFF,
                deviceClass = "light",
                iconUrl = null,
                isFavorite = false,
                isLight = true,
                isHidden = false,
                isGroupMember = false,
                zoneOrder = 2,
                zoneId = "demo_zone_outside",
                isZoneActive = false
            ),
            HomeyDevice(
                id = "demo_door_1",
                name = context.getString(R.string.demo_device_front_door),
                zoneName = context.getString(R.string.demo_zone_ground_floor),
                isActive = false, // false for lock = locked / closed
                isAvailable = true,
                primaryCapability = "locked",
                deviceClass = "lock",
                iconUrl = null,
                isFavorite = true,
                isLight = false,
                isHidden = false,
                isGroupMember = false,
                zoneOrder = 1,
                zoneId = "demo_zone_ground_floor",
                isZoneActive = true
            ),
            HomeyDevice(
                id = "demo_garage_1",
                name = context.getString(R.string.demo_device_main_garage),
                zoneName = context.getString(R.string.demo_zone_outside),
                isActive = false, // false for garagedoor = closed
                isAvailable = true,
                primaryCapability = CAP_GARAGEDOOR,
                deviceClass = "garagedoor",
                iconUrl = null,
                isFavorite = true,
                isLight = false,
                isHidden = false,
                isGroupMember = false,
                zoneOrder = 2,
                zoneId = "demo_zone_outside",
                isZoneActive = false
            )
        )
    }
}
