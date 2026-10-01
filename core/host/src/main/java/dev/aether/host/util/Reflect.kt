package dev.aether.host.util

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Minimal reflection helpers for the hidden/blacklisted framework internals the
 * container has to touch.
 *
 * Every accessor walks the *whole* superclass chain, because the field we want
 * (`mToken`, `mInstrumentation`, ...) is usually declared on the framework base
 * class while the object we hold is an OEM subclass.
 *
 * All of this requires the hidden-API unseal to have run first — see
 * [dev.aether.host.native.AetherNative.unsealHiddenApi].
 */
internal object Reflect {

    fun clazz(name: String, loader: ClassLoader? = null): Class<*> =
        if (loader == null) Class.forName(name) else Class.forName(name, true, loader)

    /** May return null instead of throwing (used for version probing). */
    fun clazzOrNull(name: String, loader: ClassLoader? = null): Class<*>? = try {
        clazz(name, loader)
    } catch (t: Throwable) {
        null
    }

    fun field(target: Class<*>, name: String): Field {
        var current: Class<*>? = target
        while (current != null) {
            runCatching { return current!!.getDeclaredField(name).apply { isAccessible = true } }
            current = current.superclass
        }
        throw NoSuchFieldException("$name on ${target.name}")
    }

    fun fieldOrNull(target: Class<*>, name: String): Field? = try {
        field(target, name)
    } catch (t: Throwable) {
        null
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(target: Any?, name: String): T? {
        if (target == null) return null
        return runCatching { field(target.javaClass, name).get(target) as T? }
            .onFailure { AetherLog.v("Reflect") { "get $name failed: $it" } }
            .getOrNull()
    }

    fun <T> getStatic(clazz: Class<*>, name: String): T? =
        runCatching { field(clazz, name).get(null) as T? }.getOrNull()

    fun set(target: Any?, name: String, value: Any?): Boolean {
        if (target == null) return false
        return runCatching {
            val f = field(target.javaClass, name)
            f.set(target, value)
            true
        }.getOrDefault(false)
    }

    fun setStatic(clazz: Class<*>, name: String, value: Any?): Boolean =
        runCatching { field(clazz, name).set(null, value); true }.getOrDefault(false)

    /**
     * Find a declared method by name across the hierarchy, optionally matching
     * the parameter count. Used for APIs whose signature drifts every release
     * (`Activity.attach`, `AssetManager.addAssetPath`, `PackageParser.parsePackage`).
     */
    fun method(target: Class<*>, name: String, paramCount: Int? = null): Method {
        var current: Class<*>? = target
        while (current != null) {
            val hit = current!!.declaredMethods.firstOrNull {
                it.name == name && (paramCount == null || it.parameterTypes.size == paramCount)
            }
            if (hit != null) {
                hit.isAccessible = true
                return hit
            }
            current = current.superclass
        }
        throw NoSuchMethodException("$name/${paramCount ?: "?"} on ${target.name}")
    }

    fun methodOrNull(target: Class<*>, name: String, paramCount: Int? = null): Method? =
        runCatching { method(target, name, paramCount) }.getOrNull()

    fun call(target: Any?, name: String, vararg args: Any?): Any? {
        if (target == null) return null
        val m = method(target.javaClass, name, args.size)
        return m.invoke(target, *args)
    }

    fun callStatic(clazz: Class<*>, name: String, vararg args: Any?): Any? =
        method(clazz, name, args.size).invoke(null, *args)

    /**
     * Invoke a method whose parameter list we cannot name (framework internals
     * are not on the bootclasspath for us): resolve each declared parameter
     * type through [resolve]. This is how [dev.aether.host.runtime.ActivityAttacher]
     * survives `Activity.attach` signature changes between API levels.
     */
    fun invokeByType(target: Any, m: Method, resolve: (Class<*>, Int) -> Any?): Any? {
        val args = Array<Any?>(m.parameterTypes.size) { i ->
            resolve(m.parameterTypes[i], i)
        }
        return m.invoke(target, *args)
    }

    fun isStatic(m: Method): Boolean = Modifier.isStatic(m.modifiers)
}
