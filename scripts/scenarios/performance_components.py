"""60 catalog-linked filesystem/retrieval experiments, NOT end-to-end skill timings.

This developer harness measures the I/O recipes used by a host AI. It does not
implement semantic internalization/merge or stand in for full connect/publish.
"""

import hashlib
import json
import subprocess
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / ".tools/scenario-run-20261107"
CASES = json.loads((ROOT / "docs/scenarios/catalog.json").read_text())["cases"]


def main():
    rows = []
    with tempfile.TemporaryDirectory(prefix="dougle-catalog-perf-") as tmp:
        root = Path(tmp)
        for variant in range(12):
            count = [
                1000,
                10000,
                100000,
                1000,
                10000,
                10000,
                10000,
                10000,
                1000,
                1000,
                1000,
                1000,
            ][variant]
            package = root / f"p{variant}"
            package.mkdir()
            for n in range(count):
                size = [4096, 65536, 1048576][n % 3] if variant == 3 else 4096
                header = f"Claim: C{n:06}\n"
                if n < 10:
                    header += "FIND_ALPHA_RULE\nCondition: approved only.\nException: HOLD means never reserve.\nEvidence: synthetic/source-v1#rule\n"
                body = (
                    header + ("Filler unrelated domain prose.\n" * ((size // 29) + 1))
                )[:size]
                (package / f"{n:06}.md").write_text(body)
            baseline = {
                p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                for p in package.iterdir()
            }
            changes = {5: 1, 6: 10, 7: 2500}.get(variant, 0)
            for n in range(changes):
                p = package / f"{n:06}.md"
                p.write_text(p.read_text().replace("Claim:", "Claim (changed):"))
            for flow in range(5):
                case = next(
                    c
                    for c in CASES
                    if c["id"] == f"DG-PERF-{flow * 12 + variant + 1:03}"
                )
                started = time.monotonic()
                read_bytes = 0
                reads = 0
                tools = 0
                paths = sorted(package.glob("*.md"))
                assert len(paths) == count
                if flow == 0:
                    # Actual bounded byte reading, no model knowledge write claimed.
                    checkpoint = 0
                    while checkpoint < count:
                        batch = paths[checkpoint : checkpoint + 100]
                        for p in batch:
                            data = p.read_bytes()
                            reads += 1
                            read_bytes += len(data)
                        checkpoint += len(batch)
                        if variant == 9 and checkpoint == 500:
                            (package.parent / "checkpoint.json").write_text(
                                json.dumps({"completed": checkpoint})
                            )
                            checkpoint = json.loads(
                                (package.parent / "checkpoint.json").read_text()
                            )["completed"]
                        if variant == 10:
                            time.sleep(0.001)
                    assert reads == count
                elif flow == 1:
                    if variant == 11:
                        miss = subprocess.run(
                            [
                                "rg",
                                "--files-with-matches",
                                "ABSENT_FIRST_QUERY",
                                str(package),
                            ],
                            capture_output=True,
                            check=False,
                        )
                        tools += 1
                        assert miss.returncode == 1
                    found = subprocess.run(
                        ["rg", "--files-with-matches", "FIND_ALPHA_RULE", str(package)],
                        capture_output=True,
                        text=True,
                        check=False,
                    )
                    tools += 1
                    assert found.returncode == 0
                    selected = [Path(p) for p in found.stdout.splitlines()]
                    assert len(selected) == 10
                    for p in selected:
                        body = p.read_text()
                        reads += 1
                        read_bytes += len(body.encode())
                        assert (
                            "approved only" in body
                            and "HOLD means never reserve" in body
                            and "source-v1#rule" in body
                        )
                else:
                    # Hash inventory comparison + changed-body processing. This is not a claim
                    # that a 100k publish completes without its mandatory final scanner/model gate.
                    current = {
                        p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                        for p in paths
                    }
                    read_bytes = sum(p.stat().st_size for p in paths)
                    reads = len(paths)
                    changed = [p for p in paths if baseline[p.name] != current[p.name]]
                    assert len(changed) == changes
                    for p in changed:
                        read_bytes += len(p.read_bytes())
                        reads += 1
                    if variant == 8:
                        for _ in range(2):
                            assert current == {
                                p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                for p in paths
                            }
                row = {
                    "id": case["id"],
                    "layer": "filesystem_recipe_microbenchmark",
                    "result": "PASS",
                    "case_complete": False,
                    "files": count,
                    "variant": variant,
                    "seconds": round(time.monotonic() - started, 4),
                    "bytes_read_instrumented": read_bytes,
                    "file_reads_instrumented": reads,
                    "external_tool_calls": tools,
                    "actual_model_calls": 0,
                    "end_to_end_seconds": None,
                    "remaining": "AI planning/curation/semantic merge, full final publish scanner and provider network timing are not measured by this recipe. No end-to-end speedup claim.",
                }
                rows.append(row)
                (OUT / "performance-components.json").write_text(
                    json.dumps(rows, indent=2) + "\n"
                )
                print(case["id"], row["seconds"], flush=True)
            for p in paths:
                p.unlink()
            package.rmdir()


if __name__ == "__main__":
    main()
