/*
 * Copyright 2026 Aleksandrs Serbajevs
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import android.content.Intent
import androidx.core.net.toUri

/** Opens Google Play to install or update the Health Connect provider. */
object HealthConnectProviderInstaller {
    private const val PROVIDER_PACKAGE_NAME = "com.google.android.apps.healthdata"
    private const val PLAY_STORE_PACKAGE_NAME = "com.android.vending"
    private const val PLAY_STORE_URL =
        "https://play.google.com/store/apps/details?id=$PROVIDER_PACKAGE_NAME"

    // Not const so shared code does not trigger constant-condition warnings.
    val isSupported = true

    fun updateIntents(): List<Intent> = listOf(
        Intent(Intent.ACTION_VIEW).apply {
            data = "market://details?id=$PROVIDER_PACKAGE_NAME".toUri()
            setPackage(PLAY_STORE_PACKAGE_NAME)
        },
        Intent(Intent.ACTION_VIEW, PLAY_STORE_URL.toUri())
    )
}

/* vim:set shiftwidth=4 softtabstop=4 expandtab: */
