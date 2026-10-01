package dev.aether.host.loader

import android.content.res.AssetManager
import android.content.res.Resources
import dev.aether.host.util.AetherLog
import dev.aether.host.util.Reflect
import java.io.File

/**
 * Builds a [Resources] instance backed by the guest's APK(s).
 *
 * `AssetManager.addAssetPath()` is a hidden API, so the unseal must have run.
 *
 * Split-APK rule: **every** split must be added, in manifest order (base
 * first, then config splits, then asset packs). Adding only the base is the
 * classic cause of "the game boots but every graphic is missing".
 */
internal object GuestResources {

    private const val TAG = "Resources"

    fun create(hostResources: Resources, apkPaths: List<String>): Resources {
        val assets = newAssetManager()

        val added = mutableListOf<String>()
        for (path in apkPaths) {
            val cookie = addAssetPath(assets, path)
            if (cookie == 0) {
                // 0 == failure. Do not silently continue: a split that fails to
                // attach means missing resources at runtime, which is far harder
                // to diagnose than an install-time error.
                AetherLog.e(TAG) { "addAssetPath failed for $path" }
            } else {
                added += path
            }
        }
        require(added.isNotEmpty()) { "no APK could be attached to the AssetManager" }

        return Resources(
            assets,
            hostResources.displayMetrics,
            hostResources.configuration,
        ).also {
            AetherLog.i(TAG) { "resources ready from ${added.size} split(s): ${added.map { File(it).name }}" }
        }
    }

    /**
     * `new AssetManager()` is hidden on modern Android; the no-arg constructor
     * is preferred over `AssetManager.class.newInstance()` because the latter
     * became a hidden-API target in its own right on API 30+.
     */
    private fun newAssetManager(): AssetManager = try {
        AssetManager::class.java.getDeclaredConstructor().newInstance()
    } catch (t: Throwable) {
        AetherLog.w(TAG) { "AssetManager() constructor blocked, falling back to system instance" }
        AssetManager::class.java
            .getDeclaredMethod("getSystem")
            .invoke(null) as AssetManager
    }

    /**
     * @return the asset cookie (non-zero on success, 0 on failure).
     */
    private fun addAssetPath(assets: AssetManager, path: String): Int {
        return try {
            val method = Reflect.methodOrNull(AssetManager::class.java, "addAssetPath", 1)
                ?: Reflect.methodOrNull(AssetManager::class.java, "addAssetPathInternal", 1)
                ?: return 0
            when (val result = method.invoke(assets, path)) {
                is Int -> result
                is Integer -> result.toInt()
                else -> 0
            }
        } catch (t: Throwable) {
            AetherLog.e(TAG, { "addAssetPath threw for $path" }, t)
            0
        }
    }
}
