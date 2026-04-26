package com.dimapp.android.homeyautomotive.screens.helpers

import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import com.dimapp.android.homeyautomotive.R
import com.dimapp.android.homeyautomotive.repository.models.HomeyDevice

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
     */
    fun buildLoadingTemplate(): Template {
        return ListTemplate.Builder()
            .setLoading(true)
            .build()
    }

    /**
     * Builds an error message template with a retry button.
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
     * Builds a sectioned list of devices grouped by zone for category tabs (Locks/Lights).
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

        // Group by zone and sort by the order defined in Homey
        val groupedDevices = filteredDevices.groupBy { it.zoneName }
        val sortedGroups = groupedDevices.entries.sortedBy { entry ->
            entry.value.firstOrNull()?.zoneOrder ?: Int.MAX_VALUE
        }

        sortedGroups.forEach { (zone, zoneDevices) ->
            val itemListBuilder = ItemList.Builder()
            zoneDevices.forEach { device ->
                itemListBuilder.addItem(_buildDeviceRow(device))
            }

            templateBuilder.addSectionedList(
                SectionedItemList.create(
                    itemListBuilder.build(),
                    zone
                )
            )
        }

        return templateBuilder.build()
    }

    // ── Private Item Builders ────────────────────────────────────────────────

    private fun _buildDeviceGridItem(device: HomeyDevice): GridItem {
        return GridItem.Builder()
            .setTitle(device.name)
            .setText(_getStateLabel(device))
            .setImage(iconManager.getIcon(device))
            .setOnClickListener { onToggleRequested(device) }
            .build()
    }

    private fun _buildDeviceRow(device: HomeyDevice): Row {
        return Row.Builder()
            .setTitle(device.name)
            .addText(_getStateLabel(device))
            .setImage(iconManager.getIcon(device), Row.IMAGE_TYPE_LARGE)
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
}
