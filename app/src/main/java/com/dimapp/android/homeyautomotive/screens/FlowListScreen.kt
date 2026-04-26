package com.dimapp.android.homeyautomotive.screens

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.dimapp.android.homeyautomotive.R
import com.dimapp.android.homeyautomotive.api.models.FlowDto
import com.dimapp.android.homeyautomotive.core.DependencyManager
import com.dimapp.android.homeyautomotive.repository.FlowRepository
import com.dimapp.android.homeyautomotive.repository.models.HomeyResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Screen showing all triggerable Homey Flows.
 *
 * NOTE: This class is intentionally kept in the project for future development purposes.
 * It is currently NOT integrated into any tab of [MainTabScreen] and therefore
 * NOT reachable by the user at runtime. It is a complete, functional implementation
 * that will be wired into the UI in a future release.
 *
 * Displays a [ListTemplate] with each [FlowDto] as a [Row]. Tapping a row
 * immediately triggers the corresponding Homey Flow via the REST API and
 * shows a [CarToast] with the result.
 *
 * Only flows with `triggerable = true` and `enabled = true` are shown.
 *
 * @param carContext The [CarContext] provided by the Car App framework.
 */
class FlowListScreen(carContext: CarContext) : Screen(carContext) {

    private val repository = DependencyManager.getFlowRepository(carContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var isLoading = true
    private var flows: List<FlowDto> = emptyList()
    private var errorMessage: String? = null

    /** Tracks which flow is currently being triggered (prevents double-tap). */
    private var triggeringFlowId: String? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                _loadFlows()
            }
            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
            }
        })
    }

    /**
     * Builds the [ListTemplate] showing all triggerable Flows.
     *
     * Handles loading, error, empty and populated states.
     *
     * @public
     * @return The [Template] to render.
     */
    override fun onGetTemplate(): Template {
        if (isLoading) {
            return ListTemplate.Builder()
                .setHeader(
                    Header.Builder()
                        .setTitle(carContext.getString(R.string.flows_header_title))
                        .setStartHeaderAction(Action.BACK)
                        .build()
                )
                .setLoading(true)
                .build()
        }

        val listBuilder = ItemList.Builder()

        when {
            errorMessage != null -> {
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle(carContext.getString(R.string.flows_error_title))
                        .addText(errorMessage ?: carContext.getString(R.string.flows_error_unknown))
                        .setOnClickListener { _loadFlows() }
                        .build()
                )
            }
            flows.isEmpty() -> {
                listBuilder.setNoItemsMessage(
                    carContext.getString(R.string.flows_empty_message)
                )
            }
            else -> {
                flows.forEach { flow ->
                    listBuilder.addItem(_buildFlowRow(flow))
                }
            }
        }

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.flows_header_title))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(listBuilder.build())
            .build()
    }

    // ── Private Methods ───────────────────────────────────────────────────────

    /**
     * Fetches all triggerable flows from [FlowRepository] and refreshes the screen.
     *
     * @private
     */
    private fun _loadFlows() {
        isLoading = true
        errorMessage = null
        invalidate()

        scope.launch {
            when (val result = repository.getTriggerableFlows()) {
                is HomeyResult.Success -> {
                    flows = result.data
                    isLoading = false
                }
                is HomeyResult.Error -> {
                    errorMessage = result.message
                    isLoading = false
                }
            }
            invalidate()
        }
    }

    /**
     * Builds a [Row] for a single Flow.
     *
     * Tapping the row triggers the flow immediately.
     * While the flow is being triggered, the row title changes to a "Running" label
     * and further taps are ignored (via [triggeringFlowId] guard).
     *
     * @private
     * @param flow The [FlowDto] to render.
     * @return A configured [Row].
     */
    private fun _buildFlowRow(flow: FlowDto): Row {
        val isTriggering = triggeringFlowId == flow.id
        val subtitle = flow.folder ?: carContext.getString(R.string.flows_manual_folder)

        val rowIcon = if (flow.folder != null) {
            CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_folder)).build()
        } else {
            CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_play_flow)).build()
        }

        return Row.Builder()
            .setImage(rowIcon)
            .setTitle(if (isTriggering) carContext.getString(R.string.flows_running) else flow.name)
            .addText(subtitle)
            .setOnClickListener { _triggerFlow(flow) }
            .build()
    }

    /**
     * Triggers a Homey Flow via [FlowRepository] and shows the result as a [CarToast].
     *
     * Prevents concurrent triggers for the same flow by checking [triggeringFlowId].
     *
     * @private
     * @param flow The [FlowDto] to trigger.
     */
    private fun _triggerFlow(flow: FlowDto) {
        if (triggeringFlowId != null) return

        triggeringFlowId = flow.id
        invalidate()

        scope.launch {
            val result = repository.triggerFlow(flow.id)
            triggeringFlowId = null

            CarToast.makeText(
                carContext,
                when (result) {
                    is HomeyResult.Success -> carContext.getString(R.string.flows_started_toast, flow.name)
                    is HomeyResult.Error   -> carContext.getString(R.string.flows_error_toast, result.message)
                },
                if (result is HomeyResult.Success) CarToast.LENGTH_SHORT else CarToast.LENGTH_LONG
            ).show()

            invalidate()
        }
    }
}
