"""Compare synthetic Codex bot-policy runs without applying host citation rules.

This checks execution and reported tokens only. Semantic answer review remains a
separate gate; a keyword or receipt count cannot certify factual accuracy.
"""

import argparse
import hashlib
import json
import re
from collections import Counter
from pathlib import Path


def synthetic_fact_checks(ident, body):
    """Small fixture-specific regression signals, never a semantic proof."""
    if ident in {"DG-ASK-017", "DG-ASK-020"}:
        return {
            "mail_next_day_09": "다음 날" in body and "9시" in body,
            "rejection_immediate": "반려" in body and "직후" in body,
            "no_recipient_no_mail": "이메일" in body
            and any(word in body for word in ("없", "비어", "미등록", "등록되어 있지"))
            and any(word in body for word in ("발송되지", "발송하지", "보내지")),
            "no_old_08_rule": "8시" not in body,
        }
    if ident == "DG-ASK-049":
        return {
            "food_exception": "식품" in body and "품질팀" in body,
            "hazard_exception": "위험물" in body and "안전팀" in body,
            "hold_exception": "보류" in body and "해제" in body,
            "completed_duplicate": "완료" in body and "중복" in body,
            "cancel_waiting_only": "신청대기" in body and "취소" in body,
        }
    if ident == "DG-ASK-081":
        return {
            "paused": "PAUSED" in body,
            "handoff_incomplete": "인계" in body and "완료되지" in body,
            "observation_time": "11월 7일" in body and "12시" in body,
        }
    if ident == "DG-ASK-121":
        return {
            "application_path": "회송" in body and "공급사 포털" in body and "회수 신청" in body,
            "completion_boundary": bool(re.search(r"실물.{0,5}인계", body))
            and "운영팀" in body and "완료" in body,
            "not_application_only": "신청만으로" in body or "신청만" in body,
            "no_fixed_duration": "고정" in body and ("소요" in body or "기간" in body),
        }
    return {}


def inspect(folder):
    folder = Path(folder)
    invocation = json.loads((folder / "invocation.json").read_text())
    cases = json.loads((folder / "batch.json").read_text())
    states = json.loads((folder / "state.json").read_text())
    trace = [json.loads(line) for line in (folder / "tool-trace.jsonl").read_text().splitlines()]
    usages = [
        event["usage"]
        for line in (folder / "events.jsonl").read_text().splitlines()
        if (event := json.loads(line)).get("type") == "turn.completed"
        and isinstance(event.get("usage"), dict)
    ]
    usage_valid = bool(usages) and all(
        all(type(item.get(key)) is int and item[key] >= 0 for key in (
            "input_tokens", "output_tokens", "cached_input_tokens"
        )) and item["cached_input_tokens"] <= item["input_tokens"]
        for item in usages
    )
    input_tokens = sum(item["input_tokens"] for item in usages) if usage_valid else None
    output_tokens = sum(item["output_tokens"] for item in usages) if usage_valid else None
    cached_tokens = sum(item["cached_input_tokens"] for item in usages) if usage_valid else None
    rows = []
    for case in cases:
        ident = case["id"]
        observed = [item for item in trace if item["args"].get("case_id") == ident]
        successful = [
            item for item in observed
            if item["tool"] == "read_source"
            and isinstance(item.get("result"), dict)
            and "error" not in item["result"]
            and isinstance(item["result"].get("content"), str)
            and bool(item["result"]["content"])
            and item["result"].get("identity") == states[ident].get("identity")
            and item["result"].get("locator") == states[ident].get("locator")
            and item["result"].get("section") == item["args"].get("section", "doc")
            and (
                "revision" not in item["args"]
                or item["result"].get("revision") == item["args"]["revision"]
            )
            and isinstance(item["result"].get("coverage"), dict)
            and item["result"].get("coverage", {}).get("pending") == []
        ]
        needs_code = "구현 질문" in case["title"] or "장애 원인" in case["title"]
        needs_live = "현재 상태" in case["title"]
        execution_checks = {
            "process_succeeded": invocation.get("exit") == 0,
            "usage_reported": usage_valid,
            "answered": bool(states[ident].get("answer")),
            "original_read": bool(successful),
            "code_read": not needs_code or any(
                item["args"].get("section") == "code" for item in successful
            ),
            "live_read": not needs_live or any(
                item["args"].get("section") == "live" for item in successful
            ),
        }
        keyword_signals = {
            "no_decoy": "오후 11시" not in states[ident].get("answer", "")
            and "관리콘솔 X" not in states[ident].get("answer", ""),
        }
        keyword_signals.update(synthetic_fact_checks(ident, states[ident].get("answer", "")))
        loaded = {
            item["args"].get("name")
            for item in observed if item["tool"] == "load_skill"
            and isinstance(item.get("result"), dict)
            and "error" not in item["result"]
        }
        if invocation.get("policy") == "bot-baseline":
            execution_checks["required_skills_loaded"] = {
                "dougle-ask", "i-have-adhd", "plain-language"
            } <= loaded
        elif invocation.get("policy") in ("compact", "compact-prefetch"):
            execution_checks["no_skill_load"] = not loaded
        rows.append({
            "id": ident,
            "execution_checks": execution_checks,
            "execution_checks_passed": all(execution_checks.values()),
            "keyword_signals_not_semantic_proof": keyword_signals,
        })
    return {
        "folder": str(folder),
        "policy": invocation.get("policy"),
        "cases": rows,
        "tool_counts": dict(Counter(item["tool"] for item in trace)),
        "usage": {
            "input_tokens": input_tokens,
            "cached_input_tokens_included_in_input": cached_tokens,
            "output_tokens": output_tokens,
            "total_input_plus_output": input_tokens + output_tokens if usage_valid else None,
        },
        "semantic_accuracy_reviewed": False,
    }


def compare(left, right):
    """Pair only identical synthetic questions and initial fixture snapshots."""
    left, right = Path(left), Path(right)
    l_state = json.loads((left / "state.json").read_text())
    r_state = json.loads((right / "state.json").read_text())
    shared = sorted(set(l_state) & set(r_state))
    pairs = []
    for ident in shared:
        keys = (
            "user_request", "conversation_context", "initial_files",
            "identity", "locator", "fact", "code",
        )
        l_fixture = {key: l_state[ident].get(key) for key in keys}
        r_fixture = {key: r_state[ident].get(key) for key in keys}
        same_fixture = l_fixture == r_fixture
        left_facts = synthetic_fact_checks(ident, l_state[ident].get("answer", ""))
        right_facts = synthetic_fact_checks(ident, r_state[ident].get("answer", ""))
        pairs.append({
            "id": ident,
            "same_fixture": same_fixture,
            "fixture_digest": hashlib.sha256(
                json.dumps(l_fixture, sort_keys=True, ensure_ascii=False).encode()
            ).hexdigest() if same_fixture else None,
            "same_answer_bytes": same_fixture
            and bool(l_state[ident].get("answer"))
            and l_state[ident].get("answer") == r_state[ident].get("answer"),
            "both_pass_keyword_signals_not_semantic_proof": (
                same_fixture and bool(left_facts)
                and all(left_facts.values()) and all(right_facts.values())
            ) if left_facts else None,
        })
    return pairs


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("folders", nargs="+", type=Path)
    parser.add_argument("--compare", action="store_true")
    args = parser.parse_args()
    reports = [inspect(folder) for folder in args.folders]
    result = {"runs": reports}
    if args.compare:
        if len(args.folders) != 2:
            parser.error("--compare requires exactly two folders")
        result["pairs"] = compare(*args.folders)
    print(json.dumps(result, ensure_ascii=False, indent=2))
