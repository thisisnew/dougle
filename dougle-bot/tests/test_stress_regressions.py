"""Failure/cost regressions: fake services, actual worker/store, no model calls."""

import copy

import pytest
from test_answer_style import READS, SESSION, SKILLS, answer
from test_runtime import event

from dougle_bot.engine import EngineResult
from dougle_bot.intake import receive


@pytest.mark.parametrize("kind,category", [("code", "implementation"), ("live", "current_state")])
def test_missing_style_activation_cannot_drop_research_requirement(app, kind, category):
    first = answer()
    first["verification"] = {
        "question_kind": category,
        "required": [kind],
        "checks": [],
        "missing": [kind],
    }
    first["status"] = "cannot_answer"
    app.engine.results = [
        EngineResult(SESSION, first, 0.01, set(), {}),
        EngineResult(SESSION, answer(), 0.01, SKILLS, copy.deepcopy(READS)),
    ]
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.run_once()
    assert app.store.inquiry(ident)["status"] == "failed"
    assert not app.store.current(app.store.inquiry(ident))
    assert len(app.engine.calls) == 2


def test_crash_after_saved_answer_does_not_repeat_paid_engine_call(app):
    ident = receive(app.store, app.cfg, event(), "UBOT")
    original = app.store.save_draft

    def crash_after_commit(*args, **kwargs):
        original(*args, **kwargs)
        raise SystemExit("synthetic interruption after durable draft")

    app.store.save_draft = crash_after_commit
    with pytest.raises(SystemExit):
        app.worker.run_once()
    app.store.save_draft = original
    assert app.store.inquiry(ident)["status"] == "awaiting_approval"
    app.store.recover()
    assert app.worker.run_once() is False
    assert len(app.engine.calls) == 1
    assert app.store.all("SELECT status FROM jobs") == [{"status": "done"}]


def test_failed_draft_transaction_does_not_mark_job_done(app):
    from dougle_bot.safety import BotError

    ident = receive(app.store, app.cfg, event(), "UBOT")
    job = app.store.claim_job()
    # A later write failure must roll back the done marker in the SAME transaction.
    with app.store.tx() as db:
        db.execute(
            "CREATE TRIGGER fail_draft BEFORE INSERT ON drafts BEGIN SELECT RAISE(ABORT, 'synthetic write failure'); END"
        )
    import sqlite3

    with pytest.raises(sqlite3.IntegrityError):
        app.store.save_draft(
            ident, answer(), "engine", "fixture", "1001.0", SESSION, job_id=job["id"]
        )
    assert app.store.one("SELECT status FROM jobs")["status"] == "running"
    assert not app.store.current(app.store.inquiry(ident))
    with pytest.raises(BotError, match="stale_job"):
        app.store.save_draft(ident, answer(), "engine", "fixture", "1001.0", SESSION, job_id=999)


def test_concurrent_duplicate_events_never_enqueue_duplicate_model_work(app):
    from concurrent.futures import ThreadPoolExecutor

    body = event()
    with ThreadPoolExecutor(max_workers=8) as pool:
        ids = list(pool.map(lambda _: receive(app.store, app.cfg, body, "UBOT"), range(200)))
    assert len([ident for ident in ids if ident is not None]) == 1
    assert len(app.store.all("SELECT * FROM jobs")) == 1
    app.worker.run_once()
    for _ in range(20):
        assert receive(app.store, app.cfg, body, "UBOT") is None
        assert app.worker.run_once() is False
    assert len(app.engine.calls) == 1


@pytest.mark.parametrize("first", ["casual", "activation", "schema", "read"])
def test_validation_retry_and_lost_session_have_bounded_engine_calls(app, first):
    from dougle_bot.safety import BotError

    invalid = answer("하면 돼." if first == "casual" else "하시면 됩니다.")
    if first == "schema":
        invalid.pop("sources")
    app.engine.results = [
        EngineResult(
            SESSION,
            invalid,
            0.01,
            set() if first == "activation" else SKILLS,
            {} if first == "read" else READS,
        ),
        BotError("session_not_found"),
        EngineResult(SESSION, answer(), 0.01, SKILLS, READS),
    ]
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.run_once()
    assert len(app.engine.calls) == 3
    assert app.store.inquiry(ident)["status"] == "awaiting_approval"
    assert app.worker.run_once() is False
    assert len(app.engine.calls) == 3


def test_daily_limit_blocks_style_retry_without_extra_call(app):
    from dataclasses import replace

    app.worker.config = replace(app.cfg, engine=replace(app.cfg.engine, daily_budget_usd=3.0))
    app.engine.results = [EngineResult(SESSION, answer("하면 돼."), 3.0, SKILLS, READS)]
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.run_once()
    assert len(app.engine.calls) == 1
    assert app.store.inquiry(ident)["error"] == "daily_budget_exceeded"


def test_cancel_during_presentation_repair_prevents_late_save(app):
    from test_progress_cancel import cancel, pending

    ref = pending(app)
    app.engine.results = [
        EngineResult(SESSION, answer("하면 돼."), 0.01, SKILLS, READS),
        EngineResult(SESSION, answer(), 0.01, SKILLS, {}),
    ]
    original = app.engine.run

    def run(*args, **kwargs):
        if app.engine.calls:
            cancel(app, ref)
        return original(*args, **kwargs)

    app.engine.run = run
    app.worker.run_once()
    assert app.store.inquiry(ref[0])["status"] == "cancelled"
    assert not app.store.current(app.store.inquiry(ref[0]))
    assert app.worker.run_once() is False
    assert len(app.engine.calls) == 2


def test_repeated_missing_session_does_not_loop(app):
    from dougle_bot.safety import BotError

    ident = receive(app.store, app.cfg, event(), "UBOT")
    with app.store.tx() as db:
        db.execute("UPDATE threads SET engine_session_id=?", (SESSION,))
    app.engine.results = [BotError("session_not_found"), BotError("session_not_found")]
    app.worker.run_once()
    assert len(app.engine.calls) == 2
    assert app.store.inquiry(ident)["status"] == "failed"
    assert app.worker.run_once() is False
