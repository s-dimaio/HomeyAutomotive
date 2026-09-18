package com.dimapp.android.homeyautomotive.screens.helpers

import androidx.car.app.CarContext
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
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.dimapp.android.homeyautomotive.R
import com.dimapp.android.homeyautomotive.api.models.FlowDto
import com.dimapp.android.homeyautomotive.repository.models.HomeyDevice
import java.util.Locale

/**
 * Helper class responsible for building AAOS Templates for the Main Tab.
 * 
 * Separates the UI construction logic from state management.
 *
 * @param carContext The Car App context for resources.
 * @param iconManager Manager to retrieve icons for devices.
 * @param onToggleRequested Callback for device toggle actions.
 * @param onRetryRequested Callback for retry actions in error states.
 */
class MainTabRenderer(
    private val carContext: CarContext,
    private val iconManager: MainTabIconManager,
    private val onToggleRequested: (HomeyDevice) -> Unit,
    private val onRetryRequested: () -> Unit
) {

    /**
     * Builds a simple loading template (spinner).
     *
     * Public method.
     *
     * @return [Template] with loading spinner.
     * @example
     * val template = renderer.buildLoadingTemplate()
     */
    fun buildLoadingTemplate(): Template {
        return ListTemplate.Builder()
            .setLoading(true)
            .build()
    }

    /**
     * Builds an error message template with a retry button.
     *
     * Public method.
     *
     * @param message The error message to display.
     * @return [Template] containing the error notice and retry action.
     * @example
     * val template = renderer.buildErrorTemplate("Timeout")
     */
    fun buildErrorTemplate(message: String): Template {
        return MessageTemplate.Builder(carContext.getString(R.string.main_error_load_devices, message))
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.main_retry_button))
                    .setBackgroundColor(CarColor.BLUE)
                    .setOnClickListener { onRetryRequested() }
                    .build()
            )
            .build()
    }

    /**
     * Builds a grid of devices for the Home/Dashboard tab.
     *
     * Public method.
     *
     * @param devices The list of devices to display.
     * @param isDashboard Whether the source is a custom dashboard (`true`) or favorites (`false`).
     * @return [Template] displaying the devices in a grid layout.
     * @example
     * val template = renderer.buildHomeGrid(favorites, false)
     */
    fun buildHomeGrid(devices: List<HomeyDevice>, isDashboard: Boolean): Template {
        val itemBuilder = ItemList.Builder()

        if (devices.isEmpty()) {
            val msg = if (isDashboard) carContext.getString(R.string.main_no_dashboard_devices)
                      else carContext.getString(R.string.main_no_favorites)
            itemBuilder.setNoItemsMessage(msg)
        } else {
            devices.forEach { device ->
                itemBuilder.addItem(_buildDeviceGridItem(device))
            }
        }

        return GridTemplate.Builder()
            .setSingleList(itemBuilder.build())
            .build()
    }

    /**
     * Builds a unified sectioned list of all devices grouped by zone for the Devices tab.
     *
     * Public method.
     * Precedes the room sections with a Home Overview summary row (showing total open barriers
     * and active lights with dynamic green/yellow color). Room headers include safe Unicode indentation (`\u2002`),
     * the active blue dot indicator, and room metrics (average temperature, lights on, open barriers).
     *
     * @param devices The complete list of devices.
     * @param zoneTemperatures Map of zone name to computed average temperature in Celsius.
     * @param avgIndoorTemperature Computed average indoor temperature of the house in Celsius, or null.
     * @param avgOutdoorTemperature Computed average outdoor temperature of the house in Celsius, or null.
     * @return [Template] with the unified devices list.
     * @example
     * val template = renderer.buildDevicesUnifiedList(devices, zoneTemps, avgIndoor, avgOutdoor)
     */
    fun buildDevicesUnifiedList(
        devices: List<HomeyDevice>,
        zoneTemperatures: Map<String, Double>,
        avgIndoorTemperature: Double? = null,
        avgOutdoorTemperature: Double? = null
    ): Template {
        val templateBuilder = ListTemplate.Builder()
        val visibleDevices = devices.filter { !it.isHidden && !it.isGroupMember }

        if (visibleDevices.isEmpty()) {
            templateBuilder.setSingleList(
                ItemList.Builder()
                    .setNoItemsMessage(carContext.getString(R.string.main_no_devices))
                    .build()
            )
            return templateBuilder.build()
        }

        // 1. Home Overview Section (Task 3.2)
        val overviewSection = _buildHomeOverviewSection(visibleDevices, avgIndoorTemperature, avgOutdoorTemperature)
        templateBuilder.addSectionedList(overviewSection)

        // 2. Zone Sections grouped by room (Task 3.5 & 3.7)
        val groupedDevices = visibleDevices.groupBy { it.zoneName }
        val sortedGroups = groupedDevices.entries.sortedBy { entry ->
            entry.value.firstOrNull()?.zoneOrder ?: Int.MAX_VALUE
        }

        sortedGroups.forEach { (zoneName, zoneDevices) ->
            val itemListBuilder = ItemList.Builder()
            zoneDevices.forEach { device ->
                itemListBuilder.addItem(_buildDeviceRow(device))
            }

            val headerTitle = _buildZoneHeaderTitle(
                zoneName = zoneName,
                zoneDevices = zoneDevices,
                avgTemp = zoneTemperatures[zoneName]
            )

            templateBuilder.addSectionedList(
                SectionedItemList.create(
                    itemListBuilder.build(),
                    headerTitle
                )
            )
        }

        return templateBuilder.build()
    }

    /**
     * Builds a list template displaying all triggerable Homey Flows grouped by folder.
     *
     * Public method.
     * Incorporates safe Unicode En-Space indentation (`\u2002`) for section headers,
     * transient running state feedback on the targeted row, and in-place retry actions.
     *
     * @param flows The list of triggerable flows to render.
     * @param triggeringFlowId UUID of the flow currently in execution, or null.
     * @param onTriggerRequested Callback invoked when the user taps a flow to trigger it.
     * @param onRetryRequested Callback invoked when retrying after a load failure.
     * @param errorMessage Optional error message if flow loading failed.
     * @return [Template] containing the flows sectioned list.
     * @example
     * val template = renderer.buildFlowsList(flows, activeId, { trigger(it) }, { retry() })
     */
    fun buildFlowsList(
        flows: List<FlowDto>,
        triggeringFlowId: String?,
        onTriggerRequested: (FlowDto) -> Unit,
        onRetryRequested: () -> Unit,
        errorMessage: String? = null
    ): Template {
        val templateBuilder = ListTemplate.Builder()

        if (errorMessage != null) {
            val itemList = ItemList.Builder()
                .addItem(
                    Row.Builder()
                        .setTitle(carContext.getString(R.string.flows_error_title))
                        .addText(errorMessage)
                        .setOnClickListener { onRetryRequested() }
                        .build()
                )
                .build()
            return templateBuilder.setSingleList(itemList).build()
        }

        if (flows.isEmpty()) {
            templateBuilder.setSingleList(
                ItemList.Builder()
                    .setNoItemsMessage(carContext.getString(R.string.flows_empty_message))
                    .build()
            )
            return templateBuilder.build()
        }

        val noFolderTitle = carContext.getString(R.string.flows_general_folder)

        // 1. Partition flows into: flows without a folder (rendered at the top) and flows with a folder
        val (flowsWithoutFolder, flowsWithFolder) = flows.partition { it.folderName == null }

        // 2. If there are flows without a folder, add them in the first section at the very top
        if (flowsWithoutFolder.isNotEmpty()) {
            val itemListBuilder = ItemList.Builder()
            val sortedWithoutFolder = flowsWithoutFolder.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            sortedWithoutFolder.forEach { flow ->
                itemListBuilder.addItem(
                    _buildFlowRow(
                        flow = flow,
                        isTriggering = (triggeringFlowId == flow.id),
                        onTriggerRequested = onTriggerRequested
                    )
                )
            }

            val safeTitle = "\u2002" + noFolderTitle.uppercase()
            templateBuilder.addSectionedList(
                SectionedItemList.create(
                    itemListBuilder.build(),
                    safeTitle
                )
            )
        }

        // 3. Group remaining flows by folder and sort folders alphabetically (case-insensitive)
        val groupedFlows = flowsWithFolder.groupBy { it.folderName ?: noFolderTitle }
        val sortedGroups = groupedFlows.entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key })

        sortedGroups.forEach { (folderName, folderFlows) ->
            val itemListBuilder = ItemList.Builder()
            val sortedFlows = folderFlows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            sortedFlows.forEach { flow ->
                itemListBuilder.addItem(
                    _buildFlowRow(
                        flow = flow,
                        isTriggering = (triggeringFlowId == flow.id),
                        onTriggerRequested = onTriggerRequested
                    )
                )
            }

            val safeFolderTitle = "\u2002" + folderName.uppercase()
            templateBuilder.addSectionedList(
                SectionedItemList.create(
                    itemListBuilder.build(),
                    safeFolderTitle
                )
            )
        }

        return templateBuilder.build()
    }

    /**
     * Builds a sectioned list of devices grouped by zone for category tabs (Locks/Lights).
     *
     * Public method.
     *
     * @param filteredDevices Pre-filtered device list for the category.
     * @param emptyMessage Message displayed when no devices are present.
     * @return [Template] containing the category list.
     * @example
     * val template = renderer.buildCategoryList(lights, "No lights found")
     */
    fun buildCategoryList(filteredDevices: List<HomeyDevice>, emptyMessage: String): Template {
        val templateBuilder = ListTemplate.Builder()

        if (filteredDevices.isEmpty()) {
            templateBuilder.setSingleList(
                ItemList.Builder()
                    .setNoItemsMessage(emptyMessage)
                    .build()
            )
            return templateBuilder.build()
        }

        val groupedDevices = filteredDevices.groupBy { it.zoneName }
        val sortedGroups = groupedDevices.entries.sortedBy { entry ->
            entry.value.firstOrNull()?.zoneOrder ?: Int.MAX_VALUE
        }

        sortedGroups.forEach { (zone, zoneDevices) ->
            val itemListBuilder = ItemList.Builder()
            zoneDevices.forEach { device ->
                itemListBuilder.addItem(_buildDeviceRow(device))
            }

            // Apply safe Unicode En-Space indentation to category headers as well
            val safeZoneTitle = "\u2002" + zone.uppercase()

            templateBuilder.addSectionedList(
                SectionedItemList.create(
                    itemListBuilder.build(),
                    safeZoneTitle
                )
            )
        }

        return templateBuilder.build()
    }

    // ── Private Item & Section Builders ───────────────────────────────────────

    /**
     * Constructs the Home Overview section row and wrapping SectionedItemList.
     *
     * @private
     * @param visibleDevices The list of all currently visible devices.
     * @param avgIndoorTemperature The computed average indoor temperature in Celsius, or null.
     * @param avgOutdoorTemperature The computed average outdoor temperature in Celsius, or null.
     * @return [SectionedItemList] representing the global overview section.
     */
    private fun _buildHomeOverviewSection(
        visibleDevices: List<HomeyDevice>,
        avgIndoorTemperature: Double? = null,
        avgOutdoorTemperature: Double? = null
    ): SectionedItemList {
        val totalLights = visibleDevices.filter { it.isLight }
        val activeLightsCount = totalLights.count { it.isActive }
        val totalBarriers = visibleDevices.filter { !it.isLight }
        val openBarriersCount = totalBarriers.count { it.isActive }

        val hasAlert = openBarriersCount > 0 || activeLightsCount > 0

        val barriersText = when {
            openBarriersCount == 1 -> carContext.getString(R.string.home_overview_open_single)
            openBarriersCount > 1  -> carContext.getString(R.string.home_overview_open_count, openBarriersCount)
            else                   -> carContext.getString(R.string.home_overview_all_secure)
        }

        val lightsText = when {
            activeLightsCount == 1 -> carContext.getString(R.string.home_overview_lights_on_single)
            activeLightsCount > 1  -> carContext.getString(R.string.home_overview_lights_on_count, activeLightsCount)
            else                   -> carContext.getString(R.string.home_overview_lights_off)
        }

        val overviewIcon = iconManager.getHomeOverviewIcon(hasAlert)

        val tempParts = mutableListOf<String>()
        if (avgIndoorTemperature != null) {
            val formatted = String.format(Locale.getDefault(), "%.1f°C", avgIndoorTemperature)
            tempParts.add(carContext.getString(R.string.home_overview_temp_indoor, formatted))
        }
        if (avgOutdoorTemperature != null) {
            val formatted = String.format(Locale.getDefault(), "%.1f°C", avgOutdoorTemperature)
            tempParts.add(carContext.getString(R.string.home_overview_temp_outdoor, formatted))
        }

        val rowTitle = if (tempParts.isNotEmpty()) {
            tempParts.joinToString(" · ")
        } else {
            carContext.getString(R.string.home_overview_title)
        }

        val overviewRow = Row.Builder()
            .setTitle(rowTitle)
            .addText(barriersText)
            .addText(lightsText)
            .setImage(overviewIcon, Row.IMAGE_TYPE_LARGE)
            .build()

        val itemList = ItemList.Builder()
            .addItem(overviewRow)
            .build()

        val headerTitle = "\u2002" + carContext.getString(R.string.home_overview_title).uppercase()
        return SectionedItemList.create(itemList, headerTitle)
    }

    /**
     * Builds an enriched room header title incorporating safe Unicode En-Space indentation (`\u2002`),
     * the active zone presence indicator (` ●`), and room average temperature if available.
     *
     * Private method.
     *
     * @private
     * @param zoneName The room or zone name.
     * @param zoneDevices The devices belonging to this room.
     * @param avgTemp The average temperature of the room in Celsius, if available.
     * @return [CharSequence] formatted text for section display.
     */
    private fun _buildZoneHeaderTitle(
        zoneName: String,
        zoneDevices: List<HomeyDevice>,
        avgTemp: Double?
    ): CharSequence {
        val isZoneActive = zoneDevices.any { it.isZoneActive }
        val prefix = "\u2002"
        val name = zoneName.uppercase()
        val presence = if (isZoneActive) " ●" else ""
        val tempText = if (avgTemp != null) {
            " (" + String.format(Locale.getDefault(), "%.1f°C", avgTemp) + ")"
        } else ""

        return prefix + name + presence + tempText
    }

    private fun _buildDeviceGridItem(device: HomeyDevice): GridItem {
        return GridItem.Builder()
            .setTitle(device.name)
            .setText(_getStateLabel(device))
            .setImage(iconManager.getIcon(device, showBadge = false))
            .setOnClickListener { onToggleRequested(device) }
            .build()
    }

    private fun _buildDeviceRow(device: HomeyDevice): Row {
        return Row.Builder()
            .setTitle(device.name)
            .addText(_getStateLabel(device))
            .setImage(iconManager.getIcon(device, showBadge = true), Row.IMAGE_TYPE_LARGE)
            .setOnClickListener { onToggleRequested(device) }
            .build()
    }

    private fun _getStateLabel(device: HomeyDevice): String {
        return when {
            !device.isAvailable -> carContext.getString(R.string.device_state_unavailable)
            device.isLight -> if (device.isActive) carContext.getString(R.string.device_state_on) else carContext.getString(R.string.device_state_off)
            else -> if (device.isActive) carContext.getString(R.string.device_state_open) else carContext.getString(R.string.device_state_closed)
        }
    }

    /**
     * Builds an AAOS Row representation for a single triggerable Homey Flow.
     *
     * Private method.
     * Displays a play action icon, the flow name, and shows a transient execution
     * indicator if the flow is currently awaiting server dispatch confirmation.
     *
     * @param flow The Homey flow data transfer object.
     * @param isTriggering True if this flow is currently awaiting dispatch confirmation.
     * @param onTriggerRequested Callback invoked when the user clicks the row.
     * @return [Row] ready to be added to the flow sectioned item list.
     * @example
     * val row = _buildFlowRow(flow, isTriggering = false) { flow -> onTrigger(flow) }
     */
    private fun _buildFlowRow(
        flow: FlowDto,
        isTriggering: Boolean,
        onTriggerRequested: (FlowDto) -> Unit
    ): Row {
        val rowBuilder = Row.Builder()
            .setTitle(flow.name)
            .setImage(
                CarIcon.Builder(
                    IconCompat.createWithResource(carContext, R.drawable.ic_play_flow)
                ).build(),
                Row.IMAGE_TYPE_ICON
            )

        if (isTriggering) {
            rowBuilder.addText(carContext.getString(R.string.flows_running))
        } else {
            rowBuilder.setOnClickListener {
                onTriggerRequested(flow)
            }
        }

        return rowBuilder.build()
    }
}

