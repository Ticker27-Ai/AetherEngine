package dev.aether.host.runtime

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import dev.aether.host.core.VirtualCore
import dev.aether.host.util.AetherLog
import dev.aether.host.util.Reflect

/**
 * [PHASE 1] VirtualActivity — the proxy that hosts a guest's lifecycle.
 *
 * The system only ever starts **this** class (a real, declared Activity). It
 * then creates the guest's real Activity by hand and forwards every callback.
 *
 * Why stubs rather than one generic proxy: Android resolves `launchMode`,
 * `theme`, `configChanges`, `screenOrientation` and `windowSoftInputMode` from
 * the *manifest entry of the started component*. A single proxy would force one
 * launch mode on every screen of the game — back stacks collapse, dialogs
 * become full screens, and rotation restarts the game. So we pre-declare a pool
 * of stubs covering every combination we care about and pick one per launch;
 * see `StubPool` and `tools/gen-stubs`.
 *
 * Lifecycle forwarding goes through `Instrumentation.callActivityOnXxx()`
 * rather than calling `onCreate()` directly: those are public API, and they
 * perform the internal bookkeeping (`performCreate`, `mCalled`, fragment
 * dispatch) that a bare call would skip.
 */
open class VirtualActivity : Activity() {

    private val tag = "Virtual/${javaClass.simpleName}"

    /** The real guest Activity, created by us and driven by us. */
    private var guest: Activity? = null
    private var guestIntent: Intent? = null
    private var instrumentation: android.app.Instrumentation? = null

    // --------------------------------------------------------------- create

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent.getStringExtra(EXTRA_GUEST_PACKAGE)
        val targetClass = intent.getStringExtra(EXTRA_TARGET_CLASS)

        if (packageName == null || targetClass == null) {
            AetherLog.e(tag) { "launched without a target; finishing" }
            finish()
            return
        }

        val session = VirtualCore.runningSession(packageName)
        if (session == null) {
            AetherLog.e(tag) { "no running session for $packageName; finishing" }
            finish()
            return
        }

        val activityInfo = session.activities[targetClass]
        if (activityInfo == null) {
            AetherLog.e(tag) {
                "$targetClass is not declared in the guest manifest " +
                    "(known: ${session.activities.keys.take(5)})"
            }
            finish()
            return
        }

        val imp = try {
            session.classLoader.loadClass(targetClass).getDeclaredConstructor().newInstance() as Activity
        } catch (t: Throwable) {
            AetherLog.e(tag, { "cannot instantiate $targetClass" }, t)
            finish()
            return
        }

        val instrumentation = session.instrumentation
        this.instrumentation = instrumentation

        // Rewrite the intent so the guest sees its own component, not our stub.
        val guestIntent = Intent(intent).apply {
            component = ComponentName(packageName, targetClass)
            removeExtra(EXTRA_GUEST_PACKAGE)
            removeExtra(EXTRA_TARGET_CLASS)
        }
        this.guestIntent = guestIntent

        try {
            val state = ActivityAttacher.capture(this, session.application)
            ActivityAttacher.attach(imp, state, guestIntent, activityInfo, lastNonConfigurationInstance)
        } catch (t: Throwable) {
            AetherLog.e(tag, { "attach failed for $targetClass" }, t)
            finish()
            return
        }

        guest = imp
        VirtualCore.onGuestActivityStarted(packageName, imp)

        try {
            instrumentation.callActivityOnCreate(imp, savedInstanceState)
        } catch (t: Throwable) {
            AetherLog.e(tag, { "guest onCreate threw" }, t)
            finish()
        }
    }

    // ------------------------------------------------------------ lifecycle

    override fun onStart() {
        super.onStart()
        guest?.let { runCatching { instrumentation?.callActivityOnStart(it) } }
    }

    override fun onResume() {
        super.onResume()
        val imp = guest ?: return
        runCatching { instrumentation?.callActivityOnResume(imp) }
            .onFailure { AetherLog.e(tag, { "guest onResume threw" }, it) }
    }

    override fun onPause() {
        guest?.let { runCatching { instrumentation?.callActivityOnPause(it) } }
        super.onPause()
    }

    override fun onStop() {
        guest?.let { runCatching { instrumentation?.callActivityOnStop(it) } }
        super.onStop()
    }

    override fun onDestroy() {
        val imp = guest
        if (imp != null) {
            runCatching { instrumentation?.callActivityOnDestroy(imp) }
            VirtualCore.onGuestActivityDestroyed(imp)
        }
        guest = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        guest?.let { runCatching { instrumentation?.callActivityOnSaveInstanceState(it, outState) } }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        guest?.let {
            runCatching { instrumentation?.callActivityOnRestoreInstanceState(it, savedInstanceState) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        guest?.let { runCatching { instrumentation?.callActivityOnNewIntent(it, intent) } }
    }

    // --------------------------------------------------------------- events

    override fun onBackPressed() {
        // The guest owns its own back handling (games use it to exit menus).
        val imp = guest ?: run { super.onBackPressed(); return }
        runCatching { callProtected(imp, "onBackPressed") }
            .onFailure { super.onBackPressed() }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val imp = guest ?: return super.dispatchTouchEvent(ev)
        return runCatching { imp.dispatchTouchEvent(ev) }
            .getOrDefault(super.dispatchTouchEvent(ev))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val imp = guest ?: return super.dispatchKeyEvent(event)
        return runCatching { imp.dispatchKeyEvent(event) }
            .getOrDefault(super.dispatchKeyEvent(event))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        guest?.let { runCatching { callProtected(it, "onConfigurationChanged", newConfig) } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        guest?.let { runCatching { it.onWindowFocusChanged(hasFocus) } }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        guest?.let {
            runCatching { callProtected(it, "onActivityResult", requestCode, resultCode, data) }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        guest?.let {
            runCatching {
                callProtected(it, "onRequestPermissionsResult", requestCode, permissions, grantResults)
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        guest?.onLowMemory()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        guest?.onTrimMemory(level)
    }

    /**
     * `Activity.onBackPressed()` etc. are protected, so a direct call from a
     * different package needs reflection. Kept tiny and non-throwing by design.
     */
    private fun callProtected(target: Activity, name: String, vararg args: Any?): Any? {
        val method = Reflect.methodOrNull(target.javaClass, name, args.size) ?: return null
        return method.invoke(target, *args)
    }

    companion object {
        const val EXTRA_GUEST_PACKAGE = "dev.aether.intent.GUEST_PACKAGE"
        const val EXTRA_TARGET_CLASS = "dev.aether.intent.TARGET_CLASS"

        /** Reserved for Phase 2 result rewriting. */
        const val EXTRA_RESULT_BRIDGE = "dev.aether.intent.RESULT_BRIDGE"

        /** Every stub Activity in the pool subclasses VirtualActivity. */
        const val STUB_PACKAGE = "dev.aether.host.runtime"
    }
}
