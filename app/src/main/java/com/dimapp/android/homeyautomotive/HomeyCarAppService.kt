package com.dimapp.android.homeyautomotive

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.dimapp.android.homeyautomotive.screens.HomeySelectionScreen

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
     * Returns the host validator.
     *
     * Using [HostValidator.ALLOW_ALL_HOSTS_VALIDATOR] for development convenience.
     * For production, this should be replaced with a signed-host validator.
     *
     * @return [HostValidator] instance.
     */
    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
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
 * Routing logic based on OAuth2 authentication state:
 * - Not authenticated → [OAuthSignInScreen]
 * - Authenticated, no Homey selected → [HomeySelectionScreen]
 * - Authenticated, Homey selected → [MainTabScreen]
 */
class HomeySession : Session() {

    /**
     * Called when the session is first created. Returns the initial screen.
     *
     * Reads the authentication state from [HomeyAuthRepository] and routes accordingly:
     * - If the user is not authenticated, shows [OAuthSignInScreen].
     * - If authenticated but no Homey hub is selected yet, shows [HomeySelectionScreen].
     * - If fully configured, shows [MainTabScreen] directly.
     *
     * @param intent The intent that started the session.
     * @return The initial [Screen] to display.
     */
    override fun onCreateScreen(intent: Intent): Screen {
        // HomeySelectionScreen is now our fixed root.
        // It handles auto-redirecting to either MainTabScreen (if logged in)
        // or HomeyIdSetupScreen (if not) during its lifecycle.
        return HomeySelectionScreen(carContext)
    }
}
