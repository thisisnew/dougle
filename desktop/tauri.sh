#!/usr/bin/env bash
set -euo pipefail
desktop_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$desktop_root/.." && pwd)"
export CARGO_HOME="$repo_root/.tools/cargo"
export RUSTUP_HOME="$repo_root/.tools/rustup"
export PATH="$CARGO_HOME/bin:$PATH"
cd "$desktop_root"
exec "$desktop_root/node_modules/.bin/tauri" "$@"
