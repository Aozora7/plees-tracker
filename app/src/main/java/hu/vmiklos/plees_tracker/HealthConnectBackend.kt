/*
 * Copyright 2026 Aleksandrs Serbajevs
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.health.connect.HealthConnectManager
import android.os.Build
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.RequiresApi
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** Health Connect availability, permission, scheduling, and record synchronization. */
object HealthConnectBackend {
    const val ENABLED_KEY = "health_connect_enabled"
    const val INITIALIZED_KEY = "health_connect_initialized"
    const val PERMISSION_REQUESTED_KEY = "health_connect_permission_requested"

    internal const val CHECK_PENDING_KEY = "health_connect_check_pending"
    internal const val CLIENT_ID_PREFIX = "plees-sleep:"
    internal const val LOCAL_PREFERENCES_NAME = "health_connect_local"
    internal const val READ_CUTOFF_KEY = "health_connect_read_cutoff"
    internal const val HEALTH_CONNECT_BATCH_SIZE = 1000
    internal const val OPERATION_KEY = "operation"
    internal const val USER_INITIATED_KEY = "user_initiated"
    internal const val FAILED_KEY = "failed"
    private const val WORK_NAME = "health_connect_sync"
    private const val LEGACY_READ_MILLIS = 30L * 24 * 60 * 60 * 1000
    private const val TAG = "HealthConnectBackend"

    enum class Availability {
        UNAVAILABLE,
        UPDATE_REQUIRED,
        AVAILABLE
    }

    internal enum class Operation {
        WRITE,
        RECONCILE,
        WIPE
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

    internal fun localPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(LOCAL_PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        localPreferences(context).getBoolean(ENABLED_KEY, false)

    internal fun setEnabled(context: Context, enabled: Boolean) {
        localPreferences(context).edit()
            .putBoolean(ENABLED_KEY, enabled)
            .apply()
    }

    private fun enqueue(
        context: Context,
        operation: Operation,
        userInitiated: Boolean,
        policy: ExistingWorkPolicy
    ): UUID {
        val request = OneTimeWorkRequestBuilder<HealthConnectWorker>()
            .setInputData(
                workDataOf(
                    OPERATION_KEY to operation.name,
                    USER_INITIATED_KEY to userInitiated
                )
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
        return request.id
    }

    fun scheduleSync(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !isEnabled(context)) {
            return
        }
        // Preserve edit order; a failed or cancelled chain starts fresh with this request.
        enqueue(
            context,
            Operation.WRITE,
            userInitiated = false,
            ExistingWorkPolicy.APPEND_OR_REPLACE
        )
    }

    fun scheduleReconcile(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !isEnabled(context)) {
            return
        }
        // Check for pending work when this request runs, after earlier queued writes finish.
        enqueue(
            context,
            Operation.RECONCILE,
            userInitiated = false,
            ExistingWorkPolicy.APPEND_OR_REPLACE
        )
    }

