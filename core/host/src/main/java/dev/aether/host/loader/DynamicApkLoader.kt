package dev.aether.host.loader

import android.app.Application
import android.os.Build
import android.app.Instrumentation
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageParserHidden
import dev.aether.host.core.GuestProfile
import dev.aether.host.native.AetherNative
import dev.aether.host.native.HiddenApi
import dev.aether.host.util.AetherLog
import dev.aether.host.vfs.VfsRouter
import dev.aether.host.gms.PlayAssetDelivery
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * [PHASE 1] DynamicApkLoader
 *
 * Turns "a directory of APK files belonging to some other app" into "a live
 * ClassLoader + Resources + Application that behaves like an installed app".
 *
 * Order of operations matters and is not negotiable:
 *
 *   1. unseal hidden APIs        — everything below uses blacklisted members
 *   2. resolve + verify splits   — App Bundle support; a missing split is fatal
 *   3. extract native libraries  — into the host's own data dir, so the linker
 *                                  namespace accepts them (a `.so` inside the
 *                                  guest APK is NOT dlopen-able on API 24+)
 *   4. build the ClassLoader
 *   5. build Resources (every split!)
 *   6. parse the guest manifest  -> ApplicationInfo, ActivityInfo map
 *   7. build the VFS router from the profile
 *   8. create the Application    -> callApplicationOnCreate()
 */
