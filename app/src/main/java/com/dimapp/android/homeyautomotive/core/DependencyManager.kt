package com.dimapp.android.homeyautomotive.core

import android.content.Context
import android.util.Log
import com.dimapp.android.homeyautomotive.BuildConfig
import com.dimapp.android.homeyautomotive.api.HomeyApiClient
import com.dimapp.android.homeyautomotive.api.HomeyApiService
import com.dimapp.android.homeyautomotive.auth.HomeyAuthRepository
import com.dimapp.android.homeyautomotive.repository.DashboardRepository
import com.dimapp.android.homeyautomotive.repository.DeviceRepository
import com.dimapp.android.homeyautomotive.repository.FlowRepository
import com.dimapp.android.homeyautomotive.storage.TokenStorage
import okhttp3.OkHttpClient

/**
 * Service Locator for managing shared application dependencies.
 *
 * This singleton ensures that heavy resources like [OkHttpClient], [Retrofit] instances,
 * and Repositories are instantiated only once and shared across screens, reducing 
 * memory usage and connection overhead.
 */
object DependencyManager {
    private const val TAG = "DependencyManager"

    private var _storage: TokenStorage? = null
    private var _okHttpClient: OkHttpClient? = null
    private var _authRepository: HomeyAuthRepository? = null
    
    private var _deviceRepository: DeviceRepository? = null
    private var _dashboardRepository: DashboardRepository? = null
    private var _flowRepository: FlowRepository? = null

    private var _apiService: HomeyApiService? = null
    private var _lastBaseUrl: String? = null

    /**
     * Gets or creates the shared [TokenStorage].
     * 
     * @param context Application context.
     * @return The singleton [TokenStorage] instance.
     */
    fun getTokenStorage(context: Context): TokenStorage {
        if (_storage == null) {
            _storage = TokenStorage(context.applicationContext)
        }
        return _storage!!
    }

    /**
     * Gets or creates the shared [HomeyAuthRepository].
     * 
     * @param context Application context.
     * @return The singleton [HomeyAuthRepository] instance.
     */
    fun getAuthRepository(context: Context): HomeyAuthRepository {
        if (_authRepository == null) {
            _authRepository = HomeyAuthRepository(
                context.applicationContext,
                getTokenStorage(context)
            )
        }
        return _authRepository!!
    }

    /**
     * Gets or creates a shared [OkHttpClient] used for all Homey API calls.
     * 
     * @param context Application context.
     * @return The singleton [OkHttpClient] equipped with token injection.
     */
    fun getOkHttpClient(context: Context): OkHttpClient {
        if (_okHttpClient == null) {
            val authRepo = getAuthRepository(context)
            _okHttpClient = HomeyApiClient.buildSharedOkHttpClient(authRepo, BuildConfig.DEBUG)
            Log.d(TAG, "Shared OkHttpClient initialized.")
        }
        return _okHttpClient!!
    }

    /**
     * Returns a [HomeyApiService] instance pointed at the currently selected Homey Hub.
     * 
     * Rebuilds the Retrofit service automatically if the Base URL has changed 
     * (e.g. after switching hubs in settings).
     * 
     * @param context Application context.
     * @return The [HomeyApiService] instance, or null if no hub is selected.
     */
    fun getApiService(context: Context): HomeyApiService? {
        val storage = getTokenStorage(context)
        val baseUrl = storage.getBaseUrl() ?: return null

        if (_apiService == null || baseUrl != _lastBaseUrl) {
            Log.d(TAG, "Building HomeyApiService for URL: $baseUrl")
            val client = getOkHttpClient(context)
            _apiService = HomeyApiClient.createWithClient(baseUrl, client)
            _lastBaseUrl = baseUrl
        }
        return _apiService
    }

    /**
     * Singleton accessor for [DeviceRepository].
     */
    fun getDeviceRepository(context: Context): DeviceRepository {
        if (_deviceRepository == null) {
            _deviceRepository = DeviceRepository(
                context.applicationContext,
                getTokenStorage(context),
                getAuthRepository(context)
            )
        }
        return _deviceRepository!!
    }

    /**
     * Singleton accessor for [DashboardRepository].
     */
    fun getDashboardRepository(context: Context): DashboardRepository {
        if (_dashboardRepository == null) {
            _dashboardRepository = DashboardRepository(
                context.applicationContext,
                getTokenStorage(context),
                getAuthRepository(context)
            )
        }
        return _dashboardRepository!!
    }

    /**
     * Singleton accessor for [FlowRepository].
     */
    fun getFlowRepository(context: Context): FlowRepository {
        if (_flowRepository == null) {
            _flowRepository = FlowRepository(
                context.applicationContext,
                getTokenStorage(context),
                getAuthRepository(context)
            )
        }
        return _flowRepository!!
    }
}
