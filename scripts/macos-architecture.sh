#!/usr/bin/env bash

forge_macos_architecture() {
  local architecture
  architecture="$(/usr/bin/lipo -archs "$1")" || return 1
  case "$architecture" in arm64|x86_64) printf '%s\n' "$architecture" ;; *) echo "Unsupported or universal architecture: $architecture" >&2; return 1 ;; esac
}

forge_verify_macos_architecture() {
  local app="$1" expected="$2" binary
  for binary in "$app/Contents/MacOS/knowledge-forge-desktop" \
      "$app/Contents/Resources/resources/runtime/bin/java" \
      "$app/Contents/Resources/resources/runtime/lib/libjli.dylib" \
      "$app/Contents/Resources/resources/runtime/lib/server/libjvm.dylib"; do
    [[ "$(forge_macos_architecture "$binary")" == "$expected" ]] || {
      echo "Architecture mismatch: $binary (expected $expected)" >&2
      return 1
    }
  done
}
