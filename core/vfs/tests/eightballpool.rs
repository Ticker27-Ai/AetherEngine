//! Policy tests for the actual launch target: `com.miniclip.eightballpool`.
//!
//! Everything here is derived from the profile in
//! `guests/com.miniclip.eightballpool.toml`. If the profile changes, these
//! tests are supposed to break — that is the point.

use aether_vfs::{Mode, SharedMedia, Status, Vfs, VfsConfig};

const GUEST: &str = "com.miniclip.eightballpool";
const HOST: &str = "dev.aether.host";
const HOST_DIR: &str = "/data/user/0/dev.aether.host";

fn vfs() -> Vfs {
    let mut cfg = VfsConfig::new(GUEST, HOST, HOST_DIR);
    cfg.shared_media = SharedMedia::Passthrough;
    cfg.strict = true;
    Vfs::new(cfg)
}

/// The profile's `extra_rules`, applied in the order they are declared.
fn profiled() -> Vfs {
    let mut v = vfs();
    v.add_rule(aether_vfs::Rule::new(
        "/sdcard/Android/data/com.facebook.katana",
        aether_vfs::Action::ReadOnlyPassthrough,
        "-",
        "facebook sdk login flow",
    ));
    v.add_rule(aether_vfs::Rule::new(
        "/data/data/com.miniclip.eightballpool/app_dumps",
        aether_vfs::Action::Shadow,
        "cache",
        "crash/telemetry spool, cleared on uninstall",
    ));
    v
}

#[test]
fn facebook_sdk_paths_are_read_only() {
    let v = profiled();
    let p = "/sdcard/Android/data/com.facebook.katana/cache/token.bin";
    assert_eq!(
        v.resolve(p, Mode::Read).status,
        Status::Native,
        "the FB SDK must find the app cache"
    );
    assert_eq!(
        v.resolve(p, Mode::Write).status,
        Status::Denied,
        "but must never be able to write it"
    );
}

#[test]
fn crash_dumps_land_in_the_cache_bucket() {
    let v = profiled();
    let r = v.resolve(
        "/data/data/com.miniclip.eightballpool/app_dumps/crash.dmp",
        Mode::Write,
    );
    assert_eq!(r.status, Status::Shadowed);
    assert_eq!(
        r.path,
        "/data/user/0/dev.aether.host/aether/virtual/com.miniclip.eightballpool/cache/crash.dmp"
    );
    // Without the profile override the same path would be in `data`.
    assert_ne!(
        vfs()
            .translate("/data/data/com.miniclip.eightballpool/app_dumps/crash.dmp")
            .path,
        r.path
    );
}

#[test]
fn unity_style_player_prefs_land_in_the_shadow() {
    let v = vfs();
    // Unity writes PlayerPrefs to shared_prefs on modern Android; older
    // Miniclip builds used a raw file under files/.
    for p in [
        "/data/data/com.miniclip.eightballpool/shared_prefs/com.miniclip.eightballpool.v2.playerprefs.xml",
        "/data/data/com.miniclip.eightballpool/files/settings.dat",
        "/data/data/com.miniclip.eightballpool/databases/miniclip.db",
        "/data/data/com.miniclip.eightballpool/app_webview/Local Storage/leveldb/000003.log",
    ] {
        let r = v.resolve(p, Mode::Write);
        assert_eq!(r.status, Status::Shadowed, "{p}");
        assert!(r.path.starts_with("/data/user/0/dev.aether.host/aether/virtual/com.miniclip.eightballpool/"));
    }
}

#[test]
fn screenshots_stay_in_shared_storage() {
    // The profile deliberately keeps shared media on passthrough so that the
    // user can find their 8 Ball Pool screenshots in the gallery.
    let v = vfs();
    let r = v.translate("/sdcard/DCIM/Screenshots/eightball-win.png");
    assert_eq!(r.status, Status::Native);
    assert!(r.writable);
}

#[test]
fn the_host_container_itself_is_never_visible() {
    let v = vfs();
    for p in [
        "/data/data/dev.aether.host/aether/virtual/com.miniclip.eightballpool",
        "/data/user/0/dev.aether.host/shared_prefs/aether.xml",
        "/data/data/com.miniclip.eightballpool/../../dev.aether.host/aether/virtual",
    ] {
        assert_eq!(
            v.translate(p).status,
            Status::Denied,
            "{p} must never resolve"
        );
    }
}

#[test]
fn split_apk_native_dir_is_readable_not_writable() {
    // `split_config.arm64_v8a.apk` is installed under /data/app; the guest
    // reads its own libs and APK from there, but must not be able to rewrite
    // them (that would be a code-injection primitive against the container).
    let v = vfs();
    let p = "/data/app/~~Qq7fZw==/com.miniclip.eightballpool-Ab12Cd==/split_config.arm64_v8a.apk";
    let r = v.translate(p);
    assert_eq!(r.status, Status::Native);
    assert_eq!(v.resolve(p, Mode::Write).status, Status::Denied);
}

#[test]
fn sibling_games_are_unreachable() {
    let v = vfs();
    // A second virtualised game in the same host process.
    assert_eq!(
        v.translate("/data/data/com.other.game/save.bin").status,
        Status::Denied
    );
    assert_eq!(
        v.translate("/sdcard/Android/data/com.other.game/files/x")
            .status,
        Status::Denied,
        "scoped storage of other apps must be denied even in passthrough mode"
    );
    assert_eq!(
        v.translate("/sdcard/Android/obb/com.other.game/main.obb")
            .status,
        Status::Denied
    );
}

#[test]
fn profile_paths_round_trip() {
    let v = vfs();
    let guest = "/data/data/com.miniclip.eightballpool/files/cues.json";
    let host = v.translate(guest).path;
    assert_eq!(v.to_guest(&host).as_deref(), Some(guest));
}
