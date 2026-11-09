"""The optional Claude settings isolation must preserve required bot capabilities."""

import json
from dataclasses import replace

import pytest

from dougle_bot.config import Config, EngineConfig, SlackConfig, load, save
from dougle_bot.engine import ClaudeCliEngine
from dougle_bot.safety import BotError


def plugin_dir(tmp_path, *, name="dougle"):
    plugin = tmp_path / "plugin"
    (plugin / ".claude-plugin").mkdir(parents=True)
    (plugin / ".claude-plugin" / "plugin.json").write_text(json.dumps({"name": name}))
    for skill in ("dougle-ask", "i-have-adhd", "plain-language"):
        target = plugin / "skills" / skill
        target.mkdir(parents=True)
        (target / "SKILL.md").write_text("# Synthetic skill\n")
    return plugin


def test_default_cli_keeps_existing_settings_and_connectors(tmp_path):
    command = ClaudeCliEngine(EngineConfig(), tmp_path).command(
        None, 1, tmp_path / "settings.json"
    )
    assert "--setting-sources" not in command
    assert "--plugin-dir" not in command
    assert "--strict-mcp-config" not in command
    assert "mcp__claude_ai_Google_Cloud_BigQuery__execute_sql_readonly" in command[
        command.index("--allowedTools") + 1
    ]


def test_opt_in_isolates_settings_with_explicit_required_plugin(tmp_path):
    plugin = plugin_dir(tmp_path)
    engine_config = replace(
        EngineConfig(), isolated_cli=True, isolated_plugin_dir=str(plugin)
    )
    config = Config(SlackConfig("UAPPROVER", ["CTEST"]), engine_config, tmp_path)
    config.validate()
    path = tmp_path / "config.toml"
    save(config, path)
    assert load(path) == config

    settings = tmp_path / "call" / "settings.json"
    settings.parent.mkdir()
    (settings.parent / "github_mcp.json").write_text("{}")
    for probe in (False, True):
        command = ClaudeCliEngine(engine_config, tmp_path, startup_probe=probe).command(
            None, 1, settings
        )
        assert command[command.index("--setting-sources") + 1] == ""
        assert command[command.index("--plugin-dir") + 1] == str(plugin.resolve())
        assert command[command.index("--settings") + 1] == str(settings)
        assert "--strict-mcp-config" not in command
        assert "Skill" in command[command.index("--allowedTools") + 1]
        if not probe:
            assert command[command.index("--mcp-config") + 1] == str(
                settings.parent / "github_mcp.json"
            )


@pytest.mark.parametrize("change", [
    {"isolated_cli": True},
    {"isolated_cli": "true"},
    {"isolated_plugin_dir": "plugin"},
    {"isolated_cli": True, "isolated_plugin_dir": "plugin"},
])
def test_invalid_isolation_config_fails_closed(tmp_path, change):
    config = Config(
        SlackConfig("UAPPROVER", ["CTEST"]),
        replace(EngineConfig(), **change),
        tmp_path,
    )
    with pytest.raises(BotError, match="invalid_config"):
        config.validate()


def test_isolation_requires_same_plugin_namespace_and_all_skills(tmp_path):
    plugin = plugin_dir(tmp_path, name="other")
    config = Config(
        SlackConfig("UAPPROVER", ["CTEST"]),
        replace(EngineConfig(), isolated_cli=True, isolated_plugin_dir=str(plugin)),
        tmp_path,
    )
    with pytest.raises(BotError, match="invalid_config"):
        config.validate()
    (plugin / ".claude-plugin" / "plugin.json").write_text('{"name":"dougle"}')
    (plugin / "skills" / "plain-language" / "SKILL.md").unlink()
    with pytest.raises(BotError, match="invalid_config"):
        config.validate()
