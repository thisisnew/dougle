#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo_root/scripts/macos-architecture.sh"
[[ $# == 3 ]] || { echo 'Usage: bash scripts/verify-macos-update.sh archive version arm64|x86_64' >&2; exit 2; }
archive="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")" version="$2" architecture="$3"
case "$architecture" in arm64|x86_64) ;; *) exit 2 ;; esac
TAURI_CONFIG='{"bundle":{"resources":[]}}' CARGO_BUILD_JOBS=2 bash "$repo_root/scripts/desktop-cargo.sh" run --locked --example updater_verify -- \
  "$repo_root/desktop/src-tauri/tauri.conf.json" "$archive" "$archive.sig" "$version"
temporary="$(mktemp -d "${TMPDIR:-/tmp}/forge-update-package-qa.XXXXXX")"
trap 'rm -rf "$temporary"' EXIT
tar -xzf "$archive" -C "$temporary"
app="$temporary/Dougle.app"
[[ "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$app/Contents/Info.plist")" == "$version" ]]
forge_verify_macos_architecture "$app" "$architecture"
codesign --verify --deep --strict "$app"
python3 "$repo_root/scripts/trim-macos-jar.py" "$repo_root/backend/target/dougle-0.1.0.jar" "$temporary/expected.jar" "$architecture"
cmp "$temporary/expected.jar" "$app/Contents/Resources/resources/dougle.jar"
echo "PASS: $architecture signed update archive, extracted app version, architecture, ad-hoc signature and current JAR; runtime NOT tested"
