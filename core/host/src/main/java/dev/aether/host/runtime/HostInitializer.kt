package dev.aether.host.runtime

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import dev.aether.host.util.AetherLog
import dev.aether.host.util.Reflect
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * [PHASE 1] HostInitializer — installs the container into the host process.
 *
 * Call once, from `Application.attachBaseContext()`. Ordering is deliberate:
 *
 *   1. hidden-API unseal   — every hook below touches blacklisted members
 *   2. Instrumentation     — lets us intercept Activity creation and launches
 *   3. IActivityManager    — rewrites `startActivity` so the system never sees
 *                            an undeclared component
 *   4. mH callback         — catches `EXECUTE_TRANSACTION` on API 28+ to fix up
 *                            intents before the Activity is created
 *
 * Everything here is best-effort: a hook that fails is logged and skipped,
 * because a container that degrades is worth more than one that refuses to
 * start. `VirtualCore` reads [health] and decides what to allow.
 */
internal object HostInitializer {

    private const val TAG = "Initializer"

    enum class Hook { UNSEAL, INSTRUMENTATION, ACTIVITY_MANAGER, HANDLER_CALLBACK }

    @Volatile
    private var installed = false

    /** Hooks that succeeded. Empty until [install] runs. */
    val health: Set<Hook>
        get() = _health.toSet()

    private val _health = java.util.Collections.synchronizedSet(enumSetNone())

    @Suppress("UNCHECKED_CAST")
    private fun enumSetNone(): MutableSet<Hook> =
        java.util.EnumSet.noneOf(Hook::class.java) as MutableSet<Hook>

    fun install(application: Application) {
        if (installed) return
        synchronized(this) {
            if (installed) return

            val activityThread = runCatching {
                Reflect.callStatic(Class.forName("android.app.ActivityThread"), "currentActivityThread")
            }.getOrNull()

            // 2 -------------------------------------------------------------
            if (activityThread != null) {
                runCatching { hookInstrumentation(activityThread) }
                    .onSuccess { _health += Hook.INSTRUMENTATION }
                    .onFailure { AetherLog.e(TAG, { "Instrumentation hook failed" }, it) }

                // 4 ---------------------------------------------------------
                runCatching { hookHandlerCallback(activityThread) }
                    .onSuccess { _health += Hook.HANDLER_CALLBACK }
                    .onFailure { AetherLog.e(TAG, { "Handler callback hook failed" }, it) }
            }

            // 3 -------------------------------------------------------------
            runCatching { hookActivityManager() }
                .onSuccess { _health += Hook.ACTIVITY_MANAGER }
                .onFailure { AetherLog.e(TAG, { "IActivityManager hook failed" }, it) }

            installed = true
            AetherLog.i(TAG) { "installed hooks: ${_health.ifEmpty { "NONE (degraded mode)" }}" }
        }
    }

    /**
     * The Instrumentation currently installed on the host's ActivityThread —
     * either our proxy or the framework's original. `VirtualCore` uses it to
     * drive guest lifecycle callbacks through the supported entry points
     * (`callActivityOnCreate` and friends) rather than calling the Activity
     * methods directly.
     */
    fun currentInstrumentation(hostContext: Context): Instrumentation {
        val activityThread = runCatching {
            Reflect.callStatic(Class.forName("android.app.ActivityThread"), "currentActivityThread")
        }.getOrNull()
        val fromThread = activityThread?.let { Reflect.get<Instrumentation>(it, "mInstrumentation") }
        return fromThread ?: Instrumentation()
    }

    // ------------------------------------------------------- instrumentation

    private fun hookInstrumentation(activityThread: Any) {
        val original = Reflect.get<Instrumentation>(activityThread, "mInstrumentation")
            ?: error("no mInstrumentation on ActivityThread")
        if (original is AetherInstrumentation) return

        Reflect.set(activityThread, "mInstrumentation", AetherInstrumentation(original))
        AetherLog.d(TAG) { "instrumentation replaced (base=${original.javaClass.simpleName})" }
    }

    /**
     * Delegating Instrumentation.
     *
     * `execStartActivity` is the choke point: every `startActivity()` from guest
     * code funnels through it. We rewrite the target component to a free stub
     * and stash the *real* target in the intent extras, so the system only ever
     * sees an Activity that is genuinely declared in our manifest.
     */
    private class AetherInstrumentation(
        private val base: Instrumentation,
    ) : Instrumentation() {

        override fun newActivity(cl: ClassLoader, className: String, intent: Intent?): Activity? {
            // Guest Activities are instantiated by VirtualActivity with the
            // guest ClassLoader; the framework path must still work for our own
            // (stub) classes.
            return base.newActivity(cl, className, intent)
        }

        override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
            base.callActivityOnCreate(activity, icicle)
        }

