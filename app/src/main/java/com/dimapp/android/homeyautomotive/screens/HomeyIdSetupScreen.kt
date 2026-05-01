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

    /** Current error message to show below the input field (if any). */
    private var errorMessage: String? = null

    /** Counter to force the host to treat the input field as new on every clear. */
    private var resetCount: Int = 0

    /**
     * Builds a [SignInTemplate] with an [InputSignInMethod] for the Homey Cloud ID entry.
     *
     * @return The [Template] for this screen.
     */
    override fun onGetTemplate(): Template {
        val inputMethodBuilder = InputSignInMethod.Builder(object : InputCallback {
            override fun onInputTextChanged(text: String) {
                inputValue = text
                // Hide error message as soon as the user starts typing again
                if (errorMessage != null) {
                    errorMessage = null
                    invalidate()
                }
            }

            override fun onInputSubmitted(text: String) {
                _onConfirm(text.trim())
            }
        })
            // Append invisible zero-width spaces to force the host to recreate the input field
            .setHint(carContext.getString(R.string.homey_id_setup_hint) + "\u200B".repeat(resetCount % 5))
            .setDefaultValue(inputValue)
            .setKeyboardType(InputSignInMethod.KEYBOARD_DEFAULT)
            .setShowKeyboardByDefault(true)

        // Set error message if validation failed
        errorMessage?.let { inputMethodBuilder.setErrorMessage(it) }

        val inputMethod = inputMethodBuilder.build()

        return SignInTemplate.Builder(inputMethod)
            .setTitle(carContext.getString(R.string.homey_id_setup_title))
            .setHeaderAction(if (isAddingHub) Action.BACK else Action.APP_ICON)
            .setInstructions(carContext.getString(R.string.homey_id_setup_instructions))
            .setAdditionalText(carContext.getString(R.string.homey_id_setup_additional))
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.homey_id_setup_confirm))
                    .setOnClickListener(ParkedOnlyOnClickListener.create { _onConfirm(inputValue) })
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.homey_id_setup_clear))
                    .setOnClickListener(ParkedOnlyOnClickListener.create {
                        inputValue = ""
                        errorMessage = null
                        resetCount++
                        invalidate()
                    })
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
            Log.w(TAG, "User submitted empty Homey ID — showing error.")
            errorMessage = carContext.getString(R.string.homey_id_setup_error_empty)
            invalidate()
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
