package com.dimapp.android.homeyautomotive.repository

import android.content.Context
import com.dimapp.android.homeyautomotive.R
import com.dimapp.android.homeyautomotive.api.models.FlowDto
import com.dimapp.android.homeyautomotive.repository.models.HomeyResult
import com.dimapp.android.homeyautomotive.auth.HomeyAuthRepository
import com.dimapp.android.homeyautomotive.storage.TokenStorage

/**
 * Repository handling flow-related operations (fetching and triggering).
 */
class FlowRepository(
    context: Context,
    storage: TokenStorage,
    authRepo: HomeyAuthRepository
) : HomeyBaseRepository(context, storage, authRepo) {

    /**
     * Returns all standard Flows that can be manually triggered.
     * Only flows with `triggerable = true` are included.
     */
    suspend fun getTriggerableFlows(): HomeyResult<List<FlowDto>> {
        val service = _buildService() ?: return HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

        return try {
            val response = service.getFlows()
            if (!response.isSuccessful) {
                return HomeyResult.Error(context.getString(R.string.repo_error_flow_http, response.code()))
            }

            val flows = (response.body() ?: emptyMap()).values
                .filter { it.triggerable == true && it.enabled != false }
                .sortedBy { it.name }

            HomeyResult.Success(flows)
        } catch (e: Exception) {
            HomeyResult.Error(context.getString(R.string.repo_error_conn_failed, e.message ?: "Unknown error"))
        }
    }

    /**
     * Triggers a Homey Flow by its UUID.
     */
    suspend fun triggerFlow(flowId: String): HomeyResult<String> {
        val service = _buildService() ?: return HomeyResult.Error(context.getString(R.string.repo_error_not_configured))

        return try {
            val response = service.triggerFlow(flowId)
            if (response.isSuccessful) {
                HomeyResult.Success(context.getString(R.string.repo_success_flow))
            } else {
                HomeyResult.Error(context.getString(R.string.repo_error_flow_trigger_http, response.code()))
            }
        } catch (e: Exception) {
            HomeyResult.Error(context.getString(R.string.repo_error_network, e.message ?: "Unknown error"))
        }
    }
}
