package com.dimapp.android.homeyautomotive.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.car.app.notification.CarAppExtender
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dimapp.android.homeyautomotive.R
import com.dimapp.android.homeyautomotive.core.DependencyManager
import com.dimapp.android.homeyautomotive.repository.models.HomeyResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver executing actions triggered from in-car heads-up notification buttons.
 */
class NotifActionReceiver : BroadcastReceiver() {

    private val TAG = "NotifActionReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val op = intent.getStringExtra("op") ?: return // "open" or "close"
        val deviceIds = intent.getStringExtra("deviceIds") ?: return
        val capabilityIds = intent.getStringExtra("capabilityIds") ?: return
        val deviceName = intent.getStringExtra("deviceName") ?: "Barrier"
        val notifId = intent.getIntExtra("notifId", 0)

        Log.d(TAG, "[NotifActionReceiver:onReceive] Action requested: op=$op, devices=$deviceIds")

        val nm = NotificationManagerCompat.from(context)
        if (notifId != 0) {
            nm.cancel(notifId)
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ids = deviceIds.split(",").map { it.trim() }.filter { it.isNotBlank() }
                val caps = capabilityIds.split(",").map { it.trim() }.filter { it.isNotBlank() }

                val deviceRepo = DependencyManager.getDeviceRepository(context)
                val makeActive = (op == "open")

                var allSuccessful = true
                for (i in ids.indices) {
                    val id = ids[i]
                    val cap = if (i < caps.size) caps[i] else "garagedoor_closed"
                    val result = deviceRepo.toggleDeviceState(id, cap, makeActive)
                    if (result !is HomeyResult.Success) {
                        allSuccessful = false
                        Log.e(TAG, "[NotifActionReceiver:onReceive] Failed to toggle device $id: $result")
                    }
                }

                val confirmationText = if (allSuccessful) {
                    context.getString(R.string.geofence_action_done, deviceName)
                } else {
                    context.getString(R.string.geofence_action_failed, deviceName)
                }

                _postFeedbackNotification(context, nm, confirmationText)
            } catch (e: Exception) {
                Log.e(TAG, "[NotifActionReceiver:onReceive] Error executing notification action: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    // ── Private Methods ──────────────────────────────────────────────────────────────

    private fun _postFeedbackNotification(
        context: Context,
        nm: NotificationManagerCompat,
        message: String
    ) {
        try {
            val builder = NotificationCompat.Builder(context, GeofenceReceiver.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tab_lock)
                .setContentTitle(message)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setTimeoutAfter(12_000L) // Auto-dismiss after 12 seconds
                .extend(
                    CarAppExtender.Builder()
                        .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                        .build()
                )

            nm.notify(GeofenceReceiver.NOTIF_ID_CONFIRMATION, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "[NotifActionReceiver:_postFeedbackNotification] Missing notification permission: ${e.message}")
        }
    }
}
