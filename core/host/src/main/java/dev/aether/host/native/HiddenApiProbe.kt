package dev.aether.host.native

/**
 * Callback used by the C++ calibration routine in `hidden_api.cpp`.
 *
 * The unseal works by *experiment*: native code writes `kNoChecks` into a
 * candidate word of `art::Runtime`, then calls this method to find out whether
 * reflection escaped the sandbox. That means this method:
 *
 *  - is called up to ~1024 times during a cold start, so it must stay cheap;
 *  - must NEVER throw — a throw here aborts calibration and the guest will be
 *    unable to load;
 *  - must exercise a genuinely *blacklisted* member, otherwise it returns true
 *    before the policy has actually been cleared and we accept the wrong slot.
 *
 * `VMRuntime.setHiddenApiExemptions` is on the blacklist on every API level
 * that has the restriction at all (28+), which makes it the ideal probe.
 */
internal class HiddenApiProbe {

    @Volatile
    var invocations: Int = 0
        private set

    /**
     * @return true when hidden/blacklisted members are reachable.
     */
    fun tryBlacklistedReflection(): Boolean {
        invocations++
        return try {
            val vmRuntime = Class.forName("dalvik.system.VMRuntime")
            // Blocked with NoSuchMethodError while enforcement is active.
            vmRuntime.getDeclaredMethod(
                "setHiddenApiExemptions",
                Array<String>::class.java,
            )
            true
        } catch (t: Throwable) {
            // Deliberately broad: ART throws NoSuchMethodError, LinkageError or
            // NoSuchMethodException depending on the release.
            false
        }
    }
}
