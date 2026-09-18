package com.dimapp.android.homeyautomotive.screens

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.InputCallback
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.signin.InputSignInMethod
import androidx.car.app.model.signin.SignInTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.dimapp.android.homeyautomotive.R
import com.dimapp.android.homeyautomotive.auth.HomeyAuthRepository

import com.dimapp.android.homeyautomotive.storage.HomeSource
import com.dimapp.android.homeyautomotive.storage.TokenStorage
import com.dimapp.android.homeyautomotive.core.DependencyManager
import com.dimapp.android.homeyautomotive.geofence.GeofenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "SettingsScreen"

/**
 * Settings screen for the Homey Automotive app (OAuth2 version).
 *
 * Displays:
 * - Current Homey account status (logged-in email + active hub name)
 * - "Switch Homey" action — navigates to [HomeySelectionScreen] to change hub
 * - "Log Out" action — clears all OAuth2 tokens and navigates to [OAuthSignInScreen]
 * - Home tab source selection
 * - Sync interval configuration
 * - Icon cache management
 * - System information
 *
 * @param carContext The [CarContext] provided by the Car App framework.
 */
class SettingsScreen(carContext: CarContext) : Screen(carContext) {

    private val storage = DependencyManager.getTokenStorage(carContext)
    private val authRepo = DependencyManager.getAuthRepository(carContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
            }
        })
    }

    /**
     * Builds the main settings list template.
     *
     * @return [ListTemplate] with all configuration rows.
     */
    override fun onGetTemplate(): Template {
        return _buildMainTemplate()
    }

    // ── Private Methods ───────────────────────────────────────────────────────

    /**
     * Constructs the main [ListTemplate] with all settings rows.
     *
     * @private
     * @return Fully constructed [ListTemplate].
     */
    private fun _buildMainTemplate(): Template {
        val listBuilder = ItemList.Builder()
        val isConnected = authRepo.isAuthenticated()
        val isDemo = storage.isDemoMode()
        val homeyName   = storage.getSelectedHomeyName()

        // ── Account section ─────────────────────────────────────────────────
        val accountStatusText = when {
            isDemo -> carContext.getString(R.string.settings_status_demo, homeyName ?: carContext.getString(R.string.demo_hub_name))
            isConnected && homeyName != null -> carContext.getString(R.string.settings_status_oauth_ok, homeyName)
            else -> carContext.getString(R.string.settings_status_oauth_missing)
        }

        val accountRowBuilder = Row.Builder()
            .setTitle(carContext.getString(R.string.settings_account_section))
            .addText(accountStatusText)

        if (isConnected) {
            accountRowBuilder
                .setBrowsable(true)
                .setOnClickListener {
                    screenManager.push(HomeySelectionScreen(carContext, showBackButton = true))
                }
        }

        listBuilder.addItem(accountRowBuilder.build())

        if (isConnected) {
            // ── Preferences ─────────────────────────────────────────────────

            // Home Tab Source
            val currentSourceLabel = when (storage.getHomeSource()) {
                HomeSource.FAVORITES -> carContext.getString(R.string.source_favorites_title)
                HomeSource.DASHBOARD -> storage.getHomeDashboardId()
                    ?.let { carContext.getString(R.string.source_dashboard_title) }
                    ?: carContext.getString(R.string.source_favorites_title)
            }
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.settings_home_tab_source))
                    .addText(currentSourceLabel)
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(HomeSourceSelectionScreen(carContext)) }
                    .build()
            )

            // Sync interval
            val interval = storage.getSyncInterval()
            val intervalText = if (interval == 0) {
                carContext.getString(R.string.settings_sync_interval_disabled)
            } else {
                carContext.getString(R.string.settings_sync_interval_seconds, interval)
            }

            listBuilder.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.settings_sync_interval))
                    .addText(intervalText)
                    .setOnClickListener {
                        screenManager.pushForResult(
                            SettingsInputScreen(
                                carContext = carContext,
                                title = carContext.getString(R.string.settings_sync_interval_label),
                                hint = carContext.getString(R.string.settings_sync_interval_hint),
                                initialValue = storage.getSyncInterval().toString(),
                                additionalText = carContext.getString(R.string.settings_sync_interval_additional_text),
                                keyboardType = InputSignInMethod.KEYBOARD_NUMBER,
                                validator = { text ->
                                    val value = text.toIntOrNull()
                                    if (text.isEmpty() || value == null || value !in 0..300) {
                                        carContext.getString(R.string.settings_sync_interval_error)
                                    } else {
                                        null
                                    }
                                },
                                onSave = { text ->
                                    text.toIntOrNull()?.let { seconds ->
                                        if (seconds in 0..300) {
                                            storage.saveSyncInterval(seconds)
                                            CarToast.makeText(carContext, R.string.settings_saved, CarToast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        ) { invalidate() }
                    }
                    .build()
            )

            // ── Geofencing & Smart Alerts ────────────────────────────────────
            val hasFinePerm = GeofenceManager.hasFineLocationPermission(carContext)
            val hasBgPerm = GeofenceManager.hasBackgroundLocationPermission(carContext)
            val homeCoords = storage.getHomeLocation()
            val isFenceEnabled = storage.isGeofenceEnabled()

            val fenceStatusText = when {
                !hasFinePerm -> carContext.getString(R.string.settings_geofence_status_no_perm)
                homeCoords == null -> carContext.getString(R.string.settings_geofence_status_no_location)
                !isFenceEnabled -> carContext.getString(R.string.settings_geofence_status_disabled)
                !hasBgPerm -> carContext.getString(R.string.settings_geofence_status_foreground_only)
                else -> carContext.getString(R.string.settings_geofence_status_active, storage.getGeofenceRadius())
            }

            listBuilder.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.settings_geofence_status_title))
                    .addText(fenceStatusText)
                    .setBrowsable(!hasFinePerm)
                    .setOnClickListener {
                        if (!hasFinePerm) {
                            screenManager.push(PermissionScreen(carContext))
                        } else if (homeCoords == null) {
                            _refreshHomeCoordinates()
                        } else {
                            val newEnabled = !isFenceEnabled
                            storage.setGeofenceEnabled(newEnabled)
                            if (newEnabled) {
                                GeofenceManager.reregisterFromStorage(carContext)
                            } else {
                                GeofenceManager.removeGeofence(carContext)
                            }
                            invalidate()
                        }
                    }
                    .build()
            )

            // Dedicated row to upgrade to background alerts if only fine location is granted
            if (hasFinePerm && !hasBgPerm && isFenceEnabled) {
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle(carContext.getString(R.string.settings_geofence_upgrade_bg_title))
                        .addText(carContext.getString(R.string.settings_geofence_upgrade_bg_desc))
                        .setBrowsable(true)
                        .setOnClickListener {
                            screenManager.push(PermissionScreen(carContext))
                        }
                        .build()
                )
            }


            // Home Coordinates refresh
            val coordsText = homeCoords?.let {
                carContext.getString(R.string.settings_geofence_coords_format, it.first, it.second)
            } ?: carContext.getString(R.string.settings_geofence_coords_not_set)

            listBuilder.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.settings_geofence_refresh_coords))
                    .addText(coordsText)
                    .setOnClickListener { _refreshHomeCoordinates() }
                    .build()
            )

            // Clear icon cache
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.settings_clear_icon_cache))
                    .addText(carContext.getString(R.string.settings_clear_icon_cache_desc))
                    .setOnClickListener { _clearIconCache() }
                    .build()
            )
        }

        // System info (always visible)
        listBuilder.addItem(
            Row.Builder()
                .setTitle(carContext.getString(R.string.settings_system_info))
                .setBrowsable(true)
                .setOnClickListener { screenManager.push(SystemInfoScreen(carContext)) }
                .build()
        )

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.settings_header_title))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(listBuilder.build())
            .build()
    }



    /**
     * Clears the icon cache and shows a status toast.
     *
     * @private
     */
    private fun _clearIconCache() {
        scope.launch {
            try {
                // Clear icon images from disk, and both device and flow structures from memory
                com.dimapp.android.homeyautomotive.utils.IconFetcher.clearCache(carContext, DependencyManager.getTokenStorage(carContext))
                DependencyManager.getDeviceRepository(carContext).invalidateCache()
                DependencyManager.getFlowRepository(carContext).invalidateCache()

                CarToast.makeText(
                    carContext,
                    carContext.getString(R.string.settings_cache_cleared),
                    CarToast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                CarToast.makeText(
                    carContext,
                    carContext.getString(R.string.settings_cache_error),
                    CarToast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Refreshes the home coordinates from the active Homey hub and re-registers the geofence.
     *
     * @private
     */
    private fun _refreshHomeCoordinates() {
        if (storage.isDemoMode()) {
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.settings_demo_coords_unsupported),
                CarToast.LENGTH_LONG
            ).show()
            return
        }
        scope.launch {
            try {
                val coords = authRepo.refreshHomeLocation()
                if (coords != null) {
                    CarToast.makeText(carContext, R.string.settings_geofence_coords_success, CarToast.LENGTH_SHORT).show()
                    GeofenceManager.reregisterFromStorage(carContext)
                    invalidate()
                } else {
                    CarToast.makeText(carContext, R.string.settings_geofence_coords_error, CarToast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "[SettingsScreen:_refreshHomeCoordinates] Failed to refresh location: ${e.message}", e)
                CarToast.makeText(carContext, R.string.settings_geofence_coords_error, CarToast.LENGTH_SHORT).show()
            }
        }
    }
}

/**
 * A dedicated screen for handling text input (Intervals, etc.).
 * Closes itself (pops) when the user submits or navigates back.
 *
 * Implements "Save on Back" logic by intercepting the screen's lifecycle (onPause).
 * Error handling follows the pattern from the official Google car-samples repository
 * (SignInTemplateDemoScreen), using two fields to track the current and last-shown error.
 *
 * @param carContext    The [CarContext] provided by the Car App framework.
 * @param title         Title shown at the top of the sign-in template.
 * @param hint          Placeholder text for the input field.
 * @param initialValue  Pre-filled value to display in the input field.
 * @param additionalText Optional helper text displayed below the input field.
 * @param keyboardType  Keyboard type hint (e.g. [InputSignInMethod.KEYBOARD_NUMBER]).
 * @param validator     Optional validation function. Returns an error string if invalid, null otherwise.
 * @param onSave        Callback invoked with the saved text on submit or back navigation.
 */
class SettingsInputScreen(
    carContext: CarContext,
    private val title: String,
    private val hint: String,
    private val initialValue: String,
    private val additionalText: String? = null,
    private val keyboardType: Int = InputSignInMethod.KEYBOARD_DEFAULT,
    private val validator: ((String) -> String?)? = null,
    private val onSave: (String) -> Unit
) : Screen(carContext) {

    private var inputBuffer = initialValue
    private var isSubmitted = false

    // Mirrors mErrorMessage / mLastErrorMessage from Google's SignInTemplateDemoScreen sample.
    // Empty string means "no error". lastErrorMessage tracks the last error actually rendered
    // to the screen, so we avoid unnecessary invalidate() calls.
    private var errorMessage: String = ""
    private var lastErrorMessage: String = ""

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) {
                // Save even if the user presses Back without hitting Submit, but ONLY if valid
                if (!isSubmitted) {
                    val error = validator?.invoke(inputBuffer) ?: ""
                    if (error.isEmpty()) {
                        onSave(inputBuffer)
                        setResult(inputBuffer)
                    }
                }
            }
        })
    }

    override fun onGetTemplate(): Template {
        val signInMethodBuilder = InputSignInMethod.Builder(object : InputCallback {
            override fun onInputTextChanged(text: String) {
                inputBuffer = text
                if (validator != null) {
                    errorMessage = validator.invoke(text) ?: ""
                    // Mirrors Google sample logic: invalidate ONLY if clearing an existing error,
                    // or if the error message has changed. This avoids the invalidate() loop.
                    if (lastErrorMessage.isNotEmpty() &&
                        (errorMessage.isEmpty() || lastErrorMessage != errorMessage)
                    ) {
                        invalidate()
                    }
                }
            }

            override fun onInputSubmitted(text: String) {
                if (validator != null) {
                    errorMessage = validator.invoke(text) ?: ""
                    if (errorMessage.isNotEmpty()) {
                        // Always invalidate on submit to ensure the error is shown
                        invalidate()
                        return
                    }
                }

                isSubmitted = true
                onSave(text)
                setResult(text)
                screenManager.pop()
            }
        })
            .setHint(hint)
            .setDefaultValue(inputBuffer)
            .setKeyboardType(keyboardType)
            .setShowKeyboardByDefault(true)

        if (errorMessage.isNotEmpty()) {
            signInMethodBuilder.setErrorMessage(errorMessage)
            lastErrorMessage = errorMessage  // Track what was actually rendered
        }

        val templateBuilder = SignInTemplate.Builder(signInMethodBuilder.build())
            .setTitle(title)
            .setHeaderAction(Action.BACK)

        if (additionalText != null) {
            templateBuilder.setAdditionalText(additionalText)
        }

        return templateBuilder.build()
    }
}


