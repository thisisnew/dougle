"""Independent repair-boundary regressions using synthetic receipts and fake services."""

import copy

import pytest
from test_answer_scope import payload
from test_research_regressions import process, response

from dougle_bot.safety import BotError

QUESTION = "실제 코드에서 이메일 발송 조건을 확인해주세요"


def repair_pair():
    original = response(
        "code",
        "Read",
        {"file_path": "/synthetic/Mail.java"},
        "class Mail {}",
        "/synthetic/Mail.java",
    )
    repaired = copy.deepcopy(original)
    original.output["verification"]["checks"][0]["source_index"] = 1
    repaired.reads = {}
    return original, repaired


@pytest.mark.parametrize(
    "required",
    ["live", {"code": True, "live": True}, ["code", "production"], None],
    ids=["live-string", "live-mapping", "unknown-kind", "null-requirements"],
)
def test_ambiguous_requirements_cannot_be_silently_repaired_using_old_reads(app, required):
    original, repaired = repair_pair()
    original.output["verification"]["required"] = required
    row = process(app, [original, repaired], QUESTION)
    assert row["status"] == "failed"
    assert app.store.current(row) is None
    assert "original_answer" not in payload(app.engine.calls[1][0])


def test_live_check_declaration_cannot_disappear_during_record_only_repair(app):
    original, repaired = repair_pair()
    original.output["verification"]["checks"].append(
        {"kind": "live", "receipt": "unavailable-live-read", "source_index": 0}
    )
    row = process(app, [original, repaired], QUESTION)
    assert row["status"] == "failed"
    assert app.store.current(row) is None
    assert "original_answer" not in payload(app.engine.calls[1][0])


def test_second_attempt_receipt_collision_cannot_replace_original_source_metadata(app):
    original, repaired = repair_pair()
    repaired.reads = copy.deepcopy(original.reads)
    original.reads["read1"].metadata["locator"]["target"] = "/synthetic/Other.java"
    row = process(app, [original, repaired], QUESTION)
    assert row["status"] == "failed"
    assert app.store.current(row) is None
    assert not app.store.one("SELECT id FROM audit WHERE action='verification_record_repaired'")


def test_session_loss_during_record_repair_discards_original_proof(app):
    original, repaired = repair_pair()
    row = process(app, [original, BotError("session_not_found"), repaired], QUESTION)
    assert len(app.engine.calls) == 3
    assert "original_answer" in payload(app.engine.calls[1][0])
    assert "original_answer" not in payload(app.engine.calls[2][0])
    assert row["status"] == "failed"
    assert app.store.current(row) is None
    assert not app.store.one("SELECT id FROM audit WHERE action='verification_record_repaired'")


@pytest.mark.parametrize(
    "declare_required", [False, True], ids=["check-only", "required-and-check"]
)
def test_record_repair_rejects_new_live_check_even_with_successful_fresh_read(
    app, declare_required
):
    original, repaired = repair_pair()
    fresh = response(
        "live",
        "mcp__example__execute_sql_readonly",
        {"query": "SELECT status FROM demo.orders"},
        '[{"status":"ready"}]',
        "demo.orders",
    )
    assert "live" in fresh.reads["read1"]
    repaired.reads = {"fresh-live": fresh.reads["read1"]}
    repaired.output["verification"]["checks"].append(
        {"kind": "live", "receipt": "fresh-live", "source_index": 0}
    )
    if declare_required:
        repaired.output["verification"]["required"].append("live")
    row = process(app, [original, repaired], QUESTION)
    assert "original_answer" in payload(app.engine.calls[1][0])
    assert len(app.engine.calls) == 2
    assert row["status"] == "failed"
    assert row["error"] == "repair_changed_evidence"
    assert app.store.current(row) is None
    assert not app.store.one("SELECT id FROM audit WHERE action='verification_record_repaired'")
    assert not any(call[0] == "post" for call in app.slack.calls)


def test_live_check_floor_survives_malformed_required_and_fresh_code_retry(app):
    original, repaired = repair_pair()
    original.output["verification"]["required"] = None
    original.output["verification"]["checks"].append(
        {"kind": "live", "receipt": "unavailable-live-read", "source_index": 0}
    )
    repaired.reads = copy.deepcopy(original.reads)
    row = process(app, [original, repaired], QUESTION)
    assert "original_answer" not in payload(app.engine.calls[1][0])
    assert row["status"] == "failed"
    assert app.store.current(row) is None
    assert not app.store.one("SELECT id FROM audit WHERE action='verification_record_repaired'")
