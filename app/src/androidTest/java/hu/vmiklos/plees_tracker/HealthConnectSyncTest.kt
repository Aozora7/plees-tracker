/*
 * Copyright 2026 aozora.one
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import android.content.Context
import android.os.RemoteException
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.testing.FakePermissionController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import java.time.Clock
import java.util.UUID
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** On-device reconciliation tests using the official in-memory Health Connect client. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class HealthConnectSyncTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val packageName = "hu.vmiklos.plees_tracker"
    private lateinit var database: AppDatabase
    private lateinit var client: FakeHealthConnectClient

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        DataModel.database = database
        client = FakeHealthConnectClient(
            packageName,
            Clock.systemUTC(),
            FakePermissionController()
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun testCreateAndDelete() = runBlocking {
        val sleep = sleep(1).apply {
            comment = "edited"
        }
        database.sleepDao().insert(sleep)

        HealthConnectBackend.sync(client, packageName)
        val records = HealthConnectBackend.readOwnRecords(client, packageName)
        assertEquals(1, records.size)
        assertEquals(HealthConnectNotes.encode(sleep), records.single().notes)

        database.healthConnectDao().insertDeletions(
            listOf(HealthConnectDeletion(sleep.healthConnectId))
        )
        database.sleepDao().delete(sleep)
        HealthConnectBackend.sync(client, packageName)
        assertEquals(0, HealthConnectBackend.readOwnRecords(client, packageName).size)
        assertEquals(0, database.healthConnectDao().getDeletions().size)
    }

    @Test
    fun testWriteCompletesBeforeReadFailure() = runBlocking {
        val sleep = sleep(1)
        database.sleepDao().insert(sleep)
        val readFailureClient = object : HealthConnectClient by client {
            override suspend fun <T : Record> readRecords(
                request: ReadRecordsRequest<T>
            ): ReadRecordsResponse<T> {
                throw RemoteException("rate limited")
            }
        }

        try {
            HealthConnectBackend.sync(readFailureClient, packageName)
        } catch (_: RemoteException) {
            // The worker retries reconciliation, but the local record must already be exported.
        }

        assertEquals(1, HealthConnectBackend.readOwnRecords(client, packageName).size)
    }

    @Test
    fun testRateLimitedOperationsEventuallyComplete() = runBlocking {
        val sleep = sleep(1)
        database.sleepDao().insert(sleep)
        val rateLimitedClient = AlternatingRateLimitClient(client)

        assertTrue(syncUntilSuccess(rateLimitedClient) > 1)
        assertEquals(1, HealthConnectBackend.readOwnRecords(client, packageName).size)

        database.healthConnectDao().insertDeletions(
            listOf(HealthConnectDeletion(sleep.healthConnectId))
        )
        database.sleepDao().delete(sleep)

        assertTrue(
            syncUntilSuccess(rateLimitedClient) {
                assertEquals(1, database.healthConnectDao().getDeletions().size)
            } > 1
        )
        assertEquals(0, HealthConnectBackend.readOwnRecords(client, packageName).size)
        assertEquals(0, database.healthConnectDao().getDeletions().size)
    }

    @Test
    fun testUnmatchedHistoricalRecordSurvivesSyncAndUnrelatedDeletion() = runBlocking {
        val historical = sleep(1)
        client.insertRecords(listOf(HealthConnectBackend.toRecord(historical)))

        val current = sleep(2)
        database.sleepDao().insert(current)
        HealthConnectBackend.sync(client, packageName)

        var records = HealthConnectBackend.readOwnRecords(client, packageName)
        assertEquals(
            setOf(historical.healthConnectId, current.healthConnectId),
            records.mapNotNull { it.metadata.clientRecordId }
                .map { it.removePrefix(HealthConnectBackend.CLIENT_ID_PREFIX) }
                .toSet()
        )

        database.healthConnectDao().insertDeletions(
            listOf(HealthConnectDeletion(current.healthConnectId))
        )
        database.sleepDao().delete(current)
        HealthConnectBackend.sync(client, packageName)

        records = HealthConnectBackend.readOwnRecords(client, packageName)
        assertEquals(
            listOf(HealthConnectBackend.CLIENT_ID_PREFIX + historical.healthConnectId),
            records.mapNotNull { it.metadata.clientRecordId }
        )
    }

    @Test
    fun testBatchedDeletionStateCleanup() = runBlocking {
        val deletions = (1..2300).map { index ->
            HealthConnectDeletion(
                UUID.nameUUIDFromBytes(index.toString().toByteArray()).toString()
            )
        }
        val dao = database.healthConnectDao()
        dao.insertDeletions(deletions)

        dao.deleteDeletionsBatched(deletions.map { it.healthConnectId })

        assertEquals(0, dao.getDeletions().size)
    }

    @Test
    fun testBatchingAndPagination() = runBlocking {
        database.sleepDao().insert((1..1001).map(::sleep))

        HealthConnectBackend.sync(client, packageName)

        assertEquals(1001, HealthConnectBackend.readOwnRecords(client, packageName).size)
    }

    private fun sleep(index: Int): Sleep = Sleep().apply {
        start = index * 3_000L
        stop = start + 1_000
        healthConnectId = UUID.nameUUIDFromBytes(index.toString().toByteArray()).toString()
    }

    private suspend fun syncUntilSuccess(
        client: HealthConnectClient,
        onRetry: suspend () -> Unit = {}
    ): Int {
        for (attempt in 1..MAX_SYNC_ATTEMPTS) {
            try {
                HealthConnectBackend.sync(client, packageName)
                return attempt
            } catch (_: RemoteException) {
                // WorkManager repeats the same synchronization after a transient provider error.
                onRetry()
            }
        }
        throw AssertionError("Health Connect synchronization did not complete")
    }

    private class AlternatingRateLimitClient(
        private val delegate: HealthConnectClient
    ) : HealthConnectClient by delegate {
        private var inserts = 0
        private var reads = 0
        private var deletions = 0

        override suspend fun insertRecords(records: List<Record>): InsertRecordsResponse {
            rateLimit(++inserts)
            return delegate.insertRecords(records)
        }

        override suspend fun <T : Record> readRecords(
            request: ReadRecordsRequest<T>
        ): ReadRecordsResponse<T> {
            rateLimit(++reads)
            return delegate.readRecords(request)
        }

        override suspend fun deleteRecords(
            recordType: KClass<out Record>,
            recordIdsList: List<String>,
            clientRecordIdsList: List<String>
        ) {
            rateLimit(++deletions)
            delegate.deleteRecords(recordType, recordIdsList, clientRecordIdsList)
        }

        private fun rateLimit(operation: Int) {
            if (operation % 2 == 1) {
                throw RemoteException("rate limited")
            }
        }
    }

    companion object {
        private const val MAX_SYNC_ATTEMPTS = 10
    }
}

/* vim:set shiftwidth=4 softtabstop=4 expandtab: */
