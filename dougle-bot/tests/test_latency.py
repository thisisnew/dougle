"""Real worker/SQLite, fake Slack/CLI. No company data or model calls."""

import copy
import json

import pytest
from test_answer_scope import payload
from test_answer_style import READS, SESSION, SKILLS, answer
from test_runtime import event, ready

from dougle_bot.config import save
from dougle_bot.engine import EngineResult
from dougle_bot.intake import receive
from dougle_bot.main import status
from dougle_bot.progress import Progress, StreamProgress, safe_profile
from dougle_bot.safety import BotError


def result(body="하시면 됩니다.", reads=None):
    return EngineResult(SESSION, answer(body), 0.01, SKILLS, READS if reads is None else reads)


def process(app):
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.run_once()
    return app.store.inquiry(ident)


def test_stage_accounting_separates_queue_and_processing():
    now = [0.0]
    progress = Progress(clock=lambda: now[0])
    now[0] = 2
    progress.phase("search", attempt=1)
    now[0] = 5
    progress.activity("lookup")
    now[0] = 9
    progress.activity("tool_result")
    now[0] = 10
    progress.sizes(prompt=123, output=456)
    progress.retry("presentation")
    profile = progress.profile(6000)
    assert profile["queue_ms"] == 6000
    assert profile["processing_ms"] == 10000
    assert sum(profile["observed_stage_ms"].values()) == 10000
    assert profile["observed_stage_ms"]["search"] == 3000
    assert profile["observed_stage_ms"]["lookup"] == 4000
    assert profile["tool_responses"] == 1
    assert profile["prompt_characters"] == 123
    assert profile["output_characters"] == 456
    assert profile["retries"]["presentation"] == 1
    assert progress.profile(6000) == profile


@pytest.mark.parametrize(
    "key,value",
    [
        ("queue_ms", -1),
        ("attempts", True),
        ("output_characters", "private"),
        ("processing_ms", 10**13),
    ],
)
def test_invalid_metrics_are_not_returned(key, value):
    profile = Progress().profile()
    profile[key] = value
    assert safe_profile(json.dumps(profile)) is None


def test_unknown_text_never_enters_status_profile(app):
    ref = ready(app)
    profile = Progress().profile()
    profile["question"] = "private question"
    profile["observed_stage_ms"]["private path"] = "private value"
    app.store.audit(ref[0], "system", "answer_timing", json.dumps(profile))
    path = app.cfg.home / "config.toml"
    save(app.cfg, path)
    timings = status(app.cfg.home, path)["recent_timings"]
    assert len(timings) == 2
    assert "private" not in json.dumps(timings)


def test_progress_classifies_paths_without_emitting_them():
    emitted = []
    parser = StreamProgress(emitted.append)
    for path in ("/synthetic/.dougle/topics/a.md", "/synthetic/source.py"):
        envelope = {
            "type": "assistant",
            "message": {
                "content": [{"type": "tool_use", "name": "Read", "input": {"file_path": path}}]
            },
        }
        parser.feed((json.dumps(envelope) + "\n").encode())
    assert emitted == ["activity", "search", "activity", "lookup"]


def test_same_inquiry_style_repair_reuses_proven_evidence(app):
    app.engine.results = [result("하면 돼."), result(reads={})]
    ref = ready(app)
    repair = payload(app.engine.calls[1][0])
    assert repair["verified_answer"]["verification"]["checks"][0]["receipt"] == "read1"
    runs = app.store.all("SELECT id FROM engine_runs ORDER BY id")
    record = app.store.one("SELECT detail FROM audit WHERE action='presentation_evidence_reused'")
    assert json.loads(record["detail"]) == {
        "evidence_run_id": runs[0]["id"],
        "repair_run_id": runs[1]["id"],
    }
    timing = json.loads(
        app.store.one("SELECT detail FROM audit WHERE action='answer_timing'")["detail"]
    )
    assert timing["retries"]["presentation"] == 1
    assert timing["attempts"] == 2
    assert not any(c[0] == "post" for c in app.slack.calls)
    assert app.store.inquiry(ref[0])["status"] == "awaiting_approval"


