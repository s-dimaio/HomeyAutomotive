package com.dimapp.android.homeyautomotive.screens

import android.net.Uri
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template
import androidx.car.app.model.signin.QRCodeSignInMethod
import androidx.car.app.model.signin.SignInTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.dimapp.android.homeyautomotive.R
import com.dimapp.android.homeyautomotive.auth.HomeyAuthRepository
import com.dimapp.android.homeyautomotive.auth.PollResult
import com.dimapp.android.homeyautomotive.core.DependencyManager
import com.dimapp.android.homeyautomotive.storage.TokenStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom

private const val TAG = "OAuthSignInScreen"

/** Polling interval in milliseconds while waiting for the user to authorize on their phone. */
private const val POLL_INTERVAL_MS = 3_000L

/**
 * Maximum duration in milliseconds for the QR code to remain valid.
 * Aligned with Homey Companion App's SESSION_TTL_MS (2 minutes).
 */
private const val QR_TIMEOUT_MS = 120_000L


/**
 * AAOS sign-in screen that orchestrates the OAuth2 Authorization Code flow
 * via the Homey AAOS Companion App.
 *
 * Flow:
 * 1. On screen creation, a 64-character hex `sessionId` is generated using [SecureRandom].
 * 2. [HomeyAuthRepository.startCompanionAuth] is called (`POST /auth/start`), which triggers
 *    `createOAuth2Callback` on the Companion App and returns the Athom authorization URL.
 * 3. The URL is displayed as a QR code via [QRCodeSignInMethod].
 * 4. The user scans the QR code with their phone, logs in on accounts.athom.com.
 * 5. Athom redirects to `callback.athom.com` — the Companion App receives the code.
 * 6. The Companion App exchanges the code for tokens and stores them.
 * 7. This screen polls [HomeyAuthRepository.pollCompanionAuth] (`GET /auth/poll`) every
 *    [POLL_INTERVAL_MS] ms until the result is `complete`.
 * 8. On success:
 *    - If [isAddingHub] is `true`: pops back to [HomeySelectionScreen] (adds hub to existing list).
 *    - If [isAddingHub] is `false`: navigates to [MainTabScreen] for initial setup completion.
 *
 * This screen requires [targetHomeyId] to always be provided. The Homey ID is collected
 * upstream by [HomeyIdSetupScreen] before this screen is pushed.
 *
 * @param carContext    The [CarContext] provided by the Car App framework.
 * @param targetHomeyId The Hub ID to authenticate against. Must not be null.
 * @param isAddingHub   If `true`, signals that this is an "add second hub" operation.
 *                      On success, the screen pops back to [HomeySelectionScreen] instead
 *                      of pushing [MainTabScreen].
 */
