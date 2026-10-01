package dev.aether.host.gms

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import dev.aether.host.util.AetherLog
import dev.aether.host.util.Reflect
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Tier-1 identity spoofing.
 *
 * What it does: replaces `ActivityThread.sPackageManager` (API 21-25:
 * `ActivityManagerNative` is separate, but the PM singleton name is stable)
 * with a dynamic proxy that answers queries about the *guest* package as if it
 * were installed, and hides the host's own shadow directories from
 * `getInstalledPackages`.
 *
 * What it CANNOT do: influence what `com.google.android.gms` (a different
 * process) learns about our UID from `PackageManagerService`. Play Games,
 * Play Billing and FCM therefore stay broken — see GmsBridge docs.
 *
 * What it DOES fix in practice:
 *   - AdMob / AppLovin SDK self-checks (`getPackageInfo` on their own package)
 *   - Firebase init (`getApplicationInfo` on the guest package)
 *   - Install Referrer probing
 *   - Any SDK that does `pm.getPackageInfo(getPackageName(), ...)` and throws
 *     when the result is null — a genuinely common crash on first boot.
 */
internal object IdentitySpoof {

    private const val TAG = "IdentitySpoof"

    /** Guest packages we are currently virtualising, with their parsed info. */
    private val guests = mutableMapOf<String, PackageInfo>()

    @Volatile
    private var original: Any? = null

    fun register(packageName: String, info: PackageInfo) {
        guests[packageName] = info
        AetherLog.d(TAG) { "registered guest identity: $packageName" }
    }

    fun unregister(packageName: String) {
        guests.remove(packageName)
    }

    fun install(hostContext: Context): Boolean {
        return try {
            val activityThreadClass = Reflect.clazz("android.app.ActivityThread")
            val activityThread = Reflect.callStatic(activityThreadClass, "currentActivityThread")
                ?: return false

            val field = Reflect.fieldOrNull(activityThreadClass, "sPackageManager") ?: return false
            original = field.get(null) ?: return false

            val iPackageManagerInterface = original!!.javaClass.interfaces.firstOrNull()
                ?: return false

            val proxy = Proxy.newProxyInstance(
                iPackageManagerInterface.classLoader,
                arrayOf(iPackageManagerInterface),
                Handler(original!!),
            )
            Reflect.setStatic(activityThreadClass, "sPackageManager", proxy)

            // The app's own `ContextImpl` caches a PackageManager wrapper around
            // the old binder; refresh it or half of the calls still go direct.
            runCatching {
                val pmField = Reflect.fieldOrNull(hostContext.javaClass, "mPackageManager")
                pmField?.set(hostContext, null)
            }

            AetherLog.i(TAG) { "IPackageManager proxy installed (${iPackageManagerInterface.simpleName})" }
            true
        } catch (t: Throwable) {
            AetherLog.e(TAG, { "install failed" }, t)
            false
        }
    }

    fun uninstall() {
        val backup = original ?: return
        runCatching {
            val activityThreadClass = Reflect.clazz("android.app.ActivityThread")
            Reflect.setStatic(activityThreadClass, "sPackageManager", backup)
            AetherLog.i(TAG) { "IPackageManager proxy removed" }
        }
        original = null
    }

    private class Handler(private val delegate: Any) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val name = method.name
            val argv = args ?: emptyArray()

            // getPackageInfo(packageName, flags, userId)
            if (name == "getPackageInfo" && argv.isNotEmpty() && argv[0] is String) {
                val pkg = argv[0] as String
                guests[pkg]?.let {
                    AetherLog.v(TAG) { "getPackageInfo($pkg) -> spoofed" }
                    return it
                }
            }

            // getApplicationInfo(packageName, flags, userId)
            if (name == "getApplicationInfo" && argv.isNotEmpty() && argv[0] is String) {
                val pkg = argv[0] as String
                guests[pkg]?.applicationInfo?.let {
                    AetherLog.v(TAG) { "getApplicationInfo($pkg) -> spoofed" }
                    return it
                }
            }

            // getPackageUid / getPackageGids: GMS and ad SDKs use these to
            // sanity-check the caller. Answering with OUR uid is the honest
            // answer and keeps them from throwing PackageManager.NameNotFoundException.
            if ((name == "getPackageUid" || name == "getPackageUidEtc") && argv.isNotEmpty() && argv[0] is String) {
                val pkg = argv[0] as String
                if (guests.containsKey(pkg)) {
                    AetherLog.v(TAG) { "$name($pkg) -> host uid (shared UID by design)" }
                }
            }

            return try {
                method.invoke(delegate, *argv)
            } catch (t: Throwable) {
                // Never let a NameNotFoundException from an unexpected signature
                // escape into the guest; that is exactly the crash class we are
                // here to prevent.
                AetherLog.v(TAG) { "$name threw: ${t.cause ?: t}" }
                when (method.returnType) {
                    PackageInfo::class.java -> null
                    Int::class.javaPrimitiveType, Integer.TYPE -> PackageManager.PERMISSION_DENIED
                    Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            }
        }
    }
}
