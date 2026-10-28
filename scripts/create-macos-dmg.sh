#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app="${1:-$repo_root/desktop/src-tauri/target/release/bundle/macos/Dougle.app}"
source "$repo_root/scripts/macos-architecture.sh"
architecture="$(forge_macos_architecture "$app/Contents/MacOS/dougle-desktop")"
forge_verify_macos_architecture "$app" "$architecture"
version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$app/Contents/Info.plist")"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Expected a numeric three-part app version.' >&2; exit 1; }
release="$repo_root/release"
[[ ! -L "$release" ]] || { echo 'Refusing a symlinked release directory.' >&2; exit 1; }
mkdir -p "$release"
staging="$(mktemp -d "${TMPDIR:-/tmp}/forge-dmg-stage.XXXXXX")"
trap 'rm -rf "$staging"' EXIT
codesign --verify --deep --strict "$app"
ditto --norsrc --noextattr "$app" "$staging/Dougle.app"
xattr -dr com.apple.FinderInfo "$staging/Dougle.app" 2>/dev/null || true
xattr -dr com.apple.ResourceFork "$staging/Dougle.app" 2>/dev/null || true
xattr -d com.apple.FinderInfo "$staging/Dougle.app" 2>/dev/null || true
if ! codesign --verify --deep --strict "$staging/Dougle.app"; then
  xattr -l "$staging/Dougle.app"
  exit 1
fi
ln -s /Applications "$staging/Applications"
disk_image="$release/Dougle_${version}_${architecture}.dmg"
[[ ! -L "$disk_image" ]] || { echo 'Refusing a symlinked output.' >&2; exit 1; }
hdiutil create -volname 'Dougle' -srcfolder "$staging" -format UDZO -ov "$disk_image"
hdiutil verify "$disk_image"
(cd "$release" && shasum -a 256 "$(basename "$disk_image")" > "SHA256SUMS-${architecture}")
(cd "$release" && shasum -a 256 Dougle_"${version}"_*.dmg > SHA256SUMS)
echo "Ad-hoc signed preview artifacts: $release (not Apple notarized)"
