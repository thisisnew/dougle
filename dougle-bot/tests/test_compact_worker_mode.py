"""Compact-policy worker contract with synthetic sources and a fake engine."""

import json
from dataclasses import replace

import pytest
from test_answer_style import READS, SESSION, SKILLS, answer
from test_runtime import event

from dougle_bot.engine import COMPACT_POLICY_DIGEST, EngineResult
from dougle_bot.intake import receive
from dougle_bot.prompts import prompt
from dougle_bot.safety import BotError


def compact(app):
    config = replace(app.cfg, engine=replace(app.cfg.engine, compact_policy=True))
    app.cfg = app.worker.config = app.actions.config = config
    return config


def test_compact_prompt_contains_dynamic_data_without_repeating_policy():
    from dougle_bot.compact_policy import COMPACT_POLICY
    from dougle_bot.config import EngineConfig

    config = replace(EngineConfig(), compact_policy=True)
    value = prompt(
        config,
        {"question_text": "합성 질문", "thread_key": "synthetic"},
        [{"text": "앞선 조건"}],
    )
    assert "합성 질문" in value and "앞선 조건" in value
    assert "MODE: answer" in value
    assert COMPACT_POLICY not in value
    assert "Invoke dougle:dougle-ask" not in value


def test_compact_worker_requires_engine_policy_digest_and_keeps_read_checks(app):
    compact(app)
    app.engine.results = [
        EngineResult(SESSION, answer(), 0.01, set(), READS,
                     policy_digest=COMPACT_POLICY_DIGEST),
    ]
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.run_once()
    row = app.store.inquiry(ident)
    assert row["status"] == "awaiting_approval"
    detail = app.store.one(
        "SELECT detail FROM audit WHERE action='compact_policy_supplied' AND inquiry_id=?",
        (ident,),
    )["detail"]
    assert json.loads(detail)["policy_digest"] == COMPACT_POLICY_DIGEST
    assert json.loads(detail)["session_fingerprint"] == app.worker._session_fingerprint(row)
    assert app.store.one(
        "SELECT id FROM audit WHERE action='evidence_checks_verified' AND inquiry_id=?",
        (ident,),
    )


def test_compact_followup_resets_unverified_old_policy_session(app):
    compact(app)
    app.engine.results = [
        EngineResult(SESSION, answer(), 0.01, set(), READS,
                     policy_digest=COMPACT_POLICY_DIGEST),
    ]
    ident = receive(app.store, app.cfg, event(), "UBOT")
    with app.store.tx() as db:
        db.execute(
            "UPDATE threads SET engine_session_id=? WHERE thread_key=(SELECT thread_key FROM inquiries WHERE id=?)",
            ("11111111-2222-4333-8444-555555555555", ident),
        )
    app.worker.run_once()
    assert app.engine.calls[0][1] is None
    assert app.store.one(
        "SELECT id FROM audit WHERE action='engine_session_reset' AND inquiry_id=?",
        (ident,),
    )


def test_compact_followup_keeps_session_with_matching_policy_digest(app):
    compact(app)
    app.engine.results = [
        EngineResult(SESSION, answer(), 0.01, set(), READS,
                     policy_digest=COMPACT_POLICY_DIGEST),
    ]
    ident = receive(app.store, app.cfg, event(), "UBOT")
    with app.store.tx() as db:
        db.execute(
            "UPDATE threads SET engine_session_id=? WHERE thread_key=(SELECT thread_key FROM inquiries WHERE id=?)",
            ("11111111-2222-4333-8444-555555555555", ident),
        )
    inquiry = app.store.inquiry(ident)
    action, detail = app.worker._session_policy_audit(
        app.worker._session_fingerprint(inquiry)
    )
    app.store.audit(ident, "system", action, detail)
    app.worker.run_once()
    assert app.engine.calls[0][1] == "11111111-2222-4333-8444-555555555555"


def test_two_jobs_reset_session_after_registered_source_disconnect(app):
    compact(app)
    source = app.worker.memory_root / "sources" / "service.md"
    source.parent.mkdir(parents=True)
    source.write_text("Location: /original/service\nState: read\n")
    app.engine.results = [
        EngineResult(SESSION, answer(), 0.01, set(), READS,
                     policy_digest=COMPACT_POLICY_DIGEST),
    ] * 2

    first = receive(app.store, app.cfg, event(), "UBOT")
    assert app.worker.run_once()
    assert app.store.inquiry(first)["status"] == "awaiting_approval"
    assert app.engine.calls[0][1] is None

    source.write_text("Location: /original/service\nState: disconnected\n")
    second = receive(
        app.store, app.cfg,
        event(ts="1002.000001", thread_ts="1001.000001"), "UBOT",
    )
    assert app.worker.run_once()
    assert app.store.inquiry(second)["status"] == "awaiting_approval"
    assert app.engine.calls[1][1] is None
    assert app.store.one(
        "SELECT id FROM audit WHERE action='engine_session_reset' AND inquiry_id=?",
        (second,),
    )


