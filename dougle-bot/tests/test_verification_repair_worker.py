"""Actual worker/SQLite and stream receipts; no company data or external services."""

import copy
import json

import pytest
from test_answer_scope import payload
from test_research_regressions import process, response

from dougle_bot.config import save
from dougle_bot.main import status

QUESTION = "현재 코드에서 회송폐기 이메일 발송 조건 확인해봐"


def code_response():
    return response(
        "code",
        "Read",
        {"file_path": "/synthetic/Mail.java"},
        "class Mail {}",
        "/synthetic/Mail.java",
    )


def broken_pair():
    original = code_response()
    repaired = copy.deepcopy(original)
    original.output["verification"]["checks"][0]["source_index"] = 1
    repaired.reads = {}
    return original, repaired


def test_code_record_index_repair_keeps_successful_read_and_avoids_research(app):
    first, second = broken_pair()
    row = process(app, [first, second], QUESTION)
    assert row["status"] == "awaiting_approval"
    correction = payload(app.engine.calls[1][0])
    assert correction["original_answer"] == first.output
    assert correction["successful_read_receipts"] == [
        {"receipt": "read1", "kinds": ["code", "knowledge"]}
    ]
    assert correction["validation_correction_detail"] == [
        {"field": "verification.checks[0].source_index", "reason": "out_of_range"}
    ]
    timing = json.loads(
        app.store.one("SELECT detail FROM audit WHERE action='answer_timing'")["detail"]
    )
    assert timing["retries"]["schema"] == 1
    assert timing["retries"]["research"] == 0
    assert app.store.one("SELECT id FROM audit WHERE action='verification_record_repaired'")
    assert not any(c[0] == "post" for c in app.slack.calls)


@pytest.mark.parametrize(
    "field", ["body", "sources", "lookup", "status", "question_kind", "required", "receipt"]
)
def test_record_repair_cannot_change_answer_or_weaken_proof(app, field):
    first, second = broken_pair()
    if field == "body":
        second.output[field] = "다른 조건에서 보내집니다."
    elif field == "sources":
        second.output[field][0]["location"] = "/synthetic/Other.java"
    elif field == "lookup":
        second.output[field] = {}
    elif field == "status":
        second.output[field] = "cannot_answer"
    elif field == "question_kind":
        second.output["verification"][field] = "domain"
    elif field == "required":
        second.output["verification"][field] = []
    else:
        second.output["verification"]["checks"][0][field] = "invented"
    row = process(app, [first, second], QUESTION)
    assert row["status"] == "failed"
    assert app.store.current(row) is None
    assert not app.store.one("SELECT id FROM audit WHERE action='verification_record_repaired'")


@pytest.mark.parametrize(
    "failure", ["missing_read", "wrong_source", "missing_skill", "declared_missing", "live"]
)
def test_record_repair_does_not_turn_missing_or_live_reads_into_proof(app, failure):
    first, second = broken_pair()
    question = QUESTION
    if failure == "missing_read":
        first.reads = {}
    elif failure == "wrong_source":
        first.reads["read1"].metadata["locator"]["target"] = "/synthetic/Other.java"
    elif failure == "missing_skill":
        first.activated = set()
    elif failure == "declared_missing":
        first.output["verification"]["missing"] = ["code"]
    else:
        question = "현재 운영 DB에서 센터 건수를 조회해주세요"
        for item in (first, second):
            item.output["verification"]["question_kind"] = "current_state"
            item.output["verification"]["required"] = ["live"]
            item.output["verification"]["checks"][0]["kind"] = "live"
        first.reads = {"read1": {"live"}}
    row = process(app, [first, second], question)
    assert row["status"] == "failed"
    assert app.store.current(row) is None
    if failure != "wrong_source":
        assert "original_answer" not in payload(app.engine.calls[1][0])


def test_record_repair_still_requires_skills_on_second_attempt(app):
    first, second = broken_pair()
    second.activated = set()
    row = process(app, [first, second], QUESTION)
    assert row["status"] == "failed"
    assert row["error"] == "required_skill_not_activated"


def test_repeated_invalid_record_preserves_both_diagnostics_and_stops(app):
    first, second = broken_pair()
    second.output["verification"]["checks"][0]["source_index"] = True
    row = process(app, [first, second], QUESTION)
    assert row["status"] == "failed"
    assert len(app.engine.calls) == 2
    path = app.cfg.home / "config.toml"
    save(app.cfg, path)
    failures = status(app.cfg.home, path)["recent_verification_failures"]
    assert len(failures) == 2
    assert failures[0]["issues"][0]["reason"] == "invalid_type"
    assert failures[1]["issues"][0]["reason"] == "out_of_range"
    assert "/synthetic" not in json.dumps(failures)


def test_status_diagnostic_filters_untrusted_persisted_values(app):
    row = process(app, list(broken_pair()), QUESTION)
    for detail in ("{", "null", '[{"field":"private path","reason":"secret"}]'):
        app.store.audit(row["id"], "system", "verification_record_invalid", detail)
    path = app.cfg.home / "config.toml"
    save(app.cfg, path)
    failures = status(app.cfg.home, path)["recent_verification_failures"]
    assert len(failures) == 1
    assert "secret" not in json.dumps(failures)


def test_cancel_during_record_repair_never_saves_or_posts(app):
    first, second = broken_pair()
    original_run = app.engine.run

    def run(*args, **kwargs):
        if app.engine.calls:
            with app.store.tx() as db:
                db.execute("UPDATE inquiries SET status='cancelled'")
        return original_run(*args, **kwargs)

    app.engine.run = run
    row = process(app, [first, second], QUESTION)
    assert row["status"] == "cancelled"
    assert app.store.current(row) is None
