package dev.aether.host.loader

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import dev.aether.host.vfs.VfsRouter
import java.io.File

/**
 * The [Context] the guest sees.
 *
 * Two jobs, in priority order:
 *
 *  1. **Identity** — `getPackageName()` returns the guest's package. Every SDK
 *     in the process (ads, analytics, the game's own code) uses this.
 *  2. **Storage redirection** — every directory getter is routed through
 *     [VfsRouter], so `getFilesDir()` returns a path the guest may actually
 *     write to and does not collide with the host or with a sibling guest.
 *
 * Deliberately NOT overridden yet (Phase 2+): `startActivity`, `bindService`,
 * `registerReceiver`, `getSystemService`. Those need the ActivityManager and
 * PackageManager hooks in `HostInitializer` to be useful; overriding them
 * before then would produce failures that look like framework bugs.
 */
internal class GuestContext(
    base: Context,
    private val packageName: String,
    private val applicationInfo: android.content.pm.ApplicationInfo,
    private val guestResources: Resources,
    private val guestClassLoader: ClassLoader,
    private val vfs: VfsRouter,
) : ContextWrapper(base) {

    // ------------------------------------------------------------- identity

    override fun getPackageName(): String = packageName
    override fun getBasePackageName(): String = packageName
    override fun getOpPackageName(): String = packageName

    override fun getApplicationContext(): Context = this
    override fun getApplicationInfo(): android.content.pm.ApplicationInfo = applicationInfo

    override fun getClassLoader(): ClassLoader = guestClassLoader
    override fun getResources(): Resources = guestResources
    override fun getAssets(): android.content.res.AssetManager = guestResources.assets

    override fun getPackageCodePath(): String = applicationInfo.sourceDir
    override fun getPackageResourcePath(): String = applicationInfo.sourceDir

    // -------------------------------------------------------------- storage
    //
    // All directory getters go through the VFS. `require` throws when the
    // policy denies the path, which is intentional: a null/denied filesDir is a
    // bug in our profile, and failing loudly at first access beats corrupting
    // the host's data later.

    override fun getFilesDir(): File = dir("/files")
    override fun getNoBackupFilesDir(): File = dir("/no_backup")
    override fun getCacheDir(): File = dir("/cache")
    override fun getCodeCacheDir(): File = dir("/code_cache")

    override fun getDir(name: String, mode: Int): File = dir("/app_$name")
    override fun getFileStreamPath(name: String): File = File(getFilesDir(), name)

    override fun getDatabasePath(name: String): File {
        val dir = dir("/databases")
        return if (name.startsWith(File.separatorChar)) File(name) else File(dir, name)
    }

    override fun getSharedPreferencesPath(name: String): File =
        File(dir("/shared_prefs"), "$name.xml")

    override fun getExternalFilesDir(type: String?): File? =
        external("/files", type)

    override fun getExternalCacheDir(): File? = external("/cache", null)
    override fun getObbDir(): File? = external("/obb", null)

    private fun dir(suffix: String): File {
        val host = vfs.require("/data/data/$packageName$suffix")
        val f = File(host)
        if (!f.exists()) {
            // First access after install: create the shadow directory. Without
            // this, SQLite and SharedPreferences fail with "no such directory"
            // and the game reports a corrupt save.
            f.mkdirs()
        }
        return f
    }

    private fun external(base: String, type: String?): File? {
        val leaf = when {
            type.isNullOrEmpty() -> base
            type.startsWith(File.separatorChar) -> type
            else -> "$base/$type"
        }
        return runCatching {
            File(vfs.require("/sdcard/Android/data/$packageName$leaf")).apply { mkdirs() }
        }.onFailure {
            dev.aether.host.util.AetherLog.w("GuestContext") {
                "external dir denied: /sdcard/Android/data/$packageName$leaf ($it)"
            }
        }.getOrNull()
    }
}
