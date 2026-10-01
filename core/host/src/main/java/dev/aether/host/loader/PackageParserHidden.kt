package dev.aether.host.loader

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import dev.aether.host.util.AetherLog
import dev.aether.host.util.Reflect
import java.io.File

/**
 * Reads a guest's `AndroidManifest.xml` using the framework's own
 * `android.content.pm.PackageParser`.
 *
 * We cannot use `PackageManager` for this: the guest is not installed, so
 * `getPackageArchiveInfo()` would return an APK we cannot influence, and
 * `getActivityInfo()` would fail outright. Parsing the binary XML by hand is
 * the only alternative, and it means re-implementing the AXML chunk parser —
 * not worth it while the framework parser is right there.
 *
 * The catch: `PackageParser` is hidden *and* its API drifts:
 *
 *   API 21-29 : parsePackage(File, int)
 *   API 30-32 : parsePackage(File, int, boolean)
 *   API 33+   : parsePackage(File, int, boolean) / (File, String, int, boolean)
 *
 * Rather than branch on SDK_INT we try every candidate signature until one
 * works, which survives OEM forks too.
 */
internal object PackageParserHidden {

    private const val TAG = "Manifest"

    private const val FLAGS = android.content.pm.PackageManager.GET_ACTIVITIES or
        android.content.pm.PackageManager.GET_META_DATA or
        android.content.pm.PackageManager.GET_PERMISSIONS

    data class ParsedGuest(
        val packageName: String,
        val applicationInfo: ApplicationInfo,
        val applicationClassName: String?,
        val activities: Map<String, ActivityInfo>,
        val requestedPermissions: List<String>,
        val versionCode: Int,
        /** The MAIN/LAUNCHER entry point, or null if the manifest has none. */
        val launcherActivity: String?,
    )

    fun parse(apk: File, classLoader: ClassLoader?): ParsedGuest {
        val parserClass = Class.forName("android.content.pm.PackageParser")

        val parser = parserClass.declaredConstructors
            .firstOrNull { it.parameterCount == 0 }
            ?.apply { isAccessible = true }
            ?.newInstance()
            ?: error("cannot construct PackageParser")

        val pkg = parsePackage(parserClass, parser, apk)
            ?: error("PackageParser.parsePackage() failed for ${apk.name}")

        val packageName = Reflect.get<String>(pkg, "packageName")
            ?: error("guest manifest has no packageName (is ${apk.name} a valid APK?)")

        @Suppress("UNCHECKED_CAST")
        val activities = readActivities(pkg, parserClass, parser)

        val applicationClassName = runCatching {
            val appInfoObj = pkg.javaClass.getDeclaredField("applicationInfo").apply { isAccessible = true }
            // The Application class lives on the internal `Package.mApplication`
            // (an Activity/Application object graph), not on ApplicationInfo.
            val app = Reflect.fieldOrNull(pkg.javaClass, "mApplication")?.get(pkg)
            val className = app?.let { Reflect.get<String>(it, "className") }
            className ?: appInfoObj.get(pkg).let { (it as ApplicationInfo).className }
        }.getOrNull()

        val applicationInfo = Reflect.get<ApplicationInfo>(pkg, "applicationInfo")
            ?: error("guest manifest has no applicationInfo")

        @Suppress("UNCHECKED_CAST")
        val permissions = (Reflect.get<ArrayList<String>>(pkg, "requestedPermissions") as? List<String>)
            ?: emptyList()

        val versionCode = Reflect.get<Int>(pkg, "mVersionCode")
            ?: Reflect.get<Int>(pkg, "mVersionCodeMajor") ?: 0

        AetherLog.i(TAG) {
            "parsed $packageName: ${activities.size} activities, " +
                "${permissions.size} permissions, app=${applicationClassName ?: "<default>"}"
        }

        return ParsedGuest(
            packageName = packageName,
            applicationInfo = applicationInfo,
            applicationClassName = applicationClassName,
            activities = activities,
            requestedPermissions = permissions,
            versionCode = versionCode,
            launcherActivity = findLauncher(pkg),
        )
    }

    private fun parsePackage(parserClass: Class<*>, parser: Any, apk: File): Any? {
        val candidates = listOf(
            arrayOf<Any?>(apk, FLAGS),
            arrayOf<Any?>(apk, FLAGS, false),
            arrayOf<Any?>(apk, FLAGS, true),
            arrayOf<Any?>(apk, apk.absolutePath, FLAGS, false),
        )
        for (args in candidates) {
            val method = parserClass.declaredMethods.firstOrNull {
                it.name == "parsePackage" && it.parameterTypes.size == args.size
            } ?: continue
            method.isAccessible = true
            val result = runCatching { method.invoke(parser, *args) }.getOrNull()
            if (result != null) {
                AetherLog.d(TAG) { "parsePackage matched signature (${args.size} args)" }
                return result
            }
        }
        return null
    }

    /**
     * Find the MAIN/LAUNCHER entry point.
     *
     * `PackageParser.IntentInfo` extends `IntentFilter`, so the standard
     * `hasAction`/`hasCategory` predicates work directly.
     */
    private fun findLauncher(pkg: Any): String? {
        @Suppress("UNCHECKED_CAST")
        val raw = Reflect.get<ArrayList<Any>>(pkg, "activities") ?: return null
        for (component in raw) {
            val className = Reflect.get<String>(component, "className") ?: continue
            @Suppress("UNCHECKED_CAST")
            val intents = Reflect.get<ArrayList<Any>>(component, "intents") ?: continue
            val isLauncher = intents.any { filter ->
                (filter as? android.content.IntentFilter)?.let {
                    it.hasAction(android.content.Intent.ACTION_MAIN) &&
                        it.hasCategory(android.content.Intent.CATEGORY_LAUNCHER)
                } ?: false
            }
            if (isLauncher) return className
        }
        return null
    }

    /**
     * `Package.activities` is an `ArrayList<PackageParser.Activity>`; each has a
     * `className` and an `info`. We want the framework's own `ActivityInfo`,
     * which requires `generateActivityInfo()`, because the raw `info` is not
     * fully initialised until that call.
     */
    private fun readActivities(
        pkg: Any,
        parserClass: Class<*>,
        parser: Any,
    ): Map<String, ActivityInfo> {
        @Suppress("UNCHECKED_CAST")
        val raw = Reflect.get<ArrayList<Any>>(pkg, "activities") ?: return emptyMap()
        val out = LinkedHashMap<String, ActivityInfo>(raw.size)

        for (component in raw) {
            val className = Reflect.get<String>(component, "className") ?: continue
            val info = runCatching {
                val generate = parserClass.declaredMethods.firstOrNull {
                    it.name == "generateActivityInfo" && it.parameterTypes.size in 2..4
                } ?: return@runCatching null
                generate.isAccessible = true
                val args = when (generate.parameterTypes.size) {
                    2 -> arrayOf<Any?>(component, FLAGS)
                    3 -> arrayOf<Any?>(component, FLAGS, null)
                    else -> arrayOf<Any?>(component, FLAGS, null, 0)
                }
                generate.invoke(parser, *args) as? ActivityInfo
            }.getOrNull() ?: Reflect.get<ActivityInfo>(component, "info") ?: continue

            out[className] = info
        }
        return out
    }
}
