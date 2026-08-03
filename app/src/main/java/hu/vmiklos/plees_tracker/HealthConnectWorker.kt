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
import androidx.work.workDataOf

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
        val operation = operation() ?: return Result.success()
        val userInitiated = inputData.getBoolean(HealthConnectBackend.USER_INITIATED_KEY, false)
        DataModel.init(context, PreferenceManager.getDefaultSharedPreferences(context))
        return try {
            HealthConnectBackend.perform(context, operation)
            Result.success()
        } catch (e: SecurityException) {
            Log.e(TAG, "doWork: Health Connect permission unavailable: $e")
            errorResult(userInitiated)
        } catch (e: Exception) {
            Log.e(TAG, "doWork: $operation failed: $e")
            if (userInitiated) errorResult(true) else Result.retry()
        }
    }

    private fun operation(): HealthConnectBackend.Operation? {
        val name = inputData.getString(HealthConnectBackend.OPERATION_KEY) ?: return null
        return try {
            HealthConnectBackend.Operation.valueOf(name)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "operation: unknown operation '$name': $e")
            null
        }
    }

    private fun errorResult(userInitiated: Boolean): Result =
        if (userInitiated) {
            Result.success(workDataOf(HealthConnectBackend.FAILED_KEY to true))
        } else {
            Result.success()
        }

    companion object {
        private const val TAG = "HealthConnectWorker"
    }
}

/* vim:set shiftwidth=4 softtabstop=4 expandtab: */