class OAuthSignInScreen(
    carContext: CarContext,
    private val targetHomeyId: String,
    private val isAddingHub: Boolean = false
) : Screen(carContext) {

    /** Internal sign-in state, drives [onGetTemplate] re-renders. */
    private enum class State {
        SETUP,
        LOADING,
        SHOWING_QR,
        AUTHORIZING
    }

    private val authRepo = DependencyManager.getAuthRepository(carContext)
    private val storage = DependencyManager.getTokenStorage(carContext)
    private val scope        = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job?    = null
    private var timeoutJob: Job? = null

    private var state: State  = State.SETUP

    private var sessionId: String = ""
    private var authUrl: String   = ""
    private var isAuthStarted: Boolean = false
    private var timeRemainingSeconds: Int = 0

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                _onScreenReady()
            }

            override fun onStop(owner: LifecycleOwner) {
                pollJob?.cancel()
                timeoutJob?.cancel()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
            }
        })
    }

    /**
     * Builds the [SignInTemplate] based on the current [State].
     *
     * @return The [Template] appropriate for the current state.
     */
    override fun onGetTemplate(): Template {
        return when (state) {
            State.SETUP, State.LOADING -> _buildLoadingTemplate()
            State.SHOWING_QR -> _buildQrTemplate()
            State.AUTHORIZING -> _buildAuthorizingTemplate()
        }
    }

    // ── Private Methods ───────────────────────────────────────────────────────

    /**
     * Entry point called once the screen is started.
     *
     * Directly initiates the auth session using [targetHomeyId] (always available, since
     * [HomeyIdSetupScreen] now pushes this screen with the ID already set).
     * Resumes polling if auth was already started (screen resumed from background).
     *
     * @private
     */
    private fun _onScreenReady() {
        if (isAuthStarted) {
            // Resume polling if the screen comes back from the background
            if (state == State.SHOWING_QR || state == State.AUTHORIZING) {
                _startPolling(targetHomeyId)
            }
            return
        }
        _startAuthSession(targetHomeyId)
    }

    /**
     * Generates a new [sessionId], calls [HomeyAuthRepository.startCompanionAuth], and
     * transitions to [State.SHOWING_QR] on success or pushes [OAuthErrorScreen] on failure.
     *
     * @private
     * @param homeyId The Homey hub ID used to build the Companion App URL.
     */
    private fun _startAuthSession(homeyId: String) {
        if (isAuthStarted) return
        isAuthStarted = true

        sessionId = _generateSessionId()
        state = State.LOADING
        invalidate()

        scope.launch {
            Log.d(TAG, "Starting companion auth session (homeyId=${homeyId.take(6)}...).")
            val url = authRepo.startCompanionAuth(homeyId = homeyId, sessionId = sessionId)
            if (url.isNullOrBlank()) {
                Log.e(TAG, "startCompanionAuth returned null or empty URL.")
                _showError(isExpired = false)
            } else {
                authUrl = url
                state = State.SHOWING_QR
                _startPolling(homeyId)
                _startCountdown()
                invalidate()
            }
        }
    }

    private fun _showError(isExpired: Boolean) {
        screenManager.push(OAuthErrorScreen(carContext, targetHomeyId, isSessionExpired = isExpired) {
            if (isExpired) {
                _refreshSession()
            } else {
                _retrySetup()
            }
        })
    }

    /**
     * Starts a coroutine that polls [HomeyAuthRepository.pollCompanionAuth] every
     * [POLL_INTERVAL_MS] milliseconds until the session is `complete` or times out.
     *
     * Post-auth navigation:
     * - [isAddingHub] = `true` → pop back to [HomeySelectionScreen] (hub added)
     * - [isAddingHub] = `false` → push [MainTabScreen] (initial setup complete)
     *
     * @private
     * @param homeyId The Homey hub ID matching the active session.
     */
    private fun _startPolling(homeyId: String) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                when (val result = authRepo.pollCompanionAuth(homeyId = homeyId, sessionId = sessionId)) {
                    is PollResult.Success -> {
                        timeoutJob?.cancel()
                        
                        // Switch to an safe non-auth template before navigating
                        state = State.LOADING
                        invalidate()

                        Log.d(TAG, "Authentication complete — returning to root (isAddingHub=$isAddingHub).")
                        screenManager.popToRoot()
                        return@launch
                    }
                    is PollResult.Expired -> {
                        Log.w(TAG, "Auth session expired on server — stopping polling.")
                        timeoutJob?.cancel()
                        _showError(isExpired = true)
                        return@launch
                    }
                    is PollResult.Error -> {
                        Log.e(TAG, "Auth session reported a server-side error — stopping polling.")
                        timeoutJob?.cancel()
                        _showError(isExpired = false)
                        return@launch
                    }
                    is PollResult.NetworkError -> {
                        Log.w(TAG, "Polling network error: ${result.message}. Retrying...")
                        // We keep polling on network error in case it's a transient glitch
                    }
                    is PollResult.Pending -> {
                        // Just keep polling
                    }
                }
            }
        }
    }

    /**
     * Starts a countdown timer that updates the UI periodically.
     * To prevent UI flicker (caused by IPC bottlenecks in AAOS), the template
     * is invalidated only every 30 seconds instead of every second.
     * On expiry, the polling is stopped and the screen pushes [OAuthErrorScreen].
     *
     * @private
     */
    private fun _startCountdown() {
        timeoutJob?.cancel()
        timeRemainingSeconds = (QR_TIMEOUT_MS / 1000).toInt()
        timeoutJob = scope.launch {
            while (timeRemainingSeconds > 0) {
                // Wait 30 seconds before next update to prevent UI flicker/IPC overhead
                delay(30_000L)
                timeRemainingSeconds -= 30
                invalidate()
            }
            pollJob?.cancel()
            _showError(isExpired = true)
        }
    }

    /**
     * Resets the authentication state and starts a new session with the same Homey ID.
     * Called when the user taps 'Refresh QR' after the QR code has expired.
     *
     * @private
     */
    private fun _refreshSession() {
        Log.d(TAG, "Refreshing QR session for homeyId=${targetHomeyId.take(6)}...")
        isAuthStarted = false
        _startAuthSession(targetHomeyId)
    }

    /**
     * Generates a cryptographically secure 64-character hex session ID (256-bit entropy).
     *
     * @private
     * @return A 64-character lowercase hex string.
     */
    private fun _generateSessionId(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ── Template Builders ─────────────────────────────────────────────────────

    /**
     * Builds a [SignInTemplate] with a loading QR placeholder while the companion app
     * session is being initialized.
     *
     * @private
     * @return [Template] showing a placeholder QR with a loading instruction.
     */
    private fun _buildLoadingTemplate(): Template {
        val companionName = carContext.getString(R.string.homey_companion_app_name)
        return SignInTemplate.Builder(QRCodeSignInMethod(Uri.parse("https://homey.app")))
            .setTitle(carContext.getString(R.string.oauth_sign_in_title))
            .setHeaderAction(if (isAddingHub) Action.BACK else Action.APP_ICON)
            .setInstructions(carContext.getString(R.string.oauth_sign_in_loading, companionName))
            .setLoading(true)
            .build()
    }

    /**
     * Builds a [SignInTemplate] with the real Athom authorization URL as a QR code.
     * Includes a visible countdown timer showing how much time is left.
     *
     * @private
     * @return [Template] showing the scannable authorization QR code.
     */
    private fun _buildQrTemplate(): Template {
        val minutes = timeRemainingSeconds / 60
        val seconds = timeRemainingSeconds % 60
        val timerText = String.format("%02d:%02d", minutes, seconds)

        return SignInTemplate.Builder(QRCodeSignInMethod(Uri.parse(authUrl)))
            .setTitle(carContext.getString(R.string.oauth_sign_in_title))
            .setHeaderAction(if (isAddingHub) Action.BACK else Action.APP_ICON)
            .setInstructions(carContext.getString(R.string.oauth_sign_in_instructions))
            .setAdditionalText(carContext.getString(R.string.oauth_sign_in_additional, targetHomeyId, timerText))
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.oauth_sign_in_back))
                    .setOnClickListener(ParkedOnlyOnClickListener.create { _retrySetup() })
                    .build()
            )
            .build()
    }

    /**
     * Builds a [SignInTemplate] shown while the token exchange is in progress.
     *
     * @private
     * @return [Template] showing the "Authorizing — please wait" message.
     */
    private fun _buildAuthorizingTemplate(): Template =
        SignInTemplate.Builder(QRCodeSignInMethod(Uri.parse(authUrl.ifBlank { "https://homey.app" })))
            .setTitle(carContext.getString(R.string.oauth_sign_in_title))
            .setHeaderAction(if (isAddingHub) Action.BACK else Action.APP_ICON)
            .setInstructions(carContext.getString(R.string.oauth_sign_in_exchanging))
            .build()



    /**
     * Navigates back to the Homey ID setup screen, pre-filling it with the current ID.
     * This allows the user to correct the entered Homey ID without restarting the entire flow.
     *
     * @private
     */
    private fun _retrySetup() {
        Log.d(TAG, "Navigating to HomeyIdSetupScreen for ID correction (isAddingHub=$isAddingHub).")
        isAuthStarted = false
        screenManager.pop()
        screenManager.push(
            HomeyIdSetupScreen(
                carContext = carContext,
                initialId = targetHomeyId,
                isAddingHub = isAddingHub
            )
        )
    }
}
