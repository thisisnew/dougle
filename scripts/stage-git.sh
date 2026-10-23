#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
architecture="${1:-$(uname -m)}"
case "$architecture" in arm64|x86_64) ;; *) exit 2 ;; esac
version=2.56.0
checksum=26c56c296b38c0695b26fa95f475f1d01704d2d38e73465ca30b0b2f5dc789d3
cache="$repo_root/.tools/git-bundle"
resources="$repo_root/desktop/src-tauri/resources"
output="$resources/git"
[[ ! -L "$cache" && ! -L "$resources" && ! -L "$output" ]] || { echo 'Symlinked Git staging paths are forbidden' >&2; exit 1; }
mkdir -p "$cache" "$resources"
archive="$cache/git-${version}.tar.xz"
verify() { printf '%s  %s\n' "$checksum" "$1" | shasum -a 256 -c - >/dev/null; }
if [[ ! -f "$archive" ]] || ! verify "$archive"; then
  curl --proto '=https' --tlsv1.2 -fL --retry 2 "https://www.kernel.org/pub/software/scm/git/git-${version}.tar.xz" -o "$archive.download"
  verify "$archive.download"
  mv "$archive.download" "$archive"
fi
staging="$(mktemp -d "$resources/.git-staging.XXXXXX")"
trap 'rm -rf "$staging"' EXIT
tar -xf "$archive" -C "$staging"
bash "$repo_root/scripts/build-bundled-git.sh" "$staging/git-$version" "$architecture" "$staging/bundle"
cp "$archive" "$staging/bundle/source/"
cp "$repo_root/scripts/build-bundled-git.sh" "$staging/bundle/source/"
cp "$repo_root/docs/bundled-git-source.txt" "$staging/bundle/source/README.txt"
printf 'Git %s / %s / source SHA-256 %s\n' "$version" "$architecture" "$checksum" > "$staging/bundle/version.txt"
source "$repo_root/scripts/macos-architecture.sh"
forge_verify_bundled_git "$staging/bundle" "$architecture"
if [[ "$architecture" == "$(uname -m)" ]]; then
  env -i PATH=/usr/bin:/bin "$staging/bundle/bin/git" --version
fi
if [[ -e "$output" ]]; then mv "$output" "$staging/previous"; fi
mv "$staging/bundle" "$output"
echo "Staged Git $version ($architecture), source and build recipe; only macOS system libraries required."