    fun forceReconcile(context: Context): UUID? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !isEnabled(context)) {
            return null
        }
        // A force sync supersedes queued writes because reconciliation uploads local changes too.
        return enqueue(
            context,
            Operation.RECONCILE,
            userInitiated = true,
            ExistingWorkPolicy.REPLACE
        )
    }

    fun scheduleWipe(context: Context): UUID? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }
        // Wiping supersedes every queued operation and must not be followed by an older write.
        return enqueue(context, Operation.WIPE, userInitiated = true, ExistingWorkPolicy.REPLACE)
    }

    internal suspend fun awaitSuccess(context: Context, id: UUID): Boolean? {
        val info = WorkManager.getInstance(context)
            .getWorkInfoByIdFlow(id)
            .filterNotNull()
            .first { it.state.isFinished }
        if (info.state != WorkInfo.State.SUCCEEDED) {
            return null
        }
        return !info.outputData.getBoolean(FAILED_KEY, false)
    }

    fun cancelSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
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

    /**
     * Whether range queries can expose the complete history of records owned by this app.
     *
     * Android 14's original Health Connect module still applied the permission-grant cutoff to
     * owned records. The current Android 14+ contract removes that cutoff, but AndroidX reports
     * the required module support only when FEATURE_READ_HEALTH_DATA_HISTORY is available (SDK
     * extension 13+ on Android 14). On Android 13 and older, feature availability means the
     * separate history permission can be requested; WRITE_SLEEP alone remains cutoff-limited.
     */
    internal fun canReadAllHistory(
        sdkInt: Int,
        historyFeatureStatus: Int
    ): Boolean =
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            historyFeatureStatus == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    internal fun canReadAllHistory(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
            sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            sdkStatus(context) != HealthConnectClient.SDK_AVAILABLE
        ) {
            return false
        }
        val client = HealthConnectClient.getOrCreate(context)
        return canReadAllHistory(
            sdkInt,
            client.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY
            )
        )
    }

    internal fun recordPermissionGrant(
        context: Context,
        grantedAtMillis: Long = System.currentTimeMillis(),
        sdkInt: Int = Build.VERSION.SDK_INT
    ) {
        if (canReadAllHistory(context, sdkInt)) {
            return
        }
        val preferences = localPreferences(context)
        if (!preferences.contains(READ_CUTOFF_KEY)) {
            preferences.edit()
                .putLong(READ_CUTOFF_KEY, grantedAtMillis - LEGACY_READ_MILLIS)
                .apply()
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    internal fun readablePeriodStart(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Instant {
        if (canReadAllHistory(context, sdkInt)) {
            return Instant.EPOCH
        }
        recordPermissionGrant(context, nowMillis, sdkInt)
        val cutoff = localPreferences(context)
            .getLong(READ_CUTOFF_KEY, nowMillis - LEGACY_READ_MILLIS)
        return Instant.ofEpochMilli(cutoff)
    }

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
    internal suspend fun perform(
        context: Context,
        operation: Operation,
        forceReconcile: Boolean = false
    ) {
        if (operation != Operation.WIPE && !isEnabled(context)) {
            return
        }
        when (operation) {
            Operation.WRITE -> write(HealthConnectClient.getOrCreate(context))
            Operation.RECONCILE -> {
                val readablePeriodStart = readablePeriodStart(context)
                if (!forceReconcile && !hasForegroundWork(readablePeriodStart)) {
                    return
                }
                sync(
                    HealthConnectClient.getOrCreate(context),
                    context.packageName,
                    readablePeriodStart
                )
            }
            Operation.WIPE -> {
                check(canReadAllHistory(context)) {
                    "Health Connect cannot expose all owned records on this system module"
                }
                wipe(HealthConnectClient.getOrCreate(context), context.packageName)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    internal suspend fun hasForegroundWork(readablePeriodStart: Instant): Boolean {
        val after = readablePeriodStart.toEpochMilli()
        return DataModel.database.sleepDao().hasPendingHealthConnectWrites(after) ||
            DataModel.database.healthConnectDao().hasDeletions(after)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    internal suspend fun wipe(client: HealthConnectClient, packageName: String) {
        val ownRecords = readOwnRecords(client, packageName)
        for (chunk in ownRecords.chunked(HEALTH_CONNECT_BATCH_SIZE)) {
            client.deleteRecords(
                SleepSessionRecord::class,
                recordIdsList = chunk.map { it.metadata.id },
                clientRecordIdsList = emptyList()
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    internal suspend fun sync(client: HealthConnectClient, packageName: String) {
        sync(client, packageName, Instant.EPOCH)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    internal suspend fun sync(
        client: HealthConnectClient,
        packageName: String,
        readablePeriodStart: Instant
    ) {
        val ownRecords = readOwnRecords(client, packageName, readablePeriodStart)
            .filter { it.metadata.clientRecordId?.startsWith(CLIENT_ID_PREFIX) == true }
        val remoteByClientId = ownRecords.associateBy { it.metadata.clientRecordId!! }
        deletePending(
            client,
            remoteByClientId,
            readablePeriodStart
        )

        val sleeps = localSleeps(readablePeriodStart)
        val healthDao = DataModel.database.healthConnectDao()
        val sleepsToWrite = mutableListOf<Sleep>()
        for (sleep in sleeps) {
            val clientId = CLIENT_ID_PREFIX + sleep.healthConnectId
            val remote = remoteByClientId[clientId]
            if (remote != null && recordMatches(sleep, remote)) {
                // Keep the local version at least as high as the provider's. Otherwise the next
                // local edit could be ignored as an older upsert even though this content matches.
                if (remote.metadata.clientRecordVersion > sleep.healthConnectVersion) {
                    val oldVersion = sleep.healthConnectVersion
                    val newVersion = remote.metadata.clientRecordVersion
                    if (
                        healthDao.updateVersionIfCurrent(
                            sleep.healthConnectId,
                            oldVersion,
                            newVersion
                        ) == 0
                    ) {
                        // A local edit won the race; leave its newer contents pending.
                        continue
                    }
                    sleep.healthConnectVersion = newVersion
                }
                if (sleep.healthConnectSyncedVersion != sleep.healthConnectVersion) {
                    healthDao.markVersionSynced(
                        sleep.healthConnectId,
                        sleep.healthConnectVersion
                    )
                }
                continue
            }
            if (
                remote != null &&
                remote.metadata.clientRecordVersion >= sleep.healthConnectVersion
            ) {
                val oldVersion = sleep.healthConnectVersion
                val newVersion = remote.metadata.clientRecordVersion + 1
                if (
                    healthDao.updateVersionIfCurrent(
                        sleep.healthConnectId,
                        oldVersion,
                        newVersion
                    ) == 0
                ) {
                    // Do not upload the stale snapshot if the user edited it during reconciliation.
                    continue
                }
                sleep.healthConnectVersion = newVersion
            }
            // The record is missing, older, or has different contents. Write only this sleep;
            // stable client IDs and versions make retrying the unfinished subset safe.
            sleepsToWrite.add(sleep)
        }
        write(client, sleepsToWrite)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    internal suspend fun write(client: HealthConnectClient) {
        val pending = validSleeps(DataModel.database.sleepDao().getPendingHealthConnectWrites())
        write(client, pending)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    internal suspend fun write(client: HealthConnectClient, sleeps: List<Sleep>) {
        val healthDao = DataModel.database.healthConnectDao()
        val writableSleeps = sleeps.filter { sleep ->
            if (sleep.healthConnectId.isNotEmpty()) {
                true
            } else {
                val id = UUID.randomUUID().toString()
                if (healthDao.assignId(sleep.sid, id) == 1) {
                    sleep.healthConnectId = id
                    true
                } else {
                    false
                }
            }
        }
        for (chunk in writableSleeps.chunked(HEALTH_CONNECT_BATCH_SIZE)) {
            client.insertRecords(chunk.map(::toRecord))
            // Persist progress after every successful provider batch. The version predicate in
            // markVersionSynced prevents a concurrent edit from being marked as uploaded.
            for (sleep in chunk) {
                healthDao.markVersionSynced(sleep.healthConnectId, sleep.healthConnectVersion)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private suspend fun localSleeps(readablePeriodStart: Instant): List<Sleep> =
        validSleeps(DataModel.database.sleepDao().getAll()).filter { sleep ->
            sleep.start >= readablePeriodStart.toEpochMilli()
        }

    private fun validSleeps(sleeps: List<Sleep>): List<Sleep> =
        sleeps.filter { sleep ->
            if (sleep.stop <= sleep.start) {
                Log.w(TAG, "Skipping non-positive sleep ${sleep.healthConnectId}")
                false
            } else {
                true
            }
        }

    @RequiresApi(Build.VERSION_CODES.P)
    internal suspend fun deletePending(client: HealthConnectClient, packageName: String) {
        val ownRecords = readOwnRecords(client, packageName)
            .filter { it.metadata.clientRecordId?.startsWith(CLIENT_ID_PREFIX) == true }
        deletePending(client, ownRecords.associateBy { it.metadata.clientRecordId!! })
    }

    @RequiresApi(Build.VERSION_CODES.P)
    internal suspend fun deletePending(
        client: HealthConnectClient,
        remoteByClientId: Map<String, SleepSessionRecord>,
        readablePeriodStart: Instant = Instant.EPOCH
    ) {
        val healthDao = DataModel.database.healthConnectDao()
        val tombstoneIds = healthDao.getDeletions()
            .filter { deletion ->
                deletion.start >= readablePeriodStart.toEpochMilli()
            }
            .map { it.healthConnectId }
        for (chunk in tombstoneIds.chunked(HEALTH_CONNECT_BATCH_SIZE)) {
            val clientIds = chunk.map { CLIENT_ID_PREFIX + it }
            val recordIds = clientIds.mapNotNull { remoteByClientId[it]?.metadata?.id }
            if (recordIds.isNotEmpty()) {
                client.deleteRecords(
                    SleepSessionRecord::class,
                    recordIdsList = recordIds,
                    clientRecordIdsList = emptyList()
                )
            }
            // Checkpoint every completed chunk. Repeating a remote deletion is safe if the
            // process is cancelled after the call above but before this local cleanup.
            healthDao.deleteDeletionsBatched(chunk)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private suspend fun readOwnRecords(context: Context): List<SleepSessionRecord> =
        readOwnRecords(
            HealthConnectClient.getOrCreate(context),
            context.packageName,
            readablePeriodStart(context)
        )

    @RequiresApi(Build.VERSION_CODES.P)
    internal suspend fun readOwnRecords(
        client: HealthConnectClient,
        packageName: String,
        readablePeriodStart: Instant = Instant.EPOCH
    ): List<SleepSessionRecord> {
        val records = mutableListOf<SleepSessionRecord>()
        val seenPageTokens = mutableSetOf<String>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest<SleepSessionRecord>(
                    timeRangeFilter = TimeRangeFilter.after(readablePeriodStart),
                    dataOriginFilter = setOf(DataOrigin(packageName)),
                    pageSize = HEALTH_CONNECT_BATCH_SIZE,
                    pageToken = pageToken
                )
            )
            records.addAll(response.records)
            pageToken = response.pageToken?.takeIf {
                it.isNotBlank() && seenPageTokens.add(it)
            }
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
