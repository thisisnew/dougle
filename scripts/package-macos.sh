#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mode="${1:-verify}"
case "$mode" in verify|--skip-verify|--stage-only) ;; *) echo 'Usage: bash scripts/package-macos.sh [--skip-verify|--stage-only]' >&2; exit 2 ;; esac
[[ "$(uname -s)" == Darwin ]] || { echo 'macOS is required.' >&2; exit 1; }
source "$repo_root/scripts/macos-architecture.sh"
target="${FORGE_MACOS_TARGET:-}"
target_args=()
target_directory="$repo_root/desktop/src-tauri/target"
case "$target" in
  '') architecture="$(uname -m)" ;;
  aarch64-apple-darwin) architecture=arm64; target_args=(--target "$target"); target_directory="$target_directory/$target" ;;
  x86_64-apple-darwin) architecture=x86_64; target_args=(--target "$target"); target_directory="$target_directory/$target" ;;
  *) echo 'FORGE_MACOS_TARGET must be aarch64-apple-darwin or x86_64-apple-darwin.' >&2; exit 2 ;;
esac
java_home="${FORGE_PACKAGE_JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
target_java_home="${FORGE_TARGET_JAVA_HOME:-$java_home}"
[[ "$(forge_macos_architecture "$target_java_home/bin/java")" == "$architecture" ]] || {
  echo 'Set FORGE_TARGET_JAVA_HOME to a Java 21 JDK for the selected architecture.' >&2; exit 1;
}
host_release="$(sed -n 's/^JAVA_VERSION=//p' "$java_home/release")"
target_release="$(sed -n 's/^JAVA_VERSION=//p' "$target_java_home/release")"
[[ -n "$host_release" && "$host_release" == "$target_release" ]] || { echo 'Host and target JDK versions must match.' >&2; exit 1; }
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
"$java_home/bin/jlink" --module-path "$target_java_home/jmods" --add-modules ALL-MODULE-PATH \
  --strip-debug --no-header-files --no-man-pages --output "$staging/runtime"
for notice in LICENSE ADDITIONAL_LICENSE_INFO ASSEMBLY_EXCEPTION; do
  if [[ -f "$target_java_home/$notice" ]]; then cp "$target_java_home/$notice" "$staging/runtime/$notice"; fi
done
find "$staging/runtime" -type f -exec chmod u+w {} +
[[ "$(forge_macos_architecture "$staging/runtime/bin/java")" == "$architecture" ]]
if [[ "$architecture" == "$(uname -m)" ]]; then "$staging/runtime/bin/java" -version; fi
cp "$jar" "$staging/knowledge-forge.jar"
rm -rf "$resources/runtime"
mv "$staging/runtime" "$resources/runtime"
mv "$staging/knowledge-forge.jar" "$resources/knowledge-forge.jar"
if [[ "$mode" == --stage-only ]]; then echo 'Java runtime and JAR staged; no installer built.'; exit 0; fi
cd "$repo_root/desktop"
npm ci --cache "$repo_root/.tools/npm-cache" --no-audit --no-fund
export APPLE_SIGNING_IDENTITY=-
export CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-2}"
cached_resources="$target_directory/release/resources"
if [[ -d "$cached_resources" && ! -L "$cached_resources" ]]; then
  find "$cached_resources" -type f -exec chmod u+w {} +
fi
unset APPLE_CERTIFICATE APPLE_CERTIFICATE_PASSWORD APPLE_ID APPLE_PASSWORD APPLE_TEAM_ID APPLE_API_ISSUER APPLE_API_KEY APPLE_API_KEY_PATH
npm run tauri -- build --bundles app ${target_args[@]+"${target_args[@]}"} -- --locked
app="$target_directory/release/bundle/macos/Knowledge Forge.app"
forge_verify_macos_architecture "$app" "$architecture"
bash "$repo_root/scripts/create-macos-dmg.sh" "$app"
