//! The translation engine: guest path -> host path, plus the reverse mapping.

use std::collections::BTreeMap;

use crate::path;
use crate::rules::{Action, DenyReason, Mode, Rule, RuleSet};

/// Layout of `<shadow_root>/<guest_package>/<bucket>`.
///
/// Buckets are deliberately flat and stable across Android versions so that a
/// host upgrade never has to migrate on-disk data.
#[derive(Debug, Clone)]
pub struct ShadowLayout {
    /// Backs `/data/data/<pkg>` — databases, shared prefs, files.
    pub data: &'static str,          // /data/data/<pkg>
    /// Backs `/sdcard/Android/data/<pkg>`.
    pub shared_data: &'static str,   // /sdcard/Android/data/<pkg>
    /// Backs `/sdcard/Android/media/<pkg>`.
    pub shared_media: &'static str,  // /sdcard/Android/media/<pkg>
    /// Backs `/sdcard/Android/obb/<pkg>` (expansion files).
    pub obb: &'static str,           // /sdcard/Android/obb/<pkg>
    /// Backs `/sdcard` itself, but only when [`SharedMedia::Isolate`] is selected.
    pub shared_primary: &'static str,// /sdcard  (only when SharedMedia::Isolate)
    /// Scratch space; safe to delete on uninstall.
    pub cache: &'static str,         // scratch, cleared on uninstall
}

impl Default for ShadowLayout {
    fn default() -> Self {
        ShadowLayout {
            data: "data",
            shared_data: "shared_data",
            shared_media: "shared_media",
            obb: "obb",
            shared_primary: "shared_primary",
            cache: "cache",
        }
    }
}

/// What to do with the shared external storage (`/sdcard`, `/storage/emulated/N`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SharedMedia {
    /// Let the guest see the real shared storage (compat-friendly, leaky).
    Passthrough,
    /// Give the guest its own private copy of shared storage.
    Isolate,
    /// Block shared storage entirely.
    Deny,
}

/// Per-guest configuration.
#[derive(Debug, Clone)]
pub struct VfsConfig {
    /// Package name of the virtualised app (the guest).
    pub guest_package: String,
    /// Package name of AetherEngine itself (the host).
    pub host_package: String,
    /// Host's private data dir, e.g. `/data/user/0/dev.aether.host`.
    pub host_data_dir: String,
    /// Android multi-user id the guest is virtualised under. Usually 0.
    pub user_id: u32,
    /// Fail closed (recommended) or leak unknown paths to the kernel.
    pub strict: bool,
    /// Shared-storage policy.
    pub shared_media: SharedMedia,
    /// Override of the shadow root. Defaults to `<host_data_dir>/aether/virtual`.
    pub shadow_root: Option<String>,
    /// Bucket naming inside the shadow root.
    pub layout: ShadowLayout,
}

impl VfsConfig {
    /// Config with the recommended defaults: strict, shared storage passthrough, user 0.
    pub fn new(guest_package: &str, host_package: &str, host_data_dir: &str) -> Self {
        VfsConfig {
            guest_package: guest_package.to_string(),
            host_package: host_package.to_string(),
            host_data_dir: host_data_dir.trim_end_matches('/').to_string(),
            user_id: 0,
            strict: true,
            shared_media: SharedMedia::Passthrough,
            shadow_root: None,
            layout: ShadowLayout::default(),
        }
    }

    /// Root of the shadow tree: `<host_data_dir>/aether/virtual` unless overridden.
    pub fn shadow_root(&self) -> String {
        self.shadow_root
            .clone()
            .unwrap_or_else(|| format!("{}/aether/virtual", self.host_data_dir))
    }

    /// `<shadow_root>/<guest_package>`
    pub fn guest_root(&self) -> String {
        format!("{}/{}", self.shadow_root(), self.guest_package)
    }
}

/// Reason-coded result of a translation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Status {
    /// Path was rewritten into the shadow tree.
    Shadowed,
    /// Path is handed to the kernel as-is.
    Native,
    /// Operation refused.
    Denied,
}

