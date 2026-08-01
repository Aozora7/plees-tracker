/*
 * Copyright 2026 Aleksandrs Serbajevs
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Displays the privacy rationale linked from Health Connect's permission UI. */
class HealthConnectRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.health_connect_privacy_title)
        setContentView(R.layout.activity_health_connect_rationale)
        DataModel.handleWindowInsets(this)
    }
}

/* vim:set shiftwidth=4 softtabstop=4 expandtab: */
