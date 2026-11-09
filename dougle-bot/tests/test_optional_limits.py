from dataclasses import replace

from dougle_bot.config import EngineConfig, load, save
from dougle_bot.engine import ClaudeCliEngine


def test_defaults_have_no_business_limits_and_setup_omits_fields(app, tmp_path):
    for probe in (False, True):
        command = ClaudeCliEngine(EngineConfig(), tmp_path, startup_probe=probe).command(None, None, tmp_path / 'settings.json')
        assert '--max-turns' not in command and '--max-budget-usd' not in command
    path = tmp_path / 'config.toml'
    save(app.cfg, path)
    assert 'max_turns =' not in path.read_text()
    assert load(path).engine.timeout_seconds is None
    for _ in range(3):
        run, amount = app.store.reserve(None, None, None, False)
        assert amount is None
        app.store.finish_run(run, cost=100000)


def test_explicit_limits_survive_and_ttl_effort_are_validated(app, tmp_path):
    cfg = replace(app.cfg, engine=replace(app.cfg.engine, timeout_seconds=900,
        max_turns=201, daily_budget_usd=30, max_budget_usd_per_call=3,
        prompt_cache_ttl='5m', effort='high'))
    path = tmp_path / 'config.toml'
    save(cfg, path)
    restored = load(path)
    assert restored.engine == cfg.engine
    cmd = ClaudeCliEngine(cfg.engine, tmp_path).command(None, 3, tmp_path / 'settings.json')
    assert cmd[cmd.index('--max-turns') + 1] == '201'
    assert cmd[cmd.index('--effort') + 1] == 'high'
