package dev.aether.host.loader

/**
 * ClassLoader for the guest.
 *
 * Delegation is **child-first** for everything except platform packages. A
 * stock `DexClassLoader` is parent-first, which means a guest that bundles its
 * own copy of a library the host also ships silently gets the *host's* version
 * — mismatched against the guest's own resources and a nightmare to debug.
 *
 * Platform prefixes must stay parent-first: `android.*` lives in the boot
 * classpath only, and child-first would make the guest load a duplicate
 * framework class that cannot be cast to the real one.
 */
internal class GuestClassLoader(
    dexPath: String,
    optimizedDirectory: File,
    librarySearchPath: String,
    parent: ClassLoader,
    private val guestPackage: String,
) : dalvik.system.DexClassLoader(dexPath, optimizedDirectory.absolutePath, librarySearchPath, parent) {

    private val platformPrefixes = arrayOf(
        "java.", "javax.", "android.", "androidx.", "com.android.", "dalvik.", "kotlin.", "kotlinx.",
    )

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (platformPrefixes.any { name.startsWith(it) }) {
            return super.loadClass(name, resolve)
        }
        findLoadedClass(name)?.let { return it }
        return try {
            findClass(name)
        } catch (e: ClassNotFoundException) {
            super.loadClass(name, resolve)
        }
    }

    override fun toString(): String = "GuestClassLoader($guestPackage)"
}
