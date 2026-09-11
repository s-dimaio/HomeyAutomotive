package com.dimapp.android.homeyautomotive.geofence

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.dimapp.android.homeyautomotive.core.DependencyManager
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import java.util.concurrent.TimeUnit

/**
 * Manages Google Play Services Geofence registration and lifecycle.
 */
object GeofenceManager {

    private const val TAG = "GeofenceManager"
    private const val FENCE_REQUEST_CODE = 2001
    private const val FENCE_WORK_NAME = "homey_fence_refresh"
    private const val FENCE_ID_HOME = "home_geofence"
    const val DEFAULT_RADIUS_METERS = 300f

    /**
     * Verifies if fine location permission is granted (sufficient for foreground geofence).
     *
     * @param context Application context.
     * @return True if fine location is granted.
     */
    fun hasFineLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Verifies if background location permission is granted.
     *
     * @param context Application context.
     * @return True if background location is granted (or not required on API < 29).
     */
    fun hasBackgroundLocationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Verifies if both fine location and background location permissions are granted.
     *
     * @param context Application context.
     * @return True if all location permissions are granted.
     */
    fun hasLocationPermissions(context: Context): Boolean {
        return hasFineLocationPermission(context) && hasBackgroundLocationPermission(context)
    }

    /**
     * Verifies if notification permission is granted.
     *
     * @param context Application context.
     * @return True if notification permission is granted.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Registers a circular geofence around the home coordinates.
     *
     * @param context Application context.
     * @param lat Home latitude.
     * @param lng Home longitude.
     * @param radius Geofence radius in meters.
     */
    @SuppressLint("MissingPermission")
    fun registerGeofence(context: Context, lat: Double, lng: Double, radius: Float = DEFAULT_RADIUS_METERS) {
        if (!hasFineLocationPermission(context)) {
            Log.w(TAG, "[GeofenceManager:registerGeofence] Missing fine location permission. Registration skipped.")
            return
        }

        if (!hasBackgroundLocationPermission(context)) {
            Log.i(TAG, "[GeofenceManager:registerGeofence] Background location not granted; geofence will operate when app is displayed.")
        }

        Log.d(TAG, "[GeofenceManager:registerGeofence] Registering geofence: id=$FENCE_ID_HOME, radius=$radius (coordinates masked)")

        val geofence = Geofence.Builder()
            .setRequestId(FENCE_ID_HOME)
            .setCircularRegion(lat, lng, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .setNotificationResponsiveness(30_000) // 30 seconds responsiveness to preserve vehicle battery
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0) // Do not trigger upon immediate registration
            .addGeofence(geofence)
            .build()

        val pendingIntent = _getGeofencePendingIntent(context)

        LocationServices.getGeofencingClient(context)
            .addGeofences(request, pendingIntent)
            .addOnSuccessListener {
                Log.d(TAG, "[GeofenceManager:registerGeofence] Geofence registered successfully.")
                schedulePeriodicRefresh(context)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "[GeofenceManager:registerGeofence] Failed to register geofence: ${e.message}", e)
            }
    }

    /**
     * Removes active geofences from Google Play Services.
     *
     * @param context Application context.
     */
    fun removeGeofence(context: Context) {
        Log.d(TAG, "[GeofenceManager:removeGeofence] Removing active geofences.")
        val pendingIntent = _getGeofencePendingIntent(context)
        LocationServices.getGeofencingClient(context)
            .removeGeofences(pendingIntent)
            .addOnSuccessListener {
                Log.d(TAG, "[GeofenceManager:removeGeofence] Geofences removed successfully.")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "[GeofenceManager:removeGeofence] Failed to remove geofences: ${e.message}", e)
            }
    }

    /**
     * Re-registers the geofence from stored preferences if enabled.
     *
     * @param context Application context.
     */
    fun reregisterFromStorage(context: Context) {
        val storage = DependencyManager.getTokenStorage(context)
        if (!storage.isGeofenceEnabled()) {
            Log.d(TAG, "[GeofenceManager:reregisterFromStorage] Geofencing disabled in preferences. Removing any active geofence.")
            removeGeofence(context)
            return
        }

        val coords = storage.getHomeLocation()
        if (coords == null) {
            Log.d(TAG, "[GeofenceManager:reregisterFromStorage] No home coordinates saved. Removing any active geofence.")
            removeGeofence(context)
            return
        }

        val radius = storage.getGeofenceRadius()
        registerGeofence(context, coords.first, coords.second, radius)
    }

    /**
     * Schedules periodic verification via WorkManager every 6 hours.
     *
     * @param context Application context.
     */
    fun schedulePeriodicRefresh(context: Context) {
        val periodicRequest = PeriodicWorkRequestBuilder<FenceWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            FENCE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )
        Log.d(TAG, "[GeofenceManager:schedulePeriodicRefresh] Periodic fence verification scheduled.")
    }

    // ── Private Methods ──────────────────────────────────────────────────────────────

    private fun _getGeofencePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, FENCE_REQUEST_CODE, intent, flags)
    }
}
