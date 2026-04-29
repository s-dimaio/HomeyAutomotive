package com.dimapp.android.homeyautomotive.screens

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.dimapp.android.homeyautomotive.R
import com.dimapp.android.homeyautomotive.repository.models.HomeyDashboard
import com.dimapp.android.homeyautomotive.repository.DashboardRepository
import com.dimapp.android.homeyautomotive.repository.models.HomeyResult
import com.dimapp.android.homeyautomotive.core.DependencyManager
import com.dimapp.android.homeyautomotive.storage.HomeSource
import com.dimapp.android.homeyautomotive.storage.TokenStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Screen for selecting the data source of the Home tab.
 *
 * Accessible only from [SettingsScreen] (never directly from the main tab bar).
 * Presents two types of options in a [ListTemplate]:
 * - "Dispositivi Preferiti" (always first): filters by [HomeyDevice.isFavorite].
 * - One row for each Virtual Dashboard found via the Companion App API.
 *
 * Selecting any row immediately persists the choice via [TokenStorage] and pops back
 * to [SettingsScreen], showing a confirmation toast.
 *
 * @param carContext The [CarContext] provided by the Car App framework.
 */
class HomeSourceSelectionScreen(carContext: CarContext) : Screen(carContext) {

    private val storage = DependencyManager.getTokenStorage(carContext)

    // The Dashboard ID is now fixed as we use the Virtual Dashboard from the Companion App
    private val VIRTUAL_DASHBOARD_ID = "virtual-aaos-home"

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()
        val currentSource = storage.getHomeSource()
        val currentDashboardId = storage.getHomeDashboardId()

        // 1. Row: Favorite Devices
        val isFavSelected = currentSource == HomeSource.FAVORITES
        val favRowBuilder = Row.Builder()
            .setTitle(carContext.getString(R.string.source_favorites_title))
        
        if (isFavSelected) {
            favRowBuilder.addText(carContext.getString(R.string.source_selected_label))
        }

        listBuilder.addItem(
            favRowBuilder
                .setOnClickListener { _selectFavorites() }
                .build()
        )

        // 2. Row: Dashboard (Virtual)
        val isDashSelected = currentSource == HomeSource.DASHBOARD && currentDashboardId == VIRTUAL_DASHBOARD_ID
        val dashRowBuilder = Row.Builder()
            .setTitle(carContext.getString(R.string.source_dashboard_title))
        
        if (isDashSelected) {
            dashRowBuilder.addText(carContext.getString(R.string.source_selected_label))
        }

        listBuilder.addItem(
            dashRowBuilder
                .setOnClickListener { _selectDashboard() }
                .build()
        )

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.source_header_title))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(listBuilder.build())
            .build()
    }

    // ── Private Methods ────────────────────────────────────────────────────────────

    private fun _selectFavorites() {
        storage.saveHomeSource(HomeSource.FAVORITES)
        CarToast.makeText(carContext, carContext.getString(R.string.source_toast_favorites), CarToast.LENGTH_SHORT).show()
        screenManager.pop()
    }

    private fun _selectDashboard() {
        storage.saveHomeSource(HomeSource.DASHBOARD)
        storage.saveHomeDashboardId(VIRTUAL_DASHBOARD_ID)
        CarToast.makeText(carContext, carContext.getString(R.string.source_toast_dashboard, carContext.getString(R.string.source_dashboard_title)), CarToast.LENGTH_SHORT).show()
        screenManager.pop()
    }
}
