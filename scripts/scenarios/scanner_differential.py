"""Compare original scanner and batched scanner on reproducible adversarial bytes."""

import hashlib
import json
import os
import random
import subprocess
import tempfile
from pathlib import Path

from local_checks import PAYLOADS

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / ".tools/scenario-run-20261107"
old = OUT / "check-sensitive-baseline.sh"
new = ROOT / "skills/dougle-publish/scripts/check-sensitive.sh"
rng = random.Random(71107)
rows = []
with tempfile.TemporaryDirectory(prefix="dougle-scanner-diff-") as tmp:
    stage = Path(tmp)
    fixtures = [
        b"",
        b"\xff",
        b"\xc0\x80",
        b"abc\0def",
        b"\xed\xa0\x80",
        b"\xf4\x90\x80\x80",
        b"\xef\xbb\xbfOrdinary\n",
    ]
    for payload in PAYLOADS.values():
        for wrap in [
            "{}",
            '"{}"',
            "```text\n{}\n```",
            "안내\r\n{}\r\n",
            "    {}\n",
            "prefix {} suffix",
        ]:
            fixtures.append(wrap.format(payload).encode())
    fixtures.extend(
        [
            (
                "safe\n" * rng.randrange(1, 150) + rng.choice(list(PAYLOADS.values()))
            ).encode()
            for _ in range(73)
        ]
    )
    for n, data in enumerate(fixtures):
        file = stage / "claim.md"
        file.write_bytes(data)
        results = []
        for script in (old, new):
            p = subprocess.run(
                ["/bin/sh", str(script), str(stage)],
                capture_output=True,
                text=True,
                check=False,
                timeout=30,
                env={**os.environ, "DOUGLE_SCAN_ENGINE": "fast"},
            )
            obj = json.loads(p.stdout)
            assert not p.stderr
            results.append((p.returncode, obj))
        left, right = results
        comparable = lambda r: (
            r[0],
            r[1]["status"],
            r[1].get("filesChecked"),
            r[1].get("packageDigest"),
            r[1].get("findingCount"),
            sorted((f["line"], f["rule"]) for f in r[1].get("findings", [])),
        )
        same = comparable(left) == comparable(right)
        try:
            data.decode("utf-8")
            invalid = False
        except UnicodeDecodeError:
            invalid = True
        hardened = invalid and left[0] == 0 and right[0] == 2
        rows.append(
            {
                "acceptable": same or hardened,
                "utf8_hardening": hardened,
                "fixture": n,
                "sha256": hashlib.sha256(data).hexdigest(),
                "same": same,
                "baseline_status": left[1]["status"],
                "batched_status": right[1]["status"],
            }
        )
        if not same:
            print("MISMATCH", n, comparable(left), comparable(right), flush=True)
(OUT / "scanner-differential.json").write_text(
    json.dumps(
        {
            "fixtures": len(rows),
            "mismatches": sum(not r["same"] for r in rows),
            "rows": rows,
        },
        indent=2,
    )
    + "\n"
)
assert all(r["acceptable"] for r in rows)
print("matched", len(rows))
