/*
 * Copyright 2026 aozora.one
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import android.content.Context
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.testing.FakePermissionController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun testCreateEditRecreateAndDelete() = runBlocking {
        val sleep = sleep(1)
        database.sleepDao().insert(sleep)

        HealthConnectBackend.sync(client, packageName)
        var records = HealthConnectBackend.readOwnRecords(client, packageName)
        assertEquals(1, records.size)

        sleep.comment = "edited"
        sleep.healthConnectVersion++
        database.sleepDao().update(sleep)
        HealthConnectBackend.sync(client, packageName)
        records = HealthConnectBackend.readOwnRecords(client, packageName)
        assertEquals(1, records.size)
        assertEquals(HealthConnectNotes.encode(sleep), records.single().notes)

        client.deleteRecords(
            SleepSessionRecord::class,
            recordIdsList = listOf(records.single().metadata.id),
            clientRecordIdsList = emptyList()
        )
        HealthConnectBackend.sync(client, packageName)
        records = HealthConnectBackend.readOwnRecords(client, packageName)
        assertEquals(1, records.size)

        database.healthConnectDao().insertDeletions(
            listOf(HealthConnectDeletion(sleep.healthConnectId))
        )
        database.sleepDao().delete(sleep)
        HealthConnectBackend.sync(client, packageName)
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
}

/* vim:set shiftwidth=4 softtabstop=4 expandtab: */
