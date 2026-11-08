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
from dougle_bot.progress import MAX_METRIC, USAGE_FIELDS, Progress, StreamProgress, safe_profile
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
    assert emitted == ["activity", "first_output", "search", "activity", "code"]


def tool_event(progress, kind, ident, stage="lookup"):
    progress.activity({"type": kind, "id": f"{ident:064x}", "stage": stage})


def test_overlapping_reads_measure_union_not_sum_and_keep_waiting_stage():
    now = [0.0]
    snapshots = []
    progress = Progress(clock=lambda: now[0], observer=snapshots.append)
    progress.phase("engine", attempt=1)
    now[0] = 1
    tool_event(progress, "tool_start", 1, "code")
    now[0] = 2
    tool_event(progress, "tool_start", 2, "live")
    now[0] = 4
    tool_event(progress, "tool_end", 2)
    assert snapshots[-1]["stage"] == "code"
    progress.activity("thinking")
    assert progress.snapshot()["stage"] == "code"
    current = progress.profile()
    assert current["tool_timing"]["active_ms"] == 3000
    assert current["tool_timing"]["total_ms"] == 5000
    assert current == progress.profile()  # Reading metrics cannot count an interval twice.
    now[0] = 7
    tool_event(progress, "tool_end", 1)
    now[0] = 9
    final = progress.profile()
    assert final["tool_timing"] == dict(
        started=2,
        finished=2,
        active=0,
        peak_active=2,
        incomplete=0,
        unmatched_results=0,
        dropped_events=0,
        active_ms=6000,
        total_ms=8000,
    )
    assert final["tool_responses"] == 2
    assert progress.snapshot()["stage"] == "thinking"
    assert safe_profile(json.dumps(final)) == final
    assert all(
        set(s) == {"stage", "elapsed_seconds", "quiet_seconds", "attempt", "completed_tools"}
        for s in snapshots
    )


def test_duplicate_and_out_of_order_results_never_create_phantom_active_calls():
    now = [0.0]
    progress = Progress(clock=lambda: now[0])
    tool_event(progress, "tool_start", 1, "code")
    now[0] = 1
    tool_event(progress, "tool_start", 1, "live")  # Repeated ID cannot change category/start.
    tool_event(progress, "tool_end", 99)  # Unmatched response, tracked once, no duration.
    tool_event(progress, "tool_end", 99)
    tool_event(progress, "tool_start", 99)
    assert progress.snapshot()["stage"] == "code"
    now[0] = 3
    tool_event(progress, "tool_end", 1)
    now[0] = 5
    tool_event(progress, "tool_end", 1)
    tool_event(progress, "tool_start", 1)
    profile = progress.profile()
    assert profile["tool_timing"]["started"] == profile["tool_timing"]["finished"] == 1
    assert profile["tool_timing"]["unmatched_results"] == 1
    assert profile["tool_timing"]["active"] == 0
    assert profile["tool_timing"]["active_ms"] == profile["tool_timing"]["total_ms"] == 3000
    assert profile["tool_responses"] == 2


def test_attempt_boundaries_close_incomplete_intervals_and_record_first_observations():
    now = [0.0]
    progress = Progress(clock=lambda: now[0])
    now[0] = 2
    progress.phase("engine", attempt=1)
    now[0] = 3
    progress.activity("activity")  # Startup is not assistant output.
    assert progress.profile()["first_output_ms"] is None
    now[0] = 4
    progress.activity("first_output")
    tool_event(progress, "tool_start", 1)
    now[0] = 7
    progress.finish_attempt()
    now[0] = 10
    progress.phase("retry", attempt=2)
    now[0] = 11
    progress.activity("first_output")
    tool_event(progress, "tool_start", 2)
    now[0] = 12
    tool_event(progress, "tool_end", 2)
    progress.phase("validation")
    now[0] = 20
    tool_event(progress, "tool_end", 1)  # A late response cannot resurrect a closed interval.
    profile = progress.profile()
    assert profile["first_output_ms"] == profile["first_tool_ms"] == 4000
    assert profile["attempt_timings"] == [
        dict(attempt=1, elapsed_ms=5000, first_output_ms=2000, first_tool_ms=2000),
        dict(attempt=2, elapsed_ms=2000, first_output_ms=1000, first_tool_ms=1000),
    ]
    assert profile["tool_timing"]["incomplete"] == 1
    assert profile["tool_timing"]["active_ms"] == profile["tool_timing"]["total_ms"] == 4000
    assert profile["tool_responses"] == 1
    assert safe_profile(json.dumps(profile)) == profile


