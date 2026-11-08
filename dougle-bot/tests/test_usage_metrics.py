"""Provider usage is counted once, with unknown values distinct from zero."""

import json

import pytest
from test_answer_style import SESSION, answer
from test_runtime import ready

from dougle_bot.engine import parse_trace, token_usage
from dougle_bot.progress import safe_profile


def test_result_usage_ignores_duplicate_per_model_totals_and_arbitrary_text():
    event = {
        "type": "result",
        "session_id": SESSION,
        "structured_output": answer(),
        "usage": {
            "input_tokens": 10,
            "output_tokens": 2,
            "cache_read_input_tokens": 0,
            "private": "never retained",
        },
        "modelUsage": {"model": {"inputTokens": 10}},
    }
    result = parse_trace(json.dumps(event).encode())
    assert result.usage == {
        "input_tokens": 10,
        "output_tokens": 2,
        "cache_read_input_tokens": 0,
        "cache_creation_input_tokens": None,
    }


@pytest.mark.parametrize("value", [-1, True, 1.5, "100", 10**13, None])
def test_bad_or_absent_token_count_is_unknown(value):
    assert token_usage({"input_tokens": value})["input_tokens"] is None
    assert token_usage(None) is None


def test_worker_records_usage_without_replacing_unknown_with_zero(app):
    from test_latency import result

    first = result("하면 돼.")
    first.usage = {"input_tokens": 10, "output_tokens": 4}
    second = result(reads={})
    app.engine.results = [first, second]
    ready(app)
    report = app.store.one("SELECT detail FROM audit WHERE action='answer_timing'")["detail"]
    profile = safe_profile(report)
    assert profile["attempts"] == 2
    assert profile["prompt_bytes"] >= profile["prompt_characters"]
    assert profile["usage"]["input_tokens"] == {"total": 10, "samples": 1}
    assert profile["usage"]["output_tokens"] == {"total": 4, "samples": 1}
    assert profile["usage"]["cache_read_input_tokens"] == {"total": None, "samples": 0}
    assert profile["usage_reports"] == 2
    assert "private" not in report
