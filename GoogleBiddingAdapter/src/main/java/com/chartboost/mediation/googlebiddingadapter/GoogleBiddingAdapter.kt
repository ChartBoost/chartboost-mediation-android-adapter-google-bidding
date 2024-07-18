/*
 * Copyright 2023-2024 Chartboost, Inc.
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.googlebiddingadapter

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.util.Size
import android.view.View.GONE
import com.chartboost.chartboostmediationsdk.domain.*
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.CUSTOM
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_CLICK
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_DISMISS
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_REWARD
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_TRACK_IMPRESSION
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_DENIED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_GRANTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_UNKNOWN
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_NOT_UNDERAGE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_UNDERAGE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USP_CONSENT_DENIED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USP_CONSENT_GRANTED
import com.chartboost.core.consent.ConsentKey
import com.chartboost.core.consent.ConsentKeys
import com.chartboost.core.consent.ConsentValue
import com.chartboost.core.consent.ConsentValues
import com.chartboost.mediation.googlebiddingadapter.GoogleBiddingAdapter.Companion.getChartboostMediationError
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.*
import com.google.android.gms.ads.initialization.AdapterStatus
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.query.QueryInfo
import com.google.android.gms.ads.query.QueryInfoGenerationCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

class GoogleBiddingAdapter : PartnerAdapter {
    companion object {
        /**
         * Convert a given Google Bidding error code into a [ChartboostMediationError].
         *
         * @param error The Google Bidding error code as an [Int].
         *
         * @return The corresponding [ChartboostMediationError].
         */
        internal fun getChartboostMediationError(error: Int) =
            when (error) {
                AdRequest.ERROR_CODE_APP_ID_MISSING -> ChartboostMediationError.LoadError.PartnerNotInitialized
                AdRequest.ERROR_CODE_INTERNAL_ERROR -> ChartboostMediationError.OtherError.InternalError
                AdRequest.ERROR_CODE_INVALID_AD_STRING -> ChartboostMediationError.LoadError.InvalidAdMarkup
                AdRequest.ERROR_CODE_INVALID_REQUEST, AdRequest.ERROR_CODE_REQUEST_ID_MISMATCH -> ChartboostMediationError.LoadError.AdRequestTimeout
                AdRequest.ERROR_CODE_NETWORK_ERROR -> ChartboostMediationError.OtherError.NoConnectivity
                AdRequest.ERROR_CODE_NO_FILL -> ChartboostMediationError.LoadError.NoFill
                else -> ChartboostMediationError.OtherError.PartnerError
            }
    }

    /**
     * The Google Bidding adapter configuration.
     */
    override var configuration: PartnerAdapterConfiguration = GoogleBiddingAdapterConfiguration

    /**
     * A map of Chartboost Mediation's listeners for the corresponding load identifier.
     */
    private val listeners = mutableMapOf<String, PartnerAdListener>()

    /**
     * Indicates whether the user has consented to allowing personalized ads when GDPR applies.
     */
    private var allowPersonalizedAds = true

    /**
     * Indicate whether the user has given consent per CCPA.
     */
    private var ccpaPrivacyString: String? = null

    /**
     * Indicates whether the user has restricted their data for advertising use.
     */
    private var restrictedDataProcessingEnabled = false

    /**
     * Initialize the Google Mobile Ads SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize Google Bidding.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration,
    ): Result<Map<String, Any>> = withContext(IO) {
        PartnerLogController.log(SETUP_STARTED)

        // Since Chartboost Mediation is the mediator, no need to initialize AdMob's partner SDKs.
        // https://developers.google.com/android/reference/com/google/android/gms/ads/MobileAds?hl=en#disableMediationAdapterInitialization(android.content.Context)
        //
        // There have been known ANRs when calling disableMediationAdapterInitialization() on the main thread.
        MobileAds.disableMediationAdapterInitialization(context)

        suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<Map<String, Any>>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            MobileAds.initialize(context) { status ->
                resumeOnce(getInitResult(status.adapterStatusMap[MobileAds::class.java.name]))
            }
        }
    }

    /**
     * Notify Google Bidding of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isUserUnderage True if the user is subject to COPPA, false otherwise.
     */
    override fun setIsUserUnderage(
        context: Context,
        isUserUnderage: Boolean,
    ) {
        PartnerLogController.log(
            if (isUserUnderage) {
                USER_IS_UNDERAGE
            } else {
                USER_IS_NOT_UNDERAGE
            },
        )

        // There have been known ANRs when calling setRequestConfiguration() on the main thread.
        CoroutineScope(IO).launch {
            MobileAds.setRequestConfiguration(
                MobileAds.getRequestConfiguration().toBuilder()
                    .setTagForChildDirectedTreatment(
                        if (isUserUnderage) {
                            RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_TRUE
                        } else {
                            RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE
                        },
                    ).build(),
            )
        }
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PartnerAdPreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PartnerAdPreBidRequest,
    ): Result<Map<String, String>> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)

        // Google-defined specs for Chartboost Mediation
        val extras = Bundle()
        extras.putString("query_info_type", "requester_type_2")

        val adRequest =
            AdRequest.Builder()
                .setRequestAgent("Chartboost")
                .addNetworkExtrasBundle(AdMobAdapter::class.java, extras)
                .build()

        val adFormat = getGoogleBiddingAdFormat(request.format)

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<Map<String, String>>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            CoroutineScope(Dispatchers.IO).launch {
                QueryInfo.generate(
                    context,
                    adFormat,
                    adRequest,
                    object : QueryInfoGenerationCallback() {
                        override fun onSuccess(queryInfo: QueryInfo) {
                            PartnerLogController.log(BIDDER_INFO_FETCH_SUCCEEDED)
                            resumeOnce(Result.success(mapOf("token" to queryInfo.query)))
                        }

                        override fun onFailure(error: String) {
                            PartnerLogController.log(BIDDER_INFO_FETCH_FAILED, error)
                            resumeOnce(Result.success(emptyMap()))
                        }
                    },
                )
            }
        }
    }

    /**
     * Attempt to load a Google Bidding ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        return when (request.format) {
            PartnerAdFormats.INTERSTITIAL ->
                loadInterstitialAd(
                    context,
                    request,
                    partnerAdListener,
                )
            PartnerAdFormats.REWARDED ->
                loadRewardedAd(
                    context,
                    request,
                    partnerAdListener,
                )
            PartnerAdFormats.BANNER ->
                loadBannerAd(
                    context,
                    request,
                    partnerAdListener,
                )
            PartnerAdFormats.REWARDED_INTERSTITIAL ->
                loadRewardedInterstitialAd(
                    context,
                    request,
                    partnerAdListener,
                )
            else -> {
                PartnerLogController.log(LOAD_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.UnsupportedAdFormat))
            }
        }
    }

    /**
     * Attempt to show the currently loaded Google Bidding ad.
     *
     * @param activity The current [Activity]
     * @param partnerAd The [PartnerAd] object containing the Google Bidding ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(
        activity: Activity,
        partnerAd: PartnerAd,
    ): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)
        val listener = listeners.remove(partnerAd.request.identifier)

        return when (partnerAd.request.format) {
            // Banner ads do not have a separate "show" mechanism.
            PartnerAdFormats.BANNER -> {
                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            }
            PartnerAdFormats.INTERSTITIAL -> showInterstitialAd(activity, partnerAd, listener)
            PartnerAdFormats.REWARDED -> showRewardedAd(activity, partnerAd, listener)
            PartnerAdFormats.REWARDED_INTERSTITIAL ->
                showRewardedInterstitialAd(
                    activity,
                    partnerAd,
                    listener,
                )
            else -> {
                PartnerLogController.log(SHOW_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.UnsupportedAdFormat))
            }
        }
    }

    /**
     * Discard unnecessary Google Bidding ad objects and release resources.
     *
     * @param partnerAd The [PartnerAd] object containing the Google Bidding ad to be discarded.
     *
     * @return Result.success(PartnerAd) if the ad was successfully discarded, Result.failure(Exception) otherwise.
     */
    override suspend fun invalidate(partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(INVALIDATE_STARTED)
        listeners.remove(partnerAd.request.identifier)

        // Only invalidate banners as there are no explicit methods to invalidate the other formats.
        return when (partnerAd.request.format) {
            PartnerAdFormats.BANNER -> destroyBannerAd(partnerAd)
            else -> {
                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            }
        }
    }

    override fun setConsents(
        context: Context,
        consents: Map<ConsentKey, ConsentValue>,
        modifiedKeys: Set<ConsentKey>,
    ) {
        val consent = consents[configuration.partnerId]?.takeIf { it.isNotBlank() }
            ?: consents[ConsentKeys.GDPR_CONSENT_GIVEN]?.takeIf { it.isNotBlank() }
        consent?.let {
            when(it) {
                ConsentValues.GRANTED -> {
                    PartnerLogController.log(GDPR_CONSENT_GRANTED)
                    allowPersonalizedAds = true
                }

                ConsentValues.DENIED -> {
                    PartnerLogController.log(GDPR_CONSENT_DENIED)
                    allowPersonalizedAds = false
                }

                else -> {
                    PartnerLogController.log(GDPR_CONSENT_UNKNOWN)
                }
            }
        }

        consents[ConsentKeys.USP]?.let {
            ccpaPrivacyString = it
        }

        consents[ConsentKeys.CCPA_OPT_IN]?.let {
            when(it) {
                ConsentValues.GRANTED -> {
                    PartnerLogController.log(USP_CONSENT_GRANTED)
                    restrictedDataProcessingEnabled = true
                }

                ConsentValues.DENIED -> {
                    PartnerLogController.log(USP_CONSENT_DENIED)
                    restrictedDataProcessingEnabled = false
                }

                else -> {
                    PartnerLogController.log(CUSTOM, "Unable to set RDP since CCPA_OPT_IN is $it")
                }
            }
        }
    }

    /**
     * Get a [Result] containing the initialization result of the Google Mobile Ads SDK.
     *
     * @param status The initialization status of the Google Mobile Ads SDK.
     *
     * @return A [Result] object containing details about the initialization result.
     */
    private fun getInitResult(status: AdapterStatus?): Result<Map<String, Any>> {
        return status?.let { it ->
            if (it.initializationState == AdapterStatus.State.READY) {
                PartnerLogController.log(SETUP_SUCCEEDED)
                Result.success(emptyMap())
            } else {
                PartnerLogController.log(
                    SETUP_FAILED,
                    "Initialization state: ${it.initializationState}. Description: ${it.description}",
                )
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.InitializationError.Unknown))
            }
        } ?: run {
            PartnerLogController.log(SETUP_FAILED, "Initialization status is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.InitializationError.Unknown))
        }
    }

    /**
     * Attempt to load a Google Bidding banner on the main thread.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     */
    private suspend fun loadBannerAd(
        context: Context,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            CoroutineScope(Main).launch {
                val adm =
                    request.adm ?: run {
                        PartnerLogController.log(
                            LOAD_FAILED,
                            ChartboostMediationError.LoadError.InvalidAdMarkup.cause.toString(),
                        )
                        continuation.resumeWith(
                            Result.failure(
                                ChartboostMediationAdException(ChartboostMediationError.LoadError.InvalidAdMarkup),
                            ),
                        )
                        return@launch
                    }

                val bannerAd = AdView(context)
                val isAdaptive = request.bannerSize?.type == BannerTypes.ADAPTIVE_BANNER
                val adSize = getGoogleBiddingAdSize(context, request.bannerSize?.size, isAdaptive)

                val partnerBannerSize =
                    PartnerBannerSize(
                        Size(adSize.width, adSize.height),
                        if (isAdaptive) BannerTypes.ADAPTIVE_BANNER else BannerTypes.BANNER,
                    )

                val partnerAd =
                    PartnerAd(
                        ad = bannerAd,
                        details = emptyMap(),
                        request = request,
                        partnerBannerSize = partnerBannerSize,
                    )

                bannerAd.setAdSize(adSize)
                bannerAd.adUnitId = request.partnerPlacement
                bannerAd.loadAd(buildRequest(adm))
                bannerAd.adListener =
                    object : AdListener() {
                        override fun onAdImpression() {
                            PartnerLogController.log(DID_TRACK_IMPRESSION)
                            listener.onPartnerAdImpression(partnerAd)
                        }

                        override fun onAdLoaded() {
                            PartnerLogController.log(LOAD_SUCCEEDED)
                            resumeOnce(Result.success(
                                PartnerAd(
                                    ad = bannerAd,
                                    details = emptyMap(),
                                    request = request,
                                    partnerBannerSize = PartnerBannerSize(
                                        Size(adSize.width, adSize.height),
                                        request.bannerSize?.type ?: BannerTypes.BANNER,
                                    )
                                )
                            ))
                        }

                        override fun onAdFailedToLoad(adError: LoadAdError) {
                            PartnerLogController.log(LOAD_FAILED, adError.message)
                            resumeOnce(
                                Result.failure(
                                    ChartboostMediationAdException(
                                        getChartboostMediationError(adError.code),
                                    ),
                                ),
                            )
                        }

                        override fun onAdOpened() {
                            // NO-OP
                        }

                        override fun onAdClicked() {
                            PartnerLogController.log(DID_CLICK)
                            listener.onPartnerAdClicked(partnerAd)
                        }

                        override fun onAdClosed() {
                            // NO-OP. Ignore banner closes to help avoid auto-refresh issues.
                        }
                    }
            }
        }
    }

    /**
     * Find the most appropriate Google Bidding ad size for the given screen area based on height.
     *
     * @param context The current [Context].
     * @param size The [Size] to parse for conversion.
     * @param isAdaptive whether or not the placement is for an adaptive banner.
     *
     * @return The Google Bidding ad size that best matches the given [Size].
     */
    private fun getGoogleBiddingAdSize(
        context: Context,
        size: Size?,
        isAdaptive: Boolean = false,
    ): AdSize {
        if (isAdaptive) {
            return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                context,
                size?.width ?: AdSize.BANNER.width,
            )
        }

        return size?.height?.let {
            when {
                it in 50 until 90 -> AdSize.BANNER
                it in 90 until 250 -> AdSize.LEADERBOARD
                it >= 250 -> AdSize.MEDIUM_RECTANGLE
                else -> AdSize.BANNER
            }
        } ?: AdSize.BANNER
    }

    /**
     * Attempt to load a Google Bidding interstitial on the main thread.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing data to load the ad with.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadInterstitialAd(
        context: Context,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        // Save the listener for later use.
        listeners[request.identifier] = listener

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            CoroutineScope(Main).launch {
                val adm =
                    request.adm ?: run {
                        PartnerLogController.log(
                            LOAD_FAILED,
                            ChartboostMediationError.LoadError.InvalidAdMarkup.cause,
                        )
                        continuation.resumeWith(
                            Result.failure(
                                ChartboostMediationAdException(ChartboostMediationError.LoadError.InvalidAdMarkup),
                            ),
                        )
                        return@launch
                    }

                InterstitialAd.load(
                    context,
                    request.partnerPlacement,
                    buildRequest(adm),
                    object : InterstitialAdLoadCallback() {
                        override fun onAdLoaded(interstitialAd: InterstitialAd) {
                            PartnerLogController.log(LOAD_SUCCEEDED)
                            resumeOnce(
                                Result.success(
                                    PartnerAd(
                                        ad = interstitialAd,
                                        details = emptyMap(),
                                        request = request,
                                    ),
                                ),
                            )
                        }

                        override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                            PartnerLogController.log(LOAD_FAILED, loadAdError.message)
                            resumeOnce(
                                Result.failure(
                                    ChartboostMediationAdException(
                                        getChartboostMediationError(loadAdError.code),
                                    ),
                                ),
                            )
                        }
                    },
                )
            }
        }
    }

    /**
     * Attempt to load a Google Bidding rewarded ad on the main thread.
     *
     * @param context The current [Context].
     * @param request The [PartnerAdLoadRequest] containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadRewardedAd(
        context: Context,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        // Save the listener for later use.
        listeners[request.identifier] = listener

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            CoroutineScope(Main).launch {
                val adm =
                    request.adm ?: run {
                        PartnerLogController.log(
                            LOAD_FAILED,
                            ChartboostMediationError.LoadError.InvalidAdMarkup.cause,
                        )
                        continuation.resumeWith(
                            Result.failure(
                                ChartboostMediationAdException(ChartboostMediationError.LoadError.InvalidAdMarkup),
                            ),
                        )
                        return@launch
                    }

                RewardedAd.load(
                    context,
                    request.partnerPlacement,
                    buildRequest(adm),
                    object : RewardedAdLoadCallback() {
                        override fun onAdLoaded(rewardedAd: RewardedAd) {
                            PartnerLogController.log(LOAD_SUCCEEDED)
                            resumeOnce(
                                Result.success(
                                    PartnerAd(
                                        ad = rewardedAd,
                                        details = emptyMap(),
                                        request = request,
                                    ),
                                ),
                            )
                        }

                        override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                            PartnerLogController.log(LOAD_FAILED, loadAdError.message)
                            resumeOnce(
                                Result.failure(
                                    ChartboostMediationAdException(
                                        getChartboostMediationError(
                                            loadAdError.code,
                                        ),
                                    ),
                                ),
                            )
                        }
                    },
                )
            }
        }
    }

    /**
     * Attempt to load a Google Bidding rewarded interstitial ad.
     *
     * @param context The current [Context].
     * @param request The [PartnerAdLoadRequest] containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadRewardedInterstitialAd(
        context: Context,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        // Save the listener for later use.
        listeners[request.identifier] = listener

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            CoroutineScope(Main).launch {
                val adm =
                    request.adm ?: run {
                        PartnerLogController.log(
                            LOAD_FAILED,
                            ChartboostMediationError.LoadError.InvalidAdMarkup.cause,
                        )
                        continuation.resumeWith(
                            Result.failure(
                                ChartboostMediationAdException(ChartboostMediationError.LoadError.InvalidAdMarkup),
                            ),
                        )
                        return@launch
                    }

                RewardedInterstitialAd.load(
                    context,
                    request.partnerPlacement,
                    buildRequest(adm),
                    object : RewardedInterstitialAdLoadCallback() {
                        override fun onAdLoaded(rewardedInterstitialAd: RewardedInterstitialAd) {
                            PartnerLogController.log(LOAD_SUCCEEDED)
                            resumeOnce(
                                Result.success(
                                    PartnerAd(
                                        ad = rewardedInterstitialAd,
                                        details = emptyMap(),
                                        request = request,
                                    ),
                                ),
                            )
                        }

                        override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                            PartnerLogController.log(LOAD_FAILED, loadAdError.message)
                            resumeOnce(
                                Result.failure(
                                    ChartboostMediationAdException(
                                        getChartboostMediationError(
                                            loadAdError.code,
                                        ),
                                    ),
                                ),
                            )
                        }
                    },
                )
            }
        }
    }

    /**
     * Attempt to show a Google Bidding interstitial ad on the main thread.
     *
     * @param activity The current [Activity].
     * @param partnerAd The [PartnerAd] object containing the Google Bidding ad to be shown.
     * @param listener The [PartnerAdListener] to be notified of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showInterstitialAd(
        activity: Activity,
        partnerAd: PartnerAd,
        listener: PartnerAdListener?,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            partnerAd.ad?.let { ad ->
                CoroutineScope(Main).launch {
                    val interstitialAd = ad as InterstitialAd

                    interstitialAd.fullScreenContentCallback =
                        InterstitialAdShowCallback(
                            listener,
                            partnerAd,
                            WeakReference(continuation),
                        )
                    interstitialAd.show(activity)
                }
            } ?: run {
                PartnerLogController.log(SHOW_FAILED, "Ad is null.")
                resumeOnce(
                    Result.failure(
                        ChartboostMediationAdException(
                            ChartboostMediationError.ShowError.AdNotFound,
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Attempt to show a Google Bidding rewarded ad on the main thread.
     *
     * @param activity The current [Activity].
     * @param partnerAd The [PartnerAd] object containing the Google Bidding ad to be shown.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showRewardedAd(
        activity: Activity,
        partnerAd: PartnerAd,
        listener: PartnerAdListener?,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            partnerAd.ad?.let { ad ->
                CoroutineScope(Main).launch {
                    val rewardedAd = ad as RewardedAd

                    rewardedAd.fullScreenContentCallback =
                        RewardedAdShowCallback(
                            listener,
                            partnerAd,
                            WeakReference(continuation),
                        )

                    rewardedAd.show(activity) {
                        PartnerLogController.log(DID_REWARD)
                        listener?.onPartnerAdRewarded(partnerAd)
                            ?: PartnerLogController.log(
                                CUSTOM,
                                "Unable to fire onPartnerAdRewarded for Google Bidding adapter.",
                            )
                    }
                }
            } ?: run {
                PartnerLogController.log(SHOW_FAILED, "Ad is null.")
                resumeOnce(
                    Result.failure(
                        ChartboostMediationAdException(
                            ChartboostMediationError.ShowError.AdNotFound,
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Attempt to show a Google Bidding rewarded interstitial ad on the main thread.
     *
     * @param activity The current [Activity].
     * @param partnerAd The [PartnerAd] object containing the Google Bidding ad to be shown.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showRewardedInterstitialAd(
        activity: Activity,
        partnerAd: PartnerAd,
        listener: PartnerAdListener?,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            partnerAd.ad?.let { ad ->
                CoroutineScope(Main).launch {
                    val rewardedInterstitialAd = ad as RewardedInterstitialAd

                    rewardedInterstitialAd.fullScreenContentCallback =
                        RewardedInterstitialAdShowCallback(
                            listener,
                            partnerAd,
                            WeakReference(continuation),
                        )

                    rewardedInterstitialAd.show(activity) {
                        PartnerLogController.log(DID_REWARD)
                        listener?.onPartnerAdRewarded(partnerAd)
                            ?: PartnerLogController.log(
                                CUSTOM,
                                "Unable to fire onPartnerAdRewarded for Google Bidding adapter.",
                            )
                    }
                }
            } ?: run {
                PartnerLogController.log(SHOW_FAILED, "Ad is null.")
                resumeOnce(
                    Result.failure(
                        ChartboostMediationAdException(
                            ChartboostMediationError.ShowError.AdNotFound,
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Destroy the current Google Bidding banner ad.
     *
     * @param partnerAd The [PartnerAd] object containing the Google Bidding ad to be destroyed.
     *
     * @return Result.success(PartnerAd) if the ad was successfully destroyed, Result.failure(Exception) otherwise.
     */
    private fun destroyBannerAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return partnerAd.ad?.let {
            if (it is AdView) {
                it.visibility = GONE
                it.destroy()

                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            } else {
                PartnerLogController.log(INVALIDATE_FAILED, "Ad is not an AdView.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.WrongResourceType))
            }
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.AdNotFound))
        }
    }

    /**
     * Get the equivalent Google Bidding ad format for a given Chartboost Mediation [PartnerAdFormat].
     *
     * @param format The Chartboost Mediation [PartnerAdFormat] to convert.
     *
     * @return The equivalent Google Bidding ad format.
     */
    private fun getGoogleBiddingAdFormat(format: PartnerAdFormat) =
        when (format) {
            PartnerAdFormats.BANNER -> com.google.android.gms.ads.AdFormat.BANNER
            PartnerAdFormats.INTERSTITIAL -> com.google.android.gms.ads.AdFormat.INTERSTITIAL
            PartnerAdFormats.REWARDED -> com.google.android.gms.ads.AdFormat.REWARDED
            else -> com.google.android.gms.ads.AdFormat.BANNER
        }

    /**
     * Build a Google Bidding ad request.
     *
     * @param adm The ad string to be used in the ad request.
     *
     * @return A Google Bidding [AdRequest] object.
     */
    private fun buildRequest(adm: String) =
        AdRequest.Builder()
            .setRequestAgent("Chartboost")
            .addNetworkExtrasBundle(AdMobAdapter::class.java, buildPrivacyConsents())
            .setAdString(adm)
            .build()

    /**
     * Build a [Bundle] containing privacy settings for the current ad request for Google Bidding.
     *
     * @return A [Bundle] containing privacy settings for the current ad request for Google Bidding.
     */
    private fun buildPrivacyConsents(): Bundle {
        return Bundle().apply {
            if (!allowPersonalizedAds) {
                putString("npa", "1")
            }

            if (restrictedDataProcessingEnabled) {
                putInt("rdp", 1)
            }

            if (!TextUtils.isEmpty(ccpaPrivacyString)) {
                putString("IABUSPrivacy_String", ccpaPrivacyString)
            }
        }
    }
}

