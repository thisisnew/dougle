#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export CARGO_HOME="$repo_root/.tools/cargo"
export RUSTUP_HOME="$repo_root/.tools/rustup"
export PATH="$CARGO_HOME/bin:$PATH"
version="$(awk -F '"' '/^channel/ { print $2 }' "$repo_root/desktop/rust-toolchain.toml")"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Invalid pinned Rust version.' >&2; exit 1; }
mkdir -p "$repo_root/.tools"
if [[ ! -x "$CARGO_HOME/bin/rustup" ]]; then
  curl --proto '=https' --tlsv1.2 --fail --show-error --location https://sh.rustup.rs -o "$repo_root/.tools/rustup-init.sh"
  sh "$repo_root/.tools/rustup-init.sh" -y --no-modify-path --profile minimal --default-toolchain "$version"
else
  rustup toolchain install "$version" --profile minimal
fi
rustc +"$version" --version
