/*
 * Copyright 2026 Aleksandrs Serbajevs
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Writes Plees Tracker's local sleeps without reading Health Connect in the background. */
class HealthConnectWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
            HealthConnectBackend.sdkStatus(context) != HealthConnectClient.SDK_AVAILABLE
        ) {
            return Result.success()
        }
        DataModel.init(context, PreferenceManager.getDefaultSharedPreferences(context))
        return try {
            HealthConnectBackend.write(context)
            Result.success()
        } catch (e: SecurityException) {
            Log.e(TAG, "doWork: Health Connect permission unavailable: $e")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "doWork: synchronization failed: $e")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "HealthConnectWorker"
    }
}

/* vim:set shiftwidth=4 softtabstop=4 expandtab: */
