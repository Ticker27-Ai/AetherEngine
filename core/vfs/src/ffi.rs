//! C ABI consumed by `vfs_bridge.cpp` (JNI) — and nothing else.
//!
//! Contract (mirrored in `VfsRouter.kt` / `vfs_bridge.h`):
//!
//! * Every function is `extern "C"` and returns `0` on success or a negative
//!   [`ErrorCode`].
//! * Two-step buffer protocol: call `..._len` first, allocate, then call the
//!   getter. This keeps ownership on the caller and avoids a cross-heap
//!   `free()`.
//! * **No panics cross the FFI boundary.** Every entry point is wrapped in
//!   `catch_unwind`; a Rust panic becomes `ERR_PANIC` instead of UB.

use std::cell::RefCell;
use std::collections::BTreeMap;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::RwLock;

use crate::rules::{Action, Mode, Rule};
use crate::translator::{SharedMedia, Vfs, VfsConfig};

// ---------------------------------------------------------------------------
// Error codes — must stay in sync with `VfsErrorCode` in VfsRouter.kt
// ---------------------------------------------------------------------------

/// Operation completed successfully.
pub const OK: c_int = 0;
/// The handle is not (or no longer) registered.
pub const ERR_INVALID_HANDLE: c_int = -1;
/// A required pointer argument was null.
pub const ERR_NULL_POINTER: c_int = -2;
/// A string argument was not valid UTF-8.
pub const ERR_INVALID_UTF8: c_int = -3;
/// The isolation policy refused the path.
pub const ERR_DENIED: c_int = -4;
/// The path could not be normalised (relative, or an interior NUL byte).
pub const ERR_MALFORMED: c_int = -5;
/// Output buffer too small; query the size with the `..._len` entry point.
pub const ERR_BUFFER_TOO_SMALL: c_int = -6;
/// An enum code or rule template was out of range.
pub const ERR_BAD_ARGUMENT: c_int = -7;
/// A Rust panic was caught at the FFI boundary (never propagates as UB).
pub const ERR_PANIC: c_int = -99;

// ---------------------------------------------------------------------------
// Handle registry
// ---------------------------------------------------------------------------

static NEXT_ID: AtomicU64 = AtomicU64::new(1);
static REGISTRY: RwLock<BTreeMap<u64, Vfs>> = RwLock::new(BTreeMap::new());

thread_local! {
    static LAST_ERROR: RefCell<String> = const { RefCell::new(String::new()) };
}

fn set_error(msg: impl Into<String>) {
    LAST_ERROR.with(|e| *e.borrow_mut() = msg.into());
}

fn clear_error() {
    LAST_ERROR.with(|e| e.borrow_mut().clear());
}

/// Run `f`, converting a panic into `ERR_PANIC`. `AssertUnwindSafe` is fine
/// here: the registry is a lock-protected map and `Vfs` is `&self`-only.
///
/// Generic over the return type so both the `c_int` accessors and the `i64`
/// handle/length accessors share one safety net.
fn guard<T, F: FnOnce() -> T>(f: F) -> T
where
    T: From<c_int>,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(v) => v,
        Err(_) => {
            set_error("rust panic at the aether-vfs FFI boundary");
            T::from(ERR_PANIC)
        }
    }
}

unsafe fn to_str<'a>(p: *const c_char) -> Result<&'a str, c_int> {
    if p.is_null() {
        return Err(ERR_NULL_POINTER);
    }
    CStr::from_ptr(p).to_str().map_err(|_| ERR_INVALID_UTF8)
}

/// Write `s` (NUL-terminated) into `out` when it fits.
/// Returns `OK`, or `ERR_BUFFER_TOO_SMALL` after reporting the requirement.
unsafe fn write_out(s: &str, out: *mut c_char, cap: usize) -> c_int {
    if out.is_null() {
        return ERR_NULL_POINTER;
    }
    let needed = s.len() + 1;
    if cap < needed {
        set_error(format!("buffer too small: need {needed}, have {cap}"));
        return ERR_BUFFER_TOO_SMALL;
    }
    let c = match CString::new(s) {
        Ok(c) => c,
        Err(_) => {
            set_error("interior NUL byte in path");
            return ERR_MALFORMED;
        }
    };
    std::ptr::copy_nonoverlapping(c.as_ptr(), out, needed);
    OK
}

