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
    private val repository = DependencyManager.getDashboardRepository(carContext)
    private val scope      = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** State: null = loading, empty = no dashboards, non-empty = ready */
    private var dashboards: List<HomeyDashboard>? = null
    private var errorMessage: String? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                _loadDashboards()
            }
            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
            }
        })
    }

    /**
     * Builds the template shown in this screen.
     *
     * Public method.
     * While loading, shows a loading state on the template.
     * On error, shows a single row with the error message.
     * When ready, shows "Favorite Devices" + one row per dashboard.
     *
     * @return The [ListTemplate] to render.
     * @example
     * val template = screen.onGetTemplate()
     */
    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        when {
            dashboards == null && errorMessage == null -> {
                // Loading state: show an empty list (the template shows a spinner)
                return ListTemplate.Builder()
                    .setHeader(
                        Header.Builder()
                            .setTitle(carContext.getString(R.string.source_header_title))
                            .setStartHeaderAction(Action.BACK)
                            .build()
                    )
                    .setLoading(true)
                    .build()
            }

            errorMessage != null -> {
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle(carContext.getString(R.string.source_error_title))
                        .addText(errorMessage ?: carContext.getString(R.string.flows_error_unknown))
                        .build()
                )
            }

            else -> {
                val currentSource     = storage.getHomeSource()
                val currentDashboardId = storage.getHomeDashboardId()

                // Row: Favorite Devices
                val favSubtitle = if (currentSource == HomeSource.FAVORITES) carContext.getString(R.string.source_selected_label) else ""
                val favRowBuilder = Row.Builder()
                    .setTitle(carContext.getString(R.string.source_favorites_title))
                
                if (favSubtitle.isNotEmpty()) {
                    favRowBuilder.addText(favSubtitle)
                }

                listBuilder.addItem(
                    favRowBuilder
                        .setOnClickListener { _selectFavorites() }
                        .build()
                )

                for (dashboard in dashboards ?: emptyList()) {
                    val isSelected = currentSource == HomeSource.DASHBOARD && currentDashboardId == dashboard.id
                    val subtitle   = if (isSelected) carContext.getString(R.string.source_selected_label) else ""
                    
                    val rowBuilder = Row.Builder()
                        .setTitle(dashboard.name)

                    if (subtitle.isNotEmpty()) {
                        rowBuilder.addText(subtitle)
                    }

                    listBuilder.addItem(
                        rowBuilder
                            .setOnClickListener { _selectDashboard(dashboard) }
                            .build()
                    )
                }
            }
        }

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

    /**
     * Loads available dashboards from Homey in background, then refreshes the template.
     *
     * Private method.
     *
     * @private
     * @example
     * _loadDashboards()
     */
    private fun _loadDashboards() {
        scope.launch {
            when (val result = repository.getDashboards()) {
                is HomeyResult.Success -> {
                    dashboards = result.data
                    errorMessage = null
                }
                is HomeyResult.Error -> {
                    dashboards = emptyList()
                    errorMessage = result.message
                }
            }
            invalidate()
        }
    }

    /**
     * Persists [HomeSource.FAVORITES] as the Home tab source and navigates back.
     *
     * Private method.
     *
     * @private
     * @example
     * _selectFavorites()
     */
    private fun _selectFavorites() {
        storage.saveHomeSource(HomeSource.FAVORITES)
        CarToast.makeText(carContext, carContext.getString(R.string.source_toast_favorites), CarToast.LENGTH_SHORT).show()
        screenManager.pop()
    }

    /**
     * Persists [HomeSource.DASHBOARD] and the given [dashboard] ID as the Home tab source,
     * then navigates back.
     *
     * Private method.
     *
     * @private
     * @param dashboard The selected [HomeyDashboard].
     * @example
     * _selectDashboard(myDashboard)
     */
    private fun _selectDashboard(dashboard: HomeyDashboard) {
        storage.saveHomeSource(HomeSource.DASHBOARD)
        storage.saveHomeDashboardId(dashboard.id)
        CarToast.makeText(carContext, carContext.getString(R.string.source_toast_dashboard, dashboard.name), CarToast.LENGTH_SHORT).show()
        screenManager.pop()
    }
}