/**
 * Callback class for interstitial ads.
 *
 * @param listener A [PartnerAdListener] to be notified of ad events.
 * @param partnerAd A [PartnerAd] object containing the Google Bidding ad to be shown.
 * @param continuationRef A [WeakReference] to the [CancellableContinuation] to be resumed once the ad is shown.
 */
private class InterstitialAdShowCallback(
    private val listener: PartnerAdListener?,
    private val partnerAd: PartnerAd,
    private val continuationRef: WeakReference<CancellableContinuation<Result<PartnerAd>>>,
) : FullScreenContentCallback() {
    override fun onAdImpression() {
        PartnerLogController.log(DID_TRACK_IMPRESSION)
        listener?.onPartnerAdImpression(partnerAd) ?: PartnerLogController.log(
            CUSTOM,
            "Unable to fire onPartnerAdImpression for Google Bidding adapter. Listener is null",
        )
    }

    override fun onAdFailedToShowFullScreenContent(adError: AdError) {
        PartnerLogController.log(SHOW_FAILED, adError.message)
        continuationRef.get()?.let {
            if (it.isActive) {
                it.resume(
                    Result.failure(
                        ChartboostMediationAdException(
                            getChartboostMediationError(adError.code),
                        ),
                    ),
                )
            }
        } ?: PartnerLogController.log(
            CUSTOM,
            "Unable to resume continuation in onAdFailedToShowFullScreenContent(). Continuation is null.",
        )
    }

    override fun onAdShowedFullScreenContent() {
        PartnerLogController.log(SHOW_SUCCEEDED)

        continuationRef.get()?.let { continuation ->
            if (continuation.isActive) {
                continuation.resume(Result.success(partnerAd))
            }
        } ?: PartnerLogController.log(
            CUSTOM,
            "Unable to resume continuation in onAdShowedFullScreenContent(). Continuation is null.",
        )
    }

    override fun onAdClicked() {
        PartnerLogController.log(DID_CLICK)
        listener?.onPartnerAdClicked(partnerAd) ?: PartnerLogController.log(
            CUSTOM,
            "Unable to fire onPartnerAdClicked for Google Bidding adapter. Listener is null",
        )
    }

    override fun onAdDismissedFullScreenContent() {
        PartnerLogController.log(DID_DISMISS)
        listener?.onPartnerAdDismissed(partnerAd, null) ?: PartnerLogController.log(
            CUSTOM,
            "Unable to fire onPartnerAdDismissed for Google Bidding adapter. Listener is null",
        )
    }
}

