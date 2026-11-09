"""Automatic, source-bound storage-time answers with synthetic local originals."""

import json

from test_answer_style import answer

from dougle_bot.evidence import read_receipts
from dougle_bot.static_claims import scope_static_timing

QUESTION = "입고는 언제 저장하나요?"


def observed(path, tool_body):
    events = [
        {"type": "assistant", "message": {"content": [{
            "type": "tool_use", "id": "read1", "name": "Read", "input": {"file_path": str(path)},
        }]}},
        {"type": "user", "message": {"content": [{
            "type": "tool_result", "tool_use_id": "read1", "content": tool_body,
        }]}},
    ]
    return read_receipts("\n".join(json.dumps(item, ensure_ascii=False) for item in events))


def scoped(tmp_path, original, model_body="입고는 확정 전 저장됩니다.", *, observed_body=None):
    path = tmp_path / "policy.md"
    path.write_text(original)
    output = answer(model_body)
    output["sources"] = [{"title": "가상 원본", "location": str(path)}]
    reads = observed(path, original if observed_body is None else observed_body)
    return scope_static_timing(output, reads, question=QUESTION)["body"]


def test_after_source_overrides_before_model(tmp_path):
    assert scoped(tmp_path, "입고는 확정 뒤 저장됩니다.\n") == "입고는 확정 뒤 저장됩니다."


def test_before_source_changes_deterministic_answer(tmp_path):
    assert scoped(tmp_path, "입고는 확정 전 저장됩니다.\n") == "입고는 확정 전 저장됩니다."


def test_explicit_condition_negative_and_exception_are_preserved(tmp_path):
    source = (
        "입고는 검수가 완료된 경우 확정 뒤 저장됩니다.\n"
        "입고는 확정 전에는 저장되지 않습니다.\n"
        "다만 취소된 건은 저장되지 않습니다.\n"
    )
    body = scoped(tmp_path, source)
    assert body == (
        "입고는 검수가 완료된 경우 확정 뒤 저장됩니다. "
        "확정 전에는 저장되지 않습니다. 다만 취소된 건은 저장되지 않습니다."
    )


def test_negative_alone_does_not_infer_positive_timing(tmp_path):
    assert "확정하지 못했습니다" in scoped(
        tmp_path, "입고는 확정 전에는 저장되지 않습니다.\n"
    )


def test_conflicting_or_unknown_rule_auto_reports_bounded_uncertainty(tmp_path):
    for source in (
        "입고는 확정 뒤 저장됩니다.\n입고는 확정 전 저장됩니다.\n",
        "입고는 확정 뒤 저장됩니다.\n단, 취소된 건은 저장되지 않습니다.\n",
        "입고는 확정 뒤 저장됩니다.\n다만 취소된 건은 제외합니다.\n",
        "# 확정 전 저장 예외\n입고는 확정 뒤 저장됩니다.\n",
        "입고는 확정 뒤 저장될 수 있습니다.\n",
    ):
        assert "확정하지 못했습니다" in scoped(tmp_path, source)


def test_partial_or_changed_read_never_supports_positive_claim(tmp_path):
    source = "입고는 확정 뒤 저장됩니다.\n다만 취소된 건은 저장되지 않습니다.\n"
    for tool_body in ("입고는 확정 뒤 저장됩니다.\n", "1→입고는 확정 뒤 저장됩니다."):
        assert "확정하지 못했습니다" in scoped(
            tmp_path, source, observed_body=tool_body
        )


def test_complete_numbered_read_is_accepted(tmp_path):
    source = "입고는 확정 뒤 저장됩니다.\n"
    assert scoped(tmp_path, source, observed_body="    1→입고는 확정 뒤 저장됩니다.") == (
        "입고는 확정 뒤 저장됩니다."
    )
