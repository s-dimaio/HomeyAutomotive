package com.dimapp.android.homeyautomotive.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver restoring geofence perimeters after vehicle reboot or app upgrade.
 */
class BootReceiver : BroadcastReceiver() {

    private val TAG = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "[BootReceiver:onReceive] Received broadcast action: $action")
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                GeofenceManager.reregisterFromStorage(context)
            }
        }
    }
}
