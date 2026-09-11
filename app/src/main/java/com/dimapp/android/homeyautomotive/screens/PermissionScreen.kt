package com.dimapp.android.homeyautomotive.screens

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.dimapp.android.homeyautomotive.R
import com.dimapp.android.homeyautomotive.core.DependencyManager
import com.dimapp.android.homeyautomotive.geofence.GeofenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Screen guiding the driver through granting necessary location and notification permissions.
 */
class PermissionScreen(carContext: CarContext) : Screen(carContext) {

    private val TAG = "PermissionScreen"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var step = if (GeofenceManager.hasFineLocationPermission(carContext) && !GeofenceManager.hasBackgroundLocationPermission(carContext)) 1 else 0
    private var messageText: String = if (step == 1) {
        carContext.getString(R.string.permission_step1_message)
    } else {
        carContext.getString(R.string.permission_step0_message)
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val builder = LongMessageTemplate.Builder(messageText)
            .setTitle(carContext.getString(R.string.permission_screen_title))
            .setHeaderAction(Action.BACK)

        if (step < 2) {
            val primaryActionTitle = if (step == 0) {
                carContext.getString(R.string.permission_btn_enable)
            } else {
                carContext.getString(R.string.permission_btn_allow_always)
            }

            builder.addAction(
                Action.Builder()
                    .setTitle(primaryActionTitle)
                    .setOnClickListener(ParkedOnlyOnClickListener.create { 
                        _requestPermissionsForCurrentStep() 
                    })
                    .build()
            )

            builder.addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.permission_btn_not_now))
                    .setOnClickListener(ParkedOnlyOnClickListener.create { 
                        if (step == 1) {
                            step = 2
                            messageText = carContext.getString(R.string.permission_foreground_only_message)
                            GeofenceManager.reregisterFromStorage(carContext)
                            invalidate()
                        } else {
                            screenManager.pop()
                        }
                    })
                    .build()
            )
        } else {
            builder.addAction(
                Action.Builder()
                    .setTitle(carContext.getString(android.R.string.ok))
                    .setOnClickListener(ParkedOnlyOnClickListener.create { 
                        screenManager.pop() 
                    })
                    .build()
            )
        }

        return builder.build()
    }

    // ── Private Methods ──────────────────────────────────────────────────────────────

    /**
     * Handles permission requests according to the active onboarding step.
     *
     * In step 0, requests ACCESS_FINE_LOCATION and POST_NOTIFICATIONS (Android 13+).
     * In step 1, requests ACCESS_BACKGROUND_LOCATION (Android 10+). If declined,
     * gracefully degrades to foreground-only geofencing with an explanatory message.
     *
     * @private
     */
    private fun _requestPermissionsForCurrentStep() {
        if (step == 0) {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }

            try {
                carContext.requestPermissions(perms) { granted, _ ->
                    if (granted.contains(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        _fetchCoordinatesAndRegisterGeofence()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            step = 1
                            messageText = carContext.getString(R.string.permission_step1_message)
                        } else {
                            step = 2
                            messageText = carContext.getString(R.string.permission_granted_message)
                            GeofenceManager.reregisterFromStorage(carContext)
                        }
                    } else {
                        messageText = carContext.getString(R.string.permission_denied_message)
                    }
                    invalidate()
                }
            } catch (e: Exception) {
                Log.e(TAG, "[PermissionScreen:_requestPermissionsForCurrentStep] Request error: ${e.message}", e)
                messageText = carContext.getString(R.string.permission_denied_message)
                invalidate()
            }
        } else if (step == 1) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    carContext.requestPermissions(listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) { granted, _ ->
                        step = 2
                        if (granted.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                            messageText = carContext.getString(R.string.permission_granted_message)
                        } else {
                            messageText = carContext.getString(R.string.permission_foreground_only_message)
                        }
                        GeofenceManager.reregisterFromStorage(carContext)
                        invalidate()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[PermissionScreen:_requestPermissionsForCurrentStep] Background location error: ${e.message}", e)
                    step = 2
                    messageText = carContext.getString(R.string.permission_foreground_only_message)
                    GeofenceManager.reregisterFromStorage(carContext)
                    invalidate()
                }
            } else {
                step = 2
                messageText = carContext.getString(R.string.permission_granted_message)
                GeofenceManager.reregisterFromStorage(carContext)
                invalidate()
            }
        }
    }

    /**
     * Immediately fetches coordinates from the companion app and registers the geofence.
     *
     * @private
     */
    private fun _fetchCoordinatesAndRegisterGeofence() {
        scope.launch {
            try {
                val authRepo = DependencyManager.getAuthRepository(carContext)
                val coords = authRepo.refreshHomeLocation()
                if (coords != null) {
                    Log.d(TAG, "[PermissionScreen:_fetchCoordinatesAndRegisterGeofence] Home coordinates retrieved: (lat=***, lng=***)")
                    GeofenceManager.reregisterFromStorage(carContext)
                } else {
                    Log.w(TAG, "[PermissionScreen:_fetchCoordinatesAndRegisterGeofence] Could not retrieve home coordinates.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[PermissionScreen:_fetchCoordinatesAndRegisterGeofence] Failed to refresh location: ${e.message}", e)
            }
        }
    }
}
