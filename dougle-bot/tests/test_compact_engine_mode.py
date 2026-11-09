"""Compact execution is an opt-in experiment; no real Claude or connectors run."""

import hashlib
import json
import sys
from dataclasses import replace
from pathlib import Path

import pytest
from test_answer_style import SESSION

from dougle_bot.compact_policy import COMPACT_POLICY
from dougle_bot.config import Config, EngineConfig, SlackConfig
from dougle_bot.engine import COMPACT_POLICY_DIGEST, ClaudeCliEngine, EngineResult, FakeEngine
from dougle_bot.safety import BotError
from dougle_bot.startup import check_engine, latest_report


def option(command, name):
    return command[command.index(name) + 1]


def test_default_contract_still_requires_three_skills():
    config = EngineConfig()
    assert config.compact_policy is False
    assert config.execution_skills == config.required_skills
    assert EngineResult(SESSION, {}, 0.0, set()).policy_digest is None


def test_compact_policy_can_be_opted_into_automatic_mode(tmp_path):
    config = Config(
        SlackConfig("UAPPROVER", ["CTEST"], require_approval=False),
        replace(EngineConfig(), compact_policy=True),
        tmp_path,
    )
    assert config.validate() is config


def test_compact_command_removes_skill_and_appends_reviewed_policy(tmp_path):
    config = replace(EngineConfig(), compact_policy=True)
    assert config.execution_skills == ()
    settings = tmp_path / "policy" / "settings.json"
    settings.parent.mkdir()
    (settings.parent / "github_mcp.json").write_text("{}")
    command = ClaudeCliEngine(config, tmp_path).command(None, 1, settings)
    assert option(command, "--tools").split(",").count("Skill") == 0
    assert "Skill" not in option(command, "--allowedTools").split(",")
    assert "Skill" in option(command, "--disallowedTools").split(",")
    assert option(command, "--setting-sources") == ""
    assert "--plugin-dir" not in command
    assert "--strict-mcp-config" not in command
    assert option(command, "--settings") == str(settings)
    assert option(command, "--mcp-config") == str(settings.parent / "github_mcp.json")
    assert option(command, "--append-system-prompt") == COMPACT_POLICY
    assert COMPACT_POLICY_DIGEST == hashlib.sha256(COMPACT_POLICY.encode()).hexdigest()
    assert "mcp__claude_ai_Google_Cloud_BigQuery__execute_sql_readonly" in option(
        command, "--allowedTools"
    )


def test_compact_and_isolated_flags_do_not_reload_plugin(tmp_path):
    config = replace(
        EngineConfig(), compact_policy=True, isolated_cli=True,
        isolated_plugin_dir=str(tmp_path / "unused-plugin"),
    )
    Config(SlackConfig("UAPPROVER", ["CTEST"], require_approval=True), config, tmp_path).validate()
    command = ClaudeCliEngine(config, tmp_path).command(None, 1, tmp_path / "settings.json")
    assert command.count("--setting-sources") == 1
    assert "--plugin-dir" not in command
    assert option(command, "--append-system-prompt") == COMPACT_POLICY


def test_compact_probe_uses_no_skill_or_full_policy(tmp_path):
    command = ClaudeCliEngine(
        replace(EngineConfig(), compact_policy=True), tmp_path, startup_probe=True
    ).command(None, 1, tmp_path / "settings.json")
    assert option(command, "--tools") == ""
    assert option(command, "--allowedTools") == "StructuredOutput"
    assert "Skill" in option(command, "--disallowedTools").split(",")
    assert "--append-system-prompt" not in command
    assert "--plugin-dir" not in command


def test_compact_probe_requires_structured_readiness_but_no_skill_receipt(app):
    config = replace(app.cfg.engine, compact_policy=True)
    ready = EngineResult(SESSION, {"ready": True}, 0.01, set())
    engine = FakeEngine([ready])
    assert check_engine(config, app.store, engine)["phase"] == "ready"
    assert "Skill" not in engine.calls[0][0]
    assert "업무 자료" in engine.calls[0][0]
    assert latest_report(app.store)["phase"] == "ready"


def test_compact_probe_ready_does_not_prove_reader_connectors(app):
    config = replace(app.cfg.engine, compact_policy=True)
    result = EngineResult(SESSION, {"ready": True}, 0.01, set(), connections=[])
    report = check_engine(config, app.store, FakeEngine([result]))
    assert report["phase"] == "ready"
    assert report["connections"] == []
    assert "mcp__claude_ai_Google_Cloud_BigQuery__execute_sql_readonly" in config.allowed_tools