def test_submillisecond_overlap_does_not_round_every_completion():
    now = [0.0]
    progress = Progress(clock=lambda: now[0])
    tool_event(progress, "tool_start", 0)
    for ident in range(1, 101):
        now[0] = ident * 0.0004
        tool_event(progress, "tool_start", ident)
        tool_event(progress, "tool_end", ident - 1)
    now[0] += 0.0004
    tool_event(progress, "tool_end", 100)
    timing = progress.profile()["tool_timing"]
    assert timing["active_ms"] >= 40
    assert timing["total_ms"] >= timing["active_ms"]
    assert safe_profile(json.dumps(progress.profile())) is not None


def test_identity_and_attempt_storage_are_bounded_without_replaying_evicted_ids(monkeypatch):
    import dougle_bot.progress as module

    monkeypatch.setattr(module, "MAX_TOOL_IDS", 2)
    monkeypatch.setattr(module, "MAX_ATTEMPTS", 2)
    progress = Progress()
    progress.phase("engine", attempt=1)
    tool_event(progress, "tool_start", 1)
    tool_event(progress, "tool_end", 2)
    tool_event(progress, "tool_start", 3)
    tool_event(progress, "tool_end", 1)  # Existing calls can still finish at capacity.
    tool_event(progress, "tool_start", 1)
    progress.phase("retry", attempt=2)
    progress.phase("retry", attempt=3)
    profile = progress.profile()
    assert profile["tool_timing"]["started"] == profile["tool_timing"]["finished"] == 1
    assert profile["tool_timing"]["dropped_events"] == 1
    assert len(profile["attempt_timings"]) == 2 and profile["attempts"] == 3
    assert len(progress._seen_tools) == 2


@pytest.mark.parametrize(
    "value",
    [
        None,
        {},
        {"input_tokens": None},
        {"input_tokens": True},
        {"input_tokens": -1},
        {"input_tokens": 1.5},
        {"input_tokens": "9"},
        {"input_tokens": float("nan")},
    ],
)
def test_missing_or_invalid_usage_is_not_a_zero_sample(value):
    progress = Progress()
    progress.phase("engine", attempt=1)
    progress.usage(value)
    profile = progress.profile()
    assert profile["usage_reports"] == 1
    assert profile["usage"] == {key: dict(total=None, samples=0) for key in USAGE_FIELDS}
    assert safe_profile(json.dumps(profile)) == profile


def test_usage_partial_attempts_zero_values_and_utf8_wrapper_bytes():
    progress = Progress()
    progress.phase("engine", attempt=1)
    progress.usage(dict(input_tokens=20, output_tokens=0, cache_read_input_tokens=8))
    progress.phase("retry", attempt=2)  # Exception: no EngineResult and no usage report.
    progress.phase("retry", attempt=3)
    progress.usage(dict(input_tokens=10, cache_creation_input_tokens=0))
    progress.sizes(prompt=2, output=3, prompt_bytes=len("한글".encode()))
    progress.sizes(prompt=4, output=5)  # Legacy caller: does not fabricate a byte sample.
    profile = progress.profile()
    assert profile["usage_reports"] == 2 and profile["attempts"] == 3
    assert profile["usage"] == dict(
        input_tokens=dict(total=30, samples=2),
        output_tokens=dict(total=0, samples=1),
        cache_read_input_tokens=dict(total=8, samples=1),
        cache_creation_input_tokens=dict(total=0, samples=1),
    )
    assert profile["prompt_bytes"] == 6 and profile["prompt_bytes_samples"] == 1
    assert profile["prompt_characters"] == 6 and profile["output_characters"] == 8
    assert safe_profile(json.dumps(profile)) == profile


@pytest.mark.parametrize("second_attempt", ["reported", "unreported", "failed"])
def test_worker_persists_usage_sums_and_sample_coverage_across_retries(app, second_attempt):
    first = result("하면 돼.")  # One correction attempt, after the first usage report.
    first.usage = dict(input_tokens=10, output_tokens=4, cache_read_input_tokens=0)
    second = result(reads={})
    if second_attempt == "reported":
        second.usage = dict(
            input_tokens=7,
            output_tokens=None,
            cache_read_input_tokens=6,
            cache_creation_input_tokens=0,
        )
    elif second_attempt == "failed":
        second = BotError("engine_timeout")
    app.engine.results = [first, second]
    row = process(app)
    assert row["status"] == ("failed" if second_attempt == "failed" else "awaiting_approval")
    profile = safe_profile(
        app.store.one(
            "SELECT detail FROM audit WHERE inquiry_id=? AND action='answer_timing'", (row["id"],)
        )["detail"]
    )
    assert profile["attempts"] == len(app.engine.calls) == 2
    assert profile["usage_reports"] == (1 if second_attempt == "failed" else 2)
    assert profile["prompt_bytes_samples"] == 2
    assert profile["prompt_bytes"] == sum(len(call[0].encode("utf-8")) for call in app.engine.calls)
    assert profile["prompt_characters"] == sum(len(call[0]) for call in app.engine.calls)
    assert profile["usage"] == dict(
        input_tokens=dict(
            total=17 if second_attempt == "reported" else 10,
            samples=2 if second_attempt == "reported" else 1,
        ),
        output_tokens=dict(total=4, samples=1),
        cache_read_input_tokens=dict(
            total=6 if second_attempt == "reported" else 0,
            samples=2 if second_attempt == "reported" else 1,
        ),
        cache_creation_input_tokens=dict(
            total=0 if second_attempt == "reported" else None,
            samples=1 if second_attempt == "reported" else 0,
        ),
    )
    # Returned results without usage and exceptions are both incomplete measurements.
    assert profile["usage"]["output_tokens"]["samples"] < profile["attempts"]
    if second_attempt != "reported":
        assert all(field["samples"] < profile["attempts"] for field in profile["usage"].values())


