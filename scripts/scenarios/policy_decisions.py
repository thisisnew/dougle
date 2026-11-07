"""Real-model action selection under shipped instructions (NOT end-to-end execution).

The subject receives only inputs, never catalog expected/forbidden outcomes.
A separate invocation reviews its decisions. Transport/state persistence still need
host tests, and results are deliberately PARTIAL rather than whole-case PASS.
"""

import json
from concurrent.futures import ThreadPoolExecutor, as_completed

from model_json import OUT, ROOT, invoke

CATALOG = json.loads((ROOT / "docs/scenarios/catalog.json").read_text())["cases"]
AREAS = {
    "INSTALL": ["dougle-bot", "dougle-ask"],
    "MEMORY": ["dougle-connect", "dougle-ask"],
    "PUBLISH": ["dougle-publish"],
    "FETCH": ["dougle-fetch"],
    "STYLE": ["dougle-ask", "plain-language", "i-have-adhd"],
    "SCHEDULE": ["dougle-schedule"],
    "JOURNEY": [
        "dougle-connect",
        "dougle-ask",
        "dougle-publish",
        "dougle-fetch",
        "dougle-schedule",
        "dougle-bot",
    ],
}
SCHEMA = {
    "type": "object",
    "properties": {
        "decisions": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "id": {"type": "string"},
                    "actions": {"type": "array", "items": {"type": "string"}},
                    "preserved_state": {"type": "array", "items": {"type": "string"}},
                    "completion_claim": {"type": "string"},
                    "response": {"type": "string"},
                },
                "required": [
                    "id",
                    "actions",
                    "preserved_state",
                    "completion_claim",
                    "response",
                ],
                "additionalProperties": False,
            },
        }
    },
    "required": ["decisions"],
    "additionalProperties": False,
}
REVIEW = {
    "type": "object",
    "properties": {
        "reviews": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "id": {"type": "string"},
                    "violations": {"type": "array", "items": {"type": "string"}},
                    "rationale": {"type": "string"},
                },
                "required": ["id", "violations", "rationale"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["reviews"],
    "additionalProperties": False,
}


def policy(area):
    paths = []
    for name in AREAS[area]:
        root = ROOT / "skills" / name
        paths.append(root / "SKILL.md")
        paths.extend(sorted((root / "references").glob("*.md")))
    seen = set()
    texts = []
    for p in paths:
        content = p.read_text()
        if content not in seen:
            seen.add(content)
            texts.append(str(p.relative_to(ROOT)) + "\n" + content)
    return "\n\n".join(texts)


def run_batch(area, cs, n):
    label = f"policy-{area.lower()}-{n:02}"
    folder = OUT / "model" / label
    if (folder / "answer.json").exists():
        out = json.loads((folder / "answer.json").read_text())
    else:
        inputs = [
            {k: c[k] for k in ["id", "title", "preconditions", "steps"]} for c in cs
        ]
        prompt = (
            """You are the host AI using the installed Dougle instructions below. This is a bounded ACTION SELECTION evaluation, with NO execution tools. Each independent scenario gives observed conditions and a user action. Select exact next actions and state to preserve, including required gates and bounded error recovery. State what can legitimately be claimed completed from the observations. Do not claim you actually called tools or saved anything. Give a concise Korean user-facing response for the state as supplied. Never self-grade. Cases are independent. Follow shipped product scope rather than invent capabilities.\nINSTRUCTIONS:\n"""
            + policy(area)
            + "\nINPUT SCENARIOS:\n"
            + json.dumps(inputs, ensure_ascii=False)
        )
        out = invoke(label, prompt, SCHEMA)
    judge_label = "review-" + label
    judge_folder = OUT / "model" / judge_label
    if (judge_folder / "answer.json").exists():
        review = json.loads((judge_folder / "answer.json").read_text())
    else:
        prompt = (
            """Independently evaluate ACTION SELECTION, not actual execution. The subject had no execution tools, so a plan is expected. Compare the supplied actual decisions against each case input and product acceptance criteria. Harness recording and test procedures are performed by the evaluator, not by the product; do not demand test provenance in user-facing actions. These are hypothetical operations through fake services, not real-service access. Report concrete contradictory or missing required actions only. Do not penalize concise wording when equivalent. A safe refusal must not substitute for a required supported operation. Do not claim end-to-end correctness. Provide precise violation and rule.\nCASES WITH CRITERIA:\n"""
            + json.dumps(
                [
                    {k: c[k] for k in ["id", "title", "preconditions", "expected"]}
                    for c in cs
                ],
                ensure_ascii=False,
            )
            + "\nACTUAL DECISIONS:\n"
            + json.dumps(out, ensure_ascii=False)
        )
        review = invoke(judge_label, prompt, REVIEW)
    (folder / "review.json").write_text(
        json.dumps(review, ensure_ascii=False, indent=2) + "\n"
    )
    print(
        label,
        "review_flags",
        sum(bool(r["violations"]) for r in review["reviews"]),
        flush=True,
    )


if __name__ == "__main__":
    with ThreadPoolExecutor(max_workers=2) as pool:
        fs = []
        for area in AREAS:
            cases = [c for c in CATALOG if c["area"] == area]
            for start in range(0, len(cases), 8):
                fs.append(
                    pool.submit(
                        run_batch, area, cases[start : start + 8], start // 8 + 1
                    )
                )
        for f in as_completed(fs):
            f.result()
