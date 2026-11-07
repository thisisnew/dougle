"""Actual-model merge procedure evaluation against independent frozen B/L/R oracles."""

import json
from concurrent.futures import ThreadPoolExecutor, as_completed

from model_json import OUT, ROOT, invoke

CASES = json.loads((ROOT / "docs/scenarios/catalog.json").read_text())["cases"]
# Ordered by catalog row. Expected results do not go to the model.
ROWS = [
    (None, None, None, "NO_CHANGE"),
    (None, None, "a", "ADD_LOCAL"),
    (None, "a", None, "KEEP_REMOTE"),
    (None, "a", "a", "SAME"),
    (None, "b", "a", "CONFLICT"),
    ("a", None, None, "RETAIN_BASE"),
    ("a", "a", None, "KEEP_REMOTE"),
    ("a", None, "a", "KEEP_LOCAL"),
    ("a", "a", "a", "SAME"),
    ("a", "b", "a", "REMOTE_CHANGE"),
    ("a", "a", "b", "LOCAL_CHANGE"),
    ("a", "c", "b", "CONFLICT"),
]
# Tuple fields deliberately explicit below: B, R, L; prevent reading expected decisions from prompt.
PROVENANCE = ["observed", "reported", "inferred"]
SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "results": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "id": {"type": "string"},
                    "decision": {"type": "string"},
                    "active_variants": {"type": "array", "items": {"type": "string"}},
                    "history_variants": {"type": "array", "items": {"type": "string"}},
                    "provenance": {"type": "string"},
                    "evidence_ids": {"type": "array", "items": {"type": "string"}},
                    "unresolved": {"type": "boolean"},
                },
                "required": [
                    "id",
                    "decision",
                    "active_variants",
                    "history_variants",
                    "provenance",
                    "evidence_ids",
                    "unresolved",
                ],
            },
        }
    },
    "required": ["results"],
}


def data(c, mode):
    n = int(c["id"].split("-")[-1]) - 1
    b, r, l, decision = ROWS[(n // 3) % 12]
    prov = PROVENANCE[n % 3]

    def claim(v):
        if v is None:
            return None
        return {
            "id": "alpha:S001:C001",
            "statement": {
                "a": "승인 후 31분에 안내합니다.",
                "b": "승인 후 47분에 안내합니다.",
                "c": "승인 후 59분에 안내합니다.",
            }[v],
            "scope": "Alpha A센터",
            "conditions": ["승인됨"],
            "exceptions": ["수신처 없으면 보내지 않음"],
            "provenance": prov,
            "variant": v,
            "evidence": [
                {
                    "id": "E-" + v,
                    "original_locator": "https://fixture.example.invalid/alpha/rule",
                    "observation": "2026-11-01T09:00:00+09:00",
                    "revision": v,
                }
            ],
        }

    if mode == "mirror":
        l, r = r, l
        decision = {
            "ADD_LOCAL": "KEEP_REMOTE",
            "KEEP_REMOTE": "ADD_LOCAL" if b is None else "KEEP_LOCAL",
            "KEEP_LOCAL": "KEEP_REMOTE",
            "LOCAL_CHANGE": "REMOTE_CHANGE",
            "REMOTE_CHANGE": "LOCAL_CHANGE",
        }.get(decision, decision)
    active = (
        sorted({v for v in (l, r) if v})
        if decision == "CONFLICT"
        else (
            []
            if decision == "NO_CHANGE"
            else [
                l
                if decision in ("ADD_LOCAL", "KEEP_LOCAL", "LOCAL_CHANGE")
                else r
                if decision in ("KEEP_REMOTE", "REMOTE_CHANGE")
                else b
                if decision == "RETAIN_BASE"
                else l
            ]
        )
    )
    history = [b] if b is not None and b not in active else []
    evidence = sorted({"E-" + v for v in (b, l, r) if v is not None})
    public = {
        "id": c["id"] + ":" + mode,
        "B": claim(b),
        "L": claim(l),
        "R": claim(r),
        "note": "Identity is verified; evidence IDs are immutable; variant field is an immutable original version marker, not an author role. Scope permits all these synthetic claims.",
    }
    gold = {
        "decision": decision,
        "active_variants": active,
        "history_variants": history,
        "provenance": prov,
        "evidence_ids": evidence,
        "unresolved": decision == "CONFLICT",
    }
    return public, gold


def batch(cases, index):
    inputs = []
    gold = {}
    for c in cases:
        for mode in ("initial", "repeat", "mirror"):
            public, expected = data(c, mode)
            inputs.append(public)
            gold[public["id"]] = expected
    prompt = (
        """Apply the supplied SHIPPED merge procedure to the independent synthetic B/L/R records. This is an algorithm-component evaluation, not a publish/fetch execution. No tools, no reading other context, no external services. Do not self-grade. Output the decision and exact immutable variant/evidence IDs retained. active_variants contains current candidate cores (both alternatives for unresolved conflict); history_variants contains original B if it is superseded/omitted and not already active. Preserve every input evidence ID, not new imported timestamps. When all inputs absent, emit provenance from case metadata below if specified; otherwise use observed. Use original class of present records. IDs after colon initial/repeat/mirror are independent tests. Return all results only in JSON.\nPOLICY:\n"""
        + (ROOT / "skills/dougle-publish/references/merge.md").read_text()
        + "\nINPUTS:\n"
        + json.dumps(inputs, ensure_ascii=False)
    )
    # For NO_CHANGE there is no actual provenance; oracle must not demand invented per-vector provenance.
    for inp in inputs:
        if not any(inp[k] for k in ("B", "L", "R")):
            gold[inp["id"]]["provenance"] = "observed"
    result = invoke(f"merge-{index:02}", prompt, SCHEMA)
    returned = {r["id"]: r for r in result["results"]}
    rows = []
    for ident, expected in gold.items():
        got = returned.get(ident, {})
        errors = []
        for k, v in expected.items():
            actual = got.get(k)
            if isinstance(v, list) and isinstance(actual, list):
                actual = sorted(actual)
            if actual != v:
                errors.append({"field": k, "expected": v, "actual": actual})
        rows.append(
            {
                "id": ident.split(":")[0],
                "variant": ident.split(":")[1],
                "layer": "actual_model_merge_procedure",
                "result": "FAIL" if errors else "PASS",
                "mismatches": errors,
                "case_complete": False,
                "remaining": "Publish/fetch host IO, final scanner, remote CAS and persistence are separate layers.",
            }
        )
    (OUT / "model" / f"merge-{index:02}" / "graded.json").write_text(
        json.dumps(rows, ensure_ascii=False, indent=2) + "\n"
    )
    print(
        json.dumps(
            {
                "batch": index,
                "checks": len(rows),
                "failed": sum(bool(r["mismatches"]) for r in rows),
            }
        ),
        flush=True,
    )


if __name__ == "__main__":
    selected = [c for c in CASES if c["area"] == "MERGE"]
    with ThreadPoolExecutor(max_workers=2) as pool:
        jobs = [
            pool.submit(batch, selected[i : i + 8], i // 8 + 1)
            for i in range(0, len(selected), 8)
        ]
        for f in as_completed(jobs):
            f.result()
