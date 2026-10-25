#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo_root/scripts/macos-architecture.sh"
static_only=false
if [[ "${1:-}" == --static-only ]]; then static_only=true; shift; fi
[[ "$(uname -s)" == Darwin ]] || { echo 'macOS GUI session required.' >&2; exit 1; }
if [[ $# -gt 0 ]]; then
  disk_image="$1"
else
  version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$repo_root/desktop/src-tauri/target/release/bundle/macos/Knowledge Forge.app/Contents/Info.plist")"
  disk_image="$repo_root/release/Knowledge.Forge_${version}_$(uname -m).dmg"
fi
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
architecture="$(forge_macos_architecture "$app/Contents/MacOS/knowledge-forge-desktop")"
forge_verify_macos_architecture "$app" "$architecture"
node "$repo_root/scripts/stage-search-model.mjs" "$architecture" "$app/Contents/Resources/resources/search-model-e5-v1" --verify-only
if ! codesign --verify --deep --strict "$app"; then
  xattr -lr "$app" | sed -n '/com.apple.FinderInfo/p;/com.apple.ResourceFork/p'
  exit 1
fi
if [[ "$static_only" == true ]]; then
  echo "PASS: $architecture DMG integrity, installed copy, architecture and signature; runtime NOT tested"
  exit 0
fi
[[ "$architecture" == "$(uname -m)" ]] || { echo 'Runtime verification requires a matching Mac. Use --static-only for cross-built packages.' >&2; exit 1; }
env -i PATH=/usr/bin:/bin "$app/Contents/Resources/resources/runtime/bin/java" -version
PATH=/usr/bin:/bin JAVA_HOME=/nonexistent TAURI_CONFIG='{"bundle":{"resources":[]}}' \
  CARGO_BUILD_JOBS=2 bash "$repo_root/scripts/desktop-cargo.sh" run --locked --example runtime_smoke -- "$app/Contents/Resources"
if [[ "${FORGE_VERIFY_PUBLIC_GITHUB:-false}" == true ]]; then
  TAURI_CONFIG='{"bundle":{"resources":[]}}' CARGO_BUILD_JOBS=2 \
    bash "$repo_root/scripts/desktop-cargo.sh" build --locked --example git_smoke
  env PATH=/nonexistent GIT_EXEC_PATH=/nonexistent GIT_CONFIG_PARAMETERS=invalid \
    "$repo_root/desktop/src-tauri/target/debug/examples/git_smoke" "$app/Contents/Resources"
fi
echo 'PASS: DMG verification, read-only mount, installed copy, signature and native runtime UI'
