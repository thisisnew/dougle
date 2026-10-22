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
if forge_verify_macos_architecture "$app" x86_64 2>/dev/null; then echo 'Wrong app architecture accepted' >&2; exit 1; fi
cp "$temporary/x86_64.o" "$app/Contents/Resources/resources/runtime/lib/server/libjvm.dylib"
if forge_verify_macos_architecture "$app" arm64 2>/dev/null; then echo 'Mixed JVM architecture accepted' >&2; exit 1; fi
/usr/bin/lipo -create "$temporary/arm64.o" "$temporary/x86_64.o" -output "$temporary/universal.o"
if forge_macos_architecture "$temporary/universal.o" 2>/dev/null; then echo 'Unconfigured universal binary accepted' >&2; exit 1; fi
if forge_macos_architecture "$temporary/missing" 2>/dev/null; then echo 'Missing binary accepted' >&2; exit 1; fi
echo 'PASS: ARM/Intel identification, matching app, wrong architecture, mixed JVM, universal and missing binary rejection'
