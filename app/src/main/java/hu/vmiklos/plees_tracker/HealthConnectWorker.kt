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
import kotlinx.coroutines.CancellationException

/** Runs serialized Health Connect writes, reconciliations, and wipes. */
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
        return runOperation(operation, userInitiated) {
            HealthConnectBackend.perform(
                context,
                operation,
                forceReconcile = userInitiated
            )
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

    companion object {
        private const val TAG = "HealthConnectWorker"

        internal suspend fun runOperation(
            operation: HealthConnectBackend.Operation,
            userInitiated: Boolean,
            block: suspend () -> Unit
        ): Result = try {
            block()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Log.e(TAG, "runOperation: Health Connect permission unavailable: $e")
            if (userInitiated) userFailure() else Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "runOperation: $operation failed: $e")
            if (userInitiated) userFailure() else Result.retry()
        }

        // Keep a completed WorkInfo available so the settings screen can report the failure.
        private fun userFailure(): Result =
            Result.success(workDataOf(HealthConnectBackend.FAILED_KEY to true))
    }
}

/* vim:set shiftwidth=4 softtabstop=4 expandtab: */
