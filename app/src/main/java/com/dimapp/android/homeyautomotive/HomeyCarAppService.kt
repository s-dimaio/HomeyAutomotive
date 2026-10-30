package com.dimapp.android.homeyautomotive

import android.content.Intent
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.dimapp.android.homeyautomotive.core.DependencyManager
import com.dimapp.android.homeyautomotive.geofence.GeofenceReceiver
import com.dimapp.android.homeyautomotive.screens.HomeyIdSetupScreen
import com.dimapp.android.homeyautomotive.screens.HomeySelectionScreen
import com.dimapp.android.homeyautomotive.screens.MainTabScreen

/**
 * Entry point for the Homey Automotive Car App.
 *
 * Extends [CarAppService] and declares the IOT category in AndroidManifest.xml.
 * Validates all hosts for development; restrict to Volvo-signed hosts for production.
 *
 * @see <a href="https://developer.android.com/training/cars/apps/iot">IOT Car Apps</a>
 */
class HomeyCarAppService : CarAppService() {

    /**
     * Returns the host validator configured for the Car App Library.
     *
     * Employs a dual-mode validation strategy:
     * - In debug builds ([BuildConfig.DEBUG] is true), permits all hosts via [HostValidator.ALLOW_ALL_HOSTS_VALIDATOR]
     *   to facilitate development with DHU (Desktop Head Unit) and custom local test environments.
     * - In release builds ([BuildConfig.DEBUG] is false), restricts host connections strictly to validated
     *   Google Automotive Services (GAS) and approved OEM hosts using [androidx.car.app.R.array.hosts_allowlist_sample].
     *
     * Public method overriding [CarAppService.createHostValidator].
     *
     * @return A configured [HostValidator] instance appropriate for the current build variant.
     */
    override fun createHostValidator(): HostValidator {
        return if (BuildConfig.DEBUG) {
            Log.d("HomeyCarAppService", "[HomeyCarAppService:createHostValidator] Allowing all hosts (DEBUG mode)")
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            Log.i("HomeyCarAppService", "[HomeyCarAppService:createHostValidator] Enforcing strict HostValidator with allowed hosts allowlist (RELEASE mode)")
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    /**
     * Creates the single [Session] for this Car App.
     *
     * @return A new [HomeySession] instance.
     */
    override fun onCreateSession(): Session {
        return HomeySession()
    }
}

/**
 * Manages the lifecycle of the Homey Automotive Car App session.
 *
 * Direct routing logic avoiding intermediate "ghost" screens:
 * - Active hub configured or Demo mode -> [MainTabScreen] directly as root.
 * - Hubs configured but none selected -> [HomeySelectionScreen]
 * - First run / no hubs configured -> [HomeyIdSetupScreen] directly as root.
 */
class HomeySession : Session() {

    /**
     * Called when the session is first created. Returns the initial screen.
     *
     * Reads the authentication state directly from [TokenStorage] and routes directly:
     * - If demo mode is active or an authenticated hub is already selected, returns [MainTabScreen] directly as root.
     * - If hubs exist in storage but none is active, returns [HomeySelectionScreen].
     * - If no hubs are configured at all, returns [HomeyIdSetupScreen] directly as root.
     *
     * This avoids pushing an unnecessary intermediate screen, preserving the 5-step AAOS quota.
     *
     * @param intent The intent that started the session.
     * @return The initial [Screen] to display.
     */
    override fun onCreateScreen(intent: Intent): Screen {
        Log.i("HomeySession", "[HomeySession:onCreateScreen] Host validated successfully. Routing to initial screen.")
        val storage = DependencyManager.getTokenStorage(carContext)
        val selectedHubId = storage.getSelectedHomeyId()
        val allHubs = storage.getAllHubTokens()

        if (storage.isDemoMode()) {
            GeofenceReceiver.postDemoArrivalNotification(carContext)
            return MainTabScreen(carContext)
        }

        return if (!selectedHubId.isNullOrBlank() && allHubs.containsKey(selectedHubId)) {
            MainTabScreen(carContext)
        } else if (allHubs.isNotEmpty()) {
            HomeySelectionScreen(carContext, showBackButton = false)
        } else {
            HomeyIdSetupScreen(carContext, initialId = selectedHubId ?: "", isAddingHub = false)
        }
    }
}
