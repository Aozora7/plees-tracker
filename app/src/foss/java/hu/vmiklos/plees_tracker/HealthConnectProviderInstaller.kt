/*
 * Copyright 2026 Aleksandrs Serbajevs
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import android.content.Intent

/** Leaves Health Connect provider installation to the user in the FOSS flavor. */
object HealthConnectProviderInstaller {
    // Not const so shared code does not trigger constant-condition warnings.
    val isSupported = false

    fun updateIntents(): List<Intent> = emptyList()
}

/* vim:set shiftwidth=4 softtabstop=4 expandtab: */
