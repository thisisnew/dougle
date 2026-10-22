#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ "$(uname -s)" == Darwin ]] || { echo 'macOS GUI session required.' >&2; exit 1; }
disk_image="${1:-$repo_root/release/Knowledge Forge_0.1.0_$(uname -m).dmg}"
[[ -f "$disk_image" ]] || { echo 'Build the DMG first.' >&2; exit 1; }
temporary="$(mktemp -d "${TMPDIR:-/tmp}/forge-package-qa.XXXXXX")"
mounted=false
cleanup() {
  if [[ "$mounted" == true ]]; then hdiutil detach "$temporary/mount" >/dev/null; fi
  rm -rf "$temporary"
}
trap cleanup EXIT
mkdir "$temporary/mount" "$temporary/installed"
hdiutil verify "$disk_image"
hdiutil attach "$disk_image" -readonly -nobrowse -mountpoint "$temporary/mount" >/dev/null
mounted=true
codesign --verify --deep --strict "$temporary/mount/Knowledge Forge.app"
ditto "$temporary/mount/Knowledge Forge.app" "$temporary/installed/Knowledge Forge.app"
[[ "$(readlink "$temporary/mount/Applications")" == /Applications ]]
hdiutil detach "$temporary/mount" >/dev/null
mounted=false
app="$temporary/installed/Knowledge Forge.app"
if ! codesign --verify --deep --strict "$app"; then
  xattr -lr "$app" | sed -n '/com.apple.FinderInfo/p;/com.apple.ResourceFork/p'
  exit 1
fi
env -i PATH=/usr/bin:/bin "$app/Contents/Resources/resources/runtime/bin/java" -version
PATH=/usr/bin:/bin JAVA_HOME=/nonexistent TAURI_CONFIG='{"bundle":{"resources":[]}}' \
  CARGO_BUILD_JOBS=2 bash "$repo_root/scripts/desktop-cargo.sh" run --locked --example runtime_smoke -- "$app/Contents/Resources"
echo 'PASS: DMG verification, read-only mount, installed copy, signature and native runtime UI'
