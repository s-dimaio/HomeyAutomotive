package com.dimapp.android.homeyautomotive.repository

import android.content.Context
import android.util.Log
import com.dimapp.android.homeyautomotive.R
import com.dimapp.android.homeyautomotive.api.models.FlowDto
import com.dimapp.android.homeyautomotive.api.models.FlowTriggerRequestDto
import com.dimapp.android.homeyautomotive.repository.models.HomeyResult
import com.dimapp.android.homeyautomotive.auth.HomeyAuthRepository
import com.dimapp.android.homeyautomotive.storage.TokenStorage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Repository handling flow-related operations (fetching, caching, and triggering).
 *
 * @param context Application context for resources.
 * @param storage Storage instance for tokens and demo mode check.
 * @param authRepo Authentication repository for credentials.
 */
class FlowRepository(
    context: Context,
    storage: TokenStorage,
    authRepo: HomeyAuthRepository
) : HomeyBaseRepository(context, storage, authRepo) {

    private val TAG = "FlowRepository"
    private val cacheMutex = Mutex()
    private var cachedFlows: List<FlowDto>? = null
    private var cachedHubId: String? = null

    /**
     * Returns all standard Flows that can be manually triggered, using an in-memory cache
     * to avoid unnecessary network queries during tab transitions.
     *
     * Only flows with `triggerable = true` and `enabled = true` are included.
     *
     * Public method.
     *
     * @param forceRefresh If true, forces a network fetch bypassing the local cache.
     * @return [HomeyResult.Success] containing the list of [FlowDto] instances, or [HomeyResult.Error].
     * @example
     * val result = flowRepo.getTriggerableFlows(forceRefresh = true)
     */
    suspend fun getTriggerableFlows(forceRefresh: Boolean = false): HomeyResult<List<FlowDto>> = cacheMutex.withLock {
        if (storage.isDemoMode()) {
            return HomeyResult.Success(_createDemoFlows())
        }

        val currentHubId = storage.getSelectedHomeyId() 
            ?: return HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

        if (!forceRefresh && cachedHubId == currentHubId && cachedFlows != null) {
            Log.d(TAG, "Returning ${cachedFlows?.size} flows from in-memory cache.")
            return HomeyResult.Success(cachedFlows!!)
        }

        val service = _buildService() 
            ?: return HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

        Log.d(TAG, "Fetching flows, advanced flows, and folders from network (forceRefresh=$forceRefresh)...")
        return try {
            coroutineScope {
                val flowsDeferred = async { 
                    try { service.getFlows() } catch (e: Exception) { null } 
                }
                val advFlowsDeferred = async { 
                    try { service.getAdvancedFlows() } catch (e: Exception) { null } 
                }
                val foldersDeferred = async {
                    try { service.getFlowFolders() } catch (e: Exception) { null }
                }

                val flowsResponse = flowsDeferred.await()
                val advFlowsResponse = advFlowsDeferred.await()
                val foldersResponse = foldersDeferred.await()

                if (flowsResponse?.isSuccessful != true && advFlowsResponse?.isSuccessful != true) {
                    val code = flowsResponse?.code() ?: advFlowsResponse?.code() ?: 500
                    Log.e(TAG, "Flows fetch failed: HTTP $code")
                    return@coroutineScope HomeyResult.Error(
                        context.getString(R.string.repo_error_flow_http, code)
                    )
                }

                val foldersMap = if (foldersResponse?.isSuccessful == true) {
                    foldersResponse.body() ?: emptyMap()
                } else {
                    emptyMap()
                }

                val standardFlows = if (flowsResponse?.isSuccessful == true) {
                    (flowsResponse.body() ?: emptyMap()).values
                        .filter { it.triggerable == true && it.enabled != false && it.broken != true }
                        .map { it.copy(isAdvanced = false) }
                } else {
                    emptyList()
                }

                val advancedFlows = if (advFlowsResponse?.isSuccessful == true) {
                    (advFlowsResponse.body() ?: emptyMap()).values
                        .filter { it.triggerable == true && it.enabled != false && it.broken != true }
                        .map { it.copy(isAdvanced = true) }
                } else {
                    emptyList()
                }

                val flows = (standardFlows + advancedFlows)
                    .map { flow ->
                        val resolvedFolderName = flow.folder?.let { foldersMap[it]?.effectiveName }
                        flow.copy(folderName = resolvedFolderName)
                    }
                    .sortedWith(
                        compareBy<FlowDto> { it.folderName ?: "" }
                            .thenBy { it.name }
                    )

                cachedFlows = flows
                cachedHubId = currentHubId
                Log.d(TAG, "Fetched and cached ${flows.size} triggerable flows (${standardFlows.size} standard, ${advancedFlows.size} advanced).")
                HomeyResult.Success(flows)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during getTriggerableFlows: ${e.message}", e)
            HomeyResult.Error(context.getString(R.string.repo_error_conn_failed, e.message ?: "Unknown error"))
        }
    }

    /**
     * Clears the in-memory flow cache, forcing the next fetch from network.
     *
     * Public method.
     *
     * @example
     * flowRepo.invalidateCache()
     */
    fun invalidateCache() {
        Log.d(TAG, "Flow cache invalidated.")
        cachedFlows = null
        cachedHubId = null
    }

    /**
     * Checks if the in-memory cache is currently empty.
     *
     * Public method.
     *
     * @return `true` if no flows are currently cached.
     * @example
     * val empty = flowRepo.isCacheEmpty()
     */
    fun isCacheEmpty(): Boolean {
        return cachedFlows == null
    }

    /**
     * Triggers a Homey Flow (standard or advanced) by its UUID.
     *
     * Public method.
     *
     * @param flowId UUID of the target Flow to trigger.
     * @param isAdvanced Whether the flow is an Advanced Flow.
     * @return [HomeyResult.Success] with localized message on success, or [HomeyResult.Error].
     * @example
     * val result = flowRepo.triggerFlow("flow-uuid-123", isAdvanced = true)
     */
    suspend fun triggerFlow(flowId: String, isAdvanced: Boolean = false): HomeyResult<String> {
        if (storage.isDemoMode()) {
            Log.d(TAG, "Triggered mock demo flow: $flowId")
            return HomeyResult.Success(context.getString(R.string.repo_success_flow))
        }

        val service = _buildService() 
            ?: return HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

        return try {
            val response = service.triggerCompanionFlow(
                FlowTriggerRequestDto(id = flowId, isAdvanced = isAdvanced)
            )
            if (response.isSuccessful && response.body()?.success == true) {
                Log.d(TAG, "[FlowRepository:triggerFlow] Flow '$flowId' (isAdvanced=$isAdvanced) triggered successfully via companion.")
                HomeyResult.Success(context.getString(R.string.repo_success_flow))
            } else {
                val errorBody = response.errorBody()?.string().orEmpty()
                val responseBodyError = response.body()?.error.orEmpty()
                Log.e(TAG, "[FlowRepository:triggerFlow] Trigger flow '$flowId' failed: HTTP ${response.code()} - errorBody: $errorBody, responseError: $responseBodyError")

                if (errorBody.contains("HOMEYSCRIPT", ignoreCase = true) ||
                    responseBodyError.contains("HOMEYSCRIPT", ignoreCase = true)
                ) {
                    HomeyResult.Error(context.getString(R.string.repo_error_flow_homeyscript_missing))
                } else {
                    HomeyResult.Error(context.getString(R.string.repo_error_flow_trigger_http, response.code()))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[FlowRepository:triggerFlow] Exception triggering flow '$flowId': ${e.message}", e)
            HomeyResult.Error(context.getString(R.string.repo_error_network, e.message ?: "Unknown error"))
        }
    }

    // ── Private Methods ───────────────────────────────────────────────────────

    /**
     * Creates a predefined list of mock flows for Demo Mode / Google Play Store review.
     *
     * @private
     * @return List of mock [FlowDto] instances organized into folders.
     */
    private fun _createDemoFlows(): List<FlowDto> {
        val automationsFolder = context.getString(R.string.demo_folder_automations)
        val securityFolder = context.getString(R.string.demo_folder_security)

        return listOf(
            FlowDto(
                id = "demo_flow_1",
                name = context.getString(R.string.demo_flow_leaving_home),
                triggerable = true,
                enabled = true,
                folder = "demo_folder_automations",
                folderName = automationsFolder
            ),
            FlowDto(
                id = "demo_flow_2",
                name = context.getString(R.string.demo_flow_arriving_home),
                triggerable = true,
                enabled = true,
                folder = "demo_folder_automations",
                folderName = automationsFolder
            ),
            FlowDto(
                id = "demo_flow_3",
                name = context.getString(R.string.demo_flow_goodnight),
                triggerable = true,
                enabled = true,
                folder = "demo_folder_security",
                folderName = securityFolder
            ),
            FlowDto(
                id = "demo_flow_4",
                name = context.getString(R.string.demo_flow_welcome),
                triggerable = true,
                enabled = true,
                folder = "demo_folder_security",
                folderName = securityFolder
            )
        )
    }
}