internal class DynamicApkLoader(
    private val hostContext: Context,
    private val profile: GuestProfile,
    private val installDir: File,
) {

    private val tag = "Loader/${profile.packageName}"

    /** Result of a successful load. */
    data class LoadedGuest(
        val profile: GuestProfile,
        val application: Application,
        val applicationInfo: ApplicationInfo,
        val classLoader: GuestClassLoader,
        val resources: android.content.res.Resources,
        val activities: Map<String, ActivityInfo>,
        val vfs: VfsRouter,
        val splits: PlayAssetDelivery.Splits,
        val nativeAbi: String,
    )

    fun load(): LoadedGuest {
        // 1 ----------------------------------------------------------------
        require(HiddenApi.unsealOnce().let { HiddenApi.isUsable }) {
            "hidden-API unseal failed; cannot virtualise ${profile.packageName}"
        }

        // 2 ----------------------------------------------------------------
        val splits = PlayAssetDelivery.resolve(installDir)
        val allSplits = listOf(splits.base) + splits.config + splits.assetPacks.values
        for (split in allSplits) {
            check(PlayAssetDelivery.verify(split)) { "corrupt or unsupported split: ${split.name}" }
        }
        if (profile.splitsRequired && splits.config.isEmpty()) {
            AetherLog.w(tag) {
                "${profile.displayName} is an App Bundle but no config splits were " +
                    "installed — native libraries and alternate resources will be missing."
            }
        }
        PlayAssetDelivery.missingPacks(splits, profile.expectedAssetPacks)

        val odexDir = File(hostContext.codeCacheDir, "aether/${profile.packageName}/dex").apply { mkdirs() }
        val libDir = File(hostContext.filesDir, "aether/native/${profile.packageName}").apply { mkdirs() }
        val dataDir = File(hostContext.filesDir, "aether/virtual/${profile.packageName}").apply { mkdirs() }

        // 3 ----------------------------------------------------------------
        val abi = selectAbi(allSplits)
        val extracted = extractNativeLibraries(allSplits, abi, libDir)
        AetherLog.i(tag) { "abi=$abi, extracted $extracted native librar(y/ies)" }

        // 4 ----------------------------------------------------------------
        val classLoader = GuestClassLoader(
            dexPath = splits.dexPath,
            optimizedDirectory = odexDir,
            librarySearchPath = libDir.absolutePath,
            parent = hostContext.classLoader,
            guestPackage = profile.packageName,
        )

        // 5 ----------------------------------------------------------------
        val resources = GuestResources.create(hostContext.resources, splits.assetPaths)

        // 6 ----------------------------------------------------------------
        val parsed = PackageParserHidden.parse(splits.base, classLoader)
        patchApplicationInfo(parsed.applicationInfo, splits.base, dataDir, libDir)

        // 7 ----------------------------------------------------------------
        val vfs = VfsRouter.create(
            guestPackage = profile.packageName,
            hostPackage = hostContext.packageName,
            hostDataDir = hostContext.applicationInfo.dataDir,
            strict = profile.strictVfs,
            sharedMedia = profile.sharedMedia,
        )
        profile.extraRules.forEach { vfs.addRule(it.template, it.action, it.bucket) }
        profile.extraLinks.forEach { (from, to) -> vfs.addLink(from, to) }

        // 8 ----------------------------------------------------------------
        val guestContext = GuestContext(
            base = hostContext,
            packageName = profile.packageName,
            applicationInfo = parsed.applicationInfo,
            guestResources = resources,
            guestClassLoader = classLoader,
            vfs = vfs,
        )

        val instrumentation = hostInstrumentation()
        val application = instrumentation.newApplication(
            classLoader,
            parsed.applicationClassName ?: Application::class.java.name,
            guestContext,
        )
        instrumentation.callApplicationOnCreate(application)

        AetherLog.i(tag) {
            "guest ready: ${parsed.activities.size} activities, " +
                "app=${parsed.applicationClassName ?: "<default>"}"
        }

        return LoadedGuest(
            profile = profile,
            application = application,
            applicationInfo = parsed.applicationInfo,
            classLoader = classLoader,
            resources = resources,
            activities = parsed.activities,
            vfs = vfs,
            splits = splits,
            nativeAbi = abi,
        )
    }

    // ------------------------------------------------------------------ ABI

    /**
     * Pick the best ABI that (a) this device supports, (b) the profile allows,
     * and (c) the APK actually contains.
     */
    private fun selectAbi(splits: List<File>): String {
        val deviceAbi = runCatching { AetherNative.currentAbi() }.getOrDefault("")
        val supported = Build.SUPPORTED_ABIS.toList()
        val present = presentAbis(splits)

        val ordered = buildList {
            if (deviceAbi.isNotBlank()) add(deviceAbi)
            addAll(supported)
            addAll(profile.abis)
        }
        val chosen = ordered.firstOrNull { it in profile.abis && it in present }
            ?: present.firstOrNull { it in profile.abis }
            ?: present.firstOrNull()

        if (chosen == null) {
            AetherLog.w(tag) { "no native libraries in any split; assuming pure-Java/DEX guest" }
            return profile.primaryAbi
        }
        if (chosen != deviceAbi) {
            AetherLog.w(tag) { "device ABI is $deviceAbi but only '$chosen' is present" }
        }
        return chosen
    }

    private fun presentAbis(splits: List<File>): Set<String> {
        val abis = linkedSetOf<String>()
        for (split in splits) {
            runCatching {
                ZipFile(split).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val name = entries.nextElement().name
                        if (name.startsWith("lib/") && name.endsWith(".so")) {
                            abis += name.removePrefix("lib/").substringBefore('/')
                        }
                    }
                }
            }
        }
        return abis
    }

    // ------------------------------------------------------- native libs

    /**
     * Extract `lib/<abi>/*.so` from whichever split holds them.
     *
     * Why extract at all: from API 24 the dynamic linker refuses to `dlopen`
     * out of an APK unless the entry is page-aligned and uncompressed, and even
     * then the ClassLoader namespace only permits paths that belong to *this*
     * app. Extracting into the host's data dir satisfies both.
     */
    private fun extractNativeLibraries(splits: List<File>, abi: String, outDir: File): Int {
        var count = 0
        for (split in splits) {
            ZipFile(split).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    if (!entry.name.startsWith("lib/$abi/")) continue
                    if (!entry.name.endsWith(".so")) continue

                    val target = File(outDir, entry.name.substringAfterLast('/'))
                    if (target.exists() && target.length() == entry.size && verify(target)) continue

                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                    AetherNative.markExecutable(target.absolutePath)
                    if (!verify(target)) {
                        AetherLog.e(tag) { "extracted library failed ELF check: ${target.name}" }
                        target.delete()
                        continue
                    }
                    count++
                }
            }
        }
        return count
    }

    /** ELF class must match the ABI we extracted for (32- vs 64-bit). */
    private fun verify(file: File): Boolean {
        val probe = runCatching { AetherNative.probeElf(file.absolutePath) }.getOrNull() ?: return false
        if (probe.size < 2) return false
        val valid = probe[0] == 1
        val elfClass = probe[1]
        val wantClass = if (abiIs64Bit) 2 else 1
        return valid && (elfClass == wantClass).also {
            if (!it) AetherLog.e(tag) {
                "${file.name}: ELF class $elfClass but ABI needs $wantClass"
            }
        }
    }

    private val abiIs64Bit: Boolean
        get() = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() &&
            Build.SUPPORTED_ABIS.firstOrNull() in Build.SUPPORTED_64_BIT_ABIS

    // ------------------------------------------------------------- manifest

    private fun patchApplicationInfo(
        info: ApplicationInfo,
        baseApk: File,
        dataDir: File,
        libDir: File,
    ) {
        // The parsed info still points at paths owned by the *host* install.
        info.sourceDir = baseApk.absolutePath
        info.publicSourceDir = baseApk.absolutePath
        info.dataDir = dataDir.absolutePath
        info.nativeLibraryDir = libDir.absolutePath
        info.uid = android.os.Process.myUid()
    }

    private fun hostInstrumentation(): Instrumentation {
        val activityThread = dev.aether.host.util.Reflect.callStatic(
            Class.forName("android.app.ActivityThread"),
            "currentActivityThread",
        ) ?: error("no ActivityThread")
        return dev.aether.host.util.Reflect.get<Instrumentation>(activityThread, "mInstrumentation")
            ?: Instrumentation()
    }
}
