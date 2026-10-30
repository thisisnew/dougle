#!/usr/bin/env bash
set -euo pipefail
desktop_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$desktop_root/.." && pwd)"
export CARGO_HOME="$repo_root/.tools/cargo"
export RUSTUP_HOME="$repo_root/.tools/rustup"
export PATH="$CARGO_HOME/bin:$PATH"
# Product builds and desktop development target Apple Silicon only.
# Host-side Rust verification tools use scripts/desktop-cargo.sh separately.
if [[ "${1:-}" == build || "${1:-}" == dev ]]; then
  arguments=("$@")
  has_target=false
  for ((i=0; i<${#arguments[@]}; i++)); do
    case "${arguments[$i]}" in
      --target|-t)
        has_target=true
        [[ "${arguments[$((i+1))]:-}" == aarch64-apple-darwin ]] || { echo 'Dougle supports Apple Silicon Macs only.' >&2; exit 2; }
        ;;
      --target=*)
        has_target=true
        [[ "${arguments[$i]}" == --target=aarch64-apple-darwin ]] || { echo 'Dougle supports Apple Silicon Macs only.' >&2; exit 2; }
        ;;
    esac
  done
  if [[ "$has_target" == false ]]; then
    command="$1"; shift
    set -- "$command" --target aarch64-apple-darwin "$@"
  fi
fi
cd "$desktop_root"
exec "$desktop_root/node_modules/.bin/tauri" "$@"
