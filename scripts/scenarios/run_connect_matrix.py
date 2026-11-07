"""Run each provider's twelve catalog cases through real Codex + synthetic host."""

import json
from concurrent.futures import ThreadPoolExecutor, as_completed

from run_host import CATALOG, OUT, run


def main():
    groups = {}
    for c in CATALOG:
        if c["area"] == "CONNECT":
            groups.setdefault(c["title"].split(" 연결:")[0], []).append(c["id"])
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = {}
        for index, (name, ids) in enumerate(groups.items(), 1):
            label = f"connect-matrix-{index:02}"
            if (OUT / label / "invocation.json").exists():
                continue
            futures[pool.submit(run, ids, label)] = name
        for f in as_completed(futures):
            try:
                f.result()
            except (OSError, ValueError, RuntimeError, AssertionError) as exc:
                print(
                    json.dumps(
                        {"provider": futures[f], "runner_error": type(exc).__name__}
                    ),
                    flush=True,
                )


if __name__ == "__main__":
    main()