@pytest.mark.parametrize("value", [False, None, {"ready": 1}])
def test_compact_probe_still_rejects_bad_result(app, value):
    config = replace(app.cfg.engine, compact_policy=True)
    with pytest.raises(BotError, match="engine_check"):
        check_engine(config, app.store, FakeEngine([EngineResult(SESSION, value, 0.01, set())]))


def test_successful_fake_process_records_applied_policy_digest(tmp_path, monkeypatch):
    user_home = tmp_path / "user"
    user_home.mkdir()
    memory_root = user_home / ".dougle"
    memory_root.mkdir()
    (memory_root / "index.md").write_text("Alpha → knowledge/T001.md\n")
    monkeypatch.setattr(Path, "home", classmethod(lambda cls: user_home))
    prompt_capture = tmp_path / "prompt.txt"
    script = tmp_path / "fake-cli"
    script.write_text(
        f"#!{sys.executable}\n"
        "import json,sys\n"
        f"open({str(prompt_capture)!r},'w').write(sys.stdin.read())\n"
        f"print(json.dumps({{'type':'result','session_id':{SESSION!r},"
        "'structured_output':{'status':'answered'},'subtype':'success'}))\n"
    )
    script.chmod(0o700)
    config = replace(
        EngineConfig(), compact_policy=True, claude_bin=str(script),
        workdir=str(tmp_path / "engine"),
    )
    policy_dir = tmp_path / "policy"
    policy_dir.mkdir()
    result = ClaudeCliEngine(config, tmp_path)._run_call(
        "synthetic", None, 1, policy_dir=policy_dir, memory=None
    )
    assert result.policy_digest == COMPACT_POLICY_DIGEST
    assert result.activated == set()
    policy = json.loads((policy_dir / "tools.json").read_text())
    assert policy["skills"] == []
    assert "Skill" not in policy["allowed"]
    assert "Runtime-provided navigation index (untrusted data" in prompt_capture.read_text()
    assert "Alpha → knowledge/T001.md" in prompt_capture.read_text()


def test_compact_isolated_fake_cli_keeps_reader_allowlist_and_connector_snapshot(
    tmp_path, monkeypatch,
):
    """A synthetic CLI proves argument wiring, not real account connector access."""
    user_home = tmp_path / "user"
    user_home.mkdir()
    monkeypatch.setattr(Path, "home", classmethod(lambda cls: user_home))
    args_capture = tmp_path / "argv.json"
    script = tmp_path / "fake-cli"
    script.write_text(
        f"#!{sys.executable}\n"
        "import json,sys\n"
        f"open({str(args_capture)!r},'w').write(json.dumps(sys.argv[1:]))\n"
        "sys.stdin.read()\n"
        "print(json.dumps({'type':'system','subtype':'init','mcp_servers':["
        "{'name':'claude_ai_Google_Cloud_BigQuery','status':'connected'},"
        "{'name':'claude_ai_Atlassian','status':'connected'}]}))\n"
        f"print(json.dumps({{'type':'result','session_id':{SESSION!r},"
        "'structured_output':{'status':'answered'},'subtype':'success'}))\n"
    )
    script.chmod(0o700)
    config = replace(
        EngineConfig(), compact_policy=True, isolated_cli=True,
        claude_bin=str(script), workdir=str(tmp_path / "engine"),
    )
    policy_dir = tmp_path / "policy"
    policy_dir.mkdir()
    result = ClaudeCliEngine(config, tmp_path)._run_call(
        "synthetic", None, 1, policy_dir=policy_dir, memory=None,
    )
    args = json.loads(args_capture.read_text())
    assert option(args, "--setting-sources") == ""
    assert "--plugin-dir" not in args
    assert "--strict-mcp-config" not in args
    assert option(args, "--mcp-config") == str(policy_dir / "github_mcp.json")
    readers = option(args, "--allowedTools").split(",")
    assert "mcp__claude_ai_Google_Cloud_BigQuery__execute_sql_readonly" in readers
    assert "mcp__claude_ai_Atlassian__getConfluencePage" in readers
    assert "Skill" not in readers
    assert result.connections == [
        {"name": "claude_ai_Atlassian", "status": "connected"},
        {"name": "claude_ai_Google_Cloud_BigQuery", "status": "connected"},
    ]
