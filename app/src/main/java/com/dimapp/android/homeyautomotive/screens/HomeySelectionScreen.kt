package com.dimapp.android.homeyautomotive.screens

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.dimapp.android.homeyautomotive.R
import com.dimapp.android.homeyautomotive.api.HomeyPayload
import com.dimapp.android.homeyautomotive.core.DependencyManager
import com.dimapp.android.homeyautomotive.storage.TokenStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "HomeySelectionScreen"

/**
 * AAOS screen that displays the list of Homey hubs saved by the user and provides
 * full hub management capabilities.
 *
 * Available actions per hub:
 * - **Tap the row**: switches the active hub (no-op if already active).
 * - **Disconnect action**: removes the hub from storage. If it was the only hub,
 *   the user is redirected to [HomeyIdSetupScreen] to configure a new one.
 *   If other hubs remain, the list auto-refreshes and the active hub switches.
 *
 * A "Add another Homey" row at the bottom navigates to [HomeyIdSetupScreen]
 * with [isAddingHub] = `true`, so [OAuthSignInScreen] knows to pop back here after auth.
 *
 * On single-hub first run ([showBackButton] = `false`), the hub is auto-selected and the
 * screen navigates directly to [MainTabScreen]. On subsequent visits ([showBackButton] = `true`),
 * the full management UI is shown.
 *
 * @param carContext    The [CarContext] provided by the Car App framework.
 * @param showBackButton If `true`, shows a back arrow (accessed from Settings).
 *                       If `false` (default), hides the back arrow (first-run flow).
 */
