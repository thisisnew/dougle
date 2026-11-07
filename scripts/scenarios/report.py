"""Publish bounded, sanitized evidence; never export raw model event/reasoning logs."""

import json
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / ".tools/scenario-run-20261107"
DEST = ROOT / "docs/scenarios/execution-20261107"


def read(p):
    return json.loads(p.read_text())


def main():
    DEST.mkdir(exist_ok=True)
    cases = read(ROOT / "docs/scenarios/catalog.json")["cases"]
    records = {c["id"]: [] for c in cases}
    for name in [
        "connect-host-results",
        "ask-host-results",
        "fresh-host-results",
        "ingest-host-results",
        "runtime-mapping-results",
        "scanner-results",
        "sensitive-semantic-results",
        "performance-components",
        "vision-15-result",
        "vision-16-result",
    ]:
        rows = read(RUN / (name + ".json"))
        rows = rows if isinstance(rows, list) else [rows]
        for row in rows:
            records[row["id"]].append(row)
    for p in sorted((RUN / "model").glob("merge-*/graded.json")):
        for row in read(p):
            records[row["id"]].append(row)
    for p in sorted((RUN / "model").glob("policy-*/answer.json")):
        for decision in read(p)["decisions"]:
            records[decision["id"]].append(
                {
                    "id": decision["id"],
                    "layer": "actual_model_action_selection_only",
                    "result": "OBSERVED",
                    "case_complete": False,
                    "decision": decision,
                    "run": p.parent.name,
                    "remaining": "No tool execution, state persistence or end-to-end behavior established by this decision.",
                }
            )
    tests = list(ET.parse(RUN / "runtime-complete.xml").iter("testcase"))
    assert len(tests) == 597 and all(not list(t) for t in tests)
    for c in cases:
        if c["area"] in ("INTAKE", "EVIDENCE"):
            matched = [t for t in tests if c["id"] in t.attrib["name"]]
            assert matched, c["id"]
            records[c["id"]].append(
                {
                    "id": c["id"],
                    "layer": "production_runtime_fake_services",
                    "result": "PASS",
                    "case_complete": False,
                    "tests": [t.attrib["name"] for t in matched],
                    "remaining": "Synthetic runtime contract assertions; no real service or model semantics.",
                }
            )
    assert all(records.values()), [k for k, v in records.items() if not v]
    calls = [
        read(p)
        for parent in ["host", "model"]
        for p in (RUN / parent).glob("*/invocation.json")
    ]
    ledger = [
        json.loads(x) for x in (RUN / "model-calls.jsonl").read_text().splitlines()
    ]
    summary = {
        "date": "2026-11-07",
        "plugin": "0.8.12",
        "bot": "0.1.9",
        "catalog_cases": len(cases),
        "full_end_to_end_pass": 0,
        "overall_status": "PARTIAL_WITH_EXPLICIT_LIMITS",
        "bot_pytest_pass": 597,
        "model_invocations_recorded": len(ledger),
        "completed_invocation_metadata": len(calls),
        "cost_usd": None,
        "model": "CLI default; exact model not recorded",
        "claude_native": "BLOCKED_EXPIRED_AUTH",
        "company_services": "NOT_RUN",
        "by_area": dict(Counter(c["area"] for c in cases)),
        "raw_model_events_published": False,
    }
    (DEST / "results.json").write_text(
        json.dumps(
            {
                "summary": summary,
                "cases": [
                    {
                        "id": c["id"],
                        "area": c["area"],
                        "status": "PARTIAL",
                        "case_complete": False,
                        "observations": records[c["id"]],
                    }
                    for c in cases
                ],
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n"
    )
    (DEST / "model-calls.json").write_text(
        json.dumps(
            {"started": ledger, "completed": calls, "cost_usd": None},
            ensure_ascii=False,
            indent=2,
        )
        + "\n"
    )
    for name in [
        "scanner-paired",
        "scanner-fast-scale",
        "scanner-baseline-interrupted",
        "scanner-differential",
        "scanner-fast-regression",
        "scanner-legacy-after",
        "scanner-stress",
        "large-package",
        "sharing",
    ]:
        p = RUN / (name + ".json")
        if p.exists():
            (DEST / p.name).write_text(p.read_text())
    (DEST / "runtime-tests.json").write_text(
        json.dumps(
            [
                {
                    "name": t.attrib["name"],
                    "module": t.attrib["classname"],
                    "seconds": t.attrib["time"],
                    "result": "PASS",
                }
                for t in tests
            ],
            indent=2,
        )
        + "\n"
    )
    print(json.dumps(summary))


if __name__ == "__main__":
    main()
