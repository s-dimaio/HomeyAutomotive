package com.dimapp.android.homeyautomotive.screens

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.dimapp.android.homeyautomotive.R
import com.dimapp.android.homeyautomotive.api.models.FlowDto
import com.dimapp.android.homeyautomotive.core.DependencyManager
import com.dimapp.android.homeyautomotive.repository.models.HomeyDevice
import com.dimapp.android.homeyautomotive.repository.DeviceRepository
import com.dimapp.android.homeyautomotive.repository.DashboardRepository
import com.dimapp.android.homeyautomotive.repository.FlowRepository
import com.dimapp.android.homeyautomotive.repository.models.HomeyResult
import com.dimapp.android.homeyautomotive.storage.HomeSource
import com.dimapp.android.homeyautomotive.storage.TokenStorage
import com.dimapp.android.homeyautomotive.screens.helpers.MainTabIconManager
import com.dimapp.android.homeyautomotive.screens.helpers.MainTabRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "MainTabScreen"

/**
 * Main dashboard using TabTemplate (API Level 6+ required, supported on API 33+ emulators and Volvo).
 */

class MainTabScreen(carContext: CarContext) : Screen(carContext) {

    private val deviceRepository = DependencyManager.getDeviceRepository(carContext)
    private val dashboardRepository = DependencyManager.getDashboardRepository(carContext)
    private val flowRepository = DependencyManager.getFlowRepository(carContext)
    private val storage = DependencyManager.getTokenStorage(carContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var isLoading = true
    private var errorMessage: String? = null
    private var devices: List<HomeyDevice> = emptyList()

    private var flows: List<FlowDto> = emptyList()
    private var flowsErrorMessage: String? = null
    private var isFlowsLoading = false
    private var triggeringFlowId: String? = null
    private var flowsLoadJob: Job? = null

    /** Cache: dashboardId -> Set of device IDs belonging to that dashboard. Populated on load. */
    private val dashboardDeviceIdsCache = mutableMapOf<String, Set<String>>()

    private var activeTabId: String = "tab_home"
    private var syncJob: Job? = null
    private var loadJob: Job? = null

    /** Tracks the last used source, dashboard and hub to detect changes when returning from settings. */
    private var lastHomeSource: HomeSource? = null
    private var lastDashboardId: String? = null
    private var lastActiveHubId: String? = null
    private var lastAvgIndoorTemp: Double? = null
    private var lastAvgOutdoorTemp: Double? = null

    /** Helper for icon caching and async downloading. */
    private val iconManager = MainTabIconManager(
        carContext,
        scope,
        onInvalidateNeeded = { invalidate() }
    )

    /** Helper for building AAOS Templates. */
    private val renderer = MainTabRenderer(
        carContext,
        iconManager,
        onToggleRequested = { device -> _toggleDevice(device) },
        onRetryRequested = { _loadDevices(force = true) }
    )

    /** Flag indicating if a specific dashboard is currently being downloaded. */
    private var isFetchingDashboard = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                _checkAndRefresh()
                _startPeriodicSync()
            }
            override fun onStop(owner: LifecycleOwner) {
                _stopPeriodicSync()
            }
            override fun onDestroy(owner: LifecycleOwner) {
                iconManager.cancelPendingJobs()
                loadJob?.cancel()
                flowsLoadJob?.cancel()
                _stopPeriodicSync()
                scope.cancel()
            }
        })
    }

    /**
     * Builds the main template for the screen.
     *
     * Public method.
     * Overrides the standard [Screen.onGetTemplate] to return a [TabTemplate].
     *
     * @return The [Template] to be rendered by the Car App host.
     * @example
     * val template = screen.onGetTemplate()
     */
    override fun onGetTemplate(): Template {
        Log.d(TAG, "onGetTemplate: loading=$isLoading, error=$errorMessage, devices=${devices.size}, tab=$activeTabId")

        val tabCallback = object : TabTemplate.TabCallback {
            override fun onTabSelected(tabContentId: String) {
                Log.d(TAG, "onTabSelected: $tabContentId")
                if (tabContentId == "tab_settings") {
                    screenManager.push(SettingsScreen(carContext))
                    invalidate()
                } else if (activeTabId != tabContentId) {
                    activeTabId = tabContentId
                    
                    // Sync-on-Entry: When switching to the devices tab, trigger a one-shot 
                    // state refresh to ensure data is fresh even if polling is disabled.
                    if (activeTabId == "tab_devices") {
                        _syncActiveTabDevices()
                    } else if (activeTabId == "tab_flows" && flows.isEmpty() && !isFlowsLoading) {
                        _loadFlows()
                    }
                    
                    invalidate()
                } else {
                    // Re-tapping the active tab acts as a manual refresh
                    if (activeTabId == "tab_flows") {
                        _loadFlows(force = true)
                    } else if (activeTabId == "tab_devices" || activeTabId == "tab_home") {
                        _loadDevices(silent = true, force = true)
                    }
                }
            }
        }

        val contentTemplate = _buildContentForCurrentState()

        return TabTemplate.Builder(tabCallback)
            .setHeaderAction(Action.APP_ICON)
            .setActiveTabContentId(activeTabId)
            .addTab(
                Tab.Builder()
                    .setTitle(carContext.getString(R.string.tab_home_title))
                    .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_tab_home)).build())
                    .setContentId("tab_home")
                    .build()
            )
            .addTab(
                Tab.Builder()
                    .setTitle(carContext.getString(R.string.tab_devices_title))
                    .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_tab_devices)).build())
                    .setContentId("tab_devices")
                    .build()
            )
            .addTab(
                Tab.Builder()
                    .setTitle(carContext.getString(R.string.tab_flows_title))
                    .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_tab_flows)).build())
                    .setContentId("tab_flows")
                    .build()
            )
            .addTab(
                Tab.Builder()
                    .setTitle(carContext.getString(R.string.tab_settings_title))
                    .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_settings)).build())
                    .setContentId("tab_settings")
                    .build()
            )
            .setTabContents(TabContents.Builder(contentTemplate).build())
            .build()
    }

    // ── Private Methods ──────────────────────────────────────────────────────────────

    /**
     * Returns the Template for the current Tab body.
     * Must not contain Title or HeaderAction, as they are incompatible with TabTemplate in AAOS.
     *
     * @private
     * @return [Template] for the current active tab content.
     */
    private fun _buildContentForCurrentState(): Template {
        if (isLoading) {
            return renderer.buildLoadingTemplate()
        }

        if (errorMessage != null) {
            return renderer.buildErrorTemplate(errorMessage!!)
        }

        return when (activeTabId) {
            "tab_home" -> _buildHomeGridTemplate()
            "tab_devices" -> renderer.buildDevicesUnifiedList(
                devices = devices,
                zoneTemperatures = deviceRepository.getZoneTemperatures(),
                avgIndoorTemperature = deviceRepository.getAverageIndoorTemperature(),
                avgOutdoorTemperature = deviceRepository.getAverageOutdoorTemperature()
            )
            "tab_flows" -> {
                if (isFlowsLoading && flows.isEmpty()) {
                    renderer.buildLoadingTemplate()
                } else {
                    renderer.buildFlowsList(
                        flows = flows,
                        triggeringFlowId = triggeringFlowId,
                        onTriggerRequested = { flow -> _triggerFlow(flow) },
                        onRetryRequested = { _loadFlows(force = true) },
                        errorMessage = flowsErrorMessage
                    )
                }
            }
            else -> _buildHomeGridTemplate()
        }
    }


    /**
     * Initiates the loading of all devices from the repository.
     *
     * Private method.
     * Performs an asynchronous fetch of devices and optionally dashboard IDs.
     * Updates [isLoading] and [errorMessage] based on result.
     *
     * @private
     * @param silent If true, skips setting [isLoading] to true and [invalidate], 
     *               performing the update in the background.
     * @example
     * _loadDevices(silent = true)
     */
    private fun _loadDevices(silent: Boolean = false, force: Boolean = false) {
        if (loadJob?.isActive == true) {
            if (silent) {
                Log.d(TAG, "_loadDevices: skip silent refresh as a job is already active.")
                return
            }
            Log.d(TAG, "_loadDevices: manual refresh requested while job is active. Cancelling existing job and restarting.")
            loadJob?.cancel()
        }
        
        Log.d(TAG, "_loadDevices: starting (silent=$silent, force=$force)")
        
        if (!silent) {
            isLoading = true
            errorMessage = null
            if (flows.isEmpty()) {
                isFlowsLoading = true
            }
            invalidate()
        }

        loadJob = scope.launch {
            val source = storage.getHomeSource()
            val dashboardId = storage.getHomeDashboardId()
            val hubId = storage.getSelectedHomeyId()
            
            lastHomeSource = source
            lastDashboardId = dashboardId
            lastActiveHubId = hubId

            // INVALIDATE CACHE for the specific dashboard to force a fresh fetch from the companion app.
            // This is critical for the "Virtual Dashboard" which has a static ID but dynamic content.
            dashboardId?.let { dashboardDeviceIdsCache.remove(it) }

            // Silently synchronize geofence configuration (home coordinates & barrier selection)
            if (storage.isGeofenceEnabled() && !storage.isDemoMode()) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val authRepo = DependencyManager.getAuthRepository(carContext)
                        authRepo.refreshHomeLocation(hubId)
                    } catch (e: Exception) {
                        Log.w(TAG, "[MainTabScreen:_loadData] Silent geofence sync failed: ${e.message}")
                    }
                }
            }

            // Executes fetch in parallel to avoid double loading spinners
            // If force is true, we bypass the repository caches.
            val devicesDeferred = async { deviceRepository.getDevices(forceRefresh = force) }
            val dashboardDeferred = if (source == HomeSource.DASHBOARD && dashboardId != null) {
                async { dashboardRepository.getDashboardDeviceIds(dashboardId) }
            } else null
            val flowsDeferred = async { flowRepository.getTriggerableFlows(forceRefresh = force) }

            val flowsResult = flowsDeferred.await()
            when (flowsResult) {
                is HomeyResult.Success -> {
                    flows = flowsResult.data
                    flowsErrorMessage = null
                    isFlowsLoading = false
                    Log.d(TAG, "_loadDevices: successfully prefetched ${flows.size} flows")
                }
                is HomeyResult.Error -> {
                    Log.w(TAG, "_loadDevices: error prefetching flows -> ${flowsResult.message}")
                    flowsErrorMessage = flowsResult.message
                    isFlowsLoading = false
                }
            }

            when (val result = devicesDeferred.await()) {
                is HomeyResult.Success -> {
                    Log.d(TAG, "_loadDevices: success, ${result.data.size} devices")
                    val newDevices = result.data

                    // If we also launched the dashboard fetch, wait for the result
                    if (dashboardDeferred != null && dashboardId != null) {
                        when (val dashResult = dashboardDeferred.await()) {
                            is HomeyResult.Success -> {
                                dashboardDeviceIdsCache[dashboardId] = dashResult.data
                                Log.d(TAG, "_loadDevices: prefetched ${dashResult.data.size} dashboard IDs")
                            }
                            is HomeyResult.Error -> {
                                Log.w(TAG, "_loadDevices: error prefetching dashboard -> ${dashResult.message}")
                            }
                        }
                    }

                    devices = newDevices
                    isLoading = false
                    errorMessage = null

                    // Initial Sync-on-Entry logic for devices tab
                    if (activeTabId == "tab_devices") {
                        _syncActiveTabDevices()
                    }

                    invalidate()
                }
                is HomeyResult.Error -> {
                    Log.e(TAG, "_loadDevices: error=${result.message}")
                    // Only show formal error screen if we are not in silent mode OR if we have no devices
                    if (!silent || devices.isEmpty()) {
                        errorMessage = result.message
                        isLoading = false
                        invalidate()
                    }
                }
            }
            loadJob = null
        }
    }

    /**
     * Executes a one-shot device state synchronization for all devices visible in the 
     * currently active tab. This is used by the "Sync-on-Entry" logic to ensure fresh
     * states are displayed without waiting for the next periodic polling cycle.
     *
     * Runs asynchronously in the background.
     *
     * @private
     */
    private fun _syncActiveTabDevices() {
        if (devices.isEmpty() || activeTabId == "tab_flows") return
        
        scope.launch {
            Log.d(TAG, "[MainTabScreen:_syncActiveTabDevices] Triggered for $activeTabId")
            
            when (val result = deviceRepository.syncDeviceStates(devices)) {
                is HomeyResult.Success -> {
                    val updatedMap = result.data.associateBy { it.id }
                    var hasChanges = false
                    val newDevices = devices.map { current ->
                        val fresh = updatedMap[current.id]
                        if (fresh != null && (fresh.isActive != current.isActive || fresh.isAvailable != current.isAvailable)) {
                            hasChanges = true
                            fresh
                        } else {
                            current
                        }
                    }
                    val currentAvgIndoor = deviceRepository.getAverageIndoorTemperature()
                    val currentAvgOutdoor = deviceRepository.getAverageOutdoorTemperature()
                    if (currentAvgIndoor != lastAvgIndoorTemp || currentAvgOutdoor != lastAvgOutdoorTemp) {
                        hasChanges = true
                        lastAvgIndoorTemp = currentAvgIndoor
                        lastAvgOutdoorTemp = currentAvgOutdoor
                    }
                    if (hasChanges) {
                        Log.d(TAG, "[MainTabScreen:_syncActiveTabDevices] State changes detected, invalidating UI.")
                        devices = newDevices
                        invalidate()
                    } else {
                        Log.d(TAG, "[MainTabScreen:_syncActiveTabDevices] No state changes detected.")
                    }
                }
                is HomeyResult.Error -> {
                    Log.w(TAG, "[MainTabScreen:_syncActiveTabDevices] Sync failed: ${result.message}")
                }
            }
        }
    }


    /**
     * Checks if the home configuration has changed (after returning from settings) 
     * and triggers a reload if necessary.
     */
    private fun _checkAndRefresh() {
        val currentSource = storage.getHomeSource()
        val currentDashboardId = storage.getHomeDashboardId()
        val currentHubId = storage.getSelectedHomeyId()

        val hubChanged = currentHubId != lastActiveHubId

        // Reload only if:
        // - It is the first run (lastHomeSource has never been set), OR
        // - The user explicitly changed the Home tab source or selected dashboard in Settings, OR
        // - The active Homey Hub changed, OR
        // - The device or flow cache was invalidated externally.
        val configChanged = lastHomeSource == null ||
                           currentSource != lastHomeSource ||
                           currentDashboardId != lastDashboardId ||
                           hubChanged

        val needsReload = configChanged || deviceRepository.isCacheEmpty() || flowRepository.isCacheEmpty()

        if (needsReload) {
            if (configChanged) {
                flowRepository.invalidateCache()
            }
            // Show a loading spinner (not silent) if the list is empty OR if the hub changed
            val isSilent = devices.isNotEmpty() && !hubChanged
            
            // Force-bypass the repository cache if the source configuration changed or caches were invalidated.
            // When the hub changes, DeviceRepository already ignores its own cache internally.
            val shouldForce = (configChanged && currentSource == HomeSource.DASHBOARD) || 
                              deviceRepository.isCacheEmpty() || 
                              flowRepository.isCacheEmpty()
            
            Log.d(TAG, "_checkAndRefresh: reload needed (configChanged=$configChanged, hubChanged=$hubChanged, cacheEmpty=${deviceRepository.isCacheEmpty()}, flowCacheEmpty=${flowRepository.isCacheEmpty()}, silent=$isSilent, force=$shouldForce)")
            _loadDevices(silent = isSilent, force = shouldForce)
        }
    }

    /**
     * Fetches IDs for the specified dashboard.
     * This function sets [isFetchingDashboard] to avoid overlapping multiple calls.
     *
     * @private
     * @param dashboardId UUID of the target Homey dashboard.
     */
    private fun _prefetchDashboardDeviceIds(dashboardId: String) {
        if (dashboardDeviceIdsCache.containsKey(dashboardId) || isFetchingDashboard) return

        isFetchingDashboard = true
        
        scope.launch {
            when (val result = dashboardRepository.getDashboardDeviceIds(dashboardId)) {
                is HomeyResult.Success -> {
                    dashboardDeviceIdsCache[dashboardId] = result.data
                    Log.d(TAG, "_prefetchDashboardDeviceIds: cached ${dashboardDeviceIdsCache[dashboardId]?.size} IDs for dashboard $dashboardId")
                    isFetchingDashboard = false
                    invalidate()
                }
                is HomeyResult.Error -> {
                    Log.w(TAG, "_prefetchDashboardDeviceIds: error=${result.message}")
                    errorMessage = carContext.getString(R.string.main_error_dashboard_load, result.message)
                    isFetchingDashboard = false
                    invalidate()
                }
            }
        }
    }

    /**
     * Filters the [devices] list based on the active tab and user preferences.
     *
     * Private method.
     *
     * @private
     * @return A list of [HomeyDevice] objects visible in the current view.
     * @example
     * val visible = _getVisibleDevices()
     */
    private fun _getVisibleDevices(): List<HomeyDevice> {
        return when (activeTabId) {
            "tab_home" -> {
                val source = storage.getHomeSource()
                val dashboardId = storage.getHomeDashboardId()
                val dashboardList = if (dashboardId != null) _dashboardDeviceIds(dashboardId) else emptySet()

                if (source == HomeSource.DASHBOARD) {
                    val dashboardList = if (dashboardId != null) _dashboardDeviceIds(dashboardId) else emptySet()
                    devices.filter { device -> device.id in dashboardList }
                } else {
                    devices.filter { it.isFavorite }
                }
            }
            "tab_devices" -> devices.filter { !it.isHidden && !it.isGroupMember }
            else -> emptyList()
        }
    }

    /**
     * Starts the periodic synchronization of device states.
     *
     * Private method.
     * Initiates a coroutine that periodically calls [_refreshDeviceStates] 
     * based on the interval stored in [storage].
     *
     * @private
     * @example
     * _startPeriodicSync()
     */
    private fun _startPeriodicSync() {
        syncJob?.cancel()

        val initialInterval = storage.getSyncInterval()
        if (initialInterval <= 0) {
            Log.d(TAG, "[MainTabScreen:_startPeriodicSync] Polling disabled (interval = $initialInterval)")
            return
        }

        Log.d(TAG, "[MainTabScreen:_startPeriodicSync] Starting polling every $initialInterval seconds")
        syncJob = scope.launch {
            while (isActive) {
                // Re-read the interval on every cycle so that changes made in Settings
                // are immediately reflected without requiring an app restart.
                val currentInterval = storage.getSyncInterval()
                if (currentInterval <= 0) {
                    Log.d(TAG, "[MainTabScreen:_startPeriodicSync] Polling disabled in cycle, exiting.")
                    break
                }
                delay(currentInterval * 1000L)
                _refreshDeviceStates()
            }
        }
    }

    /**
     * Stops the periodic synchronization of device states.
     *
     * Private method.
     * Cancels the active [syncJob].
     *
     * @private
     * @example
     * _stopPeriodicSync()
     */
    private fun _stopPeriodicSync() {
        syncJob?.cancel()
        syncJob = null
    }

    /**
     * Performs a one-time bulk refresh of states for all devices.
     *
     * Private method.
     * Fetches current states from the repository via a single HTTP bulk call and updates [devices] if changes are found.
     * Skips execution when the Flows tab is active to avoid unnecessary network traffic.
     *
     * @private
     * @example
     * _refreshDeviceStates()
     */
    private fun _refreshDeviceStates() {
        if (devices.isEmpty() || isLoading || activeTabId == "tab_flows") {
            Log.v(TAG, "[MainTabScreen:_refreshDeviceStates] Skip (empty=${devices.isEmpty()}, loading=$isLoading, tab=$activeTabId)")
            return
        }

        Log.d(TAG, "[MainTabScreen:_refreshDeviceStates] Starting bulk sync for ${devices.size} devices (Tab: $activeTabId)")
        scope.launch {
            when (val result = deviceRepository.syncDeviceStates(devices)) {
                is HomeyResult.Success -> {
                    var isChanged = false
                    val updatedList = result.data
                    val updatedMap = updatedList.associateBy { it.id }

                    // Reconciles the status of all devices
                    val newDevices = devices.map { current ->
                        val updated = updatedMap[current.id]
                        if (updated != null && (updated.isActive != current.isActive || updated.isAvailable != current.isAvailable)) {
                            isChanged = true
                            updated
                        } else {
                            current
                        }
                    }

                    val currentAvgIndoor = deviceRepository.getAverageIndoorTemperature()
                    val currentAvgOutdoor = deviceRepository.getAverageOutdoorTemperature()
                    if (currentAvgIndoor != lastAvgIndoorTemp || currentAvgOutdoor != lastAvgOutdoorTemp) {
                        isChanged = true
                        lastAvgIndoorTemp = currentAvgIndoor
                        lastAvgOutdoorTemp = currentAvgOutdoor
                    }

                    if (isChanged) {
                        Log.d(TAG, "[MainTabScreen:_refreshDeviceStates] Changes detected, invalidating interface.")
                        devices = newDevices
                        invalidate()
                    } else {
                        Log.d(TAG, "[MainTabScreen:_refreshDeviceStates] No changes detected across ${devices.size} devices.")
                    }
                }
                is HomeyResult.Error -> {
                    Log.e(TAG, "[MainTabScreen:_refreshDeviceStates] Bulk sync error: ${result.message}")
                }
            }
        }
    }

    /**
     * Toggles the power/activation state of a specific device.
     *
     * Private method.
     * Optimistically updates the UI state and sends the command to Homey.
     * Reverts on error.
     *
     * @private
     * @param device The [HomeyDevice] to toggle.
     * @example
     * _toggleDevice(myDevice)
     */
    private fun _toggleDevice(device: HomeyDevice) {
        val newState = !device.isActive
        Log.d(TAG, "_toggleDevice: ${device.name} -> $newState")
        devices = devices.map { if (it.id == device.id) it.copy(isActive = newState) else it }
        invalidate()

        scope.launch {
            val result = deviceRepository.toggleDeviceState(device.id, device.primaryCapability, newState)
            if (result is HomeyResult.Error) {
                Log.e(TAG, "_toggleDevice: error=${result.message}, reverting")
                devices = devices.map { if (it.id == device.id) it.copy(isActive = device.isActive) else it }
                invalidate()
            }
        }
    }

    private fun _buildHomeGridTemplate(): Template {
        val source = storage.getHomeSource()
        val dashboardId = storage.getHomeDashboardId()

        // Lazy-load dashboard devices if needed
        if (source == HomeSource.DASHBOARD && dashboardId != null) {
            if (!dashboardDeviceIdsCache.containsKey(dashboardId)) {
                _prefetchDashboardDeviceIds(dashboardId)
                return renderer.buildLoadingTemplate()
            }
        }

        val homeDevices = if (source == HomeSource.DASHBOARD) {
            val dashboardList = if (dashboardId != null) _dashboardDeviceIds(dashboardId) else emptySet()
            devices.filter { device -> device.id in dashboardList }
        } else {
            devices.filter { it.isFavorite }
        }

        return renderer.buildHomeGrid(
            devices = homeDevices,
            isDashboard = (source == HomeSource.DASHBOARD)
        )
    }

    private fun _dashboardDeviceIds(dashboardId: String): Set<String> {
        return dashboardDeviceIdsCache[dashboardId] ?: emptySet()
    }

    /**
     * Loads all triggerable flows independently from the repository.
     *
     * Private method.
     * Updates [flows], [isFlowsLoading], and [flowsErrorMessage], and notifies the host.
     *
     * @param force If true, forces a network fetch bypassing any in-memory cache.
     * @example
     * _loadFlows(force = true)
     */
    private fun _loadFlows(force: Boolean = false) {
        if (flowsLoadJob?.isActive == true) {
            flowsLoadJob?.cancel()
        }

        isFlowsLoading = true
        flowsErrorMessage = null
        invalidate()

        flowsLoadJob = scope.launch {
            Log.d(TAG, "_loadFlows: fetching flows (force=$force)")
            when (val result = flowRepository.getTriggerableFlows(forceRefresh = force)) {
                is HomeyResult.Success -> {
                    flows = result.data
                    flowsErrorMessage = null
                    isFlowsLoading = false
                    Log.d(TAG, "_loadFlows: successfully loaded ${flows.size} flows")
                }
                is HomeyResult.Error -> {
                    flowsErrorMessage = result.message
                    isFlowsLoading = false
                    Log.e(TAG, "_loadFlows: error -> ${result.message}")
                }
            }
            invalidate()
            flowsLoadJob = null
        }
    }

    /**
     * Triggers execution of a specific Homey Flow.
     *
     * Private method.
     * Sets [triggeringFlowId] to render transient execution feedback and prevent concurrent requests.
     * Displays a [CarToast] indicating success or error when completed.
     *
     * @param flow The [FlowDto] to trigger.
     * @example
     * _triggerFlow(flow)
     */
    private fun _triggerFlow(flow: FlowDto) {
        if (triggeringFlowId != null) return

        triggeringFlowId = flow.id
        invalidate()

        scope.launch {
            Log.d(TAG, "_triggerFlow: triggering flow '${flow.name}' (${flow.id}) [isAdvanced=${flow.isAdvanced}]")
            val result = flowRepository.triggerFlow(flow.id, flow.isAdvanced)
            triggeringFlowId = null

            CarToast.makeText(
                carContext,
                when (result) {
                    is HomeyResult.Success -> carContext.getString(R.string.flows_started_toast, flow.name)
                    is HomeyResult.Error -> carContext.getString(R.string.flows_error_toast, result.message)
                },
                if (result is HomeyResult.Success) CarToast.LENGTH_SHORT else CarToast.LENGTH_LONG
            ).show()

            invalidate()
        }
    }
}
