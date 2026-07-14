/*
 * Copyright 2023 Miklos Vajna
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import android.text.format.DateUtils
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Represents one tracked sleep.
 */
@Entity(indices = [Index(value = ["health_connect_id"], unique = true)])
class Sleep {
    @PrimaryKey(autoGenerate = true)
    var sid: Int = 0

    @ColumnInfo(name = "start_date")
    var start: Long = 0

    @ColumnInfo(name = "stop_date")
    var stop: Long = 0

    @ColumnInfo(name = "rating")
    var rating: Long = 0

    @ColumnInfo(name = "comment")
    var comment: String = ""

    @ColumnInfo(name = "wakes")
    var wakes: Int = 0

    @ColumnInfo(name = "health_connect_id")
    var healthConnectId: String = UUID.randomUUID().toString()

    @ColumnInfo(name = "health_connect_version")
    var healthConnectVersion: Long = 0

    @ColumnInfo(name = "health_connect_synced_version")
    var healthConnectSyncedVersion: Long = -1

    private val lengthMs
        get() = stop - start

    val lengthHours
        get() = lengthMs.toFloat() / DateUtils.HOUR_IN_MILLIS

    override fun equals(other: Any?): Boolean =
        other is Sleep &&
            other.start == start &&
            other.stop == stop &&
            other.rating == rating &&
            other.comment == comment &&
            other.wakes == wakes

    override fun hashCode(): Int {
        var result = start.hashCode()
        result = 31 * result + stop.hashCode()
        result = 31 * result + rating.hashCode()
        result = 31 * result + comment.hashCode()
        result = 31 * result + wakes
        return result
    }
}

/* vim:set shiftwidth=4 softtabstop=4 expandtab: */
