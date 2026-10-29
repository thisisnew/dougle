#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
export FORGE_VERIFY_SEARCH_MODEL_DIR="${FORGE_VERIFY_SEARCH_MODEL_DIR:-$root/desktop/src-tauri/resources}"
export FORGE_ACCEPTANCE_MYSQLD="${FORGE_ACCEPTANCE_MYSQLD:-$(command -v mysqld || true)}"
[[ -f "$FORGE_VERIFY_SEARCH_MODEL_DIR/search-model-e5-v1/model.onnx" && -f "$FORGE_VERIFY_SEARCH_MODEL_DIR/search-model-e5-v1/reranker.onnx" ]] || { echo 'Bundled search models required'; exit 1; }
[[ -n "$FORGE_ACCEPTANCE_MYSQLD" && -x "$FORGE_ACCEPTANCE_MYSQLD" ]] || { echo 'MySQL 8.4 mysqld required for owned temporary read-only acceptance server'; exit 1; }
unset FORGE_LIVE_CLI FORGE_DOCUMENT_EVALUATION FORGE_VERIFY_NOTION_FORM FORGE_VERIFY_SEARCH_REPO
output="${1:-$root/backend/target/portability-verification}"
mkdir -p "$output"
source_hash="$(python3 - <<'PY'
import importlib.util,pathlib
spec=importlib.util.spec_from_file_location('report','scripts/verification-report.py')
module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
print(module.source_digest(pathlib.Path.cwd()))
PY
)"
started="$(python3 -c 'import time; print(time.time())')"
java_status=0
bash scripts/maven.sh -q \
  -Dforge.acceptance.collection=true -Dforge.acceptance.folderFailure=true \
  -Dforge.acceptance.workflow=true -Dforge.acceptance.routing=true -Dforge.acceptance.git=true \
  -Dforge.acceptance.webBoundary=true -Dforge.acceptance.readonly=true -Dforge.acceptance.oauth=true \
  -Dforge.acceptance.dns=true -Dforge.acceptance.mysql=true -Dforge.acceptance.notion=true \
  -Dforge.acceptance.search=true -Dforge.acceptance.syncProgress=true -Dforge.acceptance.large=true \
  -Dforge.test.isolated-browser=true \
  test > "$output/java.log" 2>&1 || java_status=$?
report_status=0
python3 scripts/verification-report.py --since "$started" --expected-source-sha256 "$source_hash" \
  --matrix-class LocalFolderIngestionMatrixTest --matrix-class TikaJavaAnalyzerBoundaryMatrixTest \
  --matrix-class LocalFolderCollectorBoundaryMatrixTest --matrix-class WebBoundaryMatrixTest \
  --matrix-class ReadOnlyBindingMatrixTest --matrix-class UnicodeChunkMatrixTest \
  --matrix-class LocalSearchQueryMatrixTest --matrix-class LuceneLifecycleMatrixTest \
  --output "$output/java-results.json" || report_status=$?
[[ "$java_status" == 0 && "$report_status" == 0 ]] || { echo "FAIL: Java=$java_status report=$report_status; results retained at $output"; exit 1; }
(cd frontend && VITE_UPDATE_ACCEPTANCE=1 VITE_SYNC_ACCEPTANCE=1 npm test -- --maxWorkers=2) > "$output/ui.log" 2>&1
node --test --test-concurrency=2 scripts/*.test.mjs > "$output/node.log" 2>&1
python3 scripts/verification-report-test.py > "$output/report-tests.log" 2>&1
bash scripts/desktop-cargo.sh test --locked > "$output/rust.log" 2>&1
echo "PASS: synthetic JVM/UI/Node/Rust checks; physical Macs/company SSO not asserted. Results: $output"
