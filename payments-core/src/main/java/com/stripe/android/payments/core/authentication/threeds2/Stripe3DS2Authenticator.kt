package com.stripe.android.payments.core.authentication.threeds2

import android.content.Context
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.VisibleForTesting
import com.stripe.android.PaymentAuthConfig
import com.stripe.android.PaymentRelayStarter
import com.stripe.android.StripePaymentController
import com.stripe.android.exception.StripeException
import com.stripe.android.model.Stripe3ds2AuthParams
import com.stripe.android.model.Stripe3ds2AuthResult
import com.stripe.android.model.Stripe3ds2Fingerprint
import com.stripe.android.model.StripeIntent
import com.stripe.android.networking.AnalyticsEvent
import com.stripe.android.networking.AnalyticsRequestExecutor
import com.stripe.android.networking.AnalyticsRequestFactory
import com.stripe.android.networking.ApiRequest
import com.stripe.android.networking.StripeRepository
import com.stripe.android.payments.DefaultStripeChallengeStatusReceiver
import com.stripe.android.payments.PaymentFlowResult
import com.stripe.android.payments.Stripe3ds2CompletionContract
import com.stripe.android.payments.Stripe3ds2CompletionStarter
import com.stripe.android.payments.core.authentication.PaymentAuthenticator
import com.stripe.android.payments.core.authentication.WebIntentAuthenticator
import com.stripe.android.stripe3ds2.init.ui.StripeUiCustomization
import com.stripe.android.stripe3ds2.service.StripeThreeDs2Service
import com.stripe.android.stripe3ds2.transaction.ChallengeParameters
import com.stripe.android.stripe3ds2.transaction.MessageVersionRegistry
import com.stripe.android.stripe3ds2.transaction.SdkTransactionId
import com.stripe.android.stripe3ds2.transaction.Stripe3ds2ActivityStarterHost
import com.stripe.android.stripe3ds2.transaction.Transaction
import com.stripe.android.stripe3ds2.views.ChallengeProgressActivity
import com.stripe.android.view.AuthActivityStarterHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.cert.CertificateException
import kotlin.coroutines.CoroutineContext

/**
 * [PaymentAuthenticator] authenticating through Stripe's 3ds2 SDK.
 */
