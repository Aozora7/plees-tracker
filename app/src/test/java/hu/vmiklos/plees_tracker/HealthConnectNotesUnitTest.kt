/*
 * Copyright 2026 aozora.one
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for Plees Tracker's human-readable Health Connect notes format. */
class HealthConnectNotesUnitTest {
    @Test
    fun testEmptyCommentRoundTrip() {
        val sleep = sleep(comment = "", rating = 4, wakes = 2)

        assertEquals(
            "Plees Tracker metadata v1\nRating: 4\nWakes: 2",
            HealthConnectNotes.encode(sleep)
        )
        assertEquals(
            HealthConnectNotes.Values("", 4, 2),
            HealthConnectNotes.decode(HealthConnectNotes.encode(sleep))
        )
    }

    @Test
    fun testUnicodeMultilineTrailingNewlineRoundTrip() {
        val comment = "Rating: excellent\nWakes: rarely\nJó éjt! 🌙\n"
        val sleep = sleep(comment = comment, rating = Long.MAX_VALUE, wakes = Int.MAX_VALUE)

        assertEquals(
            HealthConnectNotes.Values(comment, Long.MAX_VALUE, Int.MAX_VALUE),
            HealthConnectNotes.decode(HealthConnectNotes.encode(sleep))
        )
    }

    @Test
    fun testMalformedFooterIsComment() {
        val notes = "hello\n\nPlees Tracker metadata v1\nRating: four\nWakes: 2"

        assertEquals(HealthConnectNotes.Values(notes, 0, 0), HealthConnectNotes.decode(notes))
    }

    @Test
    fun testUnknownVersionIsComment() {
        val notes = "hello\n\nPlees Tracker metadata v2\nRating: 4\nWakes: 2"

        assertEquals(HealthConnectNotes.Values(notes, 0, 0), HealthConnectNotes.decode(notes))
    }

    @Test
    fun testValidEditedFooterIsAccepted() {
        val notes = "hello\n\nPlees Tracker metadata v1\nRating: -1\nWakes: -2"

        assertEquals(HealthConnectNotes.Values("hello", -1, -2), HealthConnectNotes.decode(notes))
    }

    private fun sleep(comment: String, rating: Long, wakes: Int): Sleep = Sleep().apply {
        this.comment = comment
        this.rating = rating
        this.wakes = wakes
    }
}

/* vim:set shiftwidth=4 softtabstop=4 expandtab: */
