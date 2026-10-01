//! End-to-end policy tests that mirror real-world game/engine IO patterns.
//!
//! These are the tests that must never regress: they encode the *security*
//! guarantees of the container (isolation between guests, host protection) as
//! well as the compatibility guarantees (the paths a game expects to work).

use aether_vfs::{Action, DenyReason, Mode, SharedMedia, Status, Vfs, VfsConfig};

const GUEST: &str = "com.target.game";
const HOST: &str = "dev.aether.host";
const HOST_DIR: &str = "/data/user/0/dev.aether.host";

fn vfs() -> Vfs {
    Vfs::new(VfsConfig::new(GUEST, HOST, HOST_DIR))
}

fn shadow(p: &str) -> String {
    vfs().translate(p).path
}

// ---------------------------------------------------------------------------
// Compatibility: what a real game actually touches
// ---------------------------------------------------------------------------

#[test]
fn unity_il2cpp_runtime_paths() {
    let v = vfs();
    // Unity: Application.persistentDataPath, streamingAssets, il2cpp dumps
    for p in [
        "/data/data/com.target.game/files/il2cpp/global-metadata.dat",
        "/data/data/com.target.game/files/UnityCache/Shader/cache.bin",
        "/data/data/com.target.game/databases/save.db",
        "/data/data/com.target.game/shared_prefs/player.xml",
        "/data/data/com.target.game/app_webview/Cookies",
    ] {
        let r = v.resolve(p, Mode::Write);
        assert_eq!(r.status, Status::Shadowed, "{p} must be shadowed");
        assert!(r.path.starts_with("/data/user/0/dev.aether.host/aether/virtual/com.target.game/data/"));
    }
}

#[test]
fn unreal_expansion_files() {
    let v = vfs();
    let r = v.translate("/sdcard/Android/obb/com.target.game/main.12345.com.target.game.obb");
    assert_eq!(r.status, Status::Shadowed);
    assert!(r.path.ends_with("/obb/main.12345.com.target.game.obb"));
    assert!(v.resolve("/sdcard/Android/obb/com.target.game/patch.obb", Mode::Read).writable);
}

#[test]
fn native_libraries_and_apk_are_readable() {
    let v = vfs();
    let r = v.translate("/data/app/com.target.game-aBcD1234==/base.apk");
    assert_eq!(r.status, Status::Native);
    assert!(!r.writable);
    assert_eq!(v.resolve("/data/app/com.target.game-aBcD1234==/base.apk", Mode::Write).status, Status::Denied);

    let r = v.translate("/data/app/~~xYz==/com.target.game-AbC==/base.apk");
    assert_eq!(r.status, Status::Native, "Android 10+ randomized app dir must resolve");
}

#[test]
fn engine_reads_system_libraries_and_proc() {
    let v = vfs();
    let _ = &v;
    assert_eq!(v.translate("/system/lib64/libEGL.so").status, Status::Native);
    assert_eq!(v.translate("/vendor/lib64/libGLESv3.so").status, Status::Native);
    assert_eq!(v.translate("/apex/com.android.art/lib64/libart.so").status, Status::Native);
    assert_eq!(v.translate("/proc/self/maps").writable, true, "/proc/self is writable-ish and needed");
    assert_eq!(v.translate("/dev/ashmem").status, Status::Native);
    assert_eq!(v.translate("/dev/urandom").status, Status::Native);
}

#[test]
fn alternate_private_dir_spellings_land_in_the_same_bucket() {
    let a = shadow("/data/data/com.target.game/files/x");
    let b = shadow("/data/user/0/com.target.game/files/x");
    let c = shadow("/data/user/10/com.target.game/files/x");
    assert_eq!(a, b);
    assert_eq!(b, c, "any user id maps to the same shadow bucket");
}

// ---------------------------------------------------------------------------
// Security: what must never be reachable
// ---------------------------------------------------------------------------

#[test]
fn host_private_data_is_unreachable_every_way_you_spell_it() {
    let v = vfs();
    for p in [
        "/data/data/dev.aether.host",
        "/data/data/dev.aether.host/aether/virtual/com.target.game/data/save.db",
        "/data/user/0/dev.aether.host",
        "/data/user/0/dev.aether.host/aether/virtual",
        "/data/user/10/dev.aether.host/x",
    ] {
        let r = v.translate(p);
        assert_eq!(r.status, Status::Denied, "{p} must be denied");
        assert_eq!(r.reason, Some(DenyReason::ExplicitRule));
    }
}

#[test]
fn sibling_guests_are_isolated_from_each_other() {
    let a = Vfs::new(VfsConfig::new("com.a", HOST, HOST_DIR));
    let b = Vfs::new(VfsConfig::new("com.b", HOST, HOST_DIR));

    let a_root = a.guest_root();
    let b_root = b.guest_root();
    assert_ne!(a_root, b_root);

    // Guest A must not be able to name guest B's shadow directory...
    assert_eq!(a.translate(&b_root).status, Status::Denied);
    // ...and B's on-device data dir is denied too.
    assert_eq!(a.translate("/data/data/com.b/save").status, Status::Denied);
    assert_eq!(b.translate("/data/data/com.a/save").status, Status::Denied);
}

