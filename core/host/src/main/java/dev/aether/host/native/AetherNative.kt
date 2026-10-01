package dev.aether.host.native

import dev.aether.host.util.AetherLog

/**
 * The Java side of `libaether-native.so`.
 *
 * Loading this class loads the native library, which is why nothing in the
 * container may touch hidden framework APIs before [ensureLoaded] has run.
 */
internal object AetherNative {

    private const val TAG = "Native"

    @Volatile
    private var loaded: Boolean = false

    /** Strategy codes, mirrored from `UnsealStrategy` in hidden_api.h. */
    object Unseal {
        const val NOT_REQUIRED = 0
        const val RUNTIME_POLICY_WRITE = 1
        const val FAILED = 2
    }

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            System.loadLibrary("aether-native")
            loaded = true
            AetherLog.i(TAG) { "libaether-native loaded, vfs ABI v${vfsAbiVersion()}" }
        }
    }

    // ------------------------------------------------------------------ ART

    /**
     * Disable hidden-API enforcement for this process.
     *
     * [probe] is invoked repeatedly from native code during calibration, so it
     * must be cheap and must never throw.
     *
     * @return raw report: `[strategy, apiLevel, clearedCount, probes, off0..off3]`
     */
    external fun unsealHiddenApi(probe: HiddenApiProbe): IntArray

    /**
     * Permanently exempt every API matching [prefixes] (usually just `"L"`,
     * which means "everything"). Only meaningful after a successful unseal.
     */
    external fun setHiddenApiExemptions(prefixes: Array<String>): Boolean

    external fun apiLevel(): Int

    external fun currentAbi(): String

    // -------------------------------------------------------- native libs

    /** `chmod 0755`. Extracted `.so` files are not executable by default. */
    external fun markExecutable(path: String): Boolean

    /** `[valid, elfClass, machine, type]` for a candidate guest library. */
    external fun probeElf(path: String): IntArray

    // ----------------------------------------------------------------- VFS

    external fun vfsCreate(
        guestPackage: String,
        hostPackage: String,
        hostDataDir: String,
        userId: Int,
        strict: Boolean,
        sharedMedia: Int,
    ): Long

    external fun vfsDestroy(handle: Long)
    external fun vfsToHost(handle: Long, guestPath: String): String?
    external fun vfsToGuest(handle: Long, hostPath: String): String?
    external fun vfsCheck(handle: Long, guestPath: String, write: Boolean): Boolean
    external fun vfsAddRule(handle: Long, template: String, action: Int, bucket: String): Int
    external fun vfsAddLink(handle: Long, from: String, to: String): Int
    external fun vfsLastError(): String?
    external fun vfsAbiVersion(): Int

    // ------------------------------------------------------------ helpers

    /** Human-readable form of the last native error. */
    fun lastError(): String = try {
        vfsLastError()?.takeIf { it.isNotEmpty() } ?: "<no native error>"
    } catch (t: Throwable) {
        "<lastError threw: ${t.message}>"
    }
}
