#!/usr/bin/env bash
set -euo pipefail
FORGE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -z "${JAVA_HOME:-}" && "$(uname)" == "Darwin" ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi
export FORGE_DATA_DIR="${FORGE_DATA_DIR:-$FORGE_ROOT/.data}"
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$FORGE_ROOT/backend/target/knowledge-forge-0.1.0.jar"
