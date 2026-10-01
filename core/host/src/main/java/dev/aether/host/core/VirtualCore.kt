package dev.aether.host.core

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import dev.aether.host.loader.DynamicApkLoader
import dev.aether.host.native.HiddenApi
import dev.aether.host.runtime.HostInitializer
import dev.aether.host.runtime.StubPool
import dev.aether.host.runtime.VirtualActivity
import dev.aether.host.util.AetherLog
import dev.aether.host.vfs.VfsRouter
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Public façade of the container.
 *
 * Typical host `Application`:
 *
 * ```kotlin
 * class AetherApplication : Application() {
 *     override fun attachBaseContext(base: Context) {
 *         super.attachBaseContext(base)
 *         VirtualCore.initialize(this)
 *     }
 * }
 * ```
 */
object VirtualCore {

    private const val TAG = "Core"

    /** A live virtualised app. */
    class Session internal constructor(
        val profile: GuestProfile,
        val application: Application,
        val applicationInfo: android.content.pm.ApplicationInfo,
        val classLoader: ClassLoader,
        val resources: android.content.res.Resources,
        val activities: Map<String, ActivityInfo>,
        val vfs: VfsRouter,
        val instrumentation: Instrumentation,
        val installDir: File,
        val nativeAbi: String,
    ) {
        val packageName: String get() = profile.packageName
        val launcherActivity: String? get() = _launcher
        internal var _launcher: String? = null

        val liveActivities: List<Activity> get() = _live.toList()
        private val _live = mutableListOf<Activity>()
        internal fun onStarted(a: Activity) { synchronized(_live) { _live += a } }
        internal fun onDestroyed(a: Activity) { synchronized(_live) { _live -= a } }
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    @Volatile
    private var hostApplication: Application? = null

    @Volatile
    private var bootstrapped = false

    // ------------------------------------------------------------ bootstrap

    /**
     * Install the container into this process. Idempotent.
     *
     * Ordering: unseal first (HostInitializer's hooks are all blacklisted
     * reflection), then hooks, then the GMS bridge (which needs the
     * unseal to replace `ActivityThread.sPackageManager`).
     */
    @Synchronized
    fun initialize(application: Application) {
        if (bootstrapped) return
        hostApplication = application

        val state = HiddenApi.unsealOnce()
        if (!HiddenApi.isUsable) {
            AetherLog.w(TAG) {
                "running in DEGRADED mode (${state}): guests can load, but Activity and " +
                    "Resource hooking is unavailable. Expect crashes on the first screen."
            }
        }

        HostInitializer.install(application)
        bootstrapped = true

        AetherLog.i(TAG) { "container ready: ${HiddenApi.describe()}" }
    }

    // -------------------------------------------------------------- install

    /**
     * Virtualise [profile] from the APKs in [apkDir].
     *
     * @throws IllegalStateException if the host has not been initialised.
     */
    fun install(profile: GuestProfile, apkDir: File): Session {
        val app = hostApplication ?: error("VirtualCore.initialize() has not been called")
        check(bootstrapped) { "VirtualCore.initialize() has not been called" }
        require(apkDir.isDirectory) { "not a directory: $apkDir" }

        val existing = sessions[profile.packageName]
        if (existing != null) return existing

        val loaded = DynamicApkLoader(app, profile, apkDir).load()

        val session = Session(
            profile = profile,
            application = loaded.application,
            applicationInfo = loaded.applicationInfo,
            classLoader = loaded.classLoader,
            resources = loaded.resources,
            activities = loaded.activities,
            vfs = loaded.vfs,
            instrumentation = HostInitializer.currentInstrumentation(app),
            installDir = apkDir,
            nativeAbi = loaded.nativeAbi,
        ).also { it._launcher = guessLauncher(loaded.activities.keys, profile) }

        sessions[profile.packageName] = session
        AetherLog.i(TAG) { "installed ${profile.displayName} (${profile.packageName})" }
        return session
    }

    // --------------------------------------------------------------- launch

    /**
     * Build the Intent that starts a guest screen.
     *
     * The returned Intent targets a **stub** Activity declared in the host's
     * manifest; the real destination rides along in extras and is unpacked by
     * [VirtualActivity]. The system therefore never sees an undeclared
     * component.
     */
    fun launchIntent(
        context: Context,
        packageName: String,
        activityClass: String? = null,
    ): Intent {
        val session = sessions[packageName] ?: error("no session for $packageName; call install() first")

        val target = activityClass
            ?: session.launcherActivity
            ?: error("no launcher activity known for $packageName")

        val stub = StubPool.acquire(packageName, StubPool.Spec())

        return Intent().apply {
            component = ComponentName(context.packageName, stub.name)
            putExtra(VirtualActivity.EXTRA_GUEST_PACKAGE, packageName)
            putExtra(VirtualActivity.EXTRA_TARGET_CLASS, target)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Launch the guest's main screen. */
    fun launch(context: Context, packageName: String) {
        context.startActivity(launchIntent(context, packageName))
    }

    // ---------------------------------------------------------------- state

    fun isRunning(packageName: String): Boolean = sessions.containsKey(packageName)
    fun runningSession(packageName: String): Session? = sessions[packageName]
    fun running(): List<Session> = sessions.values.toList()

    /** Tear a guest down. Its shadow data on disk is kept. */
    fun stop(packageName: String) {
        val session = sessions.remove(packageName) ?: return
        session.liveActivities.toList().forEach { runCatching { it.finish() } }
        AetherLog.i(TAG) { "stopped $packageName" }
    }

    internal fun onGuestActivityStarted(packageName: String, activity: Activity) {
        sessions[packageName]?.onStarted(activity)
    }

    internal fun onGuestActivityDestroyed(activity: Activity) {
        StubPool.release(activity)
        sessions.values.forEach { it.onDestroyed(activity) }
    }

    // ----------------------------------------------------------- diagnostics

    fun report(): String = buildString {
        appendLine("AetherEngine VirtualCore")
        appendLine("  hidden API : ${HiddenApi.describe()}")
        appendLine("  hooks      : ${HostInitializer.health.ifEmpty { "none" }}")
        appendLine("  sessions   : ${sessions.size}")
        for (s in sessions.values) {
            appendLine("    - ${s.profile.displayName} (${s.packageName})")
            appendLine("      abi=${s.nativeAbi} activities=${s.activities.size} live=${s.liveActivities.size}")
        }
    }

    // -------------------------------------------------------------- helpers

    /**
     * `PackageParserHidden` already knows the MAIN/LAUNCHER activity; this is
     * the belt-and-braces fallback for manifests that use an unusual entry
     * point. Games are inconsistent, and failing to find a launcher means a
     * black screen with no log line — worth the redundancy.
     */
    private fun guessLauncher(classNames: Set<String>, profile: GuestProfile): String? {
        if (classNames.isEmpty()) return null
        return classNames.firstOrNull { it.endsWith(".MainActivity") }
            ?: classNames.firstOrNull { it.endsWith("UnityPlayerActivity") }
            ?: classNames.firstOrNull { it.substringAfterLast('.').contains("Main") }
            ?: classNames.firstOrNull { it.startsWith(profile.packageName) }
            ?: classNames.first()
    }
}
