package dev.aether.host.runtime

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.Window
import androidx.lifecycle.ViewModelStore
import dev.aether.host.util.AetherLog
import dev.aether.host.util.Reflect

/**
 * Attaches a *guest* Activity to the *host's* Activity machinery.
 *
 * `Activity.attach()` is hidden, takes 12-18 parameters depending on the API
 * level, and the parameter list has changed roughly every other release (added
 * `Window`, then `ViewModelStore`, then more). Hard-coding a signature is the
 * single most common reason a container breaks on a new Android version.
 *
 * Instead we:
 *   1. locate `attach` by name (any parameter count),
 *   2. resolve **each declared parameter by type** from the host Activity,
 *   3. invoke reflectively.
 *
 * Unknown parameter types resolve to null, which is correct for every optional
 * trailing parameter Android has ever added.
 */
internal object ActivityAttacher {

    private const val TAG = "Attacher"

    /** Values pulled off the real (stub) Activity before we call attach(). */
    class HostState(
        val activity: Activity,
        val activityThread: Any?,
        val instrumentation: Instrumentation?,
        val application: Application,
        val token: android.os.IBinder?,
        val ident: Int,
        val embeddedId: String?,
        val title: CharSequence?,
        val voiceInteractor: Any?,
    )

    fun capture(activity: Activity, application: Application): HostState {
        val activityThread = Reflect.callStatic(
            Class.forName("android.app.ActivityThread"), "currentActivityThread",
        )
        return HostState(
            activity = activity,
            activityThread = activityThread,
            instrumentation = Reflect.get(activityThread, "mInstrumentation"),
            application = application,
            token = Reflect.get(activity, "mToken"),
            ident = Reflect.get<Int>(activity, "mIdent") ?: 0,
            embeddedId = Reflect.get(activity, "mEmbeddedID"),
            title = Reflect.get(activity, "mTitle"),
            voiceInteractor = Reflect.get(activity, "mVoiceInteractor"),
        )
    }

    /**
     * Perform the attach. Throws if `attach` cannot be found — there is no
     * meaningful degraded mode for "the guest Activity cannot be created".
     */
    fun attach(
        guest: Activity,
        state: HostState,
        intent: Intent,
        info: ActivityInfo,
        nonConfigurationInstances: Any? = null,
    ) {
        val method = Reflect.methodOrNull(Activity::class.java, "attach")
            ?: throw NoSuchMethodException("Activity.attach() not found")

        // Several parameters share a type (there can be up to three IBinders:
        // token, assistToken and shareableActivityToken). Hand them out in
        // declaration order from a queue.
        val binders = ArrayDeque(listOf(state.token, null, null))
        var stringConsumed = false

        Reflect.invokeByType(guest, method) { type, _ ->
            when {
                type == Context::class.java -> state.activity as Context
                type.name == "android.app.ActivityThread" -> state.activityThread
                type == Instrumentation::class.java -> state.instrumentation
                type == android.os.IBinder::class.java -> binders.removeFirstOrNull()
                type == Int::class.javaPrimitiveType -> state.ident
                type == Application::class.java -> state.application
                type == Intent::class.java -> intent
                type == ActivityInfo::class.java -> info

                type == CharSequence::class.java -> state.title
                type == Activity::class.java -> null            // parent
                type == String::class.java -> when {
                    // The first String is the embedded id; later ones (referrer,
                    // attribution tag) are unused by an attach() we drive.
                    !stringConsumed -> { stringConsumed = true; state.embeddedId }
                    else -> null
                }

                type == Configuration::class.java -> state.activity.resources.configuration
                type == Bundle::class.java -> null
                type.name.endsWith("NonConfigurationInstances") -> nonConfigurationInstances
                type.name.endsWith("IVoiceInteractor") ||
                    type.name == "com.android.internal.app.IVoiceInteractor" -> state.voiceInteractor
                type == Window::class.java ->
                    // Hand the guest our real window. This is what makes
                    // setContentView() inside the guest actually draw.
                    state.activity.window
                type == ViewModelStore::class.java ||
                    type.name.endsWith("ViewModelStore") -> state.activity.viewModelStore

                else -> {
                    AetherLog.v(TAG) { "attach(): unmapped parameter ${type.name} -> null" }
                    null
                }
            }
        }

        AetherLog.d(TAG) { "attached ${guest.javaClass.name} (${method.parameterTypes.size} params)" }
    }
}
