"""Actual receipt parsing and conversion with synthetic provider responses."""

import copy
import json

import pytest

from dougle_bot.evidence import ReadReceipt, read_receipts, validate_verification
from dougle_bot.freshness import convert_unverified_live


def observed(body):
    events = [
        {"type": "assistant", "message": {"content": [{
            "type": "tool_use", "id": "query", "name": "mcp__bigquery__execute_sql_readonly",
            "input": {"query": "SELECT COUNT(*) FROM synthetic.replica.centers LIMIT 1"},
        }]}},
        {"type": "user", "message": {"content": [{
            "type": "tool_result", "tool_use_id": "query", "content": json.dumps(body),
        }]}},
    ]
    return read_receipts("\n".join(json.dumps(event) for event in events))


def answer():
    return {
        "status": "answered", "body": "현재 운영 센터는 17개입니다.", "lookup": None,
        "sources": [{"title": "센터 조회", "location": "synthetic.replica.centers"}],
        "verification": {
            "question_kind": "current_state", "required": ["live", "knowledge"],
            "missing": [], "checks": [
                {"kind": kind, "receipt": "query", "source_index": 0}
                for kind in ("live", "knowledge")
            ],
        },
    }


@pytest.mark.parametrize("metadata", [
    {}, {"data_watermark": "2026-10-29"},
    {"query_time": "2026-11-08", "updated_at": "2026-11-08"},
    {"data_watermark": "2026-11-08", "environment": "production", "verified": True},
])
def test_actual_query_routes_without_trusting_result_fields(metadata):
    reads = observed({"rows": [{"count": 17, "secret_row": "do-not-copy"}], **metadata})
    original = answer()
    before = copy.deepcopy(original)
    validate_verification(original, reads)
    result = convert_unverified_live(original, reads)
    validate_verification(result, reads)
    assert result["status"] == "needs_lookup"
    assert result["verification"]["missing"] == ["live"]
    assert [c["kind"] for c in result["verification"]["checks"]] == ["knowledge"]
    assert result["sources"] == original["sources"]
    assert result["lookup"]["target"] == "synthetic.replica.centers"
    assert result["lookup"]["sql"] == ""
    assert "17" not in result["body"]
    assert "do-not-copy" not in json.dumps(result)
    assert original == before
    assert convert_unverified_live(result, reads) is result


def test_stable_knowledge_from_bigquery_is_unchanged():
    value = answer()
    value["verification"].update(question_kind="domain", required=["knowledge"])
    value["verification"]["checks"] = value["verification"]["checks"][1:]
    reads = observed({"rows": []})
    validate_verification(value, reads)
    assert convert_unverified_live(value, reads) is value


@pytest.mark.parametrize("report", [True, {"evidence_kinds": ["live"]}])
def test_validated_manual_live_report_is_unchanged(report):
    value = answer()
    value["verification"]["checks"][0]["receipt"] = "developer_report"
    reads = observed({"rows": []})
    validate_verification(value, reads, report=report)
    assert convert_unverified_live(value, reads, report) is value


def test_report_presence_alone_does_not_bypass_gate():
    value = answer()
    reads = observed({"rows": []})
    assert convert_unverified_live(value, reads, {"evidence_kinds": ["live"]})[
        "status"
    ] == "needs_lookup"


def test_manual_live_check_cannot_attest_another_sql_live_check():
    value = answer()
    value["sources"].append({"title": "운영 원본 수동 확인", "location": "demo.centers"})
    value["verification"]["checks"].append({
        "kind": "live", "receipt": "developer_report", "source_index": 1,
    })
    reads = observed({"rows": [{"count": 17}]})
    report = {"evidence_kinds": ["live"], "summary": "센터는 19개입니다."}
    validate_verification(value, reads, report=report)
    converted = convert_unverified_live(value, reads, report)
    validate_verification(converted, reads, report=report)
    assert converted["status"] == "needs_lookup"
    assert converted["verification"]["missing"] == ["live"]
    assert [c["kind"] for c in converted["verification"]["checks"]] == ["knowledge"]
    assert converted["sources"] == value["sources"]


def test_mixed_code_is_preserved():
    value = answer()
    reads = observed({"rows": []})
    reads["code"] = ReadReceipt({"code"})
    value["verification"]["required"].append("code")
    value["verification"]["checks"].append({
        "kind": "code", "receipt": "code", "source_index": 0,
    })
    validate_verification(value, reads)
    result = convert_unverified_live(value, reads)
    validate_verification(result, reads)
    assert [c["kind"] for c in result["verification"]["checks"]] == ["knowledge", "code"]


@pytest.mark.parametrize("location", [
    "https://example.test/?token=secret", "SELECT secret FROM table", "raw\nrow", "x" * 500,
])
def test_handoff_does_not_copy_unbounded_or_sensitive_locator(location):
    value = answer()
    value["sources"][0]["location"] = location
    result = convert_unverified_live(value, observed({"rows": []}))
    assert location not in result["lookup"]["target"]
    assert location not in result["lookup"]["reason"]


def test_plain_set_fake_engine_compatibility():
    value = answer()
    assert convert_unverified_live(value, {"query": {"knowledge", "live"}}) is value


@pytest.mark.parametrize("status", ["needs_lookup", "cannot_answer"])
def test_existing_unresolved_output_unchanged(status):
    value = answer()
    value["status"] = status
    assert convert_unverified_live(value, observed({"rows": []})) is value
