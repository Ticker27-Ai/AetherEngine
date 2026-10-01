package dev.aether.host.runtime

import android.app.Activity
import android.content.pm.ActivityInfo
import dev.aether.host.util.AetherLog

/**
 * Allocates pre-declared stub Activities.
 *
 * Android reads `launchMode`, `theme`, `configChanges`, `screenOrientation`,
 * `windowSoftInputMode` and `taskAffinity` from the manifest entry of the
 * component being started — *before* our code runs, in `system_process`. We
 * cannot change them at runtime, so the only way for a guest screen to get the
 * right behaviour is for a stub with the right attributes to already exist.
 *
 * The stubs are generated (see `tools/gen-stubs`); this object is the runtime
 * half: hand out a free stub matching the guest screen's requirements, and take
 * it back when the screen is destroyed.
 */
internal object StubPool {

    private const val TAG = "StubPool"

    enum class LaunchMode(val xml: String) {
        STANDARD("standard"),
        SINGLE_TOP("singleTop"),
        SINGLE_TASK("singleTask"),
        SINGLE_INSTANCE("singleInstance"),
    }

    /** What a guest screen needs from the window that hosts it. */
    data class Spec(
        val launchMode: LaunchMode = LaunchMode.STANDARD,
        /** 0 = inherit; otherwise a style resource from the host. */
        val theme: Int = 0,
        val transparent: Boolean = false,
        val configChanges: Int = DEFAULT_CONFIG_CHANGES,
        val orientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
        val excludeFromRecents: Boolean = false,
    )

    const val DEFAULT_CONFIG_CHANGES =
        ActivityInfo.CONFIG_ORIENTATION or
            ActivityInfo.CONFIG_SCREEN_SIZE or
            ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or
            ActivityInfo.CONFIG_SCREEN_LAYOUT or
            ActivityInfo.CONFIG_KEYBOARD_HIDDEN or
            ActivityInfo.CONFIG_DENSITY or
            ActivityInfo.CONFIG_FONT_SCALE or
            ActivityInfo.CONFIG_LOCALE or
            ActivityInfo.CONFIG_UI_MODE

    /** Stubs currently in use, keyed by guest Activity instance hash. */
    private val inUse = HashMap<Int, Class<out Activity>>()

    /** Best-effort LRU so a screen that restarts often gets the same stub. */
    private val lastUsed = HashMap<String, Int>()

    /**
     * Find a free stub for [spec].
     *
     * @throws IllegalStateException when the pool is exhausted. This is a real
     *         possibility with games that stack many screens, and it must be
     *         reported as such rather than silently reusing a live stub (which
     *         would corrupt the guest's back stack).
     */
    fun acquire(guestPackage: String, spec: Spec): Class<out Activity> {
        val pool = poolFor(spec)
        val busy = inUse.values.toSet()

        // Prefer the stub this package used last time: it keeps task affinity
        // stable across configuration changes.
        lastUsed[guestPackage]?.let { index ->
            if (index < pool.size && pool[index] !in busy) {
                return markInUse(guestPackage, pool[index], index)
            }
        }

        val index = pool.indices.firstOrNull { pool[it] !in busy }
            ?: error(
                "stub pool exhausted for ${spec.launchMode} " +
                    "(${pool.size} stubs, all in use). Increase STUB_*_COUNT in build.gradle.kts.",
            )
        return markInUse(guestPackage, pool[index], index)
    }

    fun release(guestActivity: Activity) {
        inUse.remove(System.identityHashCode(guestActivity))
    }

    /** How many stubs are free — surfaced in the diagnostics surface. */
    fun freeCount(spec: Spec): Int {
        val busy = inUse.values.toSet()
        return poolFor(spec).count { it !in busy }
    }

    private fun markInUse(
        guestPackage: String,
        stub: Class<out Activity>,
        index: Int,
    ): Class<out Activity> {
        // Keyed by the *guest* Activity we are about to create; the caller
        // (VirtualActivity) registers right after instantiate.
        lastUsed[guestPackage] = index
        AetherLog.v(TAG) { "acquire ${stub.simpleName} for $guestPackage" }
        return stub
    }

    private fun poolFor(spec: Spec): Array<Class<out VirtualActivity>> = when {
        spec.transparent -> VirtualStubs.TRANSPARENT
        spec.launchMode == LaunchMode.SINGLE_TOP -> VirtualStubs.SINGLE_TOP
        spec.launchMode == LaunchMode.SINGLE_TASK -> VirtualStubs.SINGLE_TASK
        spec.launchMode == LaunchMode.SINGLE_INSTANCE -> VirtualStubs.SINGLE_INSTANCE
        else -> VirtualStubs.STANDARD
    }
}