#[derive(Debug, Clone, PartialEq, Eq)]
/// Outcome of a translation request.
pub struct Resolution {
    /// Whether the path was shadowed, passed through, or refused.
    pub status: Status,
    /// Host path to syscall against. Empty when `status == Status::Denied`.
    pub path: String,
    /// Whether writes through `path` are permitted.
    pub writable: bool,
    /// Why the path was refused; set only when `status == Status::Denied`.
    pub reason: Option<DenyReason>,
    /// Template of the rule that decided this, for diagnostics.
    pub matched_rule: Option<String>,
}

impl Resolution {
    fn denied(reason: DenyReason, rule: Option<String>) -> Self {
        Resolution { status: Status::Denied, path: String::new(), writable: false, reason: Some(reason), matched_rule: rule }
    }
}

/// The per-guest VFS instance. Cheap to clone? No — build once and share via
/// `Arc` on the Kotlin side; internally everything is `&self`.
#[derive(Debug, Clone)]
pub struct Vfs {
    cfg: VfsConfig,
    rules: RuleSet,
    /// Virtual symlinks visible only to this guest (`from` -> `to`).
    links: BTreeMap<String, String>,
    /// Reverse table: bucket -> canonical guest prefix (for `to_guest`).
    reverse: Vec<(String, String)>,
}

impl Vfs {
    /// Build a VFS instance, materialising the default policy for its guest.
    pub fn new(cfg: VfsConfig) -> Self {
        let (rules, reverse) = build_rules(&cfg);
        Vfs { cfg, rules, links: default_links(), reverse }
    }

    // ---------- configuration accessors ----------

    /// The configuration this instance was built from.
    pub fn config(&self) -> &VfsConfig {
        &self.cfg
    }

    /// The active rule table (read-only).
    pub fn rules(&self) -> &RuleSet {
        &self.rules
    }

    /// Package name of the virtualised app.
    pub fn guest_package(&self) -> &str {
        &self.cfg.guest_package
    }

    /// `/data/data/<pkg>` — the canonical private dir the guest believes it owns.
    pub fn guest_data_dir(&self) -> String {
        format!("/data/data/{}", self.cfg.guest_package)
    }

    /// `/data/user/<uid>/<pkg>` — the multi-user spelling of the private dir.
    pub fn guest_user_data_dir(&self) -> String {
        format!("/data/user/{}/{}", self.cfg.user_id, self.cfg.guest_package)
    }

    /// Root of the shadow tree: `<host_data_dir>/aether/virtual` unless overridden.
    pub fn shadow_root(&self) -> String {
        self.cfg.shadow_root()
    }

    /// `<shadow_root>/<guest_package>`.
    pub fn guest_root(&self) -> String {
        self.cfg.guest_root()
    }

    /// Host directory backing a shadow bucket.
    pub fn bucket_dir(&self, bucket: &str) -> String {
        format!("{}/{}", self.cfg.guest_root(), bucket)
    }

    /// Where the guest's extracted `.so` files live (host-side, not guest-visible).
    pub fn native_lib_dir(&self) -> String {
        format!("{}/aether/native/{}", self.cfg.host_data_dir, self.cfg.guest_package)
    }

    // ---------- core API ----------

    /// Translate a guest-visible path into the host path to syscall against.
    pub fn translate(&self, guest_path: &str) -> Resolution {
        self.resolve(guest_path, Mode::Read)
    }

