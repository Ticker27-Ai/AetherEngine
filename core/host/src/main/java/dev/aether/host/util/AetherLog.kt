package dev.aether.host.util

import android.util.Log

/**
 * Logging for the container.
 *
 * Two rules:
 *  1. Never log from a path that can be called per-frame by the guest — the
 *     container shares a thread and a heap with the game, and string building
 *     in a hot loop shows up as stutter, not as a log line.
 *  2. Always tag with the guest package. When three virtual apps are live in
 *     one process, "ClassNotFoundException" with no owner is useless.
 */
internal object AetherLog {

    private const val TAG = "Aether"

    @Volatile
    var enabled: Boolean = true

    /** Extra sink for the Flutter shell / `adb shell dumpsys` overlay. */
    @Volatile
    var sink: ((Int, String, String) -> Unit)? = null

    fun v(tag: String, msg: () -> String) = log(Log.VERBOSE, tag, msg)
    fun d(tag: String, msg: () -> String) = log(Log.DEBUG, tag, msg)
    fun i(tag: String, msg: () -> String) = log(Log.INFO, tag, msg)
    fun w(tag: String, msg: () -> String) = log(Log.WARN, tag, msg)

    fun e(tag: String, msg: () -> String, tr: Throwable? = null) {
        if (!enabled) return
        val text = safe(msg)
        Log.e(TAG, "[$tag] $text", tr)
        sink?.invoke(Log.ERROR, tag, text)
    }

    /** For the rare "we are about to crash the process" event. */
    fun wtf(tag: String, msg: () -> String, tr: Throwable? = null) {
        val text = safe(msg)
        Log.wtf(TAG, "[$tag] $text", tr)
        sink?.invoke(Log.ASSERT, tag, text)
    }

    private fun log(priority: Int, tag: String, msg: () -> String) {
        if (!enabled) return
        val text = safe(msg)
        Log.println(priority, TAG, "[$tag] $text")
        sink?.invoke(priority, tag, text)
    }

    /** A failing `toString()` must never take down the caller. */
    private fun safe(msg: () -> String): String = try {
        msg()
    } catch (t: Throwable) {
        "<log message threw: ${t.javaClass.simpleName}>"
    }
}
