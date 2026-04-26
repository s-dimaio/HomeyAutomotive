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
        val homeyName   = storage.getSelectedHomeyName()

        // ── Account section ─────────────────────────────────────────────────
        val accountRowBuilder = Row.Builder()
            .setTitle(carContext.getString(R.string.settings_account_section))
            .addText(
                if (isConnected && homeyName != null)
                    carContext.getString(R.string.settings_status_oauth_ok, homeyName)
                else
                    carContext.getString(R.string.settings_status_oauth_missing)
            )

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
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.settings_sync_interval))
                    .addText(carContext.getString(R.string.settings_sync_interval_seconds, storage.getSyncInterval()))
                    .setOnClickListener {
                        screenManager.pushForResult(
                            SettingsInputScreen(
                                carContext,
                                carContext.getString(R.string.settings_sync_interval_label),
                                carContext.getString(R.string.settings_sync_interval_hint),
                                storage.getSyncInterval().toString(),
                                InputSignInMethod.KEYBOARD_NUMBER
                            ) { text ->
                                text.toIntOrNull()?.let { seconds ->
                                    if (seconds >= 0) {
                                        storage.saveSyncInterval(seconds)
                                        CarToast.makeText(carContext, R.string.settings_saved, CarToast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        ) { invalidate() }
                    }
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
                // Clear both icon images from disk and device structure from memory
                com.dimapp.android.homeyautomotive.utils.IconFetcher.clearCache(carContext, DependencyManager.getTokenStorage(carContext))
                DependencyManager.getDeviceRepository(carContext).invalidateCache()

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
}

/**
 * A dedicated screen for handling text input (Intervals, etc.).
 * Closes itself (pops) when the user submits or navigates back.
 *
 * Implements "Save on Back" logic by intercepting the screen's lifecycle (onPause).
 *
 * @param carContext    The [CarContext] provided by the Car App framework.
 * @param title         Title shown at the top of the sign-in template.
 * @param hint          Placeholder text for the input field.
 * @param initialValue  Pre-filled value to display in the input field.
 * @param keyboardType  Keyboard type hint (e.g. [InputSignInMethod.KEYBOARD_NUMBER]).
 * @param onSave        Callback invoked with the saved text on submit or back navigation.
 */
class SettingsInputScreen(
    carContext: CarContext,
    private val title: String,
    private val hint: String,
    private val initialValue: String,
    private val keyboardType: Int = InputSignInMethod.KEYBOARD_DEFAULT,
    private val onSave: (String) -> Unit
) : Screen(carContext) {

    private var inputBuffer = initialValue
    private var isSubmitted = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) {
                // Save even if the user presses Back without hitting Submit
                if (!isSubmitted) {
                    onSave(inputBuffer)
                    setResult(inputBuffer)
                }
            }
        })
    }

    override fun onGetTemplate(): Template {
        val signInMethod = InputSignInMethod.Builder(object : InputCallback {
            override fun onInputTextChanged(text: String) {
                inputBuffer = text
            }

            override fun onInputSubmitted(text: String) {
                isSubmitted = true
                onSave(text)
                setResult(text)
                screenManager.pop()
            }
        })
            .setHint(hint)
            .setDefaultValue(initialValue)
            .setKeyboardType(keyboardType)
            .setShowKeyboardByDefault(true)
            .build()

        return SignInTemplate.Builder(signInMethod)
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .build()
    }
}
