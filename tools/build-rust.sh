#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Cross-compiles core/vfs (Rust) into a staticlib for every Android ABI we
# ship, and lays the artefacts out where CMake expects them.
#
#   ./tools/build-rust.sh                       # all ABIs, debug
#   ./tools/build-rust.sh --abi arm64-v8a       # one ABI
#   ./tools/build-rust.sh --release             # optimised
#   ./tools/build-rust.sh --test                # also run the policy tests
#
# Layout after a successful run:
#   core/vfs/target/aarch64-linux-android/release/libaether_vfs.a  (cargo-ndk)
#   core/vfs/target/arm64-v8a/release/libaether_vfs.a              (CMake input)
# ---------------------------------------------------------------------------
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VFS_DIR="$REPO_ROOT/core/vfs"

BUILD_MODE="debug"
RUN_TESTS=0
ABIS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --abi)     ABIS+=("$2"); shift 2 ;;
    --release) BUILD_MODE="release"; shift ;;
    --debug)   BUILD_MODE="debug"; shift ;;
    --test)    RUN_TESTS=1; shift ;;
    -h|--help) sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ ${#ABIS[@]} -eq 0 ]]; then
  ABIS=(arm64-v8a armeabi-v7a x86_64)
fi

triple_for() {
  case "$1" in
    arm64-v8a)       echo aarch64-linux-android ;;
    armeabi-v7a)     echo armv7-linux-androideabi ;;
    x86)             echo i686-linux-android ;;
    x86_64)          echo x86_64-linux-android ;;
    *) echo "unsupported ABI: $1" >&2; return 1 ;;
  esac
}

command -v cargo >/dev/null 2>&1 || { echo "cargo not found; install from https://rustup.rs" >&2; exit 1; }

if [[ $RUN_TESTS -eq 1 ]]; then
  echo "==> cargo test (host target, policy engine)"
  (cd "$VFS_DIR" && cargo test --all-targets)
fi

for abi in "${ABIS[@]}"; do
  triple="$(triple_for "$abi")"
  echo "==> building libaether_vfs.a for $abi ($triple, $BUILD_MODE)"

  if command -v cargo-ndk >/dev/null 2>&1; then
    (cd "$VFS_DIR" && cargo ndk --target "$triple" --platform 24 -- build "--$BUILD_MODE")
  else
    # Fallback: plain cargo with a manually configured linker. Works when the
    # NDK clang is already on PATH (as on most CI images).
    echo "    cargo-ndk not installed; falling back to cargo (run: cargo install cargo-ndk --locked)"
    (cd "$VFS_DIR" && cargo build "--$BUILD_MODE" --target "$triple")
  fi

  src="$VFS_DIR/target/$triple/$BUILD_MODE/libaether_vfs.a"
  dst_dir="$VFS_DIR/target/$abi/$BUILD_MODE"
  if [[ ! -f "$src" ]]; then
    echo "    expected artefact not found: $src" >&2
    exit 1
  fi
  mkdir -p "$dst_dir"
  cp "$src" "$dst_dir/libaether_vfs.a"
  echo "    -> $dst_dir/libaether_vfs.a"
done

echo "==> done."
