import json

import pytest

from dougle_bot.accounting import incremental_cost, report_runs
from dougle_bot.engine import parse_trace
from dougle_bot.safety import BotError


def test_resume_cost_is_delta_across_days_and_not_added_twice(app):
    first, _ = app.store.reserve(None, 3, 30, False)
    app.store.finish_run(first, cost=1.2, session='s', cost_scope='session')
    app.now[0] += 86400
    second, _ = app.store.reserve(None, 3, 30, True)
    app.store.finish_run(second, cost=1.55, session='s', cost_scope='session', usage={'input_tokens': 7})
    assert app.store.one('SELECT cost_usd FROM engine_runs WHERE id=?', (second,))['cost_usd'] == .35
    app.store.finish_run(second, cost=99, session='s', cost_scope='session')
    assert app.store.one('SELECT cost_usd FROM engine_runs WHERE id=?', (second,))['cost_usd'] == .35
    rows = report_runs(app.store, '1970-01-02')
    assert rows[0]['estimated_increment_usd'] == .35
    assert rows[0]['usage']['input_tokens'] == 7


def test_unknown_baseline_reset_and_missing_are_not_free():
    assert incremental_cost(2, 'session', True, None) == (None, 'unknown_baseline')
    assert incremental_cost(1, 'session', True, 2) == (None, 'counter_reset')
    assert incremental_cost(None, 'call', False, None) == (None, 'unknown_cost')


def test_error_result_preserves_usage():
    raw = json.dumps({'type': 'result', 'subtype': 'error_max_budget_usd', 'total_cost_usd': .3,
                      'session_id': 's', 'usage': {'input_tokens': 8}}).encode()
    with pytest.raises(BotError) as err:
        parse_trace(raw)
    assert err.value.accounting['cost'] == .3
    assert err.value.accounting['usage']['input_tokens'] == 8


def test_nonzero_process_preserves_reported_cost_and_usage(tmp_path):
    import sys
    from dataclasses import replace

    from dougle_bot.config import EngineConfig
    from dougle_bot.engine import ClaudeCliEngine
    script = tmp_path / 'fake-cli'
    result = {'type':'result','subtype':'error_max_budget_usd','total_cost_usd':0.8,
              'session_id':'11111111-1111-1111-1111-111111111111','usage':{'input_tokens':12}}
    script.write_text(f'#!{sys.executable}\nimport sys\nsys.stdin.read()\nprint({json.dumps(result)!r})\nsys.exit(1)\n')
    script.chmod(0o700)
    config = replace(EngineConfig(), claude_bin=str(script), workdir=str(tmp_path/'work'))
    policy = tmp_path / 'policy'
    policy.mkdir()
    with pytest.raises(BotError) as error:
        ClaudeCliEngine(config, tmp_path)._run_call('probe', None, None, policy_dir=policy, memory=None)
    assert error.value.accounting['cost'] == 0.8
    assert error.value.accounting['usage']['input_tokens'] == 12
