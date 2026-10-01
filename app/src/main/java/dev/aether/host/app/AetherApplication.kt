package dev.aether.host.app

import android.app.Application
import android.content.Context
import dev.aether.host.core.GuestProfile
import dev.aether.host.core.VirtualCore
import dev.aether.host.util.AetherLog

/**
 * Host application.
 *
 * The container MUST be initialised from `attachBaseContext()`, not `onCreate()`:
 * the hidden-API unseal has to happen before the first class that touches a
 * blacklisted member is loaded, and `onCreate()` is already too late for the
 * framework's own initialisation path on some OEM builds.
 */
class AetherApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        VirtualCore.initialize(this)
        AetherLog.i("App") { "host attached: ${VirtualCore.report().lines().take(3)}" }
    }

    override fun onCreate() {
        super.onCreate()

        // Install the default target. Real shells discover APKs on disk and let
        // the user pick; this keeps the reference host runnable out of the box.
        val dir = filesDir.resolve("guests/${GuestProfile.EIGHT_BALL_POOL.packageName}")
        if (dir.isDirectory) {
            runCatching { VirtualCore.install(GuestProfile.EIGHT_BALL_POOL, dir) }
                .onFailure { AetherLog.e("App", { "install failed" }, it) }
        } else {
            AetherLog.w("App") {
                "no guest APKs at ${dir.absolutePath}; the shell will ask the user " +
                    "to provide base.apk + split_config.*.apk"
            }
        }
    }
}