        override fun callActivityOnDestroy(activity: Activity) {
            StubPool.release(activity)
            base.callActivityOnDestroy(activity)
        }

        override fun execStartActivity(
            who: Context,
            contextThread: android.os.IBinder?,
            token: android.os.IBinder?,
            target: Activity?,
            intent: Intent,
            requestCode: Int,
        ): Instrumentation.ActivityResult? {
            rewrite(who, intent)
            return super.execStartActivity(who, contextThread, token, target, intent, requestCode)
        }

        /**
         * Point an intent at a stub, remembering the real destination.
         *
         * An explicit component that the host does not declare would be
         * rejected by `system_process` with `ActivityNotFoundException` — and
         * the guest would crash on its very first screen transition.
         */
        private fun rewrite(who: Context, intent: Intent) {
            val component = intent.component ?: return
            val className = component.className
            if (className.startsWith(VirtualActivity.STUB_PACKAGE)) return

            val stub = runCatching {
                StubPool.acquire(component.packageName, StubPool.Spec())
            }.getOrNull() ?: return

            intent.putExtra(VirtualActivity.EXTRA_GUEST_PACKAGE, component.packageName)
            intent.putExtra(VirtualActivity.EXTRA_TARGET_CLASS, className)
            intent.component = android.content.ComponentName(who.packageName, stub.name)
            AetherLog.v(TAG) { "rewrote $className -> ${stub.simpleName}" }
        }
    }

    // ------------------------------------------------------ activity manager

    /**
     * Replace the `IActivityManager` singleton with a proxy.
     *
     * Needed for the calls the guest makes that never pass through our
     * Instrumentation: `getContentProvider`, `getIntentSender`,
     * `getActivityOptions`, and — on API 29+ — `ActivityTaskManager.getService()`.
     */
    private fun hookActivityManager() {
        val (holder, fieldName) = runCatching {
            val amClass = Class.forName("android.app.ActivityManager")
            val singleton = Reflect.get<Any>(null, "IActivityManagerSingleton")
                ?: Reflect.fieldOrNull(amClass, "IActivityManagerSingleton")?.get(null)
            (singleton to "mInstance")
        }.getOrElse {
            val atcClass = Class.forName("android.app.ActivityTaskManager")
            val singleton = Reflect.fieldOrNull(atcClass, "IActivityTaskManagerSingleton")?.get(null)
            (singleton to "mInstance")
        }

        val holderObj = holder ?: error("no IActivityManager singleton")
        val original = Reflect.get<Any>(holderObj, fieldName) ?: error("no mInstance")

        val iface = original.javaClass.interfaces.firstOrNull()
            ?: error("IActivityManager instance implements no interface")

        val proxy = Proxy.newProxyInstance(
            iface.classLoader,
            arrayOf(iface),
            PassthroughHandler(original),
        )
        Reflect.set(holderObj, fieldName, proxy)
        AetherLog.d(TAG) { "IActivityManager proxy installed (${iface.simpleName})" }
    }

    /** Phase 1: pass everything through, but never let a call kill the guest. */
    private class PassthroughHandler(private val delegate: Any) : InvocationHandler {
        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? =
            runCatching { method.invoke(delegate, *(args ?: emptyArray())) }
                .onFailure { AetherLog.v(TAG) { "IActivityManager.${method.name} failed: ${it.cause}" } }
                .getOrNull()
    }

    // ------------------------------------------------------ handler callback

    /**
     * API 28+ launches activities via `ClientTransaction` rather than the old
     * `H.LAUNCH_ACTIVITY` message, so the intent arrives already wrapped. We
     * install a callback ahead of ActivityThread's own handler to inspect (and,
     * in Phase 2, fix up) those transactions.
     */
    private fun hookHandlerCallback(activityThread: Any) {
        val handler = Reflect.get<Handler>(activityThread, "mH") ?: error("no mH")
        val existing = Reflect.get<Handler.Callback>(handler, "mCallback")

        Reflect.set(handler, "mCallback", Handler.Callback { msg ->
            when (msg.what) {
                EXECUTE_TRANSACTION -> AetherLog.v(TAG) { "EXECUTE_TRANSACTION" }
                LAUNCH_ACTIVITY -> AetherLog.v(TAG) { "LAUNCH_ACTIVITY" }
            }
            existing?.handleMessage(msg) ?: false
        })
        AetherLog.d(TAG) { "mH callback installed" }
    }

    private const val LAUNCH_ACTIVITY = 100
    private const val EXECUTE_TRANSACTION = 159
}
