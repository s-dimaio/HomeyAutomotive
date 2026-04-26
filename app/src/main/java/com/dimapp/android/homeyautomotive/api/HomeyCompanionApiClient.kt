package com.dimapp.android.homeyautomotive.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Factory for creating [HomeyCompanionApiService] Retrofit instances.
 *
 * The Homey AAOS Companion App exposes two **public** endpoints (no auth token required).
 * This client therefore does NOT include the [TokenInjectorInterceptor] used by [HomeyApiClient].
 *
 * The base URL is dynamic because it depends on the user's Homey ID:
 * `https://{homeyId}.connect.athom.com/api/app/com.dimapp.aaos/`
 *
 * Service instances are cached by Homey ID to avoid creating a new [OkHttpClient] and [Retrofit]
 * instance on every call. This is particularly important during the OAuth polling flow, where
 * [HomeyAuthRepository] calls [create] every few seconds. The shared [OkHttpClient] ensures
 * that the TCP connection pool is reused across all polling ticks.
 *
 * @example
 * ```
 * val service = HomeyCompanionApiClient.create(
 *     homeyId = storage.getHomeyId()!!,
 *     debug   = BuildConfig.DEBUG
 * )
 * val response = service.startAuth(StartAuthRequest(sessionId))
 * ```
 */
object HomeyCompanionApiClient {

    // ── Private constants ──────────────────────────────────────────────────────

    private const val TIMEOUT_SECONDS = 15L

    /** App ID of the Homey Companion App as registered in the Homey App Store. */
    private const val COMPANION_APP_ID = "com.dimapp.aaos"

    // ── Private state ─────────────────────────────────────────────────────────

    /**
     * Shared [OkHttpClient] reused across all service instances.
     * Initialized once (with or without debug logging, based on the first call).
     */
    private var sharedOkHttpClient: OkHttpClient? = null

    /**
     * Cache of [HomeyCompanionApiService] instances keyed by Homey ID.
     * Prevents redundant object creation during frequent polling cycles.
     */
    private val serviceCache = mutableMapOf<String, HomeyCompanionApiService>()

    // ── Public Methods ─────────────────────────────────────────────────────────

    /**
     * Returns (or creates and caches) a [HomeyCompanionApiService] for the given Homey instance.
     *
     * The base URL is built as:
     * `https://{homeyId}.connect.athom.com/api/app/com.dimapp.aaos/`
     *
     * Subsequent calls with the same [homeyId] return the cached instance without allocating
     * new objects, making this safe to call during high-frequency polling loops.
     *
     * @public
     * @param homeyId The Homey instance ID (e.g. `"abc123"`), used to build the base URL.
     * @param debug   If `true`, enables HTTP BASIC-level logging (applied only on first creation).
     * @return A ready-to-use [HomeyCompanionApiService] instance.
     * @example
     * ```
     * val service = HomeyCompanionApiClient.create(homeyId = "abc123")
     * ```
     */
    fun create(homeyId: String, debug: Boolean = false): HomeyCompanionApiService {
        serviceCache[homeyId]?.let { return it }

        val baseUrl = "https://$homeyId.connect.athom.com/api/app/$COMPANION_APP_ID/"
        val client  = _getOrBuildOkHttpClient(debug)

        val service = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(HomeyCompanionApiService::class.java)

        serviceCache[homeyId] = service
        return service
    }

    // ── Private Methods ────────────────────────────────────────────────────────

    /**
     * Returns the shared [OkHttpClient], creating it on first use.
     *
     * The client is not recreated on subsequent calls, regardless of the [debug] flag value.
     * This is intentional: the client is built once and shared across all Homey ID instances
     * to maximise TCP connection pool reuse during polling.
     *
     * @private
     * @param debug If `true` and the client has not been created yet, enables BASIC-level logging.
     * @return The shared [OkHttpClient] instance.
     */
    private fun _getOrBuildOkHttpClient(debug: Boolean): OkHttpClient {
        return sharedOkHttpClient ?: _buildOkHttpClient(debug).also { sharedOkHttpClient = it }
    }

    /**
     * Builds a minimal [OkHttpClient] with timeouts and optional logging.
     *
     * No auth interceptor is added since the companion app endpoints are public.
     *
     * @private
     * @param debug If `true`, adds a BASIC-level [HttpLoggingInterceptor].
     * @return Configured [OkHttpClient].
     */
    private fun _buildOkHttpClient(debug: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        if (debug) {
            builder.addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
        }

        return builder.build()
    }
}
