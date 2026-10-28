#!/usr/bin/env bash

forge_macos_architecture() {
  local architecture
  architecture="$(/usr/bin/lipo -archs "$1")" || return 1
  case "$architecture" in arm64|x86_64) printf '%s\n' "$architecture" ;; *) echo "Unsupported or universal architecture: $architecture" >&2; return 1 ;; esac
}

forge_verify_bundled_git() {
  local root="$1" expected="$2" binary dependency
  for binary in "$root/bin/git" "$root/libexec/git-core/git" \
      "$root/libexec/git-core/git-remote-http" "$root/libexec/git-core/git-remote-https"; do
    [[ -x "$binary" && ! -L "$binary" && "$(forge_macos_architecture "$binary")" == "$expected" ]] || {
      echo "Bundled Git missing or architecture mismatch: $binary" >&2
      return 1
    }
    while IFS= read -r dependency; do
      case "$dependency" in /usr/lib/*|/System/Library/*) ;; *) echo "Non-system Git dependency: $dependency" >&2; return 1 ;; esac
    done < <(/usr/bin/otool -L "$binary" | sed -n '2,$s/^[[:space:]]*\([^ ]*\) .*/\1/p')
  done
  [[ -s "$root/COPYING" && -s "$root/source/git-2.56.0.tar.xz" && \
      -s "$root/source/build-bundled-git.sh" && -s "$root/source/README.txt" ]] || {
    echo 'Bundled Git source or license missing' >&2
    return 1
  }
}

forge_verify_macos_architecture() {
  local app="$1" expected="$2" binary
  [[ "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleName' "$app/Contents/Info.plist")" == Dougle && \
      "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app/Contents/Info.plist")" == dev.dougle.desktop && \
      "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$app/Contents/Info.plist")" == dougle-desktop ]] || {
    echo 'App name, identifier or executable does not match Dougle.' >&2
    return 1
  }
  for binary in "$app/Contents/MacOS/dougle-desktop" \
      "$app/Contents/Resources/resources/runtime/bin/java" \
      "$app/Contents/Resources/resources/runtime/lib/libjli.dylib" \
      "$app/Contents/Resources/resources/runtime/lib/server/libjvm.dylib"; do
    [[ "$(forge_macos_architecture "$binary")" == "$expected" ]] || {
      echo "Architecture mismatch: $binary (expected $expected)" >&2
      return 1
    }
  done
  if [[ -e "$app/Contents/Resources/resources/ai/codex" ]]; then
    [[ "$(forge_macos_architecture "$app/Contents/Resources/resources/ai/codex")" == "$expected" ]] || {
      echo 'Bundled Codex architecture mismatch' >&2
      return 1
    }
  fi
  if [[ -e "$app/Contents/Resources/resources/git" ]]; then
    forge_verify_bundled_git "$app/Contents/Resources/resources/git" "$expected" || return 1
  fi
}