def test_verified_implementation_read_is_reused_only_for_wording(app):
    first = result("승인 후 저장하면 돼.")
    first.output["verification"] = {
        "question_kind": "implementation",
        "required": ["code"],
        "missing": [],
        "checks": [{"kind": "code", "receipt": "code1", "source_index": 0}],
    }
    first.reads = {"code1": {"code"}}
    second = result("승인 후 저장하시면 됩니다.", reads={})
    second.output["verification"] = copy.deepcopy(first.output["verification"])
    app.engine.results = [first, second]
    ready(app, text="<@UBOT> 실제 코드에서 언제 저장하나요?")
    assert payload(app.engine.calls[1][0])["verified_answer"]["verification"]["required"] == [
        "code"
    ]


@pytest.mark.parametrize("changed", ["sources", "verification", "status", "lookup"])
def test_presentation_repair_cannot_replace_proven_metadata(app, changed):
    corrected = result(reads={})
    if changed == "sources":
        corrected.output["sources"][0]["title"] = "다른 자료"
    elif changed == "verification":
        corrected.output["verification"]["checks"][0]["receipt"] = "other"
    elif changed == "status":
        corrected.output["status"] = "cannot_answer"
    else:
        corrected.output["lookup"] = {"reason": "확인이 필요합니다."}
    app.engine.results = [result("하면 돼."), corrected]
    row = process(app)
    assert row["status"] == "failed"
    assert not app.store.current(row)
    assert not app.store.one("SELECT * FROM audit WHERE action='presentation_evidence_reused'")


@pytest.mark.parametrize("missing", ["read", "skill", "live"])
def test_repair_never_waives_missing_or_live_investigation(app, missing):
    first = result("하면 돼.")
    if missing == "read":
        first.reads = {}
    elif missing == "skill":
        first.activated = set()
    else:
        first.output["verification"]["required"].append("live")
        first.output["verification"]["checks"].append(
            {"kind": "live", "receipt": "live1", "source_index": 0}
        )
        first.reads = {**READS, "live1": {"live"}}
    second = result(reads={})
    second.output["verification"] = copy.deepcopy(first.output["verification"])
    app.engine.results = [first, second]
    row = process(app)
    assert "verified_answer" not in payload(app.engine.calls[1][0])
    assert row["status"] == "failed"


def test_repair_still_requires_current_style_skill_activation(app):
    corrected = result(reads={})
    corrected.activated = set()
    app.engine.results = [result("하면 돼."), corrected]
    row = process(app)
    assert row["error"] == "required_skill_not_activated"
    assert not app.store.current(row)


def test_lost_session_discards_presentation_receipts(app):
    app.engine.results = [result("하면 돼."), BotError("session_not_found"), result(reads={})]
    row = process(app)
    assert len(app.engine.calls) == 3
    assert app.engine.calls[2][1] is None
    assert "verified_answer" not in payload(app.engine.calls[2][0])
    assert row["status"] == "failed"


def test_another_question_cannot_reuse_prior_repair_evidence(app):
    app.engine.results = [result("하면 돼."), result(reads={}), result(reads={}), result(reads={})]
    ready(app)
    ident = receive(app.store, app.cfg, event(ts="1002.000001", thread_ts="1001.000001"), "UBOT")
    app.worker.run_once()
    assert app.store.inquiry(ident)["status"] == "failed"
    assert all("verified_answer" not in payload(call[0]) for call in app.engine.calls[2:])


def test_metrics_failure_does_not_fail_an_answer(app):
    audit = app.store.audit

    def failing(ident, actor, action, detail=None):
        if action == "answer_timing":
            raise OSError("synthetic metrics failure")
        return audit(ident, actor, action, detail)

    app.store.audit = failing
    ready(app)


def test_failed_inquiry_has_timing_without_question_text(app):
    app.engine.results = [BotError("engine_timeout")]
    row = process(app)
    assert row["status"] == "failed"
    report = app.store.one("SELECT detail FROM audit WHERE action='answer_timing'")["detail"]
    assert safe_profile(report)["attempts"] == 1
    assert row["question_text"] not in report
