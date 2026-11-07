"""Independent semantic review of actual host outputs. Not a substitute for trace assertions."""

import json
from concurrent.futures import ThreadPoolExecutor, as_completed

from ask_fixtures import CODE, EXCEPTION, FACTS
from model_json import OUT, invoke

SCHEMA = {
    "type": "object",
    "properties": {
        "reviews": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "id": {"type": "string"},
                    "violations": {"type": "array", "items": {"type": "string"}},
                    "explanation": {"type": "string"},
                },
                "required": ["id", "violations", "explanation"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["reviews"],
    "additionalProperties": False,
}


def review(folder):
    label = "judge-" + folder.name
    target = OUT / "model" / label
    if (target / "invocation.json").exists():
        return
    states = json.loads((folder / "state.json").read_text())
    observations = []
    for ident, s in states.items():
        observations.append(
            {
                "id": ident,
                "question": s["user_request"],
                "context": s["conversation_context"],
                "fixture_variant": s["variant"],
                "answer": s.get("answer", ""),
                "actual_source_reads": s["source_reads"],
                "fact_world": s["fact"],
            }
        )
    prompt = (
        """You are an independent evaluator of completed synthetic workplace answers, not the assistant that wrote them. Grade semantics only against the supplied fictional ground truth and exact question. Do not produce a better answer. Find material wrong facts, omitted conditions essential to answering this question, invented operating values, wrong-project contamination, unsupported certainty after permission denial, ignored applicable authoritative conflict, or an answer that does not answer the actual question. Do NOT demand every fact, exception or deadline from the corpus if unrelated. Stored evidence may be cited as previously observed after read failure; it must not be presented as newly verified. No need for live reads for purely static questions. Do NOT reward length. This is ordinary ask, so original source citations are required. Return violations only when you can name the exact wrong/omitted assertion and corresponding ground truth. An independent checker reviews your flags; do not self-label execution passed.
Additional original code:\n"""
        + CODE
        + "\nBase facts:\n"
        + FACTS
        + "\n"
        + EXCEPTION
        + "\nOBSERVED REQUESTS AND ANSWERS:\n"
        + json.dumps(observations, ensure_ascii=False)
    )
    result = invoke(label, prompt, SCHEMA)
    (target / "reviews.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    )
    print(label, sum(bool(x["violations"]) for x in result["reviews"]), flush=True)


if __name__ == "__main__":
    with ThreadPoolExecutor(max_workers=2) as pool:
        tasks = [
            pool.submit(review, f) for f in sorted((OUT / "host").glob("ask-matrix-*"))
        ]
        for f in as_completed(tasks):
            f.result()