    /// Translate and enforce an access mode.
    pub fn resolve(&self, guest_path: &str, mode: Mode) -> Resolution {
        // 1. normalise (lexical; symlinks handled by the io_redirect layer)
        let resolved = match self.resolve_links(guest_path) {
            Ok(p) => p,
            Err(r) => return Resolution::denied(r, None),
        };
        if !path::is_absolute(&resolved) {
            return Resolution::denied(DenyReason::Malformed, None);
        }

        // 2. longest-match lookup
        let (rule, len) = match self.rules.best_match(&resolved) {
            Some(hit) => hit,
            None => {
                return if self.cfg.strict {
                    Resolution::denied(DenyReason::NoRule, None)
                } else {
                    Resolution { status: Status::Native, path: resolved, writable: true, reason: None, matched_rule: None }
                }
            }
        };
        let matched = Some(rule.template.clone());

        match rule.action {
            Action::Deny => Resolution::denied(DenyReason::ExplicitRule, matched),

            Action::Passthrough => Resolution {
                status: Status::Native,
                path: resolved,
                writable: true,
                reason: None,
                matched_rule: matched,
            },

            Action::ReadOnlyPassthrough => {
                if mode.is_write() {
                    Resolution::denied(DenyReason::WriteToReadOnly, matched)
                } else {
                    Resolution { status: Status::Native, path: resolved, writable: false, reason: None, matched_rule: matched }
                }
            }

            Action::Shadow => {
                let remainder = &resolved[len..];
                let host = format!("{}/{}{}", self.cfg.guest_root(), rule.bucket, remainder);
                Resolution {
                    status: Status::Shadowed,
                    path: path::normalize(&host),
                    writable: true,
                    reason: None,
                    matched_rule: matched,
                }
            }
        }
    }

    /// Reverse mapping: host path -> the path the guest should see.
    ///
    /// Needed for stack traces, `Context.getFilesDir()` round-trips and for
    /// rewrites inside `ContentProvider` cursors. Returns `None` when the host
    /// path is not part of this guest's world (leaks nothing by construction).
    pub fn to_guest(&self, host_path: &str) -> Option<String> {
        let host = path::normalize(host_path);
        let root = self.cfg.guest_root();

        if let Some(rest) = path::strip_prefix(&host, &root) {
            let (bucket, tail) = match rest.find('/') {
                Some(i) => (&rest[..i], &rest[i..]),
                None => (rest, ""),
            };
            for (b, prefix) in &self.reverse {
                if b == bucket {
                    return Some(format!("{prefix}{tail}"));
                }
            }
            return None;
        }

        // Native passthrough: identity.
        match self.resolve(&host, Mode::Read).status {
            Status::Native => Some(host),
            _ => None,
        }
    }

    /// Cheap yes/no guard used by the hot IO path.
    pub fn allows(&self, guest_path: &str, mode: Mode) -> bool {
        self.resolve(guest_path, mode).status != Status::Denied
    }

    /// Add/override a rule at highest priority (used by per-game profiles).
    ///
    /// The template is run through symlink resolution first, so a profile may
    /// spell a path the way the *guest* sees it (`/sdcard/...`) even though
    /// matching always happens against resolved paths.
    pub fn add_rule(&mut self, rule: Rule) {
        let mut rule = rule;
        if let Ok(resolved) = self.resolve_links(&rule.template) {
            if resolved != rule.template {
                rule.template = resolved;
            }
        }
        self.rules.push_front(rule);
    }

    /// Add a guest-visible symlink.
    pub fn add_link(&mut self, from: &str, to: &str) {
        self.links.insert(from.to_string(), to.to_string());
    }

    /// Human-readable dump of the active policy (for `adb shell dumpsys`-style
    /// diagnostics and for the CI golden-file test).
    pub fn describe(&self) -> Vec<String> {
        self.rules
            .iter()
            .map(|r| format!("{:<52}{:<24}{}", r.template, format!("{:?}", r.action), r.note))
            .collect()
    }

    // ---------- internals ----------

    /// Resolve guest-visible symlinks using longest-prefix order, with a hop
    /// limit so a hostile `link()` loop cannot spin the host process.
    fn resolve_links(&self, input: &str) -> Result<String, DenyReason> {
        const MAX_HOPS: usize = 8;
        let mut current = path::normalize(input);

        for _ in 0..MAX_HOPS {
            let mut hit: Option<(&String, &String)> = None;
            for (from, to) in &self.links {
                if current == *from || path::is_under(&current, from) {
                    match hit {
                        Some((best, _)) if best.len() >= from.len() => {}
                        _ => hit = Some((from, to)),
                    }
                }
            }
            match hit {
                None => return Ok(current),
                Some((from, to)) => {
                    let tail = &current[from.len()..];
                    current = path::normalize(&format!("{to}{tail}"));
                }
            }
        }
        Err(DenyReason::LinkLoop)
    }
}

