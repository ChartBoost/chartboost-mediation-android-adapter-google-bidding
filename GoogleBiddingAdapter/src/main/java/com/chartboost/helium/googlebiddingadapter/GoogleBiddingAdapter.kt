package com.chartboost.helium.googlebiddingadapter

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Size
import android.view.View.GONE
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.domain.AdFormat
import com.chartboost.heliumsdk.utils.LogController
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.*
import com.google.android.gms.ads.initialization.AdapterStatus
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.query.AdInfo
import com.google.android.gms.ads.query.QueryInfo
import com.google.android.gms.ads.query.QueryInfoGenerationCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class GoogleBiddingAdapter : PartnerAdapter {
    companion object {
        /**
         * List containing device IDs to be set for enabling Google Bidding test ads. It can be populated at
         * any time and it will take effect for the next ad request. Remember to empty this list or
         * stop setting it before releasing your app.
         */
        public var testDeviceIds = listOf<String>()
            set(value) {
                field = value
                LogController.d(
                    "$TAG Google Bidding test device ID(s) to be set: ${
                        if (value.isEmpty()) "none"
                        else value.joinToString()
                    }"
                )
                MobileAds.setRequestConfiguration(
                    RequestConfiguration.Builder().setTestDeviceIds(value).build()
                )
            }

        /**
         * The tag used for log messages.
         */
        private val TAG = "[${this::class.java.simpleName}]"
    }

    /**
     * A map of Helium's listeners for the corresponding Helium placements.
     */
    private val listeners = mutableMapOf<String, PartnerAdListener>()

    /**
     * A cache storing <placement names, QueryInfo> pairs generated by Google Bidding so we can look up the
     * corresponding QueryInfo for each ad request.
     */
    private var placementToQueryInfoCache: Cache<String, QueryInfo>? = null

    /**
     * Indicate whether GDPR currently applies to the user.
     */
    private var gdprApplies: Boolean? = null

    /**
     * Indicate whether the user has consented to allowing personalized ads when GDPR applies.
     */
    private var allowPersonalizedAds = false

    /**
     * Indicate whether the user has given consent per CCPA.
     */
    private var ccpaPrivacyString: String? = null

    /**
     * Get the Google Mobile Ads SDK version.
     *
     * Note that the version string will be in the format of afma-sdk-a-v221908999.214106000.1.
     */
    override val partnerSdkVersion: String
        get() = MobileAds.getVersion().toString()

    /**
     * Get the Google Bidding adapter version.
     *
     * Note that the version string will be in the format of `Helium.Partner.Partner.Partner.Adapter`,
     * in which `Helium` is the version of the Helium SDK, `Partner` is the major.minor.patch version
     * of the partner SDK, and `Adapter` is the version of the adapter.
     */
    override val adapterVersion: String
        get() = BuildConfig.HELIUM_GOOGLE_BIDDING_ADAPTER_VERSION

    /**
     * Get the partner name for internal uses.
     */
    override val partnerId: String
        get() = "google_googlebidding"

    /**
     * Get the partner name for external uses.
     */
    override val partnerDisplayName: String
        get() = "Google Bidding"

    /**
     * Initialize the Google Mobile Ads SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize Google Bidding.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration
    ): Result<Unit> {
        // Since Helium is the mediator, no need to initialize Google Bidding's partner SDKs.
        // https://developers.google.com/android/reference/com/google/android/gms/ads/MobileAds?hl=en#disableMediationAdapterInitialization(android.content.Context)
        MobileAds.disableMediationAdapterInitialization(context)

        return suspendCoroutine { continuation ->
            MobileAds.initialize(context) { status ->
                continuation.resume(getInitResult(status.adapterStatusMap[MobileAds::class.java.name]))
            }
        }
    }

    /**
     * Save the current GDPR applicability state for later use.
     *
     * @param context The current [Context].
     * @param gdprApplies True if GDPR applies, false otherwise.
     */
    override fun setGdprApplies(context: Context, gdprApplies: Boolean) {
        this.gdprApplies = gdprApplies
    }

    /**
     * Get whether to allow personalized ads based on the user's GDPR consent status.
     *
     * @param context The current [Context].
     * @param gdprConsentStatus The user's current GDPR consent status.
     */
    override fun setGdprConsentStatus(context: Context, gdprConsentStatus: GdprConsentStatus) {
        if (gdprApplies == true) {
            allowPersonalizedAds = gdprConsentStatus == GdprConsentStatus.GDPR_CONSENT_GRANTED
        }
    }

    /**
     * Save the current CCPA privacy String to be used later.
     *
     * @param context The current [Context].
     * @param hasGivenCcpaConsent True if the user has given CCPA consent, false otherwise.
     * @param privacyString The CCPA privacy String.
     */
    override fun setCcpaConsent(context: Context, hasGivenCcpaConsent: Boolean, privacyString: String?) {
        ccpaPrivacyString = privacyString
    }

    /**
     * Notify Google Bidding of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isSubjectToCoppa True if the user is subject to COPPA, false otherwise.
     */
    override fun setUserSubjectToCoppa(context: Context, isSubjectToCoppa: Boolean) {
        MobileAds.setRequestConfiguration(
            MobileAds.getRequestConfiguration().toBuilder()
                .setTagForChildDirectedTreatment(
                    if (isSubjectToCoppa) {
                        RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_TRUE
                    } else {
                        RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE
                    }
                ).build()
        )
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PreBidRequest
    ): Map<String, String> {
        // Google-defined specs for Helium
        val extras = Bundle()
        extras.putString("query_info_type", "requester_type_2")

        val adRequest = AdRequest.Builder()
            .setRequestAgent("Helium")
            .addNetworkExtrasBundle(AdMobAdapter::class.java, extras)
            .build()

        val adFormat = getGoogleBiddingAdFormat(request.format)

        return suspendCoroutine { continuation ->
            CoroutineScope(Dispatchers.IO).launch {
                QueryInfo.generate(
                    context,
                    adFormat,
                    adRequest,
                    object : QueryInfoGenerationCallback() {
                        override fun onSuccess(queryInfo: QueryInfo) {
                            // Cache the QueryInfo so it can be looked up by the Helium placement name.
                            placementToQueryInfoCache?.put(request.heliumPlacement, queryInfo)

                            continuation.resumeWith(
                                Result.success(
                                    mapOf("token" to queryInfo.query)
                                )
                            )
                        }

                        override fun onFailure(error: String) {
                            continuation.resumeWith(
                                Result.success(
                                    emptyMap()
                                )
                            )
                        }
                    }
                )
            }
        }
    }

    /**
     * Attempt to load a Google Bidding ad.
     *
     * @param context The current [Context].
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: AdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        return when (request.format) {
            AdFormat.INTERSTITIAL -> loadInterstitialAd(
                context,
                request,
                partnerAdListener
            )
            AdFormat.REWARDED -> loadRewardedAd(
                context,
                request,
                partnerAdListener
            )
            AdFormat.BANNER -> loadBannerAd(
                context,
                request,
                partnerAdListener
            )
        }
    }

    /**
     * Attempt to show the currently loaded Google Bidding ad.
     *
     * @param context The current [Context]
     * @param partnerAd The [PartnerAd] object containing the Google Bidding ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(context: Context, partnerAd: PartnerAd): Result<PartnerAd> {
        val listener = listeners.remove(partnerAd.request.heliumPlacement)

        return when (partnerAd.request.format) {
            // Banner ads do not have a separate "show" mechanism.
            AdFormat.BANNER -> Result.success(partnerAd)
            AdFormat.INTERSTITIAL -> showInterstitialAd(context, partnerAd, listener)
            AdFormat.REWARDED -> showRewardedAd(context, partnerAd, listener)
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
        listeners.remove(partnerAd.request.heliumPlacement)

        // Only invalidate banners as there are no explicit methods to invalidate the other formats.
        return when (partnerAd.request.format) {
            AdFormat.BANNER -> destroyBannerAd(partnerAd)
            else -> Result.success(partnerAd)
        }
    }

    /**
     * Get a [Result] containing the initialization result of the Google Mobile Ads SDK.
     *
     * @param status The initialization status of the Google Mobile Ads SDK.
     *
     * @return A [Result] object containing details about the initialization result.
     */
    private fun getInitResult(status: AdapterStatus?): Result<Unit> {
        return status?.let { it ->
            if (it.initializationState == AdapterStatus.State.READY) {
                setUpQueryInfoCache()
                Result.success(LogController.i("$TAG Google Bidding successfully initialized."))
            } else {
                LogController.e(
                    "$TAG Google Bidding failed to initialize. Initialization state: " +
                            "$it.initializationState. Description: $it.description\""
                )
                Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_SDK_NOT_INITIALIZED))
            }
        } ?: run {
            LogController.e("$TAG Google Bidding failed to initialize. Initialization status is null.")
            Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_SDK_NOT_INITIALIZED))
        }
    }

    /**
     * Attempt to load a Google Bidding banner on the main thread.
     *
     * @param context The current [Context].
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     */
    private suspend fun loadBannerAd(
        context: Context,
        request: AdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            CoroutineScope(Main).launch {
                val adInfo = constructAdInfo(request) ?: run {
                    continuation.resumeWith(
                        Result.failure(
                            HeliumAdException(HeliumErrorCode.INVALID_BID_PAYLOAD)
                        )
                    )
                    return@launch
                }

                val bannerAd = AdView(context)
                val partnerAd = PartnerAd(
                    ad = bannerAd,
                    details = emptyMap(),
                    request = request,
                )

                bannerAd.setAdSize(getGoogleBiddingAdSize(request.size))
                bannerAd.adUnitId = request.partnerPlacement
                bannerAd.loadAd(buildRequest(adInfo.adString, adInfo))
                bannerAd.adListener = object : AdListener() {
                    override fun onAdImpression() {
                        listener.onPartnerAdImpression(partnerAd)

                        continuation.resume(Result.success(partnerAd))
                    }

                    override fun onAdLoaded() {
                        continuation.resume(Result.success(partnerAd))
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        LogController.e("$TAG Failed to load Google Bidding banner: ${adError.message}")
                        continuation.resume(
                            Result.failure(HeliumAdException(getHeliumErrorCode(adError.code)))
                        )
                    }

                    override fun onAdOpened() {
                        // NO-OP
                    }

                    override fun onAdClicked() {
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
     * @param size The [Size] to parse for conversion.
     *
     * @return The Google Bidding ad size that best matches the given [Size].
     */
    private fun getGoogleBiddingAdSize(size: Size?): AdSize {
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
     * @param request An [AdLoadRequest] instance containing data to load the ad with.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadInterstitialAd(
        context: Context,
        request: AdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        // Save the listener for later use.
        listeners[request.heliumPlacement] = listener

        return suspendCoroutine { continuation ->
            CoroutineScope(Main).launch {
                val adInfo = constructAdInfo(request) ?: run {
                    continuation.resumeWith(
                        Result.failure(
                            HeliumAdException(HeliumErrorCode.INVALID_BID_PAYLOAD)
                        )
                    )
                    return@launch
                }

                InterstitialAd.load(context,
                    request.partnerPlacement,
                    buildRequest(adInfo.adString, adInfo),
                    object : InterstitialAdLoadCallback() {
                        override fun onAdLoaded(interstitialAd: InterstitialAd) {
                            continuation.resume(
                                Result.success(
                                    PartnerAd(
                                        ad = interstitialAd,
                                        details = emptyMap(),
                                        request = request
                                    )
                                )
                            )
                        }

                        override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                            LogController.e("$TAG Failed to load Google Bidding interstitial ad: ${loadAdError.message}")
                            continuation.resume(
                                Result.failure(HeliumAdException(getHeliumErrorCode(loadAdError.code)))
                            )
                        }
                    }
                )
            }
        }
    }

    /**
     * Attempt to load a Google Bidding rewarded ad on the main thread.
     *
     * @param context The current [Context].
     * @param request The [AdLoadRequest] containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadRewardedAd(
        context: Context,
        request: AdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        // Save the listener for later use.
        listeners[request.heliumPlacement] = listener

        return suspendCoroutine { continuation ->
            CoroutineScope(Main).launch {
                val adInfo = constructAdInfo(request) ?: run {
                    continuation.resumeWith(
                        Result.failure(
                            HeliumAdException(HeliumErrorCode.INVALID_BID_PAYLOAD)
                        )
                    )
                    return@launch
                }

                RewardedAd.load(context,
                    request.partnerPlacement,
                    buildRequest(adInfo.adString, adInfo),
                    object : RewardedAdLoadCallback() {
                        override fun onAdLoaded(rewardedAd: RewardedAd) {
                            continuation.resume(
                                Result.success(
                                    PartnerAd(
                                        ad = rewardedAd,
                                        details = emptyMap(),
                                        request = request
                                    )
                                )
                            )
                        }

                        override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                            LogController.e("$TAG Failed to load Google Bidding rewarded ad: ${loadAdError.message}")
                            continuation.resume(
                                Result.failure(
                                    HeliumAdException(getHeliumErrorCode(loadAdError.code))
                                )
                            )
                        }
                    }
                )
            }
        }
    }

    /**
     * Attempt to show a Google Bidding interstitial ad on the main thread.
     *
     * @param context The current [Context].
     * @param partnerAd The [PartnerAd] object containing the Google Bidding ad to be shown.
     * @param listener The [PartnerAdListener] to be notified of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showInterstitialAd(
        context: Context,
        partnerAd: PartnerAd,
        listener: PartnerAdListener?
    ): Result<PartnerAd> {
        if (context !is Activity) {
            LogController.e("$TAG Failed to show Google Bidding interstitial ad. Context is not an Activity.")
            return Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
        }

        return suspendCoroutine { continuation ->
            partnerAd.ad?.let { ad ->
                CoroutineScope(Main).launch {
                    val interstitialAd = ad as InterstitialAd
                    interstitialAd.fullScreenContentCallback =
                        object : FullScreenContentCallback() {
                            override fun onAdImpression() {
                                listener?.onPartnerAdImpression(partnerAd) ?: LogController.d(
                                    "$TAG Unable to fire onPartnerAdImpression for Google Bidding adapter."
                                )
                                continuation.resume(Result.success(partnerAd))
                            }

                            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                                LogController.e(
                                    "$TAG Failed to show Google Bidding interstitial ad. " +
                                            "Error: ${adError.message}"
                                )
                                continuation.resume(
                                    Result.failure(HeliumAdException(getHeliumErrorCode(adError.code)))
                                )
                            }

                            override fun onAdShowedFullScreenContent() {
                                continuation.resume(Result.success(partnerAd))
                            }

                            override fun onAdDismissedFullScreenContent() {
                                listener?.onPartnerAdDismissed(partnerAd, null)
                                    ?: LogController.d(
                                        "$TAG Unable to fire onPartnerAdDismissed for Google Bidding adapter."
                                    )
                            }
                        }
                    interstitialAd.show(context)
                }
            } ?: run {
                LogController.e("$TAG Failed to show Google Bidding interstitial ad. Ad is null.")
                continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL)))
            }
        }
    }

    /**
     * Attempt to show a Google Bidding rewarded ad on the main thread.
     *
     * @param context The current [Context].
     * @param partnerAd The [PartnerAd] object containing the Google Bidding ad to be shown.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showRewardedAd(
        context: Context,
        partnerAd: PartnerAd,
        listener: PartnerAdListener?
    ): Result<PartnerAd> {
        if (context !is Activity) {
            LogController.e("$TAG Failed to show Google Bidding rewarded ad. Context is not an Activity.")
            return Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
        }

        return suspendCoroutine { continuation ->
            partnerAd.ad?.let { ad ->
                CoroutineScope(Main).launch {
                    val rewardedAd = ad as RewardedAd
                    rewardedAd.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdImpression() {
                            listener?.onPartnerAdImpression(partnerAd) ?: LogController.d(
                                "$TAG Unable to fire onPartnerAdImpression for Google Bidding adapter."
                            )
                            continuation.resume(Result.success(partnerAd))
                        }

                        override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                            LogController.e("$TAG Failed to show Google Bidding rewarded ad. Error: ${adError.message}")
                            continuation.resume(
                                Result.failure(HeliumAdException(getHeliumErrorCode(adError.code)))
                            )
                        }

                        override fun onAdShowedFullScreenContent() {
                            continuation.resume(Result.success(partnerAd))
                        }

                        override fun onAdDismissedFullScreenContent() {
                            listener?.onPartnerAdDismissed(partnerAd, null) ?: LogController.d(
                                "$TAG Unable to fire onPartnerAdDismissed for Google Bidding adapter."
                            )
                        }
                    }

                    rewardedAd.show(context) { reward ->
                        listener?.onPartnerAdRewarded(partnerAd, Reward(reward.amount, reward.type))
                            ?: LogController.d(
                                "$TAG Unable to fire onPartnerAdRewarded for Google Bidding adapter."
                            )
                    }
                }
            } ?: run {
                LogController.e("$TAG Failed to show Google Bidding rewarded ad. Ad is null.")
                continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL)))
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
                Result.success(partnerAd)
            } else {
                LogController.e("$TAG Failed to destroy Google Bidding banner ad. Ad is not an AdView.")
                Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
            }
        } ?: run {
            LogController.e("$TAG Failed to destroy Google Bidding banner ad. Ad is null.")
            Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
        }
    }

    /**
     * Get the equivalent Google Bidding ad format for a given Helium [AdFormat].
     *
     * @param format The Helium [AdFormat] to convert.
     *
     * @return The equivalent Google Bidding ad format.
     */
    private fun getGoogleBiddingAdFormat(format: AdFormat) = when (format) {
        AdFormat.BANNER -> com.google.android.gms.ads.AdFormat.BANNER
        AdFormat.INTERSTITIAL -> com.google.android.gms.ads.AdFormat.INTERSTITIAL
        AdFormat.REWARDED -> com.google.android.gms.ads.AdFormat.REWARDED
        else -> com.google.android.gms.ads.AdFormat.BANNER
    }

    /**
     * Set up a [QueryInfo] cache with an expiration.
     */
    private fun setUpQueryInfoCache() {
        placementToQueryInfoCache = CacheBuilder.newBuilder()
            .expireAfterWrite(
                AppConfig.googleBiddingCacheTimeoutMinutes,
                TimeUnit.MINUTES
            )
            .build()
    }

    /**
     * Construct an [AdInfo] object for network bidding purposes.
     *
     * @param request The [AdLoadRequest] instance containing data about the current ad load call.
     *
     * @return An [AdInfo] object containing biddable data for Google Bidding.
     */
    private fun constructAdInfo(request: AdLoadRequest): AdInfo? {
        val adm = request.adm ?: run {
            LogController.e(
                "$TAG Failed to load Google Bidding ${request.format} ad. Ad string" +
                        " is null."
            )
            return null
        }

        val queryInfo = placementToQueryInfoCache?.let { cache ->
            cache.getIfPresent(request.partnerPlacement) ?: run {
                LogController.e(
                    "$TAG Failed to load Google Bidding ${request.format} ad. QueryInfo is null."
                )
                return null
            }
        } ?: run {
            LogController.e(
                "$TAG Failed to load Google Bidding ${request.format} ad. QueryInfo cache is null."
            )
            return null
        }

        // We've just used that QueryInfo. Evict its entry from the cache.
        placementToQueryInfoCache?.invalidate(request.partnerPlacement)

        return AdInfo(queryInfo, adm)
    }

    /**
     * Build a Google Bidding ad request.
     *
     * @param adm The ad string to be used in the ad request.
     * @param adInfo The [AdInfo] object containing biddable data for Google Bidding.
     *
     * @return A Google Bidding [AdRequest] object.
     */
    private fun buildRequest(adm: String, adInfo: AdInfo) =
        AdRequest.Builder()
            .setAdInfo(adInfo)
            .setRequestAgent("Helium")
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

            if (!TextUtils.isEmpty(ccpaPrivacyString)) {
                putString("IABUSPrivacy_String", ccpaPrivacyString)
            }
        }
    }

    /**
     * Convert a given Google Bidding error code into a [HeliumErrorCode].
     *
     * @param error The Google Bidding error code as an [Int].
     *
     * @return The corresponding [HeliumErrorCode].
     */
    private fun getHeliumErrorCode(error: Int): HeliumErrorCode {
        return when (error) {
            AdRequest.ERROR_CODE_APP_ID_MISSING -> HeliumErrorCode.INVALID_CONFIG
            AdRequest.ERROR_CODE_INTERNAL_ERROR -> HeliumErrorCode.INTERNAL
            AdRequest.ERROR_CODE_INVALID_AD_STRING -> HeliumErrorCode.INVALID_BID_PAYLOAD
            AdRequest.ERROR_CODE_INVALID_REQUEST -> HeliumErrorCode.PARTNER_ERROR
            AdRequest.ERROR_CODE_NETWORK_ERROR -> HeliumErrorCode.NO_CONNECTIVITY
            AdRequest.ERROR_CODE_NO_FILL -> HeliumErrorCode.NO_FILL
            AdRequest.ERROR_CODE_REQUEST_ID_MISMATCH -> HeliumErrorCode.INVALID_CREDENTIALS
            else -> HeliumErrorCode.INTERNAL
        }
    }

    /**
     * Util method to convert a pixels value to a density-independent pixels value.
     *
     * @param pixels The pixels value to convert.
     * @param context The context to use for density conversion.
     *
     * @return The converted density-independent pixels value as a Float.
     */
    private fun convertPixelsToDp(pixels: Int, context: Context): Float {
        return pixels / (context.resources.displayMetrics.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT)
    }
}