class HomeySelectionScreen(
    carContext: CarContext,
    private val showBackButton: Boolean = false
) : Screen(carContext) {

    private val storage = DependencyManager.getTokenStorage(carContext)
    private val authRepo = DependencyManager.getAuthRepository(carContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** List of all authenticated Homey hubs added manually by the user. */
    private var homeys: List<HomeyPayload> = emptyList()

    /** Flag to prevent multiple pushes to screenManager. */
    private var isRedirecting = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {

            override fun onResume(owner: LifecycleOwner) {
                // onResume fires exclusively when this screen is at the top of
                // the stack — both on initial creation and when returning from a
                // back-navigation. Resetting isRedirecting here (rather than in
                // onStart) prevents the guard from blocking the first call to
                // _loadHomeys() after popToRoot(), where onStart fires before
                // the flag has been cleared.
                isRedirecting = false
                _loadHomeys()
                _syncHubNames()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
            }
        })
    }

    /**
     * Builds the [ListTemplate] with the hub list and management actions.
     *
     * @return The [Template] to render.
     */
    override fun onGetTemplate(): Template {
        val header = Header.Builder()
            .setTitle(carContext.getString(R.string.homey_selection_title))
            .apply {
                if (showBackButton) setStartHeaderAction(Action.BACK)
            }
            .build()

        // Transient empty state while _loadHomeys() is executing its redirect
        if (homeys.isEmpty()) {
            val listBuilder = ItemList.Builder()
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.label_loading))
                    .build()
            )
            return ListTemplate.Builder()
                .setHeader(header)
                .setSingleList(listBuilder.build())
                .build()
        }

        val activeId = storage.getSelectedHomeyId()
        val listBuilder = ItemList.Builder()

        homeys.forEach { homey ->
            val isActive = homey.id == activeId
            val rowTitle = if (homey.id == "demo") homey.name else "${homey.name} (id: ${homey.id.take(16)}...)"
            val rowSubtitle = if (isActive) carContext.getString(R.string.homey_selection_active_label) else ""

            val rowBuilder = Row.Builder()
                .setTitle(rowTitle)

            if (rowSubtitle.isNotEmpty()) {
                rowBuilder.addText(rowSubtitle)
            }

            rowBuilder.setOnClickListener(
                ParkedOnlyOnClickListener.create { 
                    screenManager.push(
                        HomeyDetailScreen(carContext, homey, isActive) {
                            _loadHomeys()
                        }
                    )
                }
            )

            listBuilder.addItem(rowBuilder.build())
        }

        // Add another Homey row
        listBuilder.addItem(
            Row.Builder()
                .setTitle(carContext.getString(R.string.homey_selection_add_hub))
                .addText(carContext.getString(R.string.homey_selection_add_hub_desc))
                .setOnClickListener(
                    ParkedOnlyOnClickListener.create {
                        // Use a short delay to allow the AAOS host to settle UX restrictions state.
                        // Without this, the host sometimes throws a false positive 
                        // "Sign-in not available while driving" error when pushing a SignInTemplate.
                        Handler(Looper.getMainLooper()).postDelayed({
                            screenManager.push(
                                HomeyIdSetupScreen(carContext = carContext, isAddingHub = true)
                            )
                        }, 500)
                    }
                )
                .build()
        )

        return ListTemplate.Builder()
            .setHeader(header)
            .setSingleList(listBuilder.build())
            .build()
    }

    // ── Private Methods ───────────────────────────────────────────────────────

    /**
     * Loads all manually added and authenticated hubs from [TokenStorage].
     *
     * The list is sourced exclusively from [TokenStorage.getAllHubTokens] — hub discovery
     * via Companion App is not supported since the Companion App is Hub-centric and has
     * no visibility into the other hubs associated with the Athom account.
     *
     * If no hubs are found, redirects to [HomeyIdSetupScreen].
     * On single-hub first run ([showBackButton] = `false`), auto-navigates to [MainTabScreen].
     *
     * @private
     */
    private fun _loadHomeys() {
        if (isRedirecting) return

        val authHubs = storage.getAllHubTokens()
        homeys = authHubs.entries.mapNotNull { (id, entry) ->
            val name = entry.name ?: return@mapNotNull null
            val apiUrl = entry.apiUrl ?: return@mapNotNull null
            HomeyPayload(id, name, apiUrl)
        }

        Log.d(TAG, "Loaded ${homeys.size} Homey hub(s) from storage. Redirecting check...")

        if (homeys.isEmpty()) {
            // No hubs at all — go directly to initial setup with a tiny delay
            // to allow AAOS host to settle and prevent 'Sign-in not available' alert
            isRedirecting = true
            Log.d(TAG, "No hubs found — setting up redirection delay.")
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "Executing delayed redirection to HomeyIdSetupScreen.")
                screenManager.push(HomeyIdSetupScreen(carContext = carContext, isAddingHub = false))
            }, 500)
            return
        }

        // Auto-select and navigate to Home if we are already authenticated 
        // and NOT explicitly managing the hub list.
        if (homeys.any { it.id == storage.getSelectedHomeyId() } && !showBackButton) {
            Log.d(TAG, "Already authenticated — setting up auto-navigation delay for MainTabScreen.")
            isRedirecting = true
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "Executing delayed auto-navigation to MainTabScreen.")
                screenManager.push(MainTabScreen(carContext))
            }, 500)
        } else {
            invalidate()
        }
    }

    /**
     * Synchronizes the user-defined hub display names (e.g., "Veggia", "Modena") in the background.
     *
     * Private method.
     * Queries /manager/system/name or /manager/system for each authenticated hub
     * and updates [TokenStorage] if the retrieved name differs from the current entry.
     * If any name is updated, refreshes the local hub list and invalidates the template.
     */
    private fun _syncHubNames() {
        val authHubs = storage.getAllHubTokens()
        scope.launch(Dispatchers.IO) {
            var anyUpdated = false
            for ((id, entry) in authHubs) {
                if (id == "demo") continue
                val token = entry.sessionToken ?: continue
                val apiUrl = entry.apiUrl ?: continue
                try {
                    val realName = authRepo.fetchHubSystemName(apiUrl, token)
                    if (!realName.isNullOrBlank() && realName != entry.name) {
                        Log.i(TAG, "[HomeySelectionScreen:_syncHubNames] Hub $id name updated: '${entry.name}' -> '$realName'")
                        val updated = storage.updateHubName(id, realName)
                        if (updated) anyUpdated = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[HomeySelectionScreen:_syncHubNames] Failed to fetch name for hub $id: ${e.message}")
                }
            }
            if (anyUpdated) {
                withContext(Dispatchers.Main) {
                    _loadHomeys()
                }
            }
        }
    }

}
