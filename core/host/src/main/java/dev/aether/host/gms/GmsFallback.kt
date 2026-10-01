package dev.aether.host.gms

import dev.aether.host.util.AetherLog

/**
 * Keeps GMS-dependent startup code from taking the process down.
 *
 * Design principle: **never fabricate an identity, always degrade.**
 *
 * That is why there is deliberately no `fakeAdvertisingId()` here. A synthetic
 * GAID would be ad fraud and would get the host's publisher account banned; the
 * correct behaviour is to let `AdvertisingIdClient` return what it returns
 * (usually the real, device-scoped ID — which works fine, since AD_ID is not
 * package-scoped) and to swallow only the *unavailability* error.
 */
internal object GmsFallback {

    private const val TAG = "GmsFallback"

    /** `com.google.android.gms.common.ConnectionResult` codes we expect to see. */
    enum class ConnectionResult(val code: Int, val meaning: String) {
        SUCCESS(0, "GMS present and usable"),
        SERVICE_MISSING(1, "GMS not installed"),
        SERVICE_UPDATING(2, "GMS is updating"),
        SERVICE_DISABLED(3, "GMS disabled by the user"),
        SIGN_IN_REQUIRED(4, "account required — expected inside a container"),
        INVALID_ACCOUNT(5, "account rejected"),
        RESOLUTION_REQUIRED(6, "user action needed (dialog) — impossible headless"),
        NETWORK_ERROR(7, "network unavailable"),
        INTERNAL_ERROR(8, "internal GMS error"),
        SERVICE_INVALID(9, "GMS version incompatible"),
        DEVELOPER_ERROR(10, "app misconfigured — expected: wrong package/cert"),
        LICENSE_CHECK_FAILED(11, "app not licensed"),
        CANCELED(13, "operation cancelled"),
        TIMEOUT(14, "timeout"),
        INTERRUPTED(15, "interrupted"),
        API_UNAVAILABLE(16, "API not available — expected: wrong package/cert"),
        SIGN_IN_FAILED(17, "sign-in failed"),
        SERVICE_UPDATING_2(18, "GMS updating"),
        SERVICE_MISSING_PERMISSION(19, "missing permission"),
        RESTRICTED_PROFILE(20, "restricted profile"),
        API_VERSION_UPDATE_REQUIRED(21, "update GMS"),
        ;

        companion object {
            fun of(code: Int) = values().firstOrNull { it.code == code }
        }
    }

    /**
     * Codes that mean "this will never work inside the container, and that is
     * expected". The UI should surface these once and then stay quiet.
     */
    private val EXPECTED_INSIDE_CONTAINER = setOf(
        ConnectionResult.SIGN_IN_REQUIRED,
        ConnectionResult.DEVELOPER_ERROR,
        ConnectionResult.API_UNAVAILABLE,
        ConnectionResult.RESOLUTION_REQUIRED,
        ConnectionResult.SIGN_IN_FAILED,
    )

    @Volatile
    private var tier: GmsBridge.Tier = GmsBridge.Tier.PASS_THROUGH

    fun install(activeTier: GmsBridge.Tier) {
        tier = activeTier
    }

    /**
     * Classify a `ConnectionResult` code.
     *
     * @return true when the failure is *expected* inside the container and must
     *         be logged as a warning rather than escalated.
     */
    fun isExpectedFailure(code: Int): Boolean {
        val result = ConnectionResult.of(code) ?: return false
        val expected = result in EXPECTED_INSIDE_CONTAINER
        if (expected) {
            AetherLog.w(TAG) { "GMS ${result.code} (${result.name}): ${result.meaning}" }
        } else {
            AetherLog.i(TAG) { "GMS ${result.code} (${result.name}): ${result.meaning}" }
        }
        return expected
    }

    /**
     * Should this throwable be treated as non-fatal?
     *
     * A surprising number of games call an SDK in `Application.onCreate()` and
     * let any exception propagate. Inside a container those exceptions are
     * *structural*, not bugs, so we classify them and let the crash guard decide.
     */
    fun shouldSuppress(throwable: Throwable): Boolean {
        val type = throwable.javaClass.name
        return KNOWN_NON_FATAL.any { type == it } || run {
            val message = throwable.message ?: return@run false
            KNOWN_NON_FATAL_MESSAGES.any { message.contains(it, ignoreCase = true) }
        }
    }

    private val KNOWN_NON_FATAL = setOf(
        "com.google.android.gms.common.GooglePlayServicesRepairableException",
        "com.google.android.gms.common.GooglePlayServicesNotAvailableException",
        "com.google.firebase.FirebaseException",
        "com.google.android.play.core.install.InstallException",
        "com.google.android.play.core.assetpacks.AssetPackException",
        "com.android.billingclient.api.BillingClient$BillingResult",
    )

    private val KNOWN_NON_FATAL_MESSAGES = setOf(
        "Google Play services",
        "API: is not available",
        "Asset Pack",
        "billing",
        "In-app messaging",
        "FirebaseApp",
    )

    /** Summary for the diagnostics surface. */
    fun describe(): String = buildString {
        appendLine("tier=$tier")
        appendLine("expected failure codes: ${EXPECTED_INSIDE_CONTAINER.joinToString { it.code.toString() }}")
    }
}
