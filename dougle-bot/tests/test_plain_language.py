"""Actual activation gate, fake external services; no model-quality claim."""

import json
from dataclasses import replace
from pathlib import Path

import pytest
from test_answer_style import READS, SESSION, SKILLS, answer, call, outcome, trace
from test_runtime import event, post, ready

from dougle_bot.config import load, save
from dougle_bot.engine import EngineResult, parse_trace
from dougle_bot.guard import permitted
from dougle_bot.intake import receive
from dougle_bot.safety import BotError

PLAIN = "dougle:plain-language"


@pytest.mark.parametrize("resume", [False, True])
def test_missing_plain_language_never_becomes_approvable(app, resume):
    ident = receive(app.store, app.cfg, event(), "UBOT")
    if resume:
        with app.store.tx() as db:
            db.execute("UPDATE threads SET engine_session_id=?", (SESSION,))
    app.engine.results = [EngineResult(SESSION, answer(), 0.02, SKILLS - {PLAIN}, READS)] * 2
    assert app.worker.run_once()
    assert len(app.engine.calls) == 2
    row = app.store.inquiry(ident)
    assert row["error"] == "required_skill_not_activated"
    assert app.store.current(row) is None
    assert not any(c[0] == "post" for c in app.slack.calls)
    assert all(PLAIN in c[0] for c in app.engine.calls)


@pytest.mark.parametrize("mode", ["missing", "failed", "wrong_id", "text_claim", "read_only"])
def test_failed_or_claimed_activation_is_not_a_successful_call(mode):
    calls = []
    if mode in {"failed", "wrong_id"}:
        calls = [
            call(PLAIN),
            outcome(
                "other" if mode == "wrong_id" else "tool1",
                content="Unknown skill" if mode == "failed" else "Loaded",
                error=mode == "failed",
            ),
        ]
    elif mode == "text_claim":
        calls = [
            {
                "type": "assistant",
                "message": {
                    "content": [{"type": "text", "text": "plain-language를 실행했습니다."}]
                },
            }
        ]
    elif mode == "read_only":
        read = call(PLAIN)
        read["message"]["content"][0].update(
            name="Read", input={"file_path": "plain-language/SKILL.md"}
        )
        calls = [read, outcome(content="# Plain language")]
    assert PLAIN not in parse_trace(trace(calls)).activated


def test_successful_correction_can_be_approved_but_never_auto_posts(app):
    app.engine.results = [
        EngineResult(SESSION, answer(), 0.02, SKILLS - {PLAIN}, READS),
        EngineResult(SESSION, answer(), 0.02, SKILLS, READS),
    ]
    ref = ready(app)
    assert len(app.engine.calls) == 2
    assert not any(c[0] == "post" for c in app.slack.calls)
    post(app, ref)
    assert app.store.inquiry(ref[0])["status"] == "posted"


def test_lookup_result_requires_activation_again(app):
    ref = ready(app)
    app.store.enqueue(
        ref[0],
        json.loads(ref[1])["draft_id"],
        "lookup_result",
        json.dumps(
            {"lookup": {"summary": "미처리 1건입니다.", "observed_at": "1970-01-01T00:16:00+00:00"}}
        ),
    )
    app.engine.results = [EngineResult(SESSION, answer(), 0.02, SKILLS - {PLAIN}, READS)] * 2
    app.worker.run_once()
    assert app.store.inquiry(ref[0])["error"] == "required_skill_not_activated"
    assert not any(c[0] == "post" for c in app.slack.calls)


@pytest.mark.parametrize("name", ["", "../plain", "dougle:dougle-ask", "dougle:i-have-adhd", None])
def test_plain_language_cannot_be_disabled_or_alias_another_required_skill(app, name):
    cfg = replace(app.cfg, engine=replace(app.cfg.engine, plain_language_skill=name))
    with pytest.raises(BotError, match="invalid_skill_name"):
        cfg.validate()


def test_existing_config_receives_hidden_companion_default(app):
    path = app.cfg.home / "config.toml"
    save(app.cfg, path)
    path.write_text(
        "\n".join(
            line
            for line in path.read_text().splitlines()
            if not line.startswith("plain_language_skill")
        )
    )
    assert load(path).engine.plain_language_skill == PLAIN


def test_explicit_personal_skill_names_are_allowed_without_allowing_arbitrary_skills(app):
    cfg = replace(
        app.cfg.engine,
        ask_skill="dougle-ask",
        style_skill="i-have-adhd",
        plain_language_skill="plain-language",
    )
    replace(app.cfg, engine=cfg).validate()
    policy = {"allowed": ["Skill"], "skills": list(cfg.required_skills), "memory": "/tmp/memory"}
    assert permitted(policy, "Skill", {"skill": "plain-language"})
    assert not permitted(policy, "Skill", {"skill": "unapproved-skill"})


def test_companion_ships_hidden_model_callable_and_with_license():
    root = Path(__file__).resolve().parents[2]
    for base in ("skills", "plugins/dougle/skills"):
        directory = root / base / "plain-language"
        skill = (directory / "SKILL.md").read_text()
        header = skill.split("---", 2)[1]
        assert "user-invocable: false" in header
        assert "disable-model-invocation:" not in header
        assert "Copyright (c) 2026 Arjun Prabhulal" in (directory / "LICENSE.md").read_text()
