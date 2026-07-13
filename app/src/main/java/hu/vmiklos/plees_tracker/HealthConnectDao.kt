/*
 * Copyright 2026 aozora.one
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Accesses Health Connect synchronization state stored alongside sleeps. */
@Dao
interface HealthConnectDao {
    @Query("SELECT * FROM health_connect_deletion")
    suspend fun getDeletions(): List<HealthConnectDeletion>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeletions(deletions: List<HealthConnectDeletion>)

    @Query("DELETE FROM health_connect_deletion WHERE health_connect_id IN (:ids)")
    suspend fun deleteDeletions(ids: List<String>)

    @Query(
        "UPDATE sleep SET health_connect_version = :version " +
            "WHERE health_connect_id = :id"
    )
    suspend fun updateVersion(id: String, version: Long)
}

/* vim:set shiftwidth=4 softtabstop=4 expandtab: */
