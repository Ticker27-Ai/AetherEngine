package dev.aether.host.native

import android.os.Build
import dev.aether.host.util.AetherLog

/**
 * One-shot hidden-API unseal for the host process.
 *
 * MUST run before any blacklisted reflection (`Activity.attach`,
 * `AssetManager.addAssetPath`, `LoadedApk` field writes, `ActivityManager`
 * singleton replacement). Run it from `Application.attachBaseContext`, i.e.
 * before the first guest class is ever resolved.
 *
 * If it fails, the container does not abort: [degradedMode] becomes true and
 * VirtualCore refuses to launch guests that need framework hooking, instead of
 * crashing the host with a NoSuchMethodError three frames into someone else's
 * stack.
 */
internal object HiddenApi {

    private const val TAG = "HiddenApi"

    /** Everything after this prefix is exempt. `"L"` = the whole API surface. */
    private val EXEMPT_EVERYTHING = arrayOf("L")

    @Volatile
    var state: State = State.NOT_ATTEMPTED
        private set

    /** True when the guest can be launched but framework hooking is unavailable. */
    val degradedMode: Boolean
        get() = state == State.FAILED

    val isUsable: Boolean
        get() = state == State.UNSEALED || state == State.NOT_REQUIRED

    fun unsealOnce(): State {
        if (state != State.NOT_ATTEMPTED) return state

        synchronized(this) {
            if (state != State.NOT_ATTEMPTED) return state

            AetherNative.ensureLoaded()
            val probe = HiddenApiProbe()

            val report = try {
                AetherNative.unsealHiddenApi(probe)
            } catch (t: Throwable) {
                AetherLog.e(TAG, { "unseal threw" }, t)
                state = State.FAILED
                return state
            }

            val strategy = report[0]
            val apiLevel = report[1]
            val cleared = report[2]
            val probes = report[3]

            state = when (strategy) {
                AetherNative.Unseal.NOT_REQUIRED -> {
                    AetherLog.i(TAG) { "API $apiLevel: no hidden-API enforcement present" }
                    State.NOT_REQUIRED
                }
                AetherNative.Unseal.RUNTIME_POLICY_WRITE -> {
                    AetherLog.i(TAG) {
                        "API $apiLevel: cleared $cleared policy word(s) after $probes probes " +
                            "(offsets ${report[4]}, ${report[5]}, ${report[6]}, ${report[7]})"
                    }
                    State.UNSEALED
                }
                else -> {
                    AetherLog.e(TAG) {
                        "API $apiLevel: unseal FAILED after $probes probes. " +
                            "Guests that need Activity/Resource hooks will not start."
                    }
                    State.FAILED
                }
            }

            if (state == State.UNSEALED) {
                // Belt and braces: make the exemption permanent for this
                // process, so a later policy rewrite cannot re-lock us.
                val ok = runCatching { AetherNative.setHiddenApiExemptions(EXEMPT_EVERYTHING) }
                    .getOrDefault(false)
                AetherLog.i(TAG) { "setHiddenApiExemptions(L) -> $ok" }
            }
            return state
        }
    }

    enum class State {
        NOT_ATTEMPTED,

        /** API < 28: nothing to do. */
        NOT_REQUIRED,

        /** Policy cleared; framework internals are reachable. */
        UNSEALED,

        /** Could not clear; fall back to degraded (no-hook) operation. */
        FAILED,
    }

    /** Convenience for diagnostics surfaces (`dumpsys`, the Flutter shell). */
    fun describe(): String = buildString {
        append("sdk=").append(Build.VERSION.SDK_INT)
        append(" state=").append(state)
        append(" abi=").append(runCatching { AetherNative.currentAbi() }.getOrDefault("?"))
    }
}
