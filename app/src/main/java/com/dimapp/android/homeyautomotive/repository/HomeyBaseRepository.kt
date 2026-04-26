package com.dimapp.android.homeyautomotive.repository

import android.content.Context
import com.dimapp.android.homeyautomotive.api.HomeyApiService
import com.dimapp.android.homeyautomotive.auth.HomeyAuthRepository
import com.dimapp.android.homeyautomotive.storage.TokenStorage

/**
 * Base abstract class for domain-specific Homey repositories.
 *
 * Provides shared capabilities like accessing the [HomeyApiService] from [DependencyManager].
 *
 * @property context   Application context.
 * @property storage   Shared instance of [TokenStorage].
 * @property authRepo  Shared instance of [HomeyAuthRepository].
 */
abstract class HomeyBaseRepository(
    protected val context: Context,
    protected val storage: TokenStorage,
    protected val authRepo: HomeyAuthRepository
) {

    /**
     * Retrieves the [HomeyApiService] from the [DependencyManager].
     * 
     * This ensures that the service is always correctly configured for the currently 
     * selected hub and shares the application-wide OkHttpClient.
     *
     * @return A configured [HomeyApiService], or `null` if no Homey hub is selected.
     */
    protected fun _buildService(): HomeyApiService? {
        return com.dimapp.android.homeyautomotive.core.DependencyManager.getApiService(context)
    }

}
