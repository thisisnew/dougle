"""Private read-only usage reporting without question or result contents."""

import json

import pytest
from test_runtime import event

from dougle_bot.config import save
from dougle_bot.intake import receive
from dougle_bot.main import usage_report
from dougle_bot.progress import Progress
from dougle_bot.safety import BotError


def test_usage_report_groups_actual_cost_and_complete_token_categories(app):
    path = app.cfg.home / "config.toml"
    save(app.cfg, path)
    inquiry = receive(app.store, app.cfg, event(text="<@UBOT> 비공개 합성 질문"), "UBOT")
    run, _ = app.store.reserve(inquiry, 3.0, 30.0, False)
    app.store.finish_run(run, cost=0.25, ok=True, duration=500)
    progress = Progress(clock=lambda: 1.0)
    progress.attempt = 1
    progress.usage({
        "input_tokens": 100,
        "output_tokens": 20,
        "cache_read_input_tokens": 200,
        "cache_creation_input_tokens": 30,
    })
    app.store.audit(inquiry, "system", "answer_timing", json.dumps(progress.profile()))
    result = usage_report(app.cfg.home, path, "1970-01-01")
    assert result["total_cost_usd"] == 0.25
    assert result["cost_complete"] is True
    assert result["inquiries"] == [{
        "inquiry_id": inquiry,
        "attempts": 1,
        "cost_usd": 0.25,
        "usage": {
            "input_tokens": 100,
            "output_tokens": 20,
            "cache_read_input_tokens": 200,
            "cache_creation_input_tokens": 30,
        },
    }]
    assert "비공개 합성 질문" not in str(result)


def test_usage_report_rejects_bad_day(app):
    path = app.cfg.home / "config.toml"
    save(app.cfg, path)
    with pytest.raises(BotError, match="invalid_usage_day"):
        usage_report(app.cfg.home, path, "2026-11-99")


def test_usage_report_includes_timing_logged_after_utc_midnight(app):
    path = app.cfg.home / "config.toml"
    save(app.cfg, path)
    app.now[0] = 86399.0
    inquiry = receive(app.store, app.cfg, event(text="<@UBOT> 자정 합성 질문"), "UBOT")
    run, _ = app.store.reserve(inquiry, 3.0, 30.0, False)
    app.store.finish_run(run, cost=0.1, ok=True, duration=500)
    progress = Progress(clock=lambda: 1.0)
    progress.attempt = 1
    progress.usage({
        "input_tokens": 9, "output_tokens": 3,
        "cache_read_input_tokens": 0, "cache_creation_input_tokens": 0,
    })
    app.now[0] = 86401.0
    app.store.audit(inquiry, "system", "answer_timing", json.dumps(progress.profile()))
    result = usage_report(app.cfg.home, path, "1970-01-01")
    assert result["inquiries"][0]["usage"]["input_tokens"] == 9


def test_usage_report_does_not_call_partial_cost_a_total(app):
    path = app.cfg.home / "config.toml"
    save(app.cfg, path)
    inquiry = receive(app.store, app.cfg, event(text="<@UBOT> 비용 누락 합성 질문"), "UBOT")
    run, _ = app.store.reserve(inquiry, 3.0, 30.0, False)
    app.store.finish_run(run, cost=None, ok=False, duration=500)
    result = usage_report(app.cfg.home, path, "1970-01-01")
    assert result["cost_complete"] is False
    assert result["total_cost_usd"] is None
    assert result["known_cost_usd"] == 0
