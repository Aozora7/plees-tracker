/*
 * Copyright 2026 aozora.one
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** A local deletion which still needs to be reflected in Health Connect. */
@Entity(tableName = "health_connect_deletion")
data class HealthConnectDeletion(
    @PrimaryKey
    @ColumnInfo(name = "health_connect_id")
    var healthConnectId: String = ""
)

/* vim:set shiftwidth=4 softtabstop=4 expandtab: */
