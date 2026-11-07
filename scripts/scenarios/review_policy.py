"""Re-evaluate product action decisions without confusing test-runner duties with product duties."""

import json
from concurrent.futures import ThreadPoolExecutor, as_completed

from model_json import OUT, invoke
from policy_decisions import CATALOG, REVIEW


def run(folder):
    label = "product-review-" + folder.name
    target = OUT / "model" / label
    if (target / "invocation.json").exists():
        return
    decisions = json.loads((folder / "answer.json").read_text())
    ids = {x["id"] for x in decisions["decisions"]}
    cases = [
        {k: c[k] for k in ["id", "title", "preconditions", "expected"]}
        for c in CATALOG
        if c["id"] in ids
    ]
    prompt = (
        """Evaluate the observed Dougle ACTION SELECTION against product behavior, not the test runner. The subject has no tools and must not claim execution, so plans rather than completed actions are correct. All services in the hypothetical plan are fakes. Logging fixture IDs, test versions and audit evidence is the test harness responsibility, not a product behavior requirement. Evaluate only expected product outcomes, not whether the assistant said it would run tests. Do not infer a missing feature from omitted low-level bootstrap details if the action invokes the supplied bootstrap that owns the gate. Do report concrete contradictions, wrong policy paths, fabricated certainty, lost context, or unsafe overwrite/post decisions. If provided facts do not support a next action/cause, do not require it to invent one. Do not claim execution, only evaluate plan consistency.\nCASES:\n"""
        + json.dumps(cases, ensure_ascii=False)
        + "\nOBSERVED DECISIONS:\n"
        + json.dumps(decisions, ensure_ascii=False)
    )
    result = invoke(label, prompt, REVIEW)
    print(
        label,
        [(x["id"], x["violations"]) for x in result["reviews"] if x["violations"]],
        flush=True,
    )


if __name__ == "__main__":
    with ThreadPoolExecutor(max_workers=3) as pool:
        fs = [
            pool.submit(run, p)
            for p in sorted((OUT / "model").glob("policy-*"))
            if (p / "answer.json").exists()
        ]
        for f in as_completed(fs):
            f.result()
