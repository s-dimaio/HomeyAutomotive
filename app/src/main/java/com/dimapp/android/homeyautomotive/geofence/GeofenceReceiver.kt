package com.dimapp.android.homeyautomotive.geofence

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.car.app.notification.CarAppExtender
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dimapp.android.homeyautomotive.R
import com.dimapp.android.homeyautomotive.core.DependencyManager
import com.dimapp.android.homeyautomotive.repository.models.DOOR_CLASSES
import com.dimapp.android.homeyautomotive.repository.models.HomeyDevice
import com.dimapp.android.homeyautomotive.repository.models.HomeyResult
import com.dimapp.android.homeyautomotive.storage.TokenStorage
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver triggered by Google Play Services when entering or exiting the home geofence.
 */
class GeofenceReceiver : BroadcastReceiver() {

    private val TAG = "GeofenceReceiver"

    companion object {
        const val CHANNEL_ID = "home_proximity_alerts"
        const val NOTIF_ID_DEPARTURE = 1001
        const val NOTIF_ID_ARRIVAL = 1002
        const val NOTIF_ID_CONFIRMATION = 1003
    }

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.e(TAG, "[GeofenceReceiver:onReceive] Geofencing error code: ${event.errorCode}")
            return
        }

        val transition = event.geofenceTransition
        val transitionName = when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT"
            else -> "UNKNOWN"
        }

        Log.d(TAG, "[GeofenceReceiver:onReceive] Geofence transition detected: $transitionName")

        val storage = DependencyManager.getTokenStorage(context)
        if (!storage.isGeofenceEnabled()) {
            Log.d(TAG, "[GeofenceReceiver:onReceive] Geofencing is disabled in user settings.")
            return
        }

        val timeStamp = java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
        ).format(java.util.Date())
        storage.setLastFenceEvent("$transitionName at $timeStamp")

        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER && transition != Geofence.GEOFENCE_TRANSITION_EXIT) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                _handleTransition(context, transition, storage)
            } catch (e: Exception) {
                Log.e(TAG, "[GeofenceReceiver:onReceive] Error during transition processing: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    // ── Private Methods ──────────────────────────────────────────────────────────────

    private suspend fun _handleTransition(
        context: Context,
        transition: Int,
        storage: TokenStorage
    ) {
        // Silently refresh geofence configuration to pick up any recent changes from Homey companion app
        try {
            val authRepo = DependencyManager.getAuthRepository(context)
            authRepo.refreshHomeLocation()
        } catch (e: Exception) {
            Log.w(TAG, "[GeofenceReceiver:_handleTransition] Could not refresh geofence config: ${e.message}")
        }

        val deviceRepo = DependencyManager.getDeviceRepository(context)
        val result = deviceRepo.getDevices(forceRefresh = true)

        if (result !is HomeyResult.Success) {
            Log.w(TAG, "[GeofenceReceiver:_handleTransition] Could not retrieve devices from Homey.")
            return
        }

        val allowedBarrierIds = storage.getGeofenceDeviceIds()
        if (allowedBarrierIds.isEmpty()) {
            Log.d(TAG, "[GeofenceReceiver:_handleTransition] No barrier devices configured in settings. Skipping notification.")
            return
        }

        val barrierDevices = result.data.filter { device ->
            val clazz = device.deviceClass?.lowercase()
            clazz in DOOR_CLASSES && !device.isLight && !device.isHidden && device.isAvailable && device.id in allowedBarrierIds
        }

        if (barrierDevices.isEmpty()) {
            Log.d(TAG, "[GeofenceReceiver:_handleTransition] No matching active barrier devices found from configured IDs.")
            return
        }

        val nm = NotificationManagerCompat.from(context)
        _ensureNotificationChannel(context, nm)

        if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            _handleDeparture(context, nm, barrierDevices, storage)
        } else if (transition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            _handleArrival(context, nm, barrierDevices)
        }
    }

    private suspend fun _handleDeparture(
        context: Context,
        nm: NotificationManagerCompat,
        barriers: List<HomeyDevice>,
        storage: TokenStorage
    ) {
        nm.cancel(NOTIF_ID_ARRIVAL)

        // For locks and garagedoors, isActive == true means OPEN or UNLOCKED
        val openBarriers = barriers.filter { it.isActive }
        if (openBarriers.isEmpty()) {
            Log.d(TAG, "[GeofenceReceiver:_handleDeparture] All barrier devices are closed/locked.")
            return
        }

        Log.d(TAG, "[GeofenceReceiver:_handleDeparture] Found ${openBarriers.size} open barriers.")

        val deviceRepo = DependencyManager.getDeviceRepository(context)

        // Check if auto-close on departure is enabled
        if (storage.isGeofenceAutoCloseEnabled()) {
            val closedNames = mutableListOf<String>()
            for (device in openBarriers) {
                val toggleResult = deviceRepo.toggleDeviceState(device.id, device.primaryCapability, makeActive = false)
                if (toggleResult is HomeyResult.Success) {
                    closedNames.add(device.name)
                }
            }

            if (closedNames.isNotEmpty()) {
                val summaryText = context.getString(R.string.geofence_notif_auto_closed, closedNames.joinToString(", "))
                _postSimpleNotification(
                    context, nm, NOTIF_ID_CONFIRMATION,
                    context.getString(R.string.geofence_notif_departure_title),
                    summaryText
                )
            }
            return
        }

        // Manual prompt notification
        val title = context.getString(R.string.geofence_notif_departure_title)
        val text = if (openBarriers.size == 1) {
            context.getString(R.string.geofence_notif_departure_single, openBarriers[0].name)
        } else {
            context.getString(R.string.geofence_notif_departure_multi, openBarriers.size)
        }

        val builder = _createNotificationBuilder(context, title, text, NOTIF_ID_DEPARTURE)

        // Dedicated action button per device (up to 3 for AAOS notification limit)
        val visibleTargets = openBarriers.take(3)
        visibleTargets.forEachIndexed { index, device ->
            val actionLabel = if (device.primaryCapability == "locked") {
                context.getString(R.string.geofence_action_lock, device.name)
            } else {
                context.getString(R.string.geofence_action_close, device.name)
            }
            builder.addAction(
                R.drawable.ic_tab_lock,
                actionLabel,
                _createActionPendingIntent(context, "close", device.id, device.primaryCapability, device.name, NOTIF_ID_DEPARTURE, index)
            )
        }

        try {
            nm.notify(NOTIF_ID_DEPARTURE, builder.build())
            Log.d(TAG, "[GeofenceReceiver:_handleDeparture] Departure notification posted with ${visibleTargets.size} actions.")
        } catch (e: SecurityException) {
            Log.w(TAG, "[GeofenceReceiver:_handleDeparture] Notification permission not granted: ${e.message}")
        }
    }

    private fun _handleArrival(
        context: Context,
        nm: NotificationManagerCompat,
        barriers: List<HomeyDevice>
    ) {
        nm.cancel(NOTIF_ID_DEPARTURE)

        // For locks and garagedoors, isActive == false means CLOSED or LOCKED
        val closedBarriers = barriers.filter { !it.isActive }
        if (closedBarriers.isEmpty()) {
            Log.d(TAG, "[GeofenceReceiver:_handleArrival] All barrier devices are already open/unlocked.")
            return
        }

        Log.d(TAG, "[GeofenceReceiver:_handleArrival] Found ${closedBarriers.size} closed barriers.")

        val title = context.getString(R.string.geofence_notif_arrival_title)
        val text = if (closedBarriers.size == 1) {
            context.getString(R.string.geofence_notif_arrival_single, closedBarriers[0].name)
        } else {
            context.getString(R.string.geofence_notif_arrival_multi, closedBarriers.size)
        }

        val builder = _createNotificationBuilder(context, title, text, NOTIF_ID_ARRIVAL)

        // Dedicated action button per device (up to 3 for AAOS notification limit)
        val visibleTargets = closedBarriers.take(3)
        visibleTargets.forEachIndexed { index, device ->
            val actionLabel = if (device.primaryCapability == "locked") {
                context.getString(R.string.geofence_action_unlock, device.name)
            } else {
                context.getString(R.string.geofence_action_open, device.name)
            }
            builder.addAction(
                R.drawable.ic_tab_lock,
                actionLabel,
                _createActionPendingIntent(context, "open", device.id, device.primaryCapability, device.name, NOTIF_ID_ARRIVAL, index)
            )
        }

        try {
            nm.notify(NOTIF_ID_ARRIVAL, builder.build())
            Log.d(TAG, "[GeofenceReceiver:_handleArrival] Arrival notification posted with ${visibleTargets.size} actions.")
        } catch (e: SecurityException) {
            Log.w(TAG, "[GeofenceReceiver:_handleArrival] Notification permission not granted: ${e.message}")
        }
    }

    private fun _createNotificationBuilder(
        context: Context,
        title: String,
        text: String,
        notifId: Int
    ): NotificationCompat.Builder {
        val contentIntent = PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, androidx.car.app.activity.CarAppActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setTimeoutAfter(20 * 60_000L) // 20 minutes
            .extend(
                CarAppExtender.Builder()
                    .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                    .build()
            )
    }

    private fun _postSimpleNotification(
        context: Context,
        nm: NotificationManagerCompat,
        notifId: Int,
        title: String,
        text: String
    ) {
        val builder = _createNotificationBuilder(context, title, text, notifId)
            .setTimeoutAfter(15_000L) // Auto dismiss after 15 seconds
        try {
            nm.notify(notifId, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "[GeofenceReceiver:_postSimpleNotification] Notification permission missing: ${e.message}")
        }
    }

    private fun _createActionPendingIntent(
        context: Context,
        action: String,
        deviceIds: String,
        capabilityIds: String,
        deviceName: String,
        notifId: Int,
        requestCodeIndex: Int
    ): PendingIntent {
        val intent = Intent(context, NotifActionReceiver::class.java).apply {
            putExtra("op", action)
            putExtra("deviceIds", deviceIds)
            putExtra("capabilityIds", capabilityIds)
            putExtra("deviceName", deviceName)
            putExtra("notifId", notifId)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val uniqueRequestCode = notifId * 100 + requestCodeIndex
        return PendingIntent.getBroadcast(context, uniqueRequestCode, intent, flags)
    }

    private fun _ensureNotificationChannel(context: Context, nm: NotificationManagerCompat) {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(context.getString(R.string.geofence_channel_name))
            .setDescription(context.getString(R.string.geofence_channel_desc))
            .build()
        nm.createNotificationChannel(channel)
    }
}
