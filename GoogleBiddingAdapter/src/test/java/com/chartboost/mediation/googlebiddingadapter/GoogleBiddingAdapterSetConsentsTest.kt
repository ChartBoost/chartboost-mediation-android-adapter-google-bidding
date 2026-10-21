/*
 * Copyright 2026 Chartboost, Inc.
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.googlebiddingadapter

import android.content.Context
import com.chartboost.core.consent.ConsentKeys
import com.chartboost.core.consent.ConsentValues
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.VersionInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GoogleBiddingAdapterSetConsentsTest {
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setUp() {
        // The adapter configuration reads the SDK version at class init, which needs a real device.
        mockkStatic(MobileAds::class)
        every { MobileAds.getVersion() } returns VersionInfo(25, 5, 0)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `a CCPA opt-out turns on restricted data processing`() {
        val adapter = GoogleBiddingAdapter()
        adapter.setConsents(context, mapOf(ConsentKeys.CCPA_OPT_IN to ConsentValues.DENIED), setOf(ConsentKeys.CCPA_OPT_IN))

        assertTrue(adapter.restrictedDataProcessingEnabled)
    }

    @Test
    fun `no CCPA opt-out leaves restricted data processing off`() {
        val adapter = GoogleBiddingAdapter()
        adapter.setConsents(context, mapOf(ConsentKeys.CCPA_OPT_IN to ConsentValues.DENIED), setOf(ConsentKeys.CCPA_OPT_IN))
        adapter.setConsents(context, mapOf(ConsentKeys.CCPA_OPT_IN to ConsentValues.GRANTED), setOf(ConsentKeys.CCPA_OPT_IN))

        assertFalse(adapter.restrictedDataProcessingEnabled)
    }
}