def test_worker_failure_closes_tool_intervals_before_failure_handling(app, monkeypatch):
    import dougle_bot.worker as worker

    now = [0.0]
    monkeypatch.setattr(
        worker, "Progress", lambda **kwargs: Progress(clock=lambda: now[0], **kwargs)
    )

    def fail_with_active_tool(*args, **kwargs):
        now[0] = 1
        kwargs["on_progress"]("first_output")
        kwargs["on_progress"]({"type": "tool_start", "id": "a" * 64, "stage": "code"})
        now[0] = 4
        raise BotError("engine_timeout")

    original_fail = app.store.fail_job

    def slow_failure_handling(*args, **kwargs):
        now[0] += 100
        return original_fail(*args, **kwargs)

    monkeypatch.setattr(app.engine, "run", fail_with_active_tool)
    monkeypatch.setattr(app.store, "fail_job", slow_failure_handling)
    row = process(app)
    assert row["status"] == "failed" and row["error"] == "engine_timeout"
    profile = safe_profile(
        app.store.one(
            "SELECT detail FROM audit WHERE inquiry_id=? AND action='answer_timing'", (row["id"],)
        )["detail"]
    )
    assert profile["processing_ms"] == 104000
    assert profile["attempt_timings"] == [
        dict(
            attempt=1,
            elapsed_ms=4000,
            first_output_ms=1000,
            first_tool_ms=1000,
        )
    ]
    assert profile["tool_timing"] == dict(
        started=1,
        finished=0,
        active=0,
        peak_active=1,
        incomplete=1,
        unmatched_results=0,
        dropped_events=0,
        active_ms=3000,
        total_ms=3000,
    )
    assert profile["tool_responses"] == profile["usage_reports"] == 0
    assert profile["attempts"] == 1
    assert profile["usage"] == {key: dict(total=None, samples=0) for key in USAGE_FIELDS}


def test_old_profile_remains_readable_without_new_metrics():
    profile = Progress().profile()
    for key in (
        "usage",
        "usage_reports",
        "tool_timing",
        "attempt_timings",
        "first_output_ms",
        "first_tool_ms",
        "prompt_bytes",
        "prompt_bytes_samples",
    ):
        profile.pop(key)
    assert safe_profile(json.dumps(profile)) == profile


def test_new_profile_strips_arbitrary_nested_content_and_bounds_numbers():
    progress = Progress()
    progress.sizes(prompt=10**1000, prompt_bytes=10**1000)
    progress.usage({"input_tokens": 10**1000, "private": "secret"})
    profile = progress.profile()
    assert profile["prompt_bytes"] == profile["prompt_characters"] == MAX_METRIC
    assert profile["usage"]["input_tokens"]["total"] == MAX_METRIC
    profile["usage"]["private"] = "secret"
    profile["usage"]["input_tokens"]["private"] = "secret"
    profile["tool_timing"]["ids"] = ["secret"]
    assert "secret" not in json.dumps(safe_profile(json.dumps(profile)))


@pytest.mark.parametrize(
    "field",
    [
        "usage_total",
        "usage_samples",
        "usage_reports",
        "tool_active",
        "first_output_ms",
        "prompt_bytes",
        "attempt_timings",
    ],
)
def test_new_profile_rejects_invalid_metrics(field):
    profile = Progress().profile()
    if field == "usage_total":
        profile["usage"]["input_tokens"] = dict(total=0, samples=0)
    elif field == "usage_samples":
        profile["usage"]["input_tokens"] = dict(total=1, samples=1)
    elif field == "tool_active":
        profile["tool_timing"]["active"] = 1
    else:
        profile[field] = "private"
    assert safe_profile(json.dumps(profile)) is None


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
