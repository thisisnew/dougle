"""Developer benchmark: synthetic Markdown, real scanner, no AI or company data.

Run with a developer Python. --baseline is an optional prior scanner path.
The distributed knowledge skills do not depend on this script or Python.
"""

import argparse
import json
import platform
import subprocess
import tempfile
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--counts", type=int, nargs="+", default=[100, 1000, 5000])
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if any(n <= 0 for n in args.counts):
        parser.error("counts must be positive")
    current = (
        Path(__file__).resolve().parents[1]
        / "skills/dougle-publish/scripts/check-sensitive.sh"
    )
    variants = [("before", args.baseline.resolve())] if args.baseline else []
    variants.append(("after", current))
    report = {
        "environment": platform.platform(),
        "synthetic": True,
        "realModelCalls": 0,
        "samples": [],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="dougle-scanner-benchmark-") as tmp:
        for count in args.counts:
            package = Path(tmp) / str(count)
            package.mkdir(exist_ok=True)
            for i in range(count):
                (package / f"topic-{i:06d}.md").write_text(
                    ("Domain rule with conditions and original evidence.\n" * 85)[:4096]
                )
            row = {
                "files": count,
                "bytes": sum(p.stat().st_size for p in package.iterdir()),
                "runs": {},
            }
            for label, script in variants:
                start = time.monotonic()
                result = subprocess.run(
                    ["/bin/sh", str(script), str(package)],
                    capture_output=True,
                    check=False,
                    text=True,
                )
                elapsed = round(time.monotonic() - start, 3)
                scan = json.loads(result.stdout)
                if (
                    result.returncode
                    or result.stderr
                    or scan["status"] != "PASS"
                    or scan["filesChecked"] != count
                ):
                    raise RuntimeError("synthetic package scan failed")
                row["runs"][label] = {
                    "seconds": elapsed,
                    "digest": scan["packageDigest"],
                }
                print(
                    json.dumps({"files": count, "variant": label, "seconds": elapsed}),
                    flush=True,
                )
            if len({run["digest"] for run in row["runs"].values()}) != 1:
                raise RuntimeError("baseline and current package digests differ")
            report["samples"].append(row)
            args.output.write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
