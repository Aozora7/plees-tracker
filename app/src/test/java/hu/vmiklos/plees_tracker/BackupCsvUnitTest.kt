/*
 * Copyright 2026 Miklos Vajna
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the CSV serialization shared by the file export and the Google Drive backup.
 */
class BackupCsvUnitTest {
    @Test
    fun testWriteSleepsCsv() {
        val sleep = Sleep()
        sleep.start = 10000
        sleep.stop = 20000
        sleep.rating = 3
        sleep.comment = "hi"
        sleep.wakes = 2
        sleep.healthConnectId = "21e61dba-2d6a-43e8-b86d-21ad27ee7508"
        sleep.healthConnectVersion = 7

        val os = ByteArrayOutputStream()
        DataModel.writeSleepsCsv(listOf(sleep), os, prettyBackup = false)

        val lines = os.toString("UTF-8").trim().split("\r\n")
        assertEquals(
            "sid,start,stop,rating,comment,wakes,health_connect_id,health_connect_version",
            lines[0]
        )
        assertEquals(
            "0,10000,20000,3,hi,2,21e61dba-2d6a-43e8-b86d-21ad27ee7508,7",
            lines[1]
        )
    }
}

/* vim:set shiftwidth=4 softtabstop=4 expandtab: */
