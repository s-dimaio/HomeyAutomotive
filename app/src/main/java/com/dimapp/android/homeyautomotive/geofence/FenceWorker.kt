package com.dimapp.android.homeyautomotive.geofence

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Background worker executing periodic verification and re-registration of active geofences.
 */
class FenceWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val TAG = "FenceWorker"

    override suspend fun doWork(): Result {
        Log.d(TAG, "[FenceWorker:doWork] Periodic fence worker triggered.")
        try {
            GeofenceManager.reregisterFromStorage(applicationContext)
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "[FenceWorker:doWork] Error refreshing geofence: ${e.message}", e)
            return Result.retry()
        }
    }
}
