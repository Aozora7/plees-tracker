/*
 * Copyright 2026 aozora.one
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.testing.FakePermissionController
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for mapping and origin filtering at the Health Connect boundary. */
class HealthConnectBackendUnitTest {
    @Test
    fun testFullHistoryAvailability() {
        assertFalse(
            HealthConnectBackend.canReadAllHistory(
                33,
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            )
        )
        assertFalse(
            HealthConnectBackend.canReadAllHistory(
                34,
                HealthConnectFeatures.FEATURE_STATUS_UNAVAILABLE
            )
        )
        assertTrue(
            HealthConnectBackend.canReadAllHistory(
                34,
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            )
        )
    }

    @Test
    fun testSleepRecordRepresentation() {
        val sleep = Sleep().apply {
            start = 1_000
            stop = 2_000
            comment = "comment"
            rating = 4
            wakes = 2
            healthConnectId = "00000000-0000-0000-0000-000000000001"
            healthConnectVersion = 7
        }

        val record = HealthConnectBackend.toRecord(sleep)

        assertEquals(Instant.ofEpochMilli(1_000), record.startTime)
        assertEquals(Instant.ofEpochMilli(2_000), record.endTime)
        assertNull(record.startZoneOffset)
        assertNull(record.endZoneOffset)
        assertEquals(
            HealthConnectBackend.CLIENT_ID_PREFIX + sleep.healthConnectId,
            record.metadata.clientRecordId
        )
        assertEquals(7, record.metadata.clientRecordVersion)
        assertEquals(HealthConnectNotes.encode(sleep), record.notes)
        assertEquals(1, record.stages.size)
        assertEquals(SleepSessionRecord.STAGE_TYPE_SLEEPING, record.stages.single().stage)
        assertEquals(record.startTime, record.stages.single().startTime)
        assertEquals(record.endTime, record.stages.single().endTime)
    }

    @Test
    fun testReadOwnRecordsExcludesOtherOrigins() = runBlocking {
        val client = FakeHealthConnectClient(
            "other.app",
            Clock.systemUTC(),
            FakePermissionController()
        )
        client.insertRecords(listOf(record("00000000-0000-0000-0000-000000000001")))
        client.setPackageName("hu.vmiklos.plees_tracker")
        client.insertRecords(listOf(record("00000000-0000-0000-0000-000000000002")))

        val records = HealthConnectBackend.readOwnRecords(client, "hu.vmiklos.plees_tracker")

        assertEquals(1, records.size)
        assertEquals(
            HealthConnectBackend.CLIENT_ID_PREFIX +
                "00000000-0000-0000-0000-000000000002",
            records.single().metadata.clientRecordId
        )
    }

    @Test
    fun testReadOwnRecordsExcludesRecordsBeforeReadablePeriod() = runBlocking {
        val now = Instant.parse("2026-07-14T12:00:00Z")
        val readablePeriodStart = now.minusSeconds(30L * 24 * 60 * 60)
        val client = FakeHealthConnectClient(
            "hu.vmiklos.plees_tracker",
            Clock.fixed(now, java.time.ZoneOffset.UTC),
            FakePermissionController()
        )
        client.insertRecords(
            listOf(
                record(
                    "00000000-0000-0000-0000-000000000001",
                    readablePeriodStart.minusSeconds(1)
                ),
                record(
                    "00000000-0000-0000-0000-000000000002",
                    readablePeriodStart
                )
            )
        )

        val records = HealthConnectBackend.readOwnRecords(
            client,
            "hu.vmiklos.plees_tracker",
            readablePeriodStart
        )

        assertEquals(1, records.size)
        assertEquals(
            HealthConnectBackend.CLIENT_ID_PREFIX +
                "00000000-0000-0000-0000-000000000002",
            records.single().metadata.clientRecordId
        )
    }

    private fun record(
        id: String,
        start: Instant = Instant.ofEpochMilli(1_000)
    ): SleepSessionRecord = HealthConnectBackend.toRecord(
        Sleep().apply {
            this.start = start.toEpochMilli()
            stop = start.plusSeconds(1).toEpochMilli()
            healthConnectId = id
        }
    )
}

/* vim:set shiftwidth=4 softtabstop=4 expandtab: */
