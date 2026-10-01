//! Lexical path utilities.
//!
//! AetherEngine's VFS deliberately operates on *Android-shaped* absolute paths
//! (`/`-separated, case sensitive) as plain `&str`/`String` instead of
//! `std::path::Path`. Two reasons:
//!
//! 1. The translation rules must behave **identically** on-device and in the
//!    host unit-test/CI environment (Linux/macOS/Windows).
//! 2. Every guest path crosses an FFI boundary at least twice; keeping it as
//!    a `String` avoids lossy `OsString` conversions.
//!
//! NOTE ON CORRECTNESS: this is *lexical* normalisation, not kernel resolution.
//! `..` is collapsed textually, which differs from the kernel when a path
//! component is a symlink. The authoritative interception happens in
//! `io_redirect.cpp` (libc `open`/`stat`/`access`...), which resolves symlinks
//! **after** receiving the host path from this engine. Every path that reaches
//! that layer is therefore already sandboxed by the rules in [`crate::rules`].

/// Separator used by every path handled by this crate.
pub const SEP: char = '/';

/// A path is absolute for our purposes iff it starts with `/`.
#[inline]
pub fn is_absolute(p: &str) -> bool {
    p.starts_with(SEP)
}

/// Collapse `//`, `.` and `..` without touching the filesystem.
///
/// `..` at the root is absorbed (`/..` -> `/`). Relative inputs keep at most a
/// leading run of `..` because they cannot be resolved without a cwd.
pub fn normalize(path: &str) -> String {
    let absolute = path.starts_with(SEP);
    let mut stack: Vec<&str> = Vec::new();

    for seg in path.split(SEP) {
        match seg {
            "" | "." => {}
            ".." => {
                let can_pop = matches!(stack.last(), Some(&last) if last != "..");
                if can_pop {
                    stack.pop();
                } else if !absolute {
                    stack.push("..");
                }
                // absolute + `..` at root -> absorbed
            }
            s => stack.push(s),
        }
    }

    if absolute {
        format!("/{}", stack.join("/"))
    } else if stack.is_empty() {
        ".".to_string()
    } else {
        stack.join("/")
    }
}

/// Join `child` onto `base`. An absolute `child` replaces `base` entirely.
pub fn join(base: &str, child: &str) -> String {
    if child.starts_with(SEP) {
        return normalize(child);
    }
    let base = base.trim_end_matches(SEP);
    if base.is_empty() {
        normalize(child)
    } else {
        normalize(&format!("{}{}{}", base, SEP, child))
    }
}

/// True when `path == dir` or `path` is a descendant of `dir`.
pub fn is_under(path: &str, dir: &str) -> bool {
    let dir = dir.trim_end_matches(SEP);
    if dir.is_empty() {
        return is_absolute(path);
    }
    path == dir || path.starts_with(dir) && path.as_bytes().get(dir.len()) == Some(&b'/')
}

/// Strip `dir` from `path`, returning the remainder **without** a leading `/`.
/// Returns `Some("")` when `path == dir`.
pub fn strip_prefix<'a>(path: &'a str, dir: &str) -> Option<&'a str> {
    let dir = dir.trim_end_matches(SEP);
    if path == dir {
        return Some("");
    }
    let rest = path.strip_prefix(dir)?;
    if !rest.starts_with(SEP) {
        // `/data/data/pkg` is NOT a parent of `/data/data/pkg2`.
        return None;
    }
    Some(&rest[1..])
}

/// Number of leading path segments. `/a/b/c` -> 3.
#[inline]
pub fn depth(path: &str) -> usize {
    path.split(SEP).filter(|s| !s.is_empty()).count()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_dots_and_slashes() {
        assert_eq!(normalize("/data//data/./pkg/"), "/data/data/pkg");
        assert_eq!(normalize("/data/data/../data/pkg"), "/data/data/pkg");
        assert_eq!(normalize("/../../etc"), "/etc");
        assert_eq!(normalize("a/b/../c"), "a/c");
        assert_eq!(normalize("../a"), "../a");
        assert_eq!(normalize("/"), "/");
        assert_eq!(normalize(""), ".");
    }

    #[test]
    fn joins_like_posix() {
        assert_eq!(join("/data/data/pkg", "files/x"), "/data/data/pkg/files/x");
        assert_eq!(join("/data/data/pkg/", "/sdcard"), "/sdcard");
        assert_eq!(join("", "x"), "x");
    }

    #[test]
    fn containment_is_prefix_aware() {
        assert!(is_under("/data/data/pkg/x", "/data/data/pkg"));
        assert!(is_under("/data/data/pkg", "/data/data/pkg"));
        assert!(!is_under("/data/data/pkg2", "/data/data/pkg"));
        assert!(is_under("/anything", "/"));
    }

    #[test]
    fn strips_only_on_segment_boundary() {
        assert_eq!(
            strip_prefix("/data/data/pkg/f", "/data/data/pkg"),
            Some("f")
        );
        assert_eq!(strip_prefix("/data/data/pkg", "/data/data/pkg"), Some(""));
        assert_eq!(strip_prefix("/data/data/pkg2", "/data/data/pkg"), None);
    }

    #[test]
    fn counts_depth() {
        assert_eq!(depth("/a/b/c"), 3);
        assert_eq!(depth("/"), 0);
    }
}
