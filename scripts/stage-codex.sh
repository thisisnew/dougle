#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
architecture="${1:-$(uname -m)}"
case "$architecture" in
  arm64) target=aarch64-apple-darwin; checksum=61c4d9a92521bdb42b0c1d9f87c673f6da628a06de5945cbdd9a0010632f6d10 ;;
  x86_64) target=x86_64-apple-darwin; checksum=562a1aea0e5686283a467687d268ae33705f02fd4d40f1bc4110a415c5c1c7b1 ;;
  *) echo 'Unsupported Codex architecture' >&2; exit 2 ;;
esac
version=0.163.0
cache="$repo_root/.tools/codex-bundle"
output="$repo_root/desktop/src-tauri/resources/ai"
[[ ! -L "$cache" && ! -L "$output" && ! -L "$output/codex" ]] || { echo 'Symlinked staging paths are forbidden' >&2; exit 1; }
mkdir -p "$cache" "$output"
archive="$cache/codex-${architecture}.tar.gz"
verify() { printf '%s  %s\n' "$1" "$2" | shasum -a 256 -c - >/dev/null; }
if [[ ! -f "$archive" ]] || ! verify "$checksum" "$archive"; then
  curl --proto '=https' --tlsv1.2 -fL --retry 2 "https://github.com/openai/codex/releases/download/rust-v${version}/codex-${target}.tar.gz" -o "$archive.download"
  verify "$checksum" "$archive.download"
  mv "$archive.download" "$archive"
fi
staging="$(mktemp -d "$output/.staging.XXXXXX")"
trap 'rm -rf "$staging"' EXIT
tar -xOf "$archive" "codex-${target}" > "$staging/codex"
chmod 755 "$staging/codex"
source "$repo_root/scripts/macos-architecture.sh"
[[ "$(forge_macos_architecture "$staging/codex")" == "$architecture" ]]
for notice in LICENSE NOTICE; do
  case "$notice" in
    LICENSE) digest=d17f227e4df5da1600391338865ce0f3055211760a36688f816941d58232d8dc ;;
    NOTICE) digest=9d71575ecfd9a843fc1677b0efb08053c6ba9fd686a0de1a6f5382fd3c220915 ;;
  esac
  curl --proto '=https' --tlsv1.2 -fsSL "https://raw.githubusercontent.com/openai/codex/rust-v${version}/${notice}" -o "$staging/CODEX-${notice}"
  verify "$digest" "$staging/CODEX-${notice}"
  mv "$staging/CODEX-${notice}" "$output/CODEX-${notice}"
done
mv "$staging/codex" "$output/codex"
printf 'Codex %s / %s / archive SHA-256 %s\n' "$version" "$target" "$checksum" > "$output/codex-version.txt"
echo "Staged Codex $version ($architecture), with license and notice; no login or inference executed."
