"""Developer stress checks of the actual scanner; synthetic files only, no AI.

Large cases run on demand, not on every user's publish or CI build.
"""

import argparse
import hashlib
import json
import os
import signal
import subprocess
import tempfile
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--files", type=int, default=1000)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.files < 3:
        parser.error("at least three files required")
    scanner = (
        Path(__file__).resolve().parents[1]
        / "skills/dougle-publish/scripts/check-sensitive.sh"
    )
    rows = []
    report = {
        "synthetic": True,
        "hostModelCalls": 0,
        "scannerSha256": hashlib.sha256(scanner.read_bytes()).hexdigest(),
        "runs": rows,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)

    def record(row):
        rows.append(row)
        args.output.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(row), flush=True)

    def scan(package, name, expected, files, env=None):
        start = time.monotonic()
        result = subprocess.run(
            ["/bin/sh", str(scanner), str(package)],
            capture_output=True,
            text=True,
            env=env,
            check=False,
            timeout=1200,
        )
        data = json.loads(result.stdout)
        assert result.returncode == expected and not result.stderr
        assert data["status"] == ("PASS" if expected == 0 else "BLOCK")
        assert data["filesChecked"] == files
        assert "SYNTHETIC_SENSITIVE_SENTINEL" not in result.stdout
        record(
            {
                "name": name,
                "status": data["status"],
                "files": files,
                "seconds": round(time.monotonic() - start, 3),
                "digest": data["packageDigest"],
            }
        )
        return data

    with tempfile.TemporaryDirectory(prefix="dougle-scanner-stress-") as tmp:
        root = Path(tmp)
        package = root / "package"
        package.mkdir()
        for i in range(args.files):
            # Many small notes, substantial notes and a few long original excerpts.
            size = 1024 * 1024 if i < 3 else 64 * 1024 if i % 25 == 0 else 4096
            text = (
                "Domain condition and exception; original evidence.\n"
                * (size // 49 + 2)
            )[:size]
            (package / f"claim-{i:06d}.md").write_text(text)
        original = {
            p.name: hashlib.sha256(p.read_bytes()).hexdigest()
            for p in package.iterdir()
        }
        report["packageBytes"] = sum(p.stat().st_size for p in package.iterdir())
        first = scan(package, "mixed_sizes_full_scan", 0, args.files)
        target = package / f"claim-{args.files - 1:06d}.md"
        clean = target.read_bytes()
        target.write_bytes(clean + b"\npassword: SYNTHETIC_SENSITIVE_SENTINEL\n")
        blocked = scan(package, "one_modified_file_blocks_entire_rescan", 1, args.files)
        assert blocked["packageDigest"] != first["packageDigest"]
        target.write_bytes(clean)
        repeated = scan(package, "unchanged_repeat_is_stable", 0, args.files)
        assert repeated["packageDigest"] == first["packageDigest"]
        assert original == {
            p.name: hashlib.sha256(p.read_bytes()).hexdigest()
            for p in package.iterdir()
        }
        # Deterministic interruption; signal only the isolated scanner process group.
        tiny = root / "tiny"
        tiny.mkdir()
        (tiny / "claim.md").write_text("Ordinary condition.\n")
        tools = root / "bin"
        tools.mkdir()
        marker = root / "ready"
        wrapper = tools / "awk"
        wrapper.write_text(
            '#!/bin/sh\nprintf ready > "$DOUGLE_TEST_MARKER"\nsleep 10\nexec /usr/bin/awk "$@"\n'
        )
        wrapper.chmod(0o755)
        process = subprocess.Popen(
            ["/bin/sh", str(scanner), str(tiny)],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=True,
            env={
                **os.environ,
                "PATH": str(tools) + ":" + os.environ["PATH"],
                "DOUGLE_TEST_MARKER": str(marker),
            },
        )
        try:
            limit = time.monotonic() + 5
            while (
                not marker.exists()
                and process.poll() is None
                and time.monotonic() < limit
            ):
                time.sleep(0.01)
            assert marker.exists()
            started = time.monotonic()
            os.killpg(process.pid, signal.SIGTERM)
            stdout, _ = process.communicate(timeout=5)
            assert process.returncode != 0 and b'"status":"PASS"' not in stdout
            record(
                {
                    "name": "interruption_never_emits_pass",
                    "status": "PASS",
                    "seconds": round(time.monotonic() - started, 3),
                }
            )
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)
        scan(tiny, "fresh_scan_after_interruption", 0, 1)


if __name__ == "__main__":
    main()
