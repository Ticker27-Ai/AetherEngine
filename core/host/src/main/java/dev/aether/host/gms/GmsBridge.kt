package dev.aether.host.gms

import android.content.Context
import dev.aether.host.native.HiddenApi
import dev.aether.host.util.AetherLog

/**
 * Google Mobile Services interception layer.
 *
 * See docs/GMS-ARCHITECTURE.md for the full analysis. The short version:
 *
 *   The guest shares a UID with the host, so **GMS sees the host's identity**.
 *   Nothing we do inside our own process can change what
 *   `PackageManagerService` tells `com.google.android.gms` about the calling
 *   UID. We therefore do the two things that ARE achievable:
 *
 *     1. Make GMS-dependent initialisation never crash (T0).
 *     2. Spoof the identity for SDKs that query it **inside our process** (T1).
 *
 *   Working login / IAP / FCM requires signature spoofing (T2, system
 *   privileges) and is explicitly out of scope here.
 */
object GmsBridge {

    private const val TAG = "Gms"

    /** How far the container is willing/able to go. */
    enum class Tier {
        /** GMS is called for real; the guest's identity is the host's. */
        PASS_THROUGH,

        /** PASS_THROUGH + in-process PackageManager identity spoofing. */
        IN_PROCESS_SPOOF,

        /** Reserved: system-app / signature-spoofing (microG-style). */
        SIGNATURE_SPOOFING,
    }

    /** Components we care about, and what we can honestly promise. */
    enum class Component(val packageName: String, val requiresTier: Tier) {
        PLAY_SERVICES("com.google.android.gms", Tier.PASS_THROUGH),
        PLAY_STORE("com.android.vending", Tier.SIGNATURE_SPOOFING),
        PLAY_GAMES("com.google.android.gms", Tier.SIGNATURE_SPOOFING),
        BILLING("com.android.vending", Tier.SIGNATURE_SPOOFING),
        FIREBASE("com.google.firebase", Tier.IN_PROCESS_SPOOF),
        FCM("com.google.firebase", Tier.SIGNATURE_SPOOFING),
        AD_ID("com.google.android.gms", Tier.PASS_THROUGH),
        APP_LOVIN("com.applovin", Tier.PASS_THROUGH),
        INSTALL_REFERRER("com.android.vending", Tier.IN_PROCESS_SPOOF),
        HUAWEI("com.huawei.appmarket", Tier.PASS_THROUGH),
    }

    @Volatile
    var tier: Tier = Tier.PASS_THROUGH
        private set

    @Volatile
    private var installed = false

    /**
     * Install the GMS layer. Call from `HostInitializer`, after the hidden-API
     * unseal — T1 needs to replace `ActivityThread.sPackageManager`, which is a
     * blacklisted field.
     */
    fun install(hostContext: Context, requested: Tier = Tier.IN_PROCESS_SPOOF) {
        if (installed) return
        synchronized(this) {
            if (installed) return

            tier = when {
                requested == Tier.PASS_THROUGH -> Tier.PASS_THROUGH
                !HiddenApi.isUsable -> {
                    AetherLog.w(TAG) {
                        "hidden-API unseal unavailable -> GMS tier degraded to PASS_THROUGH. " +
                            "SDKs that check their own package may misbehave."
                    }
                    Tier.PASS_THROUGH
                }
                else -> Tier.IN_PROCESS_SPOOF
            }

            if (tier >= Tier.IN_PROCESS_SPOOF) {
                val ok = IdentitySpoof.install(hostContext)
                if (!ok) {
                    AetherLog.w(TAG) { "IdentitySpoof failed to install; falling back to PASS_THROUGH" }
                    tier = Tier.PASS_THROUGH
                }
            }

            GmsFallback.install(tier)
            installed = true

            AetherLog.i(TAG) { "GMS bridge installed at tier=$tier" }
            for (c in Component.values()) {
                if (c.requiresTier > tier) {
                    AetherLog.w(TAG) {
                        "${c.name} (${c.packageName}) will NOT work: needs ${c.requiresTier}, we are at $tier"
                    }
                }
            }
        }
    }

    /** True when [component] can be expected to behave. Drives the UI warnings. */
    fun isAvailable(component: Component): Boolean = component.requiresTier <= tier

    /** Human-readable report for the diagnostics surface. */
    fun report(): String = buildString {
        appendLine("GMS bridge tier = $tier")
        appendLine("hidden API = ${HiddenApi.state}")
        for (c in Component.values()) {
            appendLine("  ${if (isAvailable(c)) "[ok]  " else "[FAIL]"} ${c.name}")
        }
    }
}
