#!/usr/bin/env bash
# Regenerates the VirtualActivity stub pool.
# Thin wrapper so CI and Gradle do not have to care about the interpreter.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec python3 "$REPO_ROOT/tools/gen-stubs/gen_stubs.py" "$@"
