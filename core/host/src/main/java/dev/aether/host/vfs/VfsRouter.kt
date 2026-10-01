package dev.aether.host.vfs

import dev.aether.host.native.AetherNative
import dev.aether.host.util.AetherLog

/**
 * Kotlin façade over the Rust policy engine (`core/vfs`).
 *
 * One instance per virtualised package. Every path the guest touches must go
 * through [toHost]; the IO-redirection layer in Phase 2 calls nothing else.
 * Until that layer exists, [toHost] is also what the Kotlin-side Context
 * wrapper ([dev.aether.host.loader.GuestContext]) uses to answer
 * `getFilesDir()` and friends.
 */
class VfsRouter internal constructor(
    val packageName: String,
    internal val handle: Long,
) {

    /** Mirrors `aether_vfs::SharedMedia`. */
    enum class SharedMedia(val code: Int) {
        /** Guest sees the real shared storage (default; leaky but compatible). */
        Passthrough(0),

        /** Guest gets a private copy of shared storage. */
        Isolate(1),

        /** Shared storage is blocked entirely. */
        Deny(2),
    }

    /** Mirrors `aether_vfs::Action`. */
    enum class Action(val code: Int) {
        Shadow(0),
        Passthrough(1),
        ReadOnlyPassthrough(2),
        Deny(3),
    }

    /** Translate a guest path into the host path to syscall against. */
    fun toHost(guestPath: String): String? =
        AetherNative.vfsToHost(handle, guestPath)

    /** [toHost], but throwing — for paths the guest is entitled to. */
    fun require(guestPath: String, write: Boolean = false): String {
        val host = toHost(guestPath)
        if (host == null) {
            throw VfsDeniedException(guestPath, AetherNative.lastError())
        }
        if (write && !allows(guestPath, write = true)) {
            throw VfsDeniedException(guestPath, "write not permitted")
        }
        return host
    }

    /**
     * Translate, falling back to the original path. Used by the IO hooks where
     * failing an open() is worse than leaking a path (read-only system files).
     */
    fun toHostOrDefault(guestPath: String): String = toHost(guestPath) ?: guestPath

    /** Reverse mapping: host path -> the path the guest should see. */
    fun toGuest(hostPath: String): String? =
        AetherNative.vfsToGuest(handle, hostPath)?.takeIf { it.isNotEmpty() }

    fun allows(guestPath: String, write: Boolean = false): Boolean =
        AetherNative.vfsCheck(handle, guestPath, write)

    /**
     * Override the built-in policy at highest priority. Used by per-game
     * profiles (see `guests/<package>.toml`).
     *
     * Note: a rule's *matched prefix is replaced* by [bucket], so two rules
     * sharing a bucket must not use colliding source names.
     */
    fun addRule(template: String, action: Action, bucket: String = "-") {
        val rc = AetherNative.vfsAddRule(handle, template, action.code, bucket)
        if (rc != 0) {
            AetherLog.w("Vfs") { "addRule('$template') failed: rc=$rc ${AetherNative.lastError()}" }
        }
    }

    /** Register a symlink that exists in the guest's world view only. */
    fun addLink(from: String, to: String) {
        AetherNative.vfsAddLink(handle, from, to)
    }

    val isValid: Boolean get() = handle > 0

    companion object {
        fun create(
            guestPackage: String,
            hostPackage: String,
            hostDataDir: String,
            userId: Int = 0,
            strict: Boolean = true,
            sharedMedia: SharedMedia = SharedMedia.Passthrough,
        ): VfsRouter {
            AetherNative.ensureLoaded()
            val handle = AetherNative.vfsCreate(
                guestPackage, hostPackage, hostDataDir, userId, strict, sharedMedia.code,
            )
            if (handle <= 0) {
                throw IllegalStateException(
                    "vfsCreate failed for $guestPackage (rc=$handle): ${AetherNative.lastError()}",
                )
            }
            AetherLog.i("Vfs") { "policy engine ready for $guestPackage (handle=$handle)" }
            return VfsRouter(guestPackage, handle)
        }
    }
}

/** Thrown when the isolation policy refuses a path. */
class VfsDeniedException(
    val guestPath: String,
    val reason: String,
) : SecurityException("VFS denied '$guestPath': $reason")
