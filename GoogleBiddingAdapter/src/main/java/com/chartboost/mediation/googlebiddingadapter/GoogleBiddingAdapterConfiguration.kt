package com.chartboost.mediation.googlebiddingadapter

import com.chartboost.chartboostmediationsdk.domain.PartnerAdapterConfiguration
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object GoogleBiddingAdapterConfiguration : PartnerAdapterConfiguration {
    /**
     * The partner name for internal uses.
     */
    override val partnerId = "google_googlebidding"

    /**
     * The partner display name for internal uses.
     */
    override val partnerDisplayName = "Google Bidding"

    /**
     * The version of the partner SDK.
     */
    override val partnerSdkVersion = MobileAds.getVersion().toString()

    /**
     * The partner adapter version.
     *
     * You may version the adapter using any preferred convention, but it is recommended to apply the
     * following format if the adapter will be published by Chartboost Mediation:
     *
     * Chartboost Mediation.Partner.Adapter
     *
     * "Chartboost Mediation" represents the Chartboost Mediation SDK’s major version that is compatible with this adapter. This must be 1 digit.
     * "Partner" represents the partner SDK’s major.minor.patch.x (where x is optional) version that is compatible with this adapter. This can be 3-4 digits.
     * "Adapter" represents this adapter’s version (starting with 0), which resets to 0 when the partner SDK’s version changes. This must be 1 digit.
     */
    override val adapterVersion = BuildConfig.CHARTBOOST_MEDIATION_GOOGLE_BIDDING_ADAPTER_VERSION

    /**
     * List containing device IDs to be set for enabling Google Bidding test ads. It can be populated at
     * any time and will take effect for the next ad request. Remember to empty this list or
     * stop setting it before releasing your app.
     */
    var testDeviceIds = listOf<String>()
        set(value) {
            field = value
            PartnerLogController.log(
                    PartnerLogController.PartnerAdapterEvents.CUSTOM,
                    "Google Bidding test device ID(s) to be set: ${
                        if (value.isEmpty()) {
                            "none"
                        } else {
                            value.joinToString()
                        }
                    }",
            )

            // There have been known ANRs when calling setRequestConfiguration() on the main thread.
            CoroutineScope(Dispatchers.IO).launch {
                MobileAds.setRequestConfiguration(
                        RequestConfiguration.Builder().setTestDeviceIds(value).build(),
                )
            }
        }
}
