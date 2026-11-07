"""Run the 72 file ingestion combinations against shipped skills and synthetic sources."""

import json
from concurrent.futures import ThreadPoolExecutor, as_completed

from run_host import CATALOG, OUT, run


def main():
    selected = [c["id"] for c in CATALOG if c["area"] == "INGEST"]
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = []
        for start in range(0, len(selected), 6):
            label = f"ingest-matrix-{start // 6 + 1:02}"
            if not (OUT / label / "invocation.json").exists():
                futures.append(pool.submit(run, selected[start : start + 6], label))
        for f in as_completed(futures):
            try:
                f.result()
            except (OSError, ValueError, RuntimeError, AssertionError) as exc:
                print(json.dumps({"runner_error": type(exc).__name__}), flush=True)


if __name__ == "__main__":
    main()
