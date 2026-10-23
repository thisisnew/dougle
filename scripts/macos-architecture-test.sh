#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo_root/scripts/macos-architecture.sh"
temporary="$(mktemp -d "${TMPDIR:-/tmp}/forge-arch-test.XXXXXX")"
trap 'rm -rf "$temporary"' EXIT
for architecture in arm64 x86_64; do
  printf 'int main(void) { return 0; }\n' | xcrun clang -arch "$architecture" -x c -c - -o "$temporary/$architecture.o"
  [[ "$(forge_macos_architecture "$temporary/$architecture.o")" == "$architecture" ]]
done
app="$temporary/Test.app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources/resources/runtime/bin" "$app/Contents/Resources/resources/runtime/lib/server"
for binary in Contents/MacOS/knowledge-forge-desktop Contents/Resources/resources/runtime/bin/java \
    Contents/Resources/resources/runtime/lib/libjli.dylib Contents/Resources/resources/runtime/lib/server/libjvm.dylib; do
  cp "$temporary/arm64.o" "$app/$binary"
done
forge_verify_macos_architecture "$app" arm64
mkdir -p "$app/Contents/Resources/resources/ai"
cp "$temporary/arm64.o" "$app/Contents/Resources/resources/ai/codex"
forge_verify_macos_architecture "$app" arm64
cp "$temporary/x86_64.o" "$app/Contents/Resources/resources/ai/codex"
if forge_verify_macos_architecture "$app" arm64 2>/dev/null; then echo 'Mixed Codex architecture accepted' >&2; exit 1; fi
cp "$temporary/arm64.o" "$app/Contents/Resources/resources/ai/codex"
if forge_verify_macos_architecture "$app" x86_64 2>/dev/null; then echo 'Wrong app architecture accepted' >&2; exit 1; fi
git_root="$temporary/git"
mkdir -p "$git_root/bin" "$git_root/libexec/git-core" "$git_root/source"
printf 'int main(void) { return 0; }\n' | xcrun clang -arch arm64 -x c - -o "$temporary/git-arm64"
printf 'int main(void) { return 0; }\n' | xcrun clang -arch x86_64 -x c - -o "$temporary/git-x86_64"
for binary in bin/git libexec/git-core/git libexec/git-core/git-remote-http libexec/git-core/git-remote-https; do
  cp "$temporary/git-arm64" "$git_root/$binary"
done
for notice in COPYING source/git-2.56.0.tar.xz source/build-bundled-git.sh source/README.txt; do
  printf 'synthetic notice\n' > "$git_root/$notice"
done
forge_verify_bundled_git "$git_root" arm64
cp "$temporary/git-x86_64" "$git_root/libexec/git-core/git-remote-https"
if forge_verify_bundled_git "$git_root" arm64 2>/dev/null; then echo 'Mixed Git helper accepted' >&2; exit 1; fi
cp "$temporary/git-arm64" "$git_root/libexec/git-core/git-remote-https"
mv "$git_root/COPYING" "$temporary/saved-copying"
if forge_verify_bundled_git "$git_root" arm64 2>/dev/null; then echo 'Missing Git license accepted' >&2; exit 1; fi
mv "$temporary/saved-copying" "$git_root/COPYING"
printf 'int forge_fixture(void) { return 0; }\n' | xcrun clang -arch arm64 -dynamiclib -x c - \
  -install_name /opt/homebrew/lib/libforge-fixture.dylib -o "$temporary/libforge-fixture.dylib"
printf 'int forge_fixture(void); int main(void) { return forge_fixture(); }\n' | xcrun clang -arch arm64 -x c - \
  -x none "$temporary/libforge-fixture.dylib" -o "$git_root/bin/git"
if forge_verify_bundled_git "$git_root" arm64 2>/dev/null; then echo 'External Git library dependency accepted' >&2; exit 1; fi
cp "$temporary/git-arm64" "$git_root/bin/git"
mv "$git_root/source/git-2.56.0.tar.xz" "$temporary/saved-source"
if forge_verify_bundled_git "$git_root" arm64 2>/dev/null; then echo 'Missing Git source accepted' >&2; exit 1; fi
cp "$temporary/x86_64.o" "$app/Contents/Resources/resources/runtime/lib/server/libjvm.dylib"
if forge_verify_macos_architecture "$app" arm64 2>/dev/null; then echo 'Mixed JVM architecture accepted' >&2; exit 1; fi
/usr/bin/lipo -create "$temporary/arm64.o" "$temporary/x86_64.o" -output "$temporary/universal.o"
if forge_macos_architecture "$temporary/universal.o" 2>/dev/null; then echo 'Unconfigured universal binary accepted' >&2; exit 1; fi
if forge_macos_architecture "$temporary/missing" 2>/dev/null; then echo 'Missing binary accepted' >&2; exit 1; fi
echo 'PASS: ARM/Intel identification, matching app, mixed JVM/Codex/Git, external Git library, missing Git license/source, universal and missing binary rejection'