/**
 * Callback class for rewarded ads.
 *
 * @param listener A [PartnerAdListener] to be notified of ad events.
 * @param partnerAd A [PartnerAd] object containing the Google Bidding ad to be shown.
 * @param continuationRef A [WeakReference] to the [CancellableContinuation] to be resumed once the ad is shown.
 */
private class RewardedAdShowCallback(
    private val listener: PartnerAdListener?,
    private val partnerAd: PartnerAd,
    private val continuationRef: WeakReference<CancellableContinuation<Result<PartnerAd>>>,
) : FullScreenContentCallback() {
    override fun onAdImpression() {
        PartnerLogController.log(DID_TRACK_IMPRESSION)

        listener?.onPartnerAdImpression(partnerAd) ?: PartnerLogController.log(
            CUSTOM,
            "Unable to fire onPartnerAdImpression for Google Bidding adapter. Listener is null",
        )
    }

    override fun onAdFailedToShowFullScreenContent(adError: AdError) {
        PartnerLogController.log(SHOW_FAILED, adError.message)
        continuationRef.get()?.let {
            if (it.isActive) {
                it.resume(
                    Result.failure(
                        ChartboostMediationAdException(
                            getChartboostMediationError(adError.code),
                        ),
                    ),
                )
            }
        } ?: PartnerLogController.log(
            CUSTOM,
            "Unable to resume continuation in onAdFailedToShowFullScreenContent(). Continuation is null.",
        )
    }

    override fun onAdShowedFullScreenContent() {
        PartnerLogController.log(SHOW_SUCCEEDED)

        continuationRef.get()?.let { continuation ->
            if (continuation.isActive) {
                continuation.resume(Result.success(partnerAd))
            }
        } ?: PartnerLogController.log(
            CUSTOM,
            "Unable to resume continuation in onAdShowedFullScreenContent(). Continuation is null.",
        )
    }

    override fun onAdClicked() {
        PartnerLogController.log(DID_CLICK)

        listener?.onPartnerAdClicked(partnerAd) ?: PartnerLogController.log(
            CUSTOM,
            "Unable to fire onPartnerAdClicked for Google Bidding adapter. Listener is null",
        )
    }

    override fun onAdDismissedFullScreenContent() {
        PartnerLogController.log(DID_DISMISS)

        listener?.onPartnerAdDismissed(partnerAd, null) ?: PartnerLogController.log(
            CUSTOM,
            "Unable to fire onPartnerAdDismissed for Google Bidding adapter. Listener is null",
        )
    }
}