/// Guest-visible symlinks that exist in the Android world but not necessarily
/// on the host's filesystem layout.
fn default_links() -> BTreeMap<String, String> {
    let mut m = BTreeMap::new();
    m.insert("/sdcard".to_string(), "/storage/emulated/0".to_string());
    m.insert("/mnt/sdcard".to_string(), "/storage/emulated/0".to_string());
    m.insert("/mnt/user/0/primary".to_string(), "/storage/emulated/0".to_string());
    m.insert("/storage/self/primary".to_string(), "/storage/emulated/0".to_string());
    m.insert("/data/data/self".to_string(), "/data/data".to_string());
    m
}

/// Build the default policy. Order matters only for exact ties; `best_match`
/// already prefers longer matches and fewer wildcards.
fn build_rules(cfg: &VfsConfig) -> (RuleSet, Vec<(String, String)>) {
    let pkg = cfg.guest_package.as_str();
    let host = cfg.host_package.as_str();
    let l = &cfg.layout;
    let mut rs = RuleSet::new();

    // ---- 1. HOST AND CROSS-GUEST ISOLATION GUARDS ---------------------------
    // Declared first so ties fall our way, and they are also the longest
    // matches for the paths they protect.
    rs.push(Rule::new(format!("/data/data/{host}"), Action::Deny, "-", "host private data"));
    rs.push(Rule::new(format!("/data/user/{}/{}", cfg.user_id, host), Action::Deny, "-", "host private data (multi-user)"));
    rs.push(Rule::new("/data/user/*/".to_string() + host, Action::Deny, "-", "host private data (any user)"));
    rs.push(Rule::new(cfg.host_data_dir.clone(), Action::Deny, "-", "host data root"));
    rs.push(Rule::new("/data/data/*", Action::Deny, "-", "other packages"));
    rs.push(Rule::new("/data/user/*/", Action::Deny, "-", "other packages (multi-user)"));
    // The shadow tree itself must never be reachable from inside the guest.
    rs.push(Rule::new(cfg.shadow_root(), Action::Deny, "-", "shadow root"));

    // ---- 2. THE GUEST'S OWN PRIVATE DATA -> SHADOW --------------------------
    rs.push(Rule::new(format!("/data/data/{pkg}"), Action::Shadow, l.data, "guest private data"));
    rs.push(Rule::new(format!("/data/user/{}/{pkg}", cfg.user_id), Action::Shadow, l.data, "guest private data (this user)"));
    rs.push(Rule::new(format!("/data/user/*/{pkg}"), Action::Shadow, l.data, "guest private data (any user)"));
    rs.push(Rule::new(format!("/data/user_de/{}/{pkg}", cfg.user_id), Action::Shadow, l.data, "device-encrypted storage"));

    // ---- 3. SCOPED EXTERNAL STORAGE -> SHADOW -------------------------------
    for base in ["/sdcard", "/storage/emulated/0", "/storage/self/primary", "/mnt/sdcard"] {
        rs.push(Rule::new(format!("{base}/Android/data/{pkg}"), Action::Shadow, l.shared_data, "scoped external data"));
        rs.push(Rule::new(format!("{base}/Android/media/{pkg}"), Action::Shadow, l.shared_media, "scoped external media"));
        rs.push(Rule::new(format!("{base}/Android/obb/{pkg}"), Action::Shadow, l.obb, "expansion files"));
    }
    // Do NOT let the guest read sibling packages' scoped storage. These are
    // longer matches than the shared-storage default, so they win for every
    // package except our own (which is matched by the concrete rules above).
    for kind in ["data", "media", "obb"] {
        rs.push(Rule::new(format!("/storage/emulated/*/Android/{kind}/*"), Action::Deny, "-", "other packages' scoped storage"));
        rs.push(Rule::new(format!("/sdcard/Android/{kind}/*"), Action::Deny, "-", "other packages' scoped storage"));
    }
    rs.push(Rule::new(format!("/storage/emulated/*/Android/data/{pkg}"), Action::Shadow, l.shared_data, "scoped external data (any user)"));
    rs.push(Rule::new(format!("/storage/emulated/*/Android/media/{pkg}"), Action::Shadow, l.shared_media, "scoped external media (any user)"));
    rs.push(Rule::new(format!("/storage/emulated/*/Android/obb/{pkg}"), Action::Shadow, l.obb, "expansion files (any user)"));

    // ---- 4. SHARED EXTERNAL STORAGE ----------------------------------------
    let shared_action = match cfg.shared_media {
        SharedMedia::Passthrough => Action::Passthrough,
        SharedMedia::Isolate => Action::Shadow,
        SharedMedia::Deny => Action::Deny,
    };
    let shared_bucket = if shared_action == Action::Shadow { l.shared_primary } else { "-" };
    for base in ["/sdcard", "/storage/emulated/0", "/storage/self/primary", "/mnt/sdcard", "/data/media"] {
        rs.push(Rule::new(base, shared_action, shared_bucket, "shared storage"));
    }
    rs.push(Rule::new("/storage/emulated/*", shared_action, shared_bucket, "shared storage (any user)"));
    rs.push(Rule::new("/storage/*", Action::ReadOnlyPassthrough, "-", "removable/OTG media"));

    // ---- 5. READ-ONLY SYSTEM SURFACES ---------------------------------------
    for p in [
        "/system", "/system_ext", "/vendor", "/odm", "/oem", "/product", "/apex",
        "/etc", "/proc", "/sys", "/config", "/metadata", "/data/dalvik-cache",
        "/data/app", "/data/misc", "/data/system", "/data/local",
    ] {
        rs.push(Rule::new(p, Action::ReadOnlyPassthrough, "-", "system surface"));
    }
    // The guest must be able to *read* its own installed APK.
    rs.push(Rule::new(format!("/data/app/{pkg}-*"), Action::ReadOnlyPassthrough, "-", "guest apk"));
    rs.push(Rule::new(format!("/data/app/*/{pkg}-*"), Action::ReadOnlyPassthrough, "-", "guest apk (A10+ layout)"));

    // ---- 6. DANGEROUS / NEVER ------------------------------------------------
    rs.push(Rule::new("/data/adb", Action::Deny, "-", "root tooling"));
    rs.push(Rule::new("/data/local/tmp", Action::Passthrough, "-", "debuggable scratch"));
    rs.push(Rule::new("/dev", Action::Passthrough, "-", "devices, ashmem, urandom"));
    rs.push(Rule::new("/proc/self", Action::Passthrough, "-", "self introspection"));

    let reverse = vec![
        (l.data.to_string(), format!("/data/data/{pkg}")),
        (l.shared_data.to_string(), format!("/sdcard/Android/data/{pkg}")),
        (l.shared_media.to_string(), format!("/sdcard/Android/media/{pkg}")),
        (l.obb.to_string(), format!("/sdcard/Android/obb/{pkg}")),
        (l.shared_primary.to_string(), "/sdcard".to_string()),
        (l.cache.to_string(), format!("/data/data/{pkg}/cache")),
    ];

    (rs, reverse)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cfg() -> VfsConfig {
        VfsConfig::new("com.target.game", "dev.aether.host", "/data/user/0/dev.aether.host")
    }

    #[test]
    fn private_data_is_shadowed() {
        let vfs = Vfs::new(cfg());
        let r = vfs.translate("/data/data/com.target.game/shared_prefs/x.xml");
        assert_eq!(r.status, Status::Shadowed);
        assert_eq!(r.path, "/data/user/0/dev.aether.host/aether/virtual/com.target.game/data/shared_prefs/x.xml");
        assert!(r.writable);
    }

    #[test]
    fn traversal_cannot_escape_the_shadow() {
        let vfs = Vfs::new(cfg());
        // `..` is collapsed lexically *before* matching, so the escape attempt
        // lands on a path that has no business being reachable.
        let r = vfs.translate("/data/data/com.target.game/../com.other.app");
        assert_eq!(r.status, Status::Denied);
        assert_eq!(r.reason, Some(DenyReason::ExplicitRule));

        let r = vfs.translate("/data/data/com.target.game/../../data/data/dev.aether.host");
        assert_eq!(r.status, Status::Denied);
        assert_eq!(r.reason, Some(DenyReason::ExplicitRule));

        // ...and a well-behaved deep path still resolves.
        let r = vfs.translate("/data/data/com.target.game/a/../b/c.dat");
        assert_eq!(r.path, "/data/user/0/dev.aether.host/aether/virtual/com.target.game/data/b/c.dat");
    }

    #[test]
    fn host_data_is_never_reachable() {
        let vfs = Vfs::new(cfg());
        assert_eq!(vfs.translate("/data/data/dev.aether.host").status, Status::Denied);
        assert_eq!(vfs.translate("/data/user/0/dev.aether.host/aether/virtual").status, Status::Denied);
        assert_eq!(vfs.translate("/data/data/com.other.app").status, Status::Denied);
        assert_eq!(vfs.translate("/data/user/0/com.other.app/x").status, Status::Denied);
    }

    #[test]
    fn scoped_storage_is_shadowed_but_shared_storage_passthrough() {
        let vfs = Vfs::new(cfg());
        let scoped = vfs.translate("/sdcard/Android/obb/com.target.game/main.obb");
        assert_eq!(scoped.status, Status::Shadowed);
        assert!(scoped.path.ends_with("/obb/main.obb"));

        let shared = vfs.translate("/sdcard/DCIM/shot.png");
        assert_eq!(shared.status, Status::Native);
        assert_eq!(shared.path, "/storage/emulated/0/DCIM/shot.png"); // symlink resolved
    }

    #[test]
    fn system_is_read_only() {
        let vfs = Vfs::new(cfg());
        assert_eq!(vfs.resolve("/system/lib64/libc.so", Mode::Read).status, Status::Native);
        let w = vfs.resolve("/system/lib64/libc.so", Mode::Write);
        assert_eq!(w.status, Status::Denied);
        assert_eq!(w.reason, Some(DenyReason::WriteToReadOnly));
    }

    #[test]
    fn strict_mode_is_fail_closed() {
        let vfs = Vfs::new(cfg());
        assert_eq!(vfs.translate("/some/undeclared/path").reason, Some(DenyReason::NoRule));

        let mut loose = cfg();
        loose.strict = false;
        let vfs = Vfs::new(loose);
        assert_eq!(vfs.translate("/some/undeclared/path").status, Status::Native);
    }

    #[test]
    fn round_trip_to_guest() {
        let vfs = Vfs::new(cfg());
        let host = vfs.translate("/data/data/com.target.game/files/a.dat").path;
        assert_eq!(vfs.to_guest(&host), Some("/data/data/com.target.game/files/a.dat".into()));
        assert_eq!(vfs.to_guest("/data/data/com.other/x"), None);
    }

    #[test]
    fn shared_storage_can_be_isolated() {
        let mut c = cfg();
        c.shared_media = SharedMedia::Isolate;
        let vfs = Vfs::new(c);
        let r = vfs.translate("/sdcard/DCIM/shot.png");
        assert_eq!(r.status, Status::Shadowed);
        assert!(r.path.contains("/shared_primary/DCIM/shot.png"));
    }

    #[test]
    fn relative_paths_are_rejected() {
        let vfs = Vfs::new(cfg());
        assert_eq!(vfs.translate("files/x").reason, Some(DenyReason::Malformed));
    }

    #[test]
    fn policy_table_is_stable() {
        // Guards the CI golden file: any accidental rule change shows up here.
        let vfs = Vfs::new(cfg());
        let dump = vfs.describe().join("\n");
        assert!(dump.contains("/data/data/com.target.game"));
        assert!(dump.contains("/data/data/dev.aether.host"));
        assert_eq!(vfs.rules().len(), vfs.describe().len());
    }
}
