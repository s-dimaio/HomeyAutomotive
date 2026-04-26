package com.dimapp.android.homeyautomotive.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template
import com.dimapp.android.homeyautomotive.R
import com.dimapp.android.homeyautomotive.storage.TokenStorage

/**
 * A [Screen] dedicated to showing OAuth authentication errors.
 *
 * In Android for Cars App Library, a single [Screen] must consistently return the same
 * template type (to avoid [BackFlowViolationException]). Since the OAuth flow requires
 * a [SignInTemplate] for the QR code, but errors need a dedicated view,
 * errors are pushed as a separate screen using [LongMessageTemplate].
 *
 * [LongMessageTemplate] supports unlimited scrollable text and up to 2 actions —
 * perfect for displaying a structured list of error causes without truncation.
 *
 * @param carContext      The [CarContext].
 * @param isSessionExpired If true, shows the session expired message instead of the general
 *                         connection error.
 * @param onRetry          Callback invoked when the user taps the Retry action.
 */
class OAuthErrorScreen(
    carContext: CarContext,
    private val homeyId: String,
    private val isSessionExpired: Boolean = false,
    private val onRetry: () -> Unit
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val companionName = carContext.getString(R.string.homey_companion_app_name)

        val title = if (isSessionExpired) {
            carContext.getString(R.string.oauth_sign_in_session_expired_title)
        } else {
            carContext.getString(R.string.oauth_sign_in_error_title)
        }

        val message = if (isSessionExpired) {
            carContext.getString(R.string.oauth_sign_in_session_expired_message) + "\n\nHomey ID: $homeyId"
        } else {
            carContext.getString(R.string.oauth_sign_in_error, companionName) +
                "\n\nHomey ID: $homeyId"
        }

        return LongMessageTemplate.Builder(message)
            .setTitle(title)
            .setHeaderAction(Action.APP_ICON)
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.oauth_sign_in_retry))
                    .setOnClickListener(ParkedOnlyOnClickListener.create {
                        screenManager.pop()
                        onRetry()
                    })
                    .build()
            )
            .build()
    }
}

