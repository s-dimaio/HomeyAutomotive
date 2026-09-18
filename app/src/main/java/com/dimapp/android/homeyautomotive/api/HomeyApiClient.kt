package com.dimapp.android.homeyautomotive.api

import com.dimapp.android.homeyautomotive.auth.HomeyAuthRepository
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Factory for creating [HomeyApiService] Retrofit instances.
 *
 * Each call to [createWithClient] builds a new Retrofit instance pointed at the provided
 * [baseUrl], reusing the application-wide shared [OkHttpClient] from [DependencyManager].
 * This ensures connection pool reuse and consistent token injection across all requests.
 *
 * Usage:
 * ```
 * val service = HomeyApiClient.createWithClient(
 *     baseUrl = storage.getBaseUrl()!!,
 *     client  = DependencyManager.getOkHttpClient(context)
 * )
 * val response = service.getDevices()
 * ```
 */
object HomeyApiClient {

    // ── Private constants ──────────────────────────────────────────────────────

    private const val TIMEOUT_SECONDS = 30L

    /**
     * Creates a new [HomeyApiService] using a shared [OkHttpClient].
     *
     * This is the preferred (and only) way to create services via [com.dimapp.android.homeyautomotive.core.DependencyManager]
     * to ensure socket and connection pool reuse across all repositories.
     *
     * @public
     * @param baseUrl Full base URL including trailing slash.
     *   Example: `"https://abc123.connect.athom.com/api/"`.
     * @param client  Shared [OkHttpClient] instance (obtained from [DependencyManager]).
     * @return A ready-to-use [HomeyApiService].
     */
    fun createWithClient(
        baseUrl: String,
        client: OkHttpClient
    ): HomeyApiService {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(HomeyApiService::class.java)
    }

    /**
     * Builds a shared [OkHttpClient] equipped with token injection.
     * 
     * @param authRepo [HomeyAuthRepository] for the injector interceptor.
     * @param debug    Whether to enable logging.
     * @return Configured [OkHttpClient].
     */
    fun buildSharedOkHttpClient(
        authRepo: HomeyAuthRepository,
        debug: Boolean = false
    ): OkHttpClient {
        return _buildOkHttpClient(authRepo, debug)
    }

    // ── Private Methods ────────────────────────────────────────────────────────

    /**
     * Builds the [OkHttpClient] with timeouts, token injection, and optional HTTP logging.
     *
     * @private
     * @param authRepo [HomeyAuthRepository] provided to [TokenInjectorInterceptor].
     * @param debug    If `true`, enables BASIC-level HTTP logging.
     * @return Configured [OkHttpClient] instance.
     */
    private fun _buildOkHttpClient(authRepo: HomeyAuthRepository, debug: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(TokenInjectorInterceptor(authRepo))

        if (debug) {
            builder.addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
        }

        return builder.build()
    }
}

/**
 * OkHttp [Interceptor] that automatically injects the OAuth2 Bearer token
 * into every outgoing request, refreshing it if expired.
 *
 * This eliminates the need to pass `Authorization` headers manually in
 * [HomeyApiService] method signatures.
 *
 * If the token cannot be obtained (user logged out or refresh failed),
 * the request proceeds without an Authorization header — upstream callers
 * will receive an HTTP 401 which triggers the sign-in flow.
 *
 * @property authRepo [HomeyAuthRepository] used to obtain a valid Bearer token.
 * @constructor Creates a new [TokenInjectorInterceptor].
 */
class TokenInjectorInterceptor(
    private val authRepo: HomeyAuthRepository
) : Interceptor {

    /**
     * Intercepts each request, injects the Bearer token, and proceeds.
     *
     * Token retrieval (including refresh if expired) is performed synchronously
     * using [runBlocking] since OkHttp interceptors execute on background threads.
     *
     * @public
     * @param chain The OkHttp [Interceptor.Chain].
     * @return The [Response] from the upstream server.
     */
    override fun intercept(chain: Interceptor.Chain): Response {
        val bearer = runBlocking { authRepo.getValidDelegationToken() }

        val request = if (bearer != null) {
            chain.request().newBuilder()
                .header("Authorization", bearer)
                .build()
        } else {
            chain.request()
        }

        val response = chain.proceed(request)

        // If the Homey Hub returns 401 (token expired), attempt a silent refresh
        if (response.code == 401) {
            val isRefreshed = runBlocking { authRepo.refreshHubSessionToken(bearer) }
            if (isRefreshed) {
                val newBearer = runBlocking { authRepo.getValidDelegationToken() }
                if (newBearer != null) {
                    // Close the previous failed response body before retrying
                    response.close()

                    val newRequest = chain.request().newBuilder()
                        .header("Authorization", newBearer)
                        .build()
                    return chain.proceed(newRequest)
                }
            }
        }

        return response
    }
}