/**
 * Callback class for rewarded interstitial ads.
 *
 * @param listener A [PartnerAdListener] to be notified of ad events.
 * @param partnerAd A [PartnerAd] object containing the Google Bidding ad to be shown.
 * @param continuationRef A [WeakReference] to the [CancellableContinuation] to be resumed once the ad is shown.
 */
private class RewardedInterstitialAdShowCallback(
    private val listener: PartnerAdListener?,
    private val partnerAd: PartnerAd,
    private val continuationRef: WeakReference<CancellableContinuation<Result<PartnerAd>>>,
) : FullScreenContentCallback() {
    override fun onAdImpression() {
        PartnerLogController.log(DID_TRACK_IMPRESSION)
        listener?.onPartnerAdImpression(partnerAd) ?: PartnerLogController.log(
            CUSTOM,
            "Unable to fire onPartnerAdImpression for Google Bidding adapter. Listener is null",
        )
    }

    override fun onAdFailedToShowFullScreenContent(adError: AdError) {
        PartnerLogController.log(SHOW_FAILED, adError.message)
        continuationRef.get()?.let {
            if (it.isActive) {
                it.resume(
                    Result.failure(
                        ChartboostMediationAdException(
                            getChartboostMediationError(adError.code),
                        ),
                    ),
                )
            }
        } ?: PartnerLogController.log(
            CUSTOM,
            "Unable to resume continuation in onAdFailedToShowFullScreenContent(). Continuation is null.",
        )
    }

    override fun onAdShowedFullScreenContent() {
        PartnerLogController.log(SHOW_SUCCEEDED)
        continuationRef.get()?.let { continuation ->
            if (continuation.isActive) {
                continuation.resume(Result.success(partnerAd))
            }
        } ?: PartnerLogController.log(
            CUSTOM,
            "Unable to resume continuation in onAdShowedFullScreenContent(). Continuation is null.",
        )
    }

    override fun onAdClicked() {
        PartnerLogController.log(DID_CLICK)

        listener?.onPartnerAdClicked(partnerAd) ?: PartnerLogController.log(
            CUSTOM,
            "Unable to fire onPartnerAdClicked for Google Bidding adapter. Listener is null",
        )
    }

    override fun onAdDismissedFullScreenContent() {
        PartnerLogController.log(DID_DISMISS)

        listener?.onPartnerAdDismissed(partnerAd, null) ?: PartnerLogController.log(
            CUSTOM,
            "Unable to fire onPartnerAdDismissed for Google Bidding adapter. Listener is null",
        )
    }
}
