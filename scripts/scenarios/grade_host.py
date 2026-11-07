"""Deterministic checks of actual answers/tool receipts/stored state; semantic review separate."""

import json
import re
from pathlib import Path

from grade_connect import grade as connect_grade

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / ".tools/scenario-run-20261107"


def grade(folder):
    if not (folder / "invocation.json").exists():
        return []
    cs = json.loads((folder / "batch.json").read_text())
    states = json.loads((folder / "state.json").read_text())
    trace = [
        json.loads(x) for x in (folder / "tool-trace.jsonl").read_text().splitlines()
    ]
    rows = []
    for c in cs:
        ident = c["id"]
        s = states[ident]
        a = s.get("answer", "")
        events = [e for e in trace if e["args"].get("case_id") == ident]
        successful = [
            e
            for e in events
            if e["tool"] == "read_source" and "error" not in e["result"]
        ]
        files = s["files"]
        sources = "\n".join(v for p, v in files.items() if "/sources/" in p)
        topics = "\n".join(v for p, v in files.items() if "/knowledge/" in p)
        checks = {
            "answer_delivered": bool(a),
            "bounded_original_reads": s["reads"] <= 4,
            "writes_only_memory": all(
                e["args"]["path"].startswith("~/.dougle/")
                for e in events
                if e["tool"] == "memory_write"
            ),
        }
        if c["area"] in ("ASK", "FRESH"):
            checks["companion_loaded_this_case"] = any(
                e["tool"] == "load_skill"
                and e["args"].get("name") == "i-have-adhd"
                and "error" not in e["result"]
                for e in events
            )
            # Claims may cite a real file/system locator; a truthful refusal or user correction
            # contains no newly asserted domain claim requiring a manufactured source.
            no_fact = (s.get("variant") == "권한 없는 원본" and not successful) or (
                "정정" in c["title"] and "정정" in a
            )
            live_locator = (
                "SYNTHETIC-418" in a
                and "출처" in a
                and any(e["args"].get("section") == "live" for e in successful)
            )
            checks["actual_original_citation"] = (
                s["locator"] in a or live_locator or no_fact
            )
        if c["area"] == "ASK":
            denied = s["variant"] == "권한 없는 원본"
            if any(word in c["title"] for word in ["구현 질문", "장애 원인"]):
                checks["code_read_or_clear_gap"] = any(
                    e["args"].get("section") == "code" for e in successful
                ) or (denied and bool(re.search("권한|접근|못|미확인", a)))
            if "현재 상태" in c["title"]:
                checks["live_read_or_clear_gap"] = any(
                    e["args"].get("section") == "live" for e in successful
                ) or (denied and bool(re.search("권한|접근|못|미확인", a)))
            if "후속 질문" in c["title"]:
                checks["conversation_subject_retained"] = "flags.yml" in a or (
                    "권한" in a and "설정" in a
                )
            if s["variant"] == "다른 프로젝트의 유사 문서":
                checks["no_decoy_fact"] = "오후 11시" not in a and "관리콘솔 X" not in a
        if c["area"] == "FRESH":
            v = s["variant"]
            if v != "주기 미도달":
                checks["original_check_performed"] = any(
                    e["tool"] in ("check_source", "read_source") for e in events
                )
            if v in ("확인 실패", "저장 실패"):
                checks["verification_not_advanced"] = not re.search(
                    r"(?im)^\s*(last_verified_at|last successful verification)\s*:\s*2026-11-07T12",
                    sources,
                )
            if v == "저장 실패":
                checks["failed_topic_preserved"] = all(
                    files.get(p) == v0
                    for p, v0 in s["initial_files"].items()
                    if "/knowledge/" in p
                )
                checks["write_failure_not_hidden"] = bool(
                    re.search("저장.*(실패|못)|실패.*저장", a)
                )
            if v == "관련 부분 변경":
                checks["changed_claim_read_and_saved"] = (
                    bool(successful) and "오전 10시" in a and "오전 10시" in topics
                )
            if v == "현재값 질문":
                checks["actual_live_read"] = any(
                    e["args"].get("section") == "live" for e in successful
                )
            if v in ("변경 없음", "무관한 부분 변경", "주기 미도달"):
                checks["no_full_topic_rewrite"] = all(
                    files.get(p) == v0
                    for p, v0 in s["initial_files"].items()
                    if "/knowledge/" in p
                )
            if v == "부분 확인":
                checks["partial_gap_retained"] = bool(
                    re.search(
                        "미확인|미처리|pending|부분|미완료|못|unverified|not verified",
                        sources,
                        re.IGNORECASE,
                    )
                )
        if c["area"] == "INGEST":
            v = s["variant"]
            n = int(ident.split("-")[-1])
            checks["unrelated_topic_preserved"] = (
                files.get("~/.dougle/knowledge/OLD.md")
                == s["initial_files"]["~/.dougle/knowledge/OLD.md"]
            )
            checks["file_reader_used"] = s["reads"] > 0
            checks["original_path_preserved"] = s["locator"] in "\n".join(
                files.values()
            )
            if v in ("정상", "한글·공백 경로", "크기·컨텍스트 초과"):
                checks["condition_and_exception_retained"] = (
                    "승인" in topics and "보류" in topics
                )
            if v == "크기·컨텍스트 초과":
                checks["all_chunks_or_pending"] = "chunk-2" in s[
                    "source_reads"
                ] or bool(re.search("pending|미처리|부분", sources, re.IGNORECASE))
            if v in ("빈 내용", "읽기 거부"):
                checks["unread_claim_not_saved"] = f"R{n:03}" not in topics
            if v == "읽는 동안 변경":
                checks["inconsistent_version_disclosed"] = bool(
                    re.search(
                        "일관|변경|버전|미확인|unstable|inconsistent",
                        sources + a,
                        re.IGNORECASE,
                    )
                )
        bad = [k for k, v in checks.items() if not v]
        rows.append(
            {
                "id": ident,
                "layer": "actual_codex_fixture_host",
                "result": "FAIL" if bad else "PASS",
                "checks": checks,
                "failures": bad,
                "case_complete": False,
                "evidence": str(folder.relative_to(ROOT)),
                "remaining": "Synthetic host capability/answer checks only; provider-specific transport and native format extraction are not exercised.",
            }
        )
    return rows


def main():
    for area in ["ASK", "FRESH", "INGEST", "CONNECT"]:
        rows = {}
        for f in sorted((RUN / "host").glob(area.lower() + "-matrix-*")):
            for r in connect_grade(f) if area == "CONNECT" else grade(f):
                rows[r["id"]] = r
        extras = {"FRESH": "fresh-fixture-retest-*", "CONNECT": "connect-retest-*"}.get(
            area
        )
        if extras:
            for f in sorted((RUN / "host").glob(extras)):
                for r in connect_grade(f) if area == "CONNECT" else grade(f):
                    r["prior_result"] = rows.get(r["id"], {}).get("result")
                    rows[r["id"]] = r
        (RUN / (area.lower() + "-host-results.json")).write_text(
            json.dumps(list(rows.values()), ensure_ascii=False, indent=2) + "\n"
        )
        print(
            area,
            len(rows),
            [(r["id"], r["failures"]) for r in rows.values() if r["result"] == "FAIL"],
        )


if __name__ == "__main__":
    main()
