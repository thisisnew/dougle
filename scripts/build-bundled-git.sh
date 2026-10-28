#!/usr/bin/env bash
set -euo pipefail
[[ $# == 3 ]] || { echo 'Usage: bash build-bundled-git.sh extracted-source arm64|x86_64 output-directory' >&2; exit 2; }
source_directory="$1" architecture="$2" output="$3"
case "$architecture" in arm64|x86_64) ;; *) exit 2 ;; esac
[[ "$(uname -s)" == Darwin && -f "$source_directory/Makefile" && ! -e "$output" ]] || exit 1
sdk="$(/usr/bin/xcrun --show-sdk-path)"
/usr/bin/make -C "$source_directory" -j2 git git-remote-http \
  CC=/usr/bin/clang AR=/usr/bin/ar \
  CFLAGS="-O2 -arch $architecture -isysroot $sdk -mmacosx-version-min=11.3" \
  LDFLAGS="-arch $architecture -isysroot $sdk -mmacosx-version-min=11.3" \
  HOST_CPU="$architecture" prefix=/dougle gitexecdir=libexec/git-core \
  RUNTIME_PREFIX=YesPlease NO_GETTEXT=YesPlease NO_PERL=YesPlease NO_PYTHON=YesPlease \
  NO_TCLTK=YesPlease NO_EXPAT=YesPlease NO_OPENSSL=YesPlease NO_RUST=YesPlease \
  NO_FINK=YesPlease NO_DARWIN_PORTS=YesPlease NO_HOMEBREW=YesPlease \
  NEEDS_GOOD_LIBICONV= USE_HOMEBREW_LIBICONV= \
  CURL_CONFIG=/usr/bin/curl-config CURL_LDFLAGS=-lcurl
mkdir -p "$output/bin" "$output/libexec/git-core" "$output/source"
cp "$source_directory/git" "$output/bin/git"
cp "$source_directory/git-remote-http" "$output/libexec/git-core/git-remote-http"
cp "$source_directory/git-remote-http" "$output/libexec/git-core/git-remote-https"
cp "$source_directory/git" "$output/libexec/git-core/git"
cp "$source_directory/COPYING" "$output/COPYING"
chmod 755 "$output/bin/git" "$output/libexec/git-core/"*
