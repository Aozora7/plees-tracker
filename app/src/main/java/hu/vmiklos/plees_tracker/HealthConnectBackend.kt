/*
 * Copyright 2026 aozora.one
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import android.content.Context
import android.content.Intent
import android.health.connect.HealthConnectManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.RequiresApi
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.preference.PreferenceManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Instant
import java.util.UUID
import androidx.core.net.toUri

/** Health Connect availability, permission, scheduling, and record synchronization. */
object HealthConnectBackend {
    const val ENABLED_KEY = "health_connect_enabled"
    const val INITIALIZED_KEY = "health_connect_initialized"
    const val PERMISSION_REQUESTED_KEY = "health_connect_permission_requested"

    internal const val CLIENT_ID_PREFIX = "plees-sleep:"
    private const val PROVIDER_PACKAGE_NAME = "com.google.android.apps.healthdata"
    private const val WORK_NAME = "health_connect_sync"
    private const val PAGE_SIZE = 1000
    private const val TAG = "HealthConnectBackend"

    enum class Availability {
        UNAVAILABLE,
        UPDATE_REQUIRED,
        AVAILABLE
    }

    fun sdkStatus(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return HealthConnectClient.SDK_UNAVAILABLE
        }
        return HealthConnectClient.getSdkStatus(context)
    }

    fun availability(context: Context): Availability = when (sdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
            Availability.UPDATE_REQUIRED
        else -> Availability.UNAVAILABLE
    }

    fun isEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(ENABLED_KEY, false)

    fun scheduleSync(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !isEnabled(context)) {
            return
        }
        val request = OneTimeWorkRequestBuilder<HealthConnectWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancelSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun providerUpdateIntent(): Intent = Intent(Intent.ACTION_VIEW).apply {
        data = "market://details?id=$PROVIDER_PACKAGE_NAME".toUri()
        setPackage("com.android.vending")
    }

    fun permissionSettingsIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return Intent(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS).apply {
                putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
            }
        }
        return Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    @RequiresApi(Build.VERSION_CODES.P)
    suspend fun hasWritePermission(context: Context): Boolean {
        if (sdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return false
        }
        val client = HealthConnectClient.getOrCreate(context)
        return client.permissionController.getGrantedPermissions().contains(writePermission())
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun requestedPermissions(): Set<String> = setOf(writePermission())

    @RequiresApi(Build.VERSION_CODES.P)
    suspend fun previousSleeps(context: Context): List<Sleep> {
        val records = readOwnRecords(context)
        return records.mapNotNull { record ->
            val clientId = record.metadata.clientRecordId ?: return@mapNotNull null
            if (!clientId.startsWith(CLIENT_ID_PREFIX)) {
                return@mapNotNull null
            }
            val rawId = clientId.removePrefix(CLIENT_ID_PREFIX)
            try {
                UUID.fromString(rawId)
            } catch (_: IllegalArgumentException) {
                return@mapNotNull null
            }
            val values = HealthConnectNotes.decode(record.notes)
            Sleep().apply {
                start = record.startTime.toEpochMilli()
                stop = record.endTime.toEpochMilli()
                comment = values.comment
                rating = values.rating
                wakes = values.wakes
                healthConnectId = rawId
                healthConnectVersion = record.metadata.clientRecordVersion
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    suspend fun sync(context: Context) {
        if (!isEnabled(context)) {
            return
        }
        val client = HealthConnectClient.getOrCreate(context)
        sync(client, context.packageName)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    internal suspend fun sync(client: HealthConnectClient, packageName: String) {
        val healthDao = DataModel.database.healthConnectDao()
        val sleeps = DataModel.database.sleepDao().getAll().filter { sleep ->
            if (sleep.stop <= sleep.start) {
                Log.w(TAG, "Skipping non-positive sleep ${sleep.healthConnectId}")
                false
            } else {
                true
            }
        }
        // Persist current local state before reconciliation. The APK provider has a much tighter
        // read quota than its write quota; if the following read is temporarily rejected, stable
        // client IDs and versions still make these upserts safe and the worker retries cleanup.
        for (chunk in sleeps.map(::toRecord).chunked(PAGE_SIZE)) {
            client.insertRecords(chunk)
        }

        val ownRecords = readOwnRecords(client, packageName)
            .filter { it.metadata.clientRecordId?.startsWith(CLIENT_ID_PREFIX) == true }
        val remoteByClientId = ownRecords.associateBy { it.metadata.clientRecordId }

        val tombstoneIds = healthDao.getDeletions().map { it.healthConnectId }
        for (chunk in tombstoneIds.chunked(PAGE_SIZE)) {
            val clientIds = chunk.map { CLIENT_ID_PREFIX + it }
            val recordIds = clientIds.mapNotNull { remoteByClientId[it]?.metadata?.id }
            if (recordIds.isNotEmpty()) {
                client.deleteRecords(
                    SleepSessionRecord::class,
                    recordIdsList = recordIds,
                    clientRecordIdsList = emptyList()
                )
            }
            healthDao.deleteDeletionsBatched(chunk)
        }

        val recordsToRewrite = mutableListOf<SleepSessionRecord>()
        for (sleep in sleeps) {
            val clientId = CLIENT_ID_PREFIX + sleep.healthConnectId
            val remote = remoteByClientId[clientId]
            if (remote != null && recordMatches(sleep, remote)) {
                continue
            }
            if (
                remote != null &&
                remote.metadata.clientRecordVersion >= sleep.healthConnectVersion
            ) {
                sleep.healthConnectVersion = remote.metadata.clientRecordVersion + 1
                healthDao.updateVersion(sleep.healthConnectId, sleep.healthConnectVersion)
                recordsToRewrite.add(toRecord(sleep))
            }
        }
        for (chunk in recordsToRewrite.chunked(PAGE_SIZE)) {
            client.insertRecords(chunk)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private suspend fun readOwnRecords(context: Context): List<SleepSessionRecord> =
        readOwnRecords(HealthConnectClient.getOrCreate(context), context.packageName)

    @RequiresApi(Build.VERSION_CODES.P)
    internal suspend fun readOwnRecords(
        client: HealthConnectClient,
        packageName: String
    ): List<SleepSessionRecord> {
        val records = mutableListOf<SleepSessionRecord>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest<SleepSessionRecord>(
                    timeRangeFilter = TimeRangeFilter.after(Instant.EPOCH),
                    dataOriginFilter = setOf(DataOrigin(packageName)),
                    pageSize = PAGE_SIZE,
                    pageToken = pageToken
                )
            )
            records.addAll(response.records)
            pageToken = response.pageToken
        } while (pageToken != null)
        return records
    }

    @RequiresApi(Build.VERSION_CODES.P)
    internal fun toRecord(sleep: Sleep): SleepSessionRecord {
        val start = Instant.ofEpochMilli(sleep.start)
        val stop = Instant.ofEpochMilli(sleep.stop)
        val device = Device(
            type = Device.TYPE_PHONE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL
        )
        return SleepSessionRecord(
            startTime = start,
            startZoneOffset = null,
            endTime = stop,
            endZoneOffset = null,
            metadata = Metadata.activelyRecorded(
                device = device,
                clientRecordId = CLIENT_ID_PREFIX + sleep.healthConnectId,
                clientRecordVersion = sleep.healthConnectVersion
            ),
            notes = HealthConnectNotes.encode(sleep),
            stages = listOf(
                SleepSessionRecord.Stage(
                    startTime = start,
                    endTime = stop,
                    stage = SleepSessionRecord.STAGE_TYPE_SLEEPING
                )
            )
        )
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun recordMatches(sleep: Sleep, record: SleepSessionRecord): Boolean =
        record.startTime.toEpochMilli() == sleep.start &&
            record.endTime.toEpochMilli() == sleep.stop &&
            record.notes == HealthConnectNotes.encode(sleep) &&
            record.stages.size == 1 &&
            record.stages[0].startTime == record.startTime &&
            record.stages[0].endTime == record.endTime &&
            record.stages[0].stage == SleepSessionRecord.STAGE_TYPE_SLEEPING

    @RequiresApi(Build.VERSION_CODES.P)
    private fun writePermission(): String =
        HealthPermission.getWritePermission(SleepSessionRecord::class)
}

/* vim:set shiftwidth=4 softtabstop=4 expandtab: */
