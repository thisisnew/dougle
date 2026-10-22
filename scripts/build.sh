#!/usr/bin/env bash
set -euo pipefail
FORGE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$FORGE_ROOT/frontend"
npm ci --cache "$FORGE_ROOT/.tools/npm-cache" --no-audit --no-fund
npm test -- --maxWorkers=2
npm run build
bash "$FORGE_ROOT/scripts/maven.sh" clean verify
