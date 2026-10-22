#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mode="${1:-verify}"
case "$mode" in verify|--skip-verify|--stage-only) ;; *) echo 'Usage: bash scripts/package-macos.sh [--skip-verify|--stage-only]' >&2; exit 2 ;; esac
[[ "$(uname -s)" == Darwin ]] || { echo 'macOS is required.' >&2; exit 1; }
java_home="${FORGE_PACKAGE_JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
java_version="$("$java_home/bin/java" -version 2>&1 | head -1)"
[[ "$java_version" == *'"21.'* ]] || { echo 'Java 21 is required.' >&2; exit 1; }
if [[ "$mode" == verify ]]; then
  bash "$repo_root/scripts/build.sh"
  (cd "$repo_root" && node scripts/smoke.mjs)
fi
jar="$repo_root/backend/target/knowledge-forge-0.1.0.jar"
[[ -f "$jar" ]] || { echo 'Build the application first: bash scripts/build.sh' >&2; exit 1; }
resources="$repo_root/desktop/src-tauri/resources"
[[ ! -L "$resources" && ! -L "$resources/runtime" && ! -L "$resources/knowledge-forge.jar" ]] || { echo 'Refusing symlinked staging paths.' >&2; exit 1; }
mkdir -p "$resources"
staging="$(mktemp -d "$resources/.staging.XXXXXX")"
trap 'rm -rf "$staging"' EXIT
"$java_home/bin/jlink" --module-path "$java_home/jmods" --add-modules ALL-MODULE-PATH \
  --strip-debug --no-header-files --no-man-pages --output "$staging/runtime"
for notice in LICENSE ADDITIONAL_LICENSE_INFO ASSEMBLY_EXCEPTION; do
  if [[ -f "$java_home/$notice" ]]; then cp "$java_home/$notice" "$staging/runtime/$notice"; fi
done
find "$staging/runtime" -type f -exec chmod u+w {} +
"$staging/runtime/bin/java" -version
cp "$jar" "$staging/knowledge-forge.jar"
rm -rf "$resources/runtime"
mv "$staging/runtime" "$resources/runtime"
mv "$staging/knowledge-forge.jar" "$resources/knowledge-forge.jar"
if [[ "$mode" == --stage-only ]]; then echo 'Java runtime and JAR staged; no installer built.'; exit 0; fi
cd "$repo_root/desktop"
npm ci --cache "$repo_root/.tools/npm-cache" --no-audit --no-fund
export APPLE_SIGNING_IDENTITY=-
export CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-2}"
cached_resources="$repo_root/desktop/src-tauri/target/release/resources"
if [[ -d "$cached_resources" && ! -L "$cached_resources" ]]; then
  find "$cached_resources" -type f -exec chmod u+w {} +
fi
unset APPLE_CERTIFICATE APPLE_CERTIFICATE_PASSWORD APPLE_ID APPLE_PASSWORD APPLE_TEAM_ID APPLE_API_ISSUER APPLE_API_KEY APPLE_API_KEY_PATH
npm run tauri -- build --bundles app -- --locked
bash "$repo_root/scripts/create-macos-dmg.sh"
