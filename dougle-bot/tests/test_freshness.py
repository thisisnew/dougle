"""Only validated query plans may auto answer; unknown SQL results are held."""

import copy
import json

import pytest

from dougle_bot.evidence import ReadReceipt, read_receipts, validate_verification
from dougle_bot.freshness import convert_unverified_live


def observed(body, *, error=False):
    events = [
        {"type": "assistant", "message": {"content": [{
            "type": "tool_use", "id": "query",
            "name": "mcp__claude_ai_Google_Cloud_BigQuery__execute_sql_readonly",
            "input": {"query": "SELECT COUNT(*) FROM synthetic.replica.centers LIMIT 1"},
        }]}},
        {"type": "user", "message": {"content": [{
            "type": "tool_result", "tool_use_id": "query",
            "content": json.dumps(body), "is_error": error,
        }]}},
    ]
    return read_receipts("\n".join(json.dumps(event) for event in events))


def observed_query(sql, body):
    events = [
        {"type": "assistant", "message": {"content": [{
            "type": "tool_use", "id": "query",
            "name": "mcp__claude_ai_Google_Cloud_BigQuery__execute_sql_readonly",
            "input": {"query": sql},
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


@pytest.mark.parametrize("untrusted", [
    {}, {"data_watermark": "2026-10-29"},
    {"query_time": "2026-11-08", "updated_at": "2026-11-08"},
    {"data_watermark": "2026-11-08", "environment": "production", "verified": True},
])
def test_untrusted_result_metadata_never_promotes_query(untrusted):
    reads = observed({"rows": [{"count": 17, "secret_row": "do-not-copy"}], **untrusted})
    original = answer()
    before = copy.deepcopy(original)
    validate_verification(original, reads)
    result = convert_unverified_live(original, reads)
    validate_verification(result, reads)
    assert result["status"] == "needs_lookup"
    assert "do-not-copy" not in json.dumps(result)
    assert original == before
    assert convert_unverified_live(result, reads) is result


def test_unqualified_empty_rows_are_held():
    reads = observed({"rows": []})
    assert reads["query"].metadata["result_rows"] == 0
    result = convert_unverified_live(answer(), reads)
    validate_verification(result, reads)
    assert result["status"] == "needs_lookup"
    assert "결과가 없습니다" not in result["body"]
    assert "운영 DB에 데이터가 없습니다" not in result["body"]
    assert result["lookup"] is not None


def test_unqualified_zero_aggregate_is_held():
    reads = observed({"rows": [{"count": 0}]})
    assert reads["query"].metadata["result_rows"] == 1
    assert reads["query"].metadata["zero_count"] is True
    result = convert_unverified_live(answer(), reads)
    assert result["status"] == "needs_lookup"


def test_zero_valued_data_column_is_not_a_zero_count():
    reads = observed_query(
        "SELECT count FROM synthetic.replica.centers LIMIT 1", [{"count": 0}]
    )
    assert reads["query"].metadata["result_rows"] == 1
    assert reads["query"].metadata["zero_count"] is False


def test_unreviewed_sql_answer_is_held_in_automatic_mode():
    value = answer()
    value["body"] = "운영 센터는 999개입니다."
    reads = observed({"rows": [{"count": 17}]})
    validate_verification(value, reads)
    held = convert_unverified_live(value, reads)
    assert held["status"] == "needs_lookup"
    assert "문의 조건" in held["body"]
    assert "live" in held["verification"]["missing"]
    validate_verification(held, reads)


def test_explicit_whole_table_count_uses_completed_result_not_model_body():
    relation = "synthetic.replica.centers"
    reads = observed_query(
        f"SELECT COUNT(*) AS count FROM `{relation}`",
        {"jobComplete": True, "rows": [{"count": 0}], "totalRows": "1"},
    )
    value = answer()
    value["body"] = "이 테이블은 999건입니다."
    result = convert_unverified_live(
        value, reads, question=f"`{relation}` 테이블 전체에 데이터가 있나요?"
    )
    assert result["status"] == "answered"
    assert result["body"] == "이번 BigQuery 조회에서는 결과가 없습니다. 잠시 후 다시 문의해 주세요."
    assert result["verification"] == value["verification"]
    validate_verification(result, reads)


def test_explicit_whole_table_count_rejects_wrong_target_or_predicate():
    relation = "synthetic.replica.centers"
    question = f"`{relation}` 테이블 전체에 데이터가 있나요?"
    for sql, asked in (
        (f"SELECT COUNT(*) AS count FROM `{relation}` WHERE FALSE", question),
        (f"SELECT COUNT(*) AS count FROM `{relation}`", "현재 입하 이슈는 몇 건인가요?"),
    ):
        reads = observed_query(sql, {"jobComplete": True, "rows": [{"count": 0}]})
        held = convert_unverified_live(answer(), reads, question=asked)
        assert held["status"] == "needs_lookup"


def test_explicit_whole_table_count_rejects_incomplete_result():
    relation = "synthetic.replica.centers"
    reads = observed_query(
        f"SELECT COUNT(*) AS count FROM `{relation}`",
        {"rows": [{"count": 0}]},
    )
    held = convert_unverified_live(
        answer(), reads, question=f"`{relation}` 테이블 전체에 데이터가 있나요?"
    )
    assert held["status"] == "needs_lookup"


def test_explicit_count_holds_when_another_live_read_or_report_exists():
    relation = "synthetic.replica.centers"
    sql = f"SELECT COUNT(*) AS count FROM `{relation}`"
    reads = observed_query(sql, {"jobComplete": True, "rows": [{"count": 0}]})
    later = observed_query(sql, {"jobComplete": True, "rows": [{"count": 1}]})
    reads["later_one"] = later["query"]
    question = f"`{relation}` 테이블 전체에 데이터가 있나요?"
    assert convert_unverified_live(answer(), reads, question=question)["status"] == "needs_lookup"
    assert convert_unverified_live(
        answer(), {"query": reads["query"]},
        report={"evidence_kinds": ["live"]}, question=question,
    )["status"] == "needs_lookup"


def test_explicit_count_requires_exact_private_source_location():
    relation = "synthetic.replica.centers"
    reads = observed_query(
        f"SELECT COUNT(*) AS count FROM `{relation}`",
        {"jobComplete": True, "rows": [{"count": 0}]},
    )
    value = answer()
    value["sources"][0]["location"] = "synthetic.replica"
    validate_verification(value, reads)  # Existing general binding allows a dataset prefix.
    result = convert_unverified_live(
        value, reads, question=f"`{relation}` 테이블 전체에 데이터가 있나요?"
    )
    assert result["status"] == "needs_lookup"


def test_duplicate_live_checks_do_not_bypass_count_scoping():
    value = answer()
    value["verification"]["checks"].append({
        "kind": "live", "receipt": "query", "source_index": 0,
    })
    reads = observed({"rows": []})
    validate_verification(value, reads)
    result = convert_unverified_live(value, reads)
    assert result["status"] == "needs_lookup"


def test_unreviewed_empty_query_with_false_filter_is_not_auto_postable():
    reads = observed_query(
        "SELECT * FROM synthetic.replica.centers WHERE 1=0", {"rows": []}
    )
    value = answer()
    validate_verification(value, reads)
    held = convert_unverified_live(value, reads)
    assert held["status"] == "needs_lookup"
    assert "결과가 없습니다" not in held["body"]


def test_query_for_a_different_table_cannot_support_cited_source():
    value = answer()
    value["sources"][0]["location"] = "synthetic.replica.other_centers"
    with pytest.raises(Exception, match="required_read_not_verified"):
        validate_verification(value, observed({"rows": []}))


@pytest.mark.parametrize("body", [
    {"jobComplete": False, "rows": []},
    {"rows": [], "nextPageToken": "next"},
    {"rows": [], "totalRows": "3"},
    {"errors": [{"reason": "badRequest"}], "rows": []},
])
def test_incomplete_or_paged_result_is_never_reported_as_empty(body):
    reads = observed(body)
    if reads:
        assert reads["query"].metadata["result_rows"] is None
        assert reads["query"].metadata["zero_count"] is False
    else:
        assert reads == {}


def test_unknown_result_shape_never_becomes_empty():
    reads = observed({"unknown": []})
    assert reads["query"].metadata["result_rows"] is None
    result = convert_unverified_live(answer(), reads)
    assert "결과가 없습니다" not in result["body"]
    assert result["status"] == "needs_lookup"


def test_failed_read_does_not_create_a_live_receipt():
    assert observed({"error": "permission denied"}, error=True) == {}


def test_stable_knowledge_query_and_manual_live_report_are_unchanged():
    value = answer()
    value["verification"].update(question_kind="domain", required=["knowledge"])
    value["verification"]["checks"] = value["verification"]["checks"][1:]
    reads = observed({"rows": []})
    validate_verification(value, reads)
    assert convert_unverified_live(value, reads) is value

    manual = answer()
    manual["verification"]["checks"][0]["receipt"] = "developer_report"
    report = {"evidence_kinds": ["live"]}
    validate_verification(manual, reads, report=report)
    assert convert_unverified_live(manual, reads, report) is manual


def test_mixed_code_answer_is_not_replaced_by_empty_data_message():
    value = answer()
    reads = observed({"rows": []})
    reads["code"] = ReadReceipt({"code"})
    value["verification"]["required"].append("code")
    value["verification"]["checks"].append({
        "kind": "code", "receipt": "code", "source_index": 0,
    })
    validate_verification(value, reads)
    result = convert_unverified_live(value, reads)
    assert "결과가 없습니다" not in result["body"]
    assert result["status"] == "needs_lookup"
    assert any(check["kind"] == "code" for check in result["verification"]["checks"])


def test_plain_set_live_receipt_cannot_bypass_automatic_hold():
    value = answer()
    held = convert_unverified_live(value, {"query": {"knowledge", "live"}})
    assert held["status"] == "needs_lookup"
    assert "live" in held["verification"]["missing"]


@pytest.mark.parametrize("status", ["needs_lookup"])
def test_existing_unresolved_output_unchanged(status):
    value = answer()
    value["status"] = status
    assert convert_unverified_live(value, observed({"rows": []})) is value


def test_cannot_answer_cannot_publish_unverified_live_claim():
    value = answer()
    value["status"] = "cannot_answer"
    changed = convert_unverified_live(value, observed({"rows": []}))
    assert changed["status"] == "needs_lookup"
    assert "17" not in changed["body"]
