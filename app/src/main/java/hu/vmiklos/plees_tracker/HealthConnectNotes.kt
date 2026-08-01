/*
 * Copyright 2026 Aleksandrs Serbajevs
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

/** Encodes Plees-only fields into Health Connect's human-readable notes field. */
object HealthConnectNotes {
    private const val HEADER = "Plees Tracker metadata v1"

    data class Values(val comment: String, val rating: Long, val wakes: Int)

    fun encode(sleep: Sleep): String {
        if (sleep.rating == 0L && sleep.wakes == 0) {
            return sleep.comment
        }
        val footer = "$HEADER\nRating: ${sleep.rating}\nWakes: ${sleep.wakes}"
        return if (sleep.comment.isEmpty()) footer else "${sleep.comment}\n\n$footer"
    }

    fun decode(notes: String?): Values {
        val value = notes ?: ""
        val marker = "\n\n$HEADER\nRating: "
        val footerStart = value.lastIndexOf(marker)
        val metadata = if (footerStart >= 0) {
            value.substring(footerStart + marker.length)
        } else if (value.startsWith("$HEADER\nRating: ")) {
            value.substring("$HEADER\nRating: ".length)
        } else {
            return Values(value, 0, 0)
        }
        val lines = metadata.split('\n')
        if (lines.size != 2 || !lines[1].startsWith("Wakes: ")) {
            return Values(value, 0, 0)
        }
        val rating = lines[0].toLongOrNull() ?: return Values(value, 0, 0)
        val wakes = lines[1].substring("Wakes: ".length).toIntOrNull()
            ?: return Values(value, 0, 0)
        val comment = if (footerStart >= 0) value.substring(0, footerStart) else ""
        return Values(comment, rating, wakes)
    }
}

/* vim:set shiftwidth=4 softtabstop=4 expandtab: */