fn with_vfs<R>(handle: u64, f: impl FnOnce(&Vfs) -> R) -> Result<R, c_int> {
    let reg = REGISTRY.read().map_err(|_| ERR_PANIC)?;
    match reg.get(&handle) {
        Some(vfs) => Ok(f(vfs)),
        None => Err(ERR_INVALID_HANDLE),
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

/// Create a VFS instance for one guest.
///
/// `strict`: 1 = fail closed on unknown paths, 0 = passthrough.
/// `shared_media`: 0 = passthrough, 1 = isolate, 2 = deny.
///
/// Returns a non-zero handle, or a negative error code.
#[no_mangle]
pub extern "C" fn aether_vfs_create(
    guest_package: *const c_char,
    host_package: *const c_char,
    host_data_dir: *const c_char,
    user_id: c_int,
    strict: c_int,
    shared_media: c_int,
) -> i64 {
    guard(|| unsafe {
        clear_error();
        let (guest, host, dir) = match (to_str(guest_package), to_str(host_package), to_str(host_data_dir)) {
            (Ok(a), Ok(b), Ok(c)) => (a, b, c),
            (Err(e), _, _) | (_, Err(e), _) | (_, _, Err(e)) => return e as i64,
        };
        let shared = match shared_media {
            0 => SharedMedia::Passthrough,
            1 => SharedMedia::Isolate,
            2 => SharedMedia::Deny,
            _ => {
                set_error("shared_media must be 0, 1 or 2");
                return ERR_BAD_ARGUMENT as i64;
            }
        };
        let mut cfg = VfsConfig::new(guest, host, dir);
        cfg.user_id = user_id.max(0) as u32;
        cfg.strict = strict != 0;
        cfg.shared_media = shared;

        let vfs = Vfs::new(cfg);
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        match REGISTRY.write() {
            Ok(mut reg) => {
                reg.insert(id, vfs);
                id as i64
            }
            Err(_) => ERR_PANIC as i64,
        }
    })
}

/// Release a handle. Idempotent.
#[no_mangle]
pub extern "C" fn aether_vfs_destroy(handle: u64) -> c_int {
    guard(|| match REGISTRY.write() {
        Ok(mut reg) => {
            reg.remove(&handle);
            OK
        }
        Err(_) => ERR_PANIC,
    })
}

// ---------------------------------------------------------------------------
// Translation
// ---------------------------------------------------------------------------

/// Required buffer size (including the NUL) for `aether_vfs_to_host`,
/// or a negative error code.
#[no_mangle]
pub extern "C" fn aether_vfs_to_host_len(handle: u64, guest_path: *const c_char) -> i64 {
    guard(|| unsafe {
        clear_error();
        let p = match to_str(guest_path) {
            Ok(p) => p,
            Err(e) => return e as i64,
        };
        match with_vfs(handle, |v| v.translate(p)) {
            Ok(r) if r.status == crate::translator::Status::Denied => {
                set_error(format!("denied: {}", r.reason.map(|x| x.as_str()).unwrap_or("unknown")));
                ERR_DENIED as i64
            }
            Ok(r) => (r.path.len() + 1) as i64,
            Err(e) => e as i64,
        }
    })
}

/// Translate a guest path into the host path to syscall against.
#[no_mangle]
pub extern "C" fn aether_vfs_to_host(
    handle: u64,
    guest_path: *const c_char,
    out: *mut c_char,
    out_cap: usize,
) -> c_int {
    guard(|| unsafe {
        clear_error();
        let p = match to_str(guest_path) {
            Ok(p) => p,
            Err(e) => return e,
        };
        match with_vfs(handle, |v| v.translate(p)) {
            Ok(r) if r.status == crate::translator::Status::Denied => {
                set_error(format!("denied: {}", r.reason.map(|x| x.as_str()).unwrap_or("unknown")));
                ERR_DENIED
            }
            Ok(r) => write_out(&r.path, out, out_cap),
            Err(e) => e,
        }
    })
}

/// Reverse mapping. `0` bytes written with `OK` means "not part of this
/// guest's world" (the caller should keep the host path).
#[no_mangle]
pub extern "C" fn aether_vfs_to_guest(
    handle: u64,
    host_path: *const c_char,
    out: *mut c_char,
    out_cap: usize,
) -> c_int {
    guard(|| unsafe {
        clear_error();
        let p = match to_str(host_path) {
            Ok(p) => p,
            Err(e) => return e,
        };
        match with_vfs(handle, |v| v.to_guest(p)) {
            Ok(Some(guest)) => write_out(&guest, out, out_cap),
            Ok(None) => match write_out("", out, out_cap) {
                OK => OK,
                other => other,
            },
            Err(e) => e,
        }
    })
}

/// Policy probe. `write_mode`: 1 = the guest intends to write/create/delete.
/// Returns `OK` (allowed) or `ERR_DENIED`.
#[no_mangle]
pub extern "C" fn aether_vfs_check(handle: u64, guest_path: *const c_char, write_mode: c_int) -> c_int {
    guard(|| unsafe {
        clear_error();
        let p = match to_str(guest_path) {
            Ok(p) => p,
            Err(e) => return e,
        };
        let mode = if write_mode != 0 { Mode::Write } else { Mode::Read };
        match with_vfs(handle, |v| v.resolve(p, mode)) {
            Ok(r) if r.status == crate::translator::Status::Denied => {
                set_error(format!("denied: {}", r.reason.map(|x| x.as_str()).unwrap_or("unknown")));
                ERR_DENIED
            }
            Ok(_) => OK,
            Err(e) => e,
        }
    })
}

// ---------------------------------------------------------------------------
// Runtime policy mutation (per-game profiles)
// ---------------------------------------------------------------------------

/// `action`: 0 = shadow, 1 = passthrough, 2 = read-only, 3 = deny.
/// Rules added this way take priority over every built-in rule.
#[no_mangle]
pub extern "C" fn aether_vfs_add_rule(
    handle: u64,
    template: *const c_char,
    action: c_int,
    bucket: *const c_char,
) -> c_int {
    guard(|| unsafe {
        clear_error();
        let t = match to_str(template) {
            Ok(t) => t.to_string(),
            Err(e) => return e,
        };
        let b = match to_str(bucket) {
            Ok(b) => b.to_string(),
            Err(e) => return e,
        };
        let a = match Action::from_code(action) {
            Some(a) => a,
            None => {
                set_error(format!("unknown action code {action}"));
                return ERR_BAD_ARGUMENT;
            }
        };
        match crate::rules::validate_template(&t) {
            Ok(()) => {}
            Err(msg) => {
                set_error(msg);
                return ERR_BAD_ARGUMENT;
            }
        }
        match REGISTRY.write() {
            Ok(mut reg) => match reg.get_mut(&handle) {
                Some(vfs) => {
                    vfs.add_rule(Rule::new(t, a, &b, "runtime override"));
                    OK
                }
                None => ERR_INVALID_HANDLE,
            },
            Err(_) => ERR_PANIC,
        }
    })
}

/// Register a guest-visible symlink.
#[no_mangle]
pub extern "C" fn aether_vfs_add_link(handle: u64, from: *const c_char, to: *const c_char) -> c_int {
    guard(|| unsafe {
        clear_error();
        let (f, t) = match (to_str(from), to_str(to)) {
            (Ok(f), Ok(t)) => (f.to_string(), t.to_string()),
            (Err(e), _) | (_, Err(e)) => return e,
        };
        match REGISTRY.write() {
            Ok(mut reg) => match reg.get_mut(&handle) {
                Some(vfs) => {
                    vfs.add_link(&f, &t);
                    OK
                }
                None => ERR_INVALID_HANDLE,
            },
            Err(_) => ERR_PANIC,
        }
    })
}

// ---------------------------------------------------------------------------
// Diagnostics
// ---------------------------------------------------------------------------

/// Copy the last error for the calling thread into `out`.
#[no_mangle]
pub extern "C" fn aether_vfs_last_error(out: *mut c_char, out_cap: usize) -> c_int {
    guard(|| unsafe {
        let msg = LAST_ERROR.with(|e| e.borrow().clone());
        write_out(&msg, out, out_cap)
    })
}

/// Semantic version of this ABI. Bump whenever a signature changes.
#[no_mangle]
pub extern "C" fn aether_vfs_abi_version() -> c_int {
    1
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CString;

    fn cs(s: &str) -> CString {
        CString::new(s).unwrap()
    }

    fn make() -> u64 {
        let g = cs("com.target.game");
        let h = cs("dev.aether.host");
        let d = cs("/data/user/0/dev.aether.host");
        let id = aether_vfs_create(g.as_ptr(), h.as_ptr(), d.as_ptr(), 0, 1, 0);
        assert!(id > 0);
        id as u64
    }

    #[test]
    fn create_translate_destroy_over_ffi() {
        let h = make();
        let guest = cs("/data/data/com.target.game/files/a.dat");
        let n = aether_vfs_to_host_len(h, guest.as_ptr());
        assert!(n > 0);

        let mut buf = vec![0u8; n as usize];
        let rc = aether_vfs_to_host(h, guest.as_ptr(), buf.as_mut_ptr() as *mut c_char, buf.len());
        assert_eq!(rc, OK);

        let s = unsafe { CStr::from_ptr(buf.as_ptr() as *const c_char) }.to_str().unwrap();
        assert_eq!(s, "/data/user/0/dev.aether.host/aether/virtual/com.target.game/data/files/a.dat");

        let rc = aether_vfs_check(h, guest.as_ptr(), 1);
        assert_eq!(rc, OK);

        let forbidden = cs("/data/data/dev.aether.host/x");
        assert_eq!(aether_vfs_check(h, forbidden.as_ptr(), 0), ERR_DENIED);

        assert_eq!(aether_vfs_destroy(h), OK);
        // handle is gone -> invalid
        let mut buf2 = vec![0u8; 64];
        assert_eq!(
            aether_vfs_to_host(h, guest.as_ptr(), buf2.as_mut_ptr() as *mut c_char, buf2.len()),
            ERR_INVALID_HANDLE
        );
    }

    #[test]
    fn buffer_too_small_is_reported_not_truncated() {
        let h = make();
        let guest = cs("/data/data/com.target.game");
        let mut small = vec![0u8; 4];
        let rc = aether_vfs_to_host(h, guest.as_ptr(), small.as_mut_ptr() as *mut c_char, small.len());
        assert_eq!(rc, ERR_BUFFER_TOO_SMALL);
        let mut err = vec![0u8; 256];
        aether_vfs_last_error(err.as_mut_ptr() as *mut c_char, err.len());
        let msg = unsafe { CStr::from_ptr(err.as_ptr() as *const c_char) }.to_str().unwrap();
        assert!(msg.starts_with("buffer too small"));
        aether_vfs_destroy(h);
    }

    #[test]
    fn runtime_rule_overrides_builtin_deny() {
        let h = make();
        let p = cs("/data/data/com.other.app/shared.bin");
        assert_eq!(aether_vfs_check(h, p.as_ptr(), 0), ERR_DENIED);

        let t = cs("/data/data/com.other.app");
        let b = cs("shared_data");
        assert_eq!(aether_vfs_add_rule(h, t.as_ptr(), 0, b.as_ptr()), OK);
        assert_eq!(aether_vfs_check(h, p.as_ptr(), 0), OK);

        let n = aether_vfs_to_host_len(h, p.as_ptr()) as usize;
        let mut buf = vec![0u8; n];
        aether_vfs_to_host(h, p.as_ptr(), buf.as_mut_ptr() as *mut c_char, buf.len());
        let s = unsafe { CStr::from_ptr(buf.as_ptr() as *const c_char) }.to_str().unwrap();
        assert!(s.ends_with("/com.target.game/shared_data/shared.bin"));
        aether_vfs_destroy(h);
    }

    #[test]
    fn null_pointer_is_rejected_not_crashed() {
        let h = make();
        assert_eq!(aether_vfs_to_host_len(h, std::ptr::null()), ERR_NULL_POINTER as i64);
        aether_vfs_destroy(h);
    }
}
