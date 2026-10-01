package dev.aether.host.core

import dev.aether.host.gms.GmsBridge
import dev.aether.host.vfs.VfsRouter

/**
 * Static description of a game we know how to virtualise.
 *
 * Mirrors `guests/<package>.toml`, which is the human-editable source of
 * truth. This Kotlin copy exists because we need compile-time constants for
 * the shipping default; `tools/check-readiness.py` fails the build when the
 * two drift apart.
 */
data class GuestProfile(

    val packageName: String,
    val displayName: String,

    // ---- APK layout ----
    /** True when the target ships as an App Bundle (base + splits). */
    val splitsRequired: Boolean,
    /** Filename of the split that carries the native libraries, if any. */
    val nativeSplitPattern: String?,
    /** Play Asset Delivery packs the game expects. Missing ones are reported. */
    val expectedAssetPacks: Set<String>,

    // ---- ABI ----
    val abis: List<String>,

    // ---- SDK ----
    val minSdk: Int,
    val targetSdk: Int,

    // ---- VFS ----
    val strictVfs: Boolean,
    val sharedMedia: VfsRouter.SharedMedia,
    val extraRules: List<VfsRule>,
    val extraLinks: List<Pair<String, String>>,

    // ---- GMS ----
    val gmsTier: GmsBridge.Tier,
    val declaredPermissions: List<String>,
) {

    data class VfsRule(
        val template: String,
        val action: VfsRouter.Action,
        val bucket: String = "-",
    )

    /** Primary ABI, used for native-library extraction. */
    val primaryAbi: String get() = abis.first()

    companion object {

        /**
         * 8 Ball Pool — Miniclip.com
         *
         * Facts verified against the published package:
         *   * ships as an App Bundle: base.apk + split_config.arm64_v8a.apk
         *   * arm64-v8a primary, armeabi-v7a fallback, no x86 build
         *   * minSdk 23 / targetSdk 34
         *   * login is Miniclip ID / Facebook, NOT Google Play Games
         *   * has a Huawei AppGallery code path => survives without GMS
         *   * Play Billing + FCM will NOT work inside the container
         *
         * See docs/GMS-ARCHITECTURE.md.
         */
        val EIGHT_BALL_POOL = GuestProfile(
            packageName = "com.miniclip.eightballpool",
            displayName = "8 Ball Pool",

            splitsRequired = true,
            nativeSplitPattern = "split_config.arm64_v8a.apk",
            expectedAssetPacks = emptySet(),

            abis = listOf("arm64-v8a", "armeabi-v7a"),

            minSdk = 23,
            targetSdk = 34,

            strictVfs = true,
            sharedMedia = VfsRouter.SharedMedia.Passthrough,
            extraRules = listOf(
                // Telemetry/crash spool: junk that must not survive uninstall.
                VfsRule(
                    "/data/data/com.miniclip.eightballpool/app_dumps",
                    VfsRouter.Action.Shadow,
                    bucket = "cache",
                ),
                // Facebook SDK login reads the installed FB app's cache.
                // Read-only: the guest must never write into another app.
                VfsRule(
                    "/sdcard/Android/data/com.facebook.katana",
                    VfsRouter.Action.ReadOnlyPassthrough,
                ),
                VfsRule(
                    "/sdcard/Android/data/com.facebook.orca",
                    VfsRouter.Action.ReadOnlyPassthrough,
                ),
            ),
            extraLinks = emptyList(),

            gmsTier = GmsBridge.Tier.IN_PROCESS_SPOOF,
            declaredPermissions = listOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.WAKE_LOCK",
                "android.permission.VIBRATE",
                "android.permission.POST_NOTIFICATIONS",
                "com.google.android.gms.permission.AD_ID",
                "com.android.vending.BILLING",
                "com.google.android.c2dm.permission.RECEIVE",
            ),
        )

        /** Everything the host knows how to run. */
        val builtIn: List<GuestProfile> = listOf(EIGHT_BALL_POOL)

        fun forPackage(packageName: String): GuestProfile? =
            builtIn.firstOrNull { it.packageName == packageName }
    }
}
