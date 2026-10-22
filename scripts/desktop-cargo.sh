#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export CARGO_HOME="$repo_root/.tools/cargo"
export RUSTUP_HOME="$repo_root/.tools/rustup"
export PATH="$CARGO_HOME/bin:$PATH"
case "${1:-}" in
  check|test|clippy) export TAURI_CONFIG='{"bundle":{"resources":[]}}' ;;
esac
cd "$repo_root/desktop/src-tauri"
exec cargo "$@"
