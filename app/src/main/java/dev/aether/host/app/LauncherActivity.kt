package dev.aether.host.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dev.aether.host.core.GuestProfile
import dev.aether.host.core.VirtualCore

/**
 * Minimal launcher: installs the default guest (if present) and starts it.
 *
 * A production shell replaces this with a real UI (see ui/flutter), but the
 * contract with the container is exactly these three calls:
 *
 *   1. VirtualCore.initialize(application)   — once, from attachBaseContext
 *   2. VirtualCore.install(profile, apkDir)  — per guest
 *   3. VirtualCore.launch(context, pkg)      — per screen
 */
class LauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val profile = GuestProfile.EIGHT_BALL_POOL
        val dir = filesDir.resolve("guests/${profile.packageName}")

        runCatching { VirtualCore.install(profile, dir) }
            .onSuccess { VirtualCore.launch(this, profile.packageName) }
            .onFailure {
                // Surface it rather than dying silently: the most common cause
                // is a missing split_config.<abi>.apk, which the user can fix.
                dev.aether.host.util.AetherLog.e("Launcher", { "cannot start ${profile.displayName}" }, it)
            }

        finish()
    }
}