internal class Stripe3DS2Authenticator(
    private val config: PaymentAuthConfig,
    private val stripeRepository: StripeRepository,
    private val webIntentAuthenticator: WebIntentAuthenticator,
    private val paymentRelayStarterFactory: (AuthActivityStarterHost) -> PaymentRelayStarter,
    private val analyticsRequestExecutor: AnalyticsRequestExecutor,
    private val analyticsRequestFactory: AnalyticsRequestFactory,
    private val workContext: CoroutineContext,
    private val uiContext: CoroutineContext,
    private val threeDs2Service: StripeThreeDs2Service,
    private val messageVersionRegistry: MessageVersionRegistry,
    private val challengeProgressActivityStarter: ChallengeProgressActivityStarter
) : PaymentAuthenticator<StripeIntent> {
    init {
        threeDs2Service.initialize(config.stripe3ds2Config.uiCustomization.uiCustomization)
    }

    /**
     * [stripe3ds2ChallengeLauncher] is mutable and might be updated during
     * through [onNewActivityResultCaller]
     */
    private var stripe3ds2ChallengeLauncher: ActivityResultLauncher<PaymentFlowResult.Unvalidated>? =
        null
    val stripe3ds2CompletionStarterFactory =
        { host: AuthActivityStarterHost, requestCode: Int ->
            stripe3ds2ChallengeLauncher?.let {
                Stripe3ds2CompletionStarter.Modern(it)
            } ?: Stripe3ds2CompletionStarter.Legacy(host, requestCode)
        }

    override suspend fun authenticate(
        host: AuthActivityStarterHost,
        authenticatable: StripeIntent,
        requestOptions: ApiRequest.Options
    ) {
        handle3ds2Auth(
            host,
            authenticatable,
            requestOptions,
            authenticatable.nextActionData as StripeIntent.NextActionData.SdkData.Use3DS2
        )
    }

    override fun onNewActivityResultCaller(
        activityResultCaller: ActivityResultCaller,
        activityResultCallback: ActivityResultCallback<PaymentFlowResult.Unvalidated>
    ) {
        stripe3ds2ChallengeLauncher = activityResultCaller.registerForActivityResult(
            Stripe3ds2CompletionContract(),
            activityResultCallback
        )
    }

    override fun onLauncherInvalidated() {
        stripe3ds2ChallengeLauncher?.unregister()
        stripe3ds2ChallengeLauncher = null
    }

    private suspend fun handle3ds2Auth(
        host: AuthActivityStarterHost,
        stripeIntent: StripeIntent,
        requestOptions: ApiRequest.Options,
        nextActionData: StripeIntent.NextActionData.SdkData.Use3DS2
    ) {
        analyticsRequestExecutor.executeAsync(
            analyticsRequestFactory.createRequest(AnalyticsEvent.Auth3ds2Fingerprint)
        )
        try {
            begin3ds2Auth(
                host,
                stripeIntent,
                Stripe3ds2Fingerprint(nextActionData),
                requestOptions
            )
        } catch (e: CertificateException) {
            handleError(
                host,
                StripePaymentController.getRequestCode(stripeIntent),
                e
            )
        }
    }

    private suspend fun handleError(
        host: AuthActivityStarterHost,
        requestCode: Int,
        throwable: Throwable
    ) = withContext(uiContext) {
        paymentRelayStarterFactory(host)
            .start(
                PaymentRelayStarter.Args.ErrorArgs(
                    StripeException.create(throwable),
                    requestCode
                )
            )
    }

    private suspend fun begin3ds2Auth(
        host: AuthActivityStarterHost,
        stripeIntent: StripeIntent,
        stripe3ds2Fingerprint: Stripe3ds2Fingerprint,
        requestOptions: ApiRequest.Options
    ) {
        val transaction = threeDs2Service.createTransaction(
            stripe3ds2Fingerprint.directoryServerEncryption.directoryServerId,
            messageVersionRegistry.current, stripeIntent.isLiveMode,
            stripe3ds2Fingerprint.directoryServerName,
            stripe3ds2Fingerprint.directoryServerEncryption.rootCerts,
            stripe3ds2Fingerprint.directoryServerEncryption.directoryServerPublicKey,
            stripe3ds2Fingerprint.directoryServerEncryption.keyId
        )

        when (host) {
            is AuthActivityStarterHost.ActivityHost -> {
                challengeProgressActivityStarter.start(
                    host.activity,
                    stripe3ds2Fingerprint.directoryServerName,
                    false,
                    config.stripe3ds2Config.uiCustomization.uiCustomization,
                    transaction.sdkTransactionId
                )
            }
            is AuthActivityStarterHost.FragmentHost -> {
                challengeProgressActivityStarter.start(
                    host.fragment.requireActivity(),
                    stripe3ds2Fingerprint.directoryServerName,
                    false,
                    config.stripe3ds2Config.uiCustomization.uiCustomization,
                    transaction.sdkTransactionId
                )
            }
        }

        val paymentRelayStarter = paymentRelayStarterFactory(host)
        val timeout = config.stripe3ds2Config.timeout
        runCatching {
            perform3ds2AuthenticationRequest(
                transaction,
                stripe3ds2Fingerprint,
                requestOptions,
                timeout
            )
        }.fold(
            onSuccess = { authResult ->
                on3ds2AuthSuccess(
                    authResult,
                    transaction,
                    stripe3ds2Fingerprint.source,
                    timeout,
                    paymentRelayStarter,
                    StripePaymentController.getRequestCode(stripeIntent),
                    host,
                    stripeIntent,
                    requestOptions
                )
            },
            onFailure = { throwable ->
                on3ds2AuthFailure(
                    throwable,
                    StripePaymentController.getRequestCode(stripeIntent),
                    paymentRelayStarter
                )
            }
        )
    }

    /**
     * Fire the 3DS2 AReq.
     */
    private suspend fun perform3ds2AuthenticationRequest(
        transaction: Transaction,
        stripe3ds2Fingerprint: Stripe3ds2Fingerprint,
        requestOptions: ApiRequest.Options,
        timeout: Int
    ) = withContext(workContext) {
        val areqParams = transaction.createAuthenticationRequestParameters()

        val authParams = Stripe3ds2AuthParams(
            stripe3ds2Fingerprint.source,
            areqParams.sdkAppId,
            areqParams.sdkReferenceNumber,
            areqParams.sdkTransactionId.value,
            areqParams.deviceData,
            areqParams.sdkEphemeralPublicKey,
            areqParams.messageVersion,
            timeout,
            // We do not currently have a fallback url
            // TODO(smaskell-stripe): Investigate more robust error handling
            returnUrl = null
        )

        requireNotNull(
            stripeRepository.start3ds2Auth(
                authParams,
                requestOptions
            )
        )
    }

    @VisibleForTesting
    internal suspend fun on3ds2AuthSuccess(
        result: Stripe3ds2AuthResult,
        transaction: Transaction,
        sourceId: String,
        timeout: Int,
        paymentRelayStarter: PaymentRelayStarter,
        requestCode: Int,
        host: AuthActivityStarterHost,
        stripeIntent: StripeIntent,
        requestOptions: ApiRequest.Options
    ) {
        val ares = result.ares
        if (ares != null) {
            if (ares.isChallenge) {
                startChallengeFlow(
                    ares,
                    transaction,
                    sourceId,
                    timeout,
                    host,
                    stripeIntent,
                    requestOptions
                )
            } else {
                startFrictionlessFlow(
                    paymentRelayStarter,
                    stripeIntent
                )
            }
        } else if (result.fallbackRedirectUrl != null) {
            on3ds2AuthFallback(
                result.fallbackRedirectUrl,
                host,
                stripeIntent,
                requestOptions
            )
        } else {
            val errorMessage = result.error?.let { error ->
                listOf(
                    "Code: ${error.errorCode}",
                    "Detail: ${error.errorDetail}",
                    "Description: ${error.errorDescription}",
                    "Component: ${error.errorComponent}"
                ).joinToString(separator = ", ")
            } ?: "Invalid 3DS2 authentication response"

            on3ds2AuthFailure(
                RuntimeException(
                    "Error encountered during 3DS2 authentication request. $errorMessage"
                ),
                requestCode,
                paymentRelayStarter
            )
        }
    }

    /**
     * Used when standard 3DS2 authentication mechanisms are unavailable.
     */
    private suspend fun on3ds2AuthFallback(
        fallbackRedirectUrl: String,
        host: AuthActivityStarterHost,
        stripeIntent: StripeIntent,
        requestOptions: ApiRequest.Options
    ) {
        analyticsRequestExecutor.executeAsync(
            analyticsRequestFactory.createRequest(AnalyticsEvent.Auth3ds2Fallback)
        )

        webIntentAuthenticator.beginWebAuth(
            host,
            stripeIntent,
            StripePaymentController.getRequestCode(stripeIntent),
            stripeIntent.clientSecret.orEmpty(),
            fallbackRedirectUrl,
            requestOptions.stripeAccount,
            // 3D-Secure requires cancelling the source when the user cancels auth (AUTHN-47)
            shouldCancelSource = true
        )
    }

    private suspend fun on3ds2AuthFailure(
        throwable: Throwable,
        requestCode: Int,
        paymentRelayStarter: PaymentRelayStarter
    ) = withContext(uiContext) {
        paymentRelayStarter.start(
            PaymentRelayStarter.Args.ErrorArgs(
                StripeException.create(throwable),
                requestCode
            )
        )
    }

    private suspend fun startFrictionlessFlow(
        paymentRelayStarter: PaymentRelayStarter,
        stripeIntent: StripeIntent
    ) = withContext(uiContext) {
        analyticsRequestExecutor.executeAsync(
            analyticsRequestFactory.createRequest(AnalyticsEvent.Auth3ds2Frictionless)
        )
        paymentRelayStarter.start(
            PaymentRelayStarter.Args.create(stripeIntent)
        )
    }

    @VisibleForTesting
    internal suspend fun startChallengeFlow(
        ares: Stripe3ds2AuthResult.Ares,
        transaction: Transaction,
        sourceId: String,
        maxTimeout: Int,
        host: AuthActivityStarterHost,
        stripeIntent: StripeIntent,
        requestOptions: ApiRequest.Options
    ) = withContext(workContext) {
        when (host) {
            is AuthActivityStarterHost.ActivityHost -> {
                Stripe3ds2ActivityStarterHost(host.activity)
            }
            is AuthActivityStarterHost.FragmentHost -> {
                Stripe3ds2ActivityStarterHost(host.fragment)
            }
        }.let { stripe3ds2Host ->
            delay(StripePaymentController.CHALLENGE_DELAY)

            transaction.doChallenge(
                stripe3ds2Host,
                ChallengeParameters(
                    acsSignedContent = ares.acsSignedContent,
                    threeDsServerTransactionId = ares.threeDSServerTransId,
                    acsTransactionId = ares.acsTransId
                ),
                DefaultStripeChallengeStatusReceiver(
                    stripe3ds2CompletionStarterFactory(
                        host,
                        StripePaymentController.getRequestCode(stripeIntent)
                    ),
                    stripeRepository,
                    stripeIntent,
                    sourceId,
                    requestOptions,
                    analyticsRequestExecutor,
                    analyticsRequestFactory,
                    transaction,
                    {
                        transaction.close()
                    },
                    workContext = workContext
                ),
                maxTimeout
            )
        }
    }

    internal fun interface ChallengeProgressActivityStarter {
        fun start(
            context: Context,
            directoryServerName: String,
            cancelable: Boolean,
            uiCustomization: StripeUiCustomization,
            sdkTransactionId: SdkTransactionId
        )
    }

    internal class DefaultChallengeProgressActivityStarter : ChallengeProgressActivityStarter {
        override fun start(
            context: Context,
            directoryServerName: String,
            cancelable: Boolean,
            uiCustomization: StripeUiCustomization,
            sdkTransactionId: SdkTransactionId
        ) {
            ChallengeProgressActivity.show(
                context,
                directoryServerName,
                cancelable,
                uiCustomization,
                sdkTransactionId
            )
        }
    }
}
