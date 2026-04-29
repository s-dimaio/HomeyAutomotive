package com.dimapp.android.homeyautomotive.screens

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.InputCallback
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template
import androidx.car.app.model.signin.InputSignInMethod
import androidx.car.app.model.signin.SignInTemplate
import com.dimapp.android.homeyautomotive.R

private const val TAG = "HomeyIdSetupScreen"

/**
 * A parked-only [Screen] that prompts the user to enter their Homey Cloud ID.
 *
 * This screen is shown:
 * - On first launch (when no hub is configured).
 * - When [isAddingHub] is `true`, to add an additional hub without losing existing ones.
 * - When all hubs are removed via [HomeySelectionScreen] (full re-setup flow).
 *
 * Unlike the previous implementation, this screen uses **no callbacks**. On confirmation,
 * it directly pushes [OAuthSignInScreen] with the entered ID and the [isAddingHub] flag,
 * eliminating all lifecycle-based race conditions that previously required double ID entry.
 *
 * @param carContext  The [CarContext] provided by the Car App framework.
 * @param initialId   Optional pre-filled value for the input field (e.g. when retrying).
 * @param isAddingHub If `true`, signals to [OAuthSignInScreen] that this is an "add hub"
 *                    operation (it will pop back after success instead of going to MainTabScreen).
 */
class HomeyIdSetupScreen(
    carContext: CarContext,
    private val initialId: String = "",
    private val isAddingHub: Boolean = false,
) : Screen(carContext) {
    /** The current value typed into the input field. Updated by [InputCallback.onInputTextChanged]. */
    private var inputValue: String = initialId

    /**
     * Builds a [SignInTemplate] with an [InputSignInMethod] for the Homey Cloud ID entry.
     *
     * @return The [Template] for this screen.
     */
    override fun onGetTemplate(): Template {
        val inputMethod = InputSignInMethod.Builder(object : InputCallback {
            override fun onInputTextChanged(text: String) {
                inputValue = text
            }

            override fun onInputSubmitted(text: String) {
                // The CarHost passes the final text reliably here — use it directly.
                _onConfirm(text.trim())
            }
        })
            .setHint(carContext.getString(R.string.homey_id_setup_hint))
            .setDefaultValue(inputValue)
            .setKeyboardType(InputSignInMethod.KEYBOARD_DEFAULT)
            .setShowKeyboardByDefault(true)
            .build()

        return SignInTemplate.Builder(inputMethod)
            .setTitle(carContext.getString(R.string.homey_id_setup_title))
            .setHeaderAction(if (isAddingHub) Action.BACK else Action.APP_ICON)
            .setInstructions(carContext.getString(R.string.homey_id_setup_instructions))
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.homey_id_setup_confirm))
                    .setOnClickListener(ParkedOnlyOnClickListener.create { _onConfirm(inputValue) })
                    .build()
            )
            .build()
    }

    // ── Private Methods ────────────────────────────────────────────────────────

    /**
     * Validates the entered Homey Cloud ID and directly navigates to [OAuthSignInScreen].
     *
     * No callbacks are involved: this method pushes [OAuthSignInScreen] with the
     * confirmed ID as a constructor parameter, completely avoiding the lifecycle
     * race condition that existed in the callback-based approach.
     *
     * @private
     * @param id The Homey Cloud ID entered by the user. Must not be blank.
     */
    private fun _onConfirm(id: String) {
        // Remove all whitespace characters (spaces, tabs, newlines) from the ID.
        // This is especially useful for voice dictation which often inserts spaces during pauses.
        val cleanedId = id.replace("\\s".toRegex(), "")

        if (cleanedId.isBlank()) {
            Log.w(TAG, "User submitted empty Homey ID — ignoring.")
            return
        }

        Log.d(TAG, "Homey ID confirmed: ${cleanedId.take(6)}... (isAddingHub=$isAddingHub). Pushing OAuth screen.")
        screenManager.push(
            OAuthSignInScreen(
                carContext = carContext,
                targetHomeyId = cleanedId,
                isAddingHub = isAddingHub
            )
        )
    }
}
