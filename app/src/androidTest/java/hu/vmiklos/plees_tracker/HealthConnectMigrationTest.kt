/*
 * Copyright 2026 aozora.one
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** On-device validation of the database state introduced for Health Connect. */
@RunWith(AndroidJUnit4::class)
class HealthConnectMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "health-connect-migration-test"

    @Before
    fun createVersionFourDatabase() {
        context.deleteDatabase(databaseName)
        openHelper(4) { database ->
            database.execSQL(
                "CREATE TABLE sleep (" +
                    "sid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "start_date INTEGER NOT NULL, stop_date INTEGER NOT NULL, " +
                    "rating INTEGER NOT NULL, comment TEXT NOT NULL, " +
                    "wakes INTEGER NOT NULL)"
            )
            database.execSQL(
                "INSERT INTO sleep (start_date, stop_date, rating, comment, wakes) " +
                    "VALUES (1000, 2000, 4, 'one', 1), (3000, 4000, 5, 'two', 2)"
            )
        }.close()
    }

    @After
    fun deleteDatabase() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun testMigrationCreatesIdentitiesAndTombstones() = runBlocking {
        openHelper(5) { database -> MIGRATION_4_5.migrate(database) }.close()

        val roomDatabase = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_4_5)
            .build()
        try {
            val sleeps = roomDatabase.sleepDao().getAll()
            assertEquals(2, sleeps.size)
            UUID.fromString(sleeps[0].healthConnectId)
            UUID.fromString(sleeps[1].healthConnectId)
            assertNotEquals(sleeps[0].healthConnectId, sleeps[1].healthConnectId)
            assertEquals(0, sleeps[0].healthConnectVersion)

            val deletion = HealthConnectDeletion().apply {
                healthConnectId = sleeps[0].healthConnectId
            }
            roomDatabase.healthConnectDao().insertDeletions(listOf(deletion))
            assertEquals(listOf(deletion), roomDatabase.healthConnectDao().getDeletions())
        } finally {
            roomDatabase.close()
        }
    }

    private fun openHelper(
        version: Int,
        onOpen: (SupportSQLiteDatabase) -> Unit
    ): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                onOpen(db)
            }

            override fun onUpgrade(
                db: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int
            ) {
                onOpen(db)
            }
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(callback)
                .build()
        ).also { it.writableDatabase }
    }
}

/* vim:set shiftwidth=4 softtabstop=4 expandtab: */
