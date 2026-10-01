//! `aether-vfs` — the path-translation and isolation policy engine at the
//! heart of AetherEngine.
//!
//! Layer map:
//!
//! ```text
//!   Guest app (Dalvik/ART)  ->  libc open()/stat()/fopen()
//!        |                            |
//!        |                     io_redirect.cpp   (PLT/GOT hooks, Phase 2)
//!        |                            |
//!        +-----------------------> aether-vfs  <-- THIS CRATE
//!                                     |
//!                              host kernel VFS
//! ```
//!
//! The engine is deliberately *pure*: no syscalls, no allocation of file
//! descriptors, no knowledge of Java. That is what makes the policy testable in
//! CI on any host OS (see `tests/`), which matters because a single wrong rule
//! here is a silent data leak rather than a crash.

#![warn(missing_docs)]

pub mod ffi;
pub mod path;
pub mod rules;
pub mod translator;

pub use rules::{Action, DenyReason, Mode, Rule, RuleSet};
pub use translator::{Resolution, ShadowLayout, SharedMedia, Status, Vfs, VfsConfig};

/// Crate version, exposed to Kotlin via `VfsRouter.nativeVersion()`.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");
