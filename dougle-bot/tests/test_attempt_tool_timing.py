"""Deterministic local timings; no model calls or external services."""

import json

import pytest

from dougle_bot.progress import MAX_METRIC, Progress, safe_profile


def tool(progress, kind, ident):
    progress.activity({"type": kind, "id": f"{ident:064x}", "stage": "code"})


def test_overlap_snapshots_and_retry_waits_are_separate():
    now = [0.0]
    progress = Progress(clock=lambda: now[0])
    now[0] = 10  # Preparation is outside the attempt.
    progress.phase("engine", attempt=1)
    now[0] = 12
    tool(progress, "tool_start", 1)
    now[0] = 13
    tool(progress, "tool_start", 2)
    now[0] = 15
    tool(progress, "tool_end", 1)
    current = progress.profile()["attempt_tool_timings"][0]
    assert current == dict(
        attempt=1,
        elapsed_ms=5000,
        tool_free_ms=2000,
        started=2,
        finished=1,
        active=1,
        peak_active=2,
        incomplete=0,
        unmatched_results=0,
        dropped_events=0,
        active_ms=3000,
        total_ms=5000,
    )
    assert progress.profile()["attempt_tool_timings"][0] == current
    now[0] = 17
    tool(progress, "tool_end", 2)
    now[0] = 20
    progress.finish_attempt()
    first = progress.profile()["attempt_tool_timings"][0]
    assert first["active_ms"] == 5000
    assert first["total_ms"] == 7000  # Sum includes overlap; active time does not.
    assert first["tool_free_ms"] == 5000
    assert first["finished"] == 2 and first["active"] == 0
    now[0] = 100  # Validation and retry preparation must not inflate attempt one.
    progress.phase("retry", attempt=2)
    now[0] = 103
    progress.finish_attempt()
    profile = progress.profile()
    assert profile["attempt_tool_timings"][0] == first
    second = profile["attempt_tool_timings"][1]
    assert second["elapsed_ms"] == second["tool_free_ms"] == 3000
    assert second["active_ms"] == second["total_ms"] == second["started"] == 0
    assert safe_profile(json.dumps(profile)) == profile


def test_cancel_closes_incomplete_calls_once_and_does_not_leak_into_retry():
    now = [0.0]
    progress = Progress(clock=lambda: now[0])
    progress.phase("engine", attempt=1)
    now[0] = 1
    tool(progress, "tool_start", 1)
    tool(progress, "tool_start", 1)  # Duplicate.
    now[0] = 4
    progress.finish_attempt()
    first = progress.profile()["attempt_tool_timings"][0]
    assert first["started"] == first["incomplete"] == 1
    assert first["finished"] == first["active"] == 0
    assert first["active_ms"] == first["total_ms"] == 3000
    assert first["tool_free_ms"] == 1000
    now[0] = 40
    progress.finish_attempt()
    progress.phase("retry", attempt=2)
    tool(progress, "tool_end", 1)  # Late result cannot complete the closed attempt.
    tool(progress, "tool_end", 2)  # Unknown result: count it without inventing duration.
    tool(progress, "tool_end", 2)
    now[0] = 42
    progress.phase("validation")
    profile = progress.profile()
    assert profile["attempt_tool_timings"][0] == first
    second = profile["attempt_tool_timings"][1]
    assert second["unmatched_results"] == 1
    assert second["started"] == second["total_ms"] == 0
    assert second["tool_free_ms"] == 2000
    assert safe_profile(json.dumps(profile)) == profile


def test_bounds_and_submillisecond_intervals(monkeypatch):
    import dougle_bot.progress as module

    monkeypatch.setattr(module, "MAX_TOOL_IDS", 2)
    monkeypatch.setattr(module, "MAX_ATTEMPTS", 2)
    now = [0.0]
    progress = Progress(clock=lambda: now[0])
    progress.phase("engine", attempt=1)
    tool(progress, "tool_start", 1)
    now[0] = 0.0006
    tool(progress, "tool_end", 1)
    tool(progress, "tool_start", 2)
    now[0] = 0.0012
    tool(progress, "tool_end", 2)
    tool(progress, "tool_start", 3)
    progress.phase("retry", attempt=2)
    first = progress.profile()["attempt_tool_timings"][0]
    assert first["active_ms"] == first["total_ms"] == 1
    assert first["dropped_events"] == 1
    now[0] += MAX_METRIC
    progress.phase("retry", attempt=3)
    profile = progress.profile()
    assert len(profile["attempt_tool_timings"]) == 2
    assert profile["attempt_tool_timings"][1]["tool_free_ms"] == MAX_METRIC
    assert safe_profile(json.dumps(profile)) == profile


def test_legacy_profiles_and_unknown_content_are_safe():
    progress = Progress(clock=lambda: 0.0)
    progress.phase("engine", attempt=1)
    profile = progress.profile()
    legacy = dict(profile)
    legacy.pop("attempt_tool_timings")
    assert safe_profile(json.dumps(legacy)) == legacy
    profile["attempt_tool_timings"][0]["tool_arguments"] = "PRIVATE"
    cleaned = safe_profile(json.dumps(profile))
    assert cleaned is not None
    assert "PRIVATE" not in json.dumps(cleaned)


@pytest.mark.parametrize(
    "key,value",
    [
        ("tool_free_ms", -1),
        ("tool_free_ms", True),
        ("tool_free_ms", "secret"),
        ("tool_free_ms", MAX_METRIC + 1),
        ("tool_free_ms", float("nan")),
        ("elapsed_ms", 1),
        ("active_ms", 1),
        ("started", 1),
        ("peak_active", 1),
        ("attempt", 2),
        ("total_ms", []),
    ],
)
def test_invalid_attempt_metrics_are_rejected(key, value):
    progress = Progress(clock=lambda: 0.0)
    progress.phase("engine", attempt=1)
    profile = progress.profile()
    profile["attempt_tool_timings"][0][key] = value
    assert safe_profile(json.dumps(profile)) is None


def test_new_rows_must_match_existing_attempts():
    progress = Progress(clock=lambda: 0.0)
    progress.phase("engine", attempt=1)
    profile = progress.profile()
    profile["attempt_tool_timings"] = []
    assert safe_profile(json.dumps(profile)) is None