#[test]
fn root_and_debug_tooling_is_denied() {
    let v = vfs();
    assert_eq!(v.translate("/data/adb/magisk.db").status, Status::Denied);
    assert_eq!(v.translate("/data/data/com.topjohnwu.magisk").status, Status::Denied);
}

#[test]
fn strict_mode_denies_the_unknown_and_non_strict_does_not() {
    let v = vfs();
    assert_eq!(v.translate("/weird/vendor/path").reason, Some(DenyReason::NoRule));
    assert!(!v.allows("/weird/vendor/path", Mode::Read));

    let mut loose = VfsConfig::new(GUEST, HOST, HOST_DIR);
    loose.strict = false;
    assert!(Vfs::new(loose).allows("/weird/vendor/path", Mode::Read));
}

#[test]
fn unknown_paths_never_silently_become_the_host_data_dir() {
    // Regression guard for the nastiest possible bug: a rule gap that leaks
    // the shadow root itself.
    let v = vfs();
    for p in ["/data", "/data/data", "/", "/data/user", "/data/user/0"] {
        let r = v.translate(p);
        if r.status != Status::Denied {
            assert!(!r.path.starts_with(HOST_DIR), "{p} resolved into host storage: {}", r.path);
        }
    }
}

// ---------------------------------------------------------------------------
// Reverse mapping
// ---------------------------------------------------------------------------

#[test]
fn host_paths_convert_back_to_guest_paths() {
    let v = vfs();
    let pairs = [
        ("/data/data/com.target.game/files/a", "data/files/a"),
        ("/sdcard/Android/obb/com.target.game/main.obb", "obb/main.obb"),
        ("/sdcard/Android/media/com.target.game/x.png", "shared_media/x.png"),
    ];
    for (guest, tail) in pairs {
        let host = v.translate(guest).path;
        assert!(host.ends_with(tail), "{host} should end with {tail}");
        assert_eq!(v.to_guest(&host).as_deref(), Some(guest), "reverse of {host}");
    }
    // Foreign host paths must not be reverse-mapped into the guest's world.
    assert_eq!(v.to_guest("/data/data/com.other/x"), None);
    assert_eq!(v.to_guest(&format!("{HOST_DIR}/aether/virtual/com.other/data")), None);
}

// ---------------------------------------------------------------------------
// Per-game policy overrides
// ---------------------------------------------------------------------------

#[test]
fn profiles_can_allow_a_specific_cross_package_path() {
    let mut v = vfs();
    let p = "/sdcard/Android/data/com.other.sdk/files/cache";
    assert_eq!(v.translate(p).status, Status::Denied);

    v.add_rule(aether_vfs::Rule::new("/sdcard/Android/data/com.other.sdk", Action::Shadow, "shared_data", "sdk bridge"));
    let r = v.translate(p);
    assert_eq!(r.status, Status::Shadowed);
    assert!(r.path.contains("/com.target.game/shared_data/files/cache"));
}

#[test]
fn shared_storage_isolation_mode() {
    let mut cfg = VfsConfig::new(GUEST, HOST, HOST_DIR);
    cfg.shared_media = SharedMedia::Isolate;
    let v = Vfs::new(cfg);
    assert!(v.translate("/sdcard/Download/x.apk").path.contains("/shared_primary/Download/x.apk"));
    // scoped dirs still win over the shared default
    assert!(v.translate("/sdcard/Android/obb/com.target.game/a.obb").path.contains("/obb/a.obb"));

    let mut cfg = VfsConfig::new(GUEST, HOST, HOST_DIR);
    cfg.shared_media = SharedMedia::Deny;
    let v = Vfs::new(cfg);
    assert_eq!(v.translate("/sdcard/Download/x.apk").status, Status::Denied);
    assert_eq!(v.translate("/sdcard/Android/obb/com.target.game/a.obb").status, Status::Shadowed);
}

// ---------------------------------------------------------------------------
// Determinism / golden output
// ---------------------------------------------------------------------------

#[test]
fn policy_dump_is_deterministic() {
    let a = vfs().describe().join("\n");
    let b = vfs().describe().join("\n");
    assert_eq!(a, b);

    let vfs = vfs();
    let rules = vfs.rules();
    assert!(rules.len() > 20);
    // every rule must be an absolute, normalised template
    for r in rules.iter() {
        assert!(r.template.starts_with('/'), "{}", r.template);
        let n = aether_vfs::path::normalize(&r.template);
        assert!(r.template == n || r.template == format!("{n}/"), "{} is not normalised", r.template);
    }
}