def test_two_jobs_keep_session_when_execution_and_source_scope_match(app):
    compact(app)
    app.engine.results = [
        EngineResult(SESSION, answer(), 0.01, set(), READS,
                     policy_digest=COMPACT_POLICY_DIGEST),
    ] * 2
    first = receive(app.store, app.cfg, event(), "UBOT")
    assert app.worker.run_once()
    assert app.store.inquiry(first)["status"] == "awaiting_approval"
    second = receive(
        app.store, app.cfg,
        event(ts="1002.000001", thread_ts="1001.000001"), "UBOT",
    )
    assert app.worker.run_once()
    assert app.store.inquiry(second)["status"] == "awaiting_approval"
    assert app.engine.calls[1][1] == SESSION
    assert not app.store.one(
        "SELECT id FROM audit WHERE action='engine_session_reset' AND inquiry_id=?",
        (second,),
    )


@pytest.mark.parametrize(
    "setting, value",
    [
        ("model", "synthetic-model"),
        ("allowed_tools", ["Read"]),
        ("isolated_cli", True),
    ],
)
def test_two_jobs_reset_session_after_execution_setting_change(app, setting, value):
    compact(app)
    app.engine.results = [
        EngineResult(SESSION, answer(), 0.01, set(), READS,
                     policy_digest=COMPACT_POLICY_DIGEST),
    ] * 2

    first = receive(app.store, app.cfg, event(), "UBOT")
    assert app.worker.run_once()
    assert app.store.inquiry(first)["status"] == "awaiting_approval"
    assert app.engine.calls[0][1] is None

    config = replace(app.cfg, engine=replace(app.cfg.engine, **{setting: value}))
    app.cfg = app.worker.config = app.actions.config = config
    second = receive(
        app.store, app.cfg,
        event(ts="1002.000001", thread_ts="1001.000001"), "UBOT",
    )
    assert app.worker.run_once()
    assert app.store.inquiry(second)["status"] == "awaiting_approval"
    assert app.engine.calls[1][1] is None
    assert app.store.one(
        "SELECT id FROM audit WHERE action='engine_session_reset' AND inquiry_id=?",
        (second,),
    )


def test_compact_followup_resets_old_digest(app):
    compact(app)
    app.engine.results = [
        EngineResult(SESSION, answer(), 0.01, set(), READS,
                     policy_digest=COMPACT_POLICY_DIGEST),
    ]
    ident = receive(app.store, app.cfg, event(), "UBOT")
    with app.store.tx() as db:
        db.execute(
            "UPDATE threads SET engine_session_id=? WHERE thread_key=(SELECT thread_key FROM inquiries WHERE id=?)",
            (SESSION, ident),
        )
    app.store.audit(ident, "system", "compact_policy_supplied", "old-policy-digest")
    app.worker.run_once()
    assert app.engine.calls[0][1] is None


def test_baseline_followup_resets_a_compact_session(app):
    app.engine.results = [EngineResult(SESSION, answer(), 0.01, SKILLS, READS)]
    ident = receive(app.store, app.cfg, event(), "UBOT")
    with app.store.tx() as db:
        db.execute(
            "UPDATE threads SET engine_session_id=? WHERE thread_key=(SELECT thread_key FROM inquiries WHERE id=?)",
            (SESSION, ident),
        )
    app.store.audit(ident, "system", "compact_policy_supplied", COMPACT_POLICY_DIGEST)
    app.worker.run_once()
    assert app.engine.calls[0][1] is None


def test_failed_draft_save_does_not_mark_old_session_as_compact(app, monkeypatch):
    compact(app)
    app.engine.results = [
        EngineResult(SESSION, answer(), 0.01, set(), READS,
                     policy_digest=COMPACT_POLICY_DIGEST),
    ]
    ident = receive(app.store, app.cfg, event(), "UBOT")
    old_session = "11111111-2222-4333-8444-555555555555"
    with app.store.tx() as db:
        db.execute(
            "UPDATE threads SET engine_session_id=? WHERE thread_key=(SELECT thread_key FROM inquiries WHERE id=?)",
            (old_session, ident),
        )
    app.store.audit(ident, "system", "required_skills_verified", "[]")
    def fail_save(*args, **kwargs):
        raise BotError("stale_job")
    monkeypatch.setattr(app.store, "save_draft", fail_save)
    app.worker.run_once()
    thread_key = app.store.inquiry(ident)["thread_key"]
    assert app.store.one(
        "SELECT engine_session_id FROM threads WHERE thread_key=?", (thread_key,)
    )["engine_session_id"] == old_session
    assert app.worker._session_policy_compatible(
        thread_key, app.worker._session_fingerprint(app.store.inquiry(ident))
    ) is False
    assert not app.store.one(
        "SELECT id FROM audit WHERE action='compact_policy_supplied' AND inquiry_id=?",
        (ident,),
    )


def test_compact_output_without_policy_digest_is_rejected(app):
    compact(app)
    app.engine.results = [EngineResult(SESSION, answer(), 0.01, set(), READS)] * 2
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.run_once()
    assert app.store.inquiry(ident)["status"] == "failed"
    assert not app.store.one(
        "SELECT id FROM audit WHERE action='evidence_checks_verified' AND inquiry_id=?",
        (ident,),
    )
