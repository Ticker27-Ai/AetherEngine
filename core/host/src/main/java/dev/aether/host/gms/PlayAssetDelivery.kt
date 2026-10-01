package dev.aether.host.gms

import dev.aether.host.util.AetherLog
import java.io.File
import java.util.zip.ZipFile

/**
 * Play Asset Delivery, without Play.
 *
 * The target ships as an Android App Bundle:
 *
 *     base.apk
 *     split_config.arm64_v8a.apk      <- native libs (IL2CPP/Unity, etc.)
 *     split_config.<locale>.apk       <- strings
 *     split_asset_pack_<name>.apk     <- optional Play Asset Delivery packs
 *
 * The Play Store normally delivers those splits. Inside the container there is
 * no Play Store, so `com.google.android.play.core.assetpacks.AssetPackManager`
 * will report `AssetPackException` and the game may start with missing assets
 * (a black screen is the usual symptom).
 *
 * This object does the only thing that works: **install every split up front**
 * and expose them so `DynamicApkLoader` can put them on the dex path and the
 * asset path.
 */
internal object PlayAssetDelivery {

    private const val TAG = "AssetDelivery"

    /** Split prefix used by App Bundles for Play Asset Delivery packs. */
    private const val ASSET_PACK_PREFIX = "split_asset_pack_"

    /** Split prefix for configuration splits (ABI, locale, density). */
    private const val CONFIG_PREFIX = "split_config."

    data class Splits(
        val base: File,
        val config: List<File>,
        val assetPacks: Map<String, File>,
    ) {
        /** Everything that must go on the dex path, base first. */
        val dexPath: String
            get() = (listOf(base) + config).joinToString(File.pathSeparator) { it.absolutePath }

        /** Everything `AssetManager.addAssetPath` must see, in manifest order. */
        val assetPaths: List<String>
            get() = (listOf(base) + config + assetPacks.values).map { it.absolutePath }
    }

    /**
     * Classify the files in `dir` into base / config / asset-pack splits.
     *
     * @throws IllegalStateException when no base APK is present — an install
     *         without a base is not recoverable and must fail loudly.
     */
    fun resolve(dir: File, baseCandidates: List<String> = listOf("base.apk")): Splits {
        val files = dir.listFiles()?.filter { it.isFile && it.extension == "apk" } ?: emptyList()

        val base = baseCandidates.firstNotNullOfOrNull { name -> files.firstOrNull { it.name == name } }
            ?: files.firstOrNull { !it.name.startsWith("split_") }
            ?: error("no base APK in ${dir.absolutePath} (have: ${files.map { it.name }})")

        val config = files.filter { it.name.startsWith(CONFIG_PREFIX) || it.name.startsWith("split_config_") }
        val assetPacks = files
            .filter { it.name.startsWith(ASSET_PACK_PREFIX) }
            .associateBy { it.name.removePrefix(ASSET_PACK_PREFIX).removeSuffix(".apk") }

        AetherLog.i(TAG) {
            "resolved splits: base=${base.name}, config=${config.map { it.name }}, " +
                "assetPacks=${assetPacks.keys}"
        }
        return Splits(base, config, assetPacks)
    }

    /**
     * Sanity-check a split before it reaches the ClassLoader: a truncated or
     * corrupt split produces a `ClassNotFoundException` three frames deep
     * inside the guest, which is close to undebuggable.
     */
    fun verify(split: File): Boolean {
        return try {
            ZipFile(split).use { zip ->
                val entries = zip.entries().toList()
                when {
                    split.name.startsWith(ASSET_PACK_PREFIX) ->
                        entries.any { it.name.startsWith("assets/") }
                    split.name.startsWith(CONFIG_PREFIX) -> entries.isNotEmpty()
                    else -> entries.any { it.name.endsWith(".dex") || it.name.startsWith("lib/") } ||
                        entries.any { it.name == "AndroidManifest.xml" }
                }
            }
        } catch (t: Throwable) {
            AetherLog.e(TAG, { "corrupt split: ${split.name}" }, t)
            false
        }
    }

    /**
     * Report asset packs that the game will ask for but that we do not have.
     * Surfaced as a pre-launch warning so the user knows to install them
     * instead of discovering a black screen.
     */
    fun missingPacks(splits: Splits, expected: Set<String>): Set<String> {
        val missing = expected - splits.assetPacks.keys
        if (missing.isNotEmpty()) {
            AetherLog.w(TAG) { "asset packs not installed: $missing (game may render black)" }
        }
        return missing
    }
}
