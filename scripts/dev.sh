#!/usr/bin/env bash
set -euo pipefail
FORGE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export FORGE_DATA_DIR="${FORGE_DATA_DIR:-$FORGE_ROOT/.data}"
cd "$FORGE_ROOT"
bash scripts/maven.sh spring-boot:run &
BACKEND_PID=$!
cleanup() {
  kill "$BACKEND_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM
cd frontend
npm run dev
