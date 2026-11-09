"""Startup gating with fake services; no company credentials, network or real Claude."""

import json
import subprocess
import time
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace

import pytest
from test_answer_style import SESSION

from dougle_bot import main, runtime
from dougle_bot.config import save
from dougle_bot.engine import ClaudeCliEngine, EngineResult, FakeEngine, parse_trace
from dougle_bot.guard import permitted
from dougle_bot.safety import BotError
from dougle_bot.secrets import FakeSecretStore
from dougle_bot.startup import check_engine, connection_snapshot, latest_report, report


def result(app, *, connections=None, activated=None, output=None):
    return EngineResult(
        SESSION,
        {"ready": True} if output is None else output,
        0.02,
        set(app.cfg.engine.required_skills) if activated is None else activated,
        connections=connections,
    )


def test_startup_uses_real_skill_receipts_not_model_claim(app):
    engine = FakeEngine([result(app, activated=set())])
    with pytest.raises(BotError, match="required_skills"):
        check_engine(app.cfg.engine, app.store, engine)
    assert latest_report(app.store)["phase"] == "required_skills"
    run = app.store.one("SELECT * FROM engine_runs")
    assert run["ok"] == 0 and run["finished"] == 1 and run["cost_usd"] == 0.02
    assert run["inquiry_id"] is None


@pytest.mark.parametrize("missing", range(3))
def test_every_required_skill_is_mandatory_even_with_optional_override(app, missing):
    skills = set(app.cfg.engine.required_skills)
    skills.remove(app.cfg.engine.required_skills[missing])
    with pytest.raises(BotError, match="required_skills"):
        check_engine(
            app.cfg.engine,
            app.store,
            FakeEngine([result(app, activated=skills)]),
            allow_unverified_readers=True,
        )


def test_bounded_probe_does_not_resume_a_customer_session(app):
    engine = FakeEngine([result(app)])
    value = check_engine(app.cfg.engine, app.store, engine)
    assert value["phase"] == "ready" and value["connections"] is None
    assert engine.calls[0][1:] == (None, app.cfg.engine.max_budget_usd_per_call)
    assert engine.timeouts == [None]
    assert all(skill in engine.calls[0][0] for skill in app.cfg.engine.required_skills)
    assert not app.store.all("SELECT * FROM threads")
    assert not app.store.all("SELECT * FROM inquiries")


def test_startup_probe_can_exceed_old_fixed_half_dollar_cap(app):
    engine = FakeEngine([result(app)])
    check_engine(app.cfg.engine, app.store, engine)
    assert engine.calls[0][2] is None


def test_startup_probe_uses_configured_call_cap(app):
    cfg = replace(app.cfg.engine, max_budget_usd_per_call=1.75)
    engine = FakeEngine([result(app)])
    check_engine(cfg, app.store, engine)
    assert engine.calls[0][2] == 1.75


def test_startup_reports_configured_budget_failure(app):
    with pytest.raises(BotError, match="engine_budget_exceeded"):
        check_engine(app.cfg.engine, app.store, FakeEngine([BotError("engine_budget_exceeded")]))
    assert latest_report(app.store)["phase"] == "engine_budget_exceeded"


def test_claude_budget_result_has_safe_specific_error():
    raw = json.dumps({"type": "result", "subtype": "error_max_budget_usd", "is_error": True})
    with pytest.raises(BotError, match="engine_budget_exceeded"):
        parse_trace(raw.encode())


@pytest.mark.parametrize(
    "failure",
    [
        "engine_timeout",
        "engine_launch_failed",
        "engine_failed",
        "invalid_engine_output",
        "bot_stopped",
    ],
)
def test_failed_probe_safe_report_and_no_unknown_cost_as_zero(app, failure):
    with pytest.raises(BotError):
        check_engine(app.cfg.engine, app.store, FakeEngine([BotError(failure)]))
    row = app.store.one("SELECT * FROM engine_runs")
    assert row["finished"] == 1 and row["cost_usd"] is None
    assert row["reserved"] == 0  # No configured reservation; observed cost remains unknown.
    assert latest_report(app.store)["phase"] == (
        "bot_stopped" if failure == "bot_stopped" else "engine_check"
    )


def test_unexpected_failure_never_records_raw_exception(app):
    with pytest.raises(BotError, match="engine_check"):
        check_engine(app.cfg.engine, app.store, FakeEngine([ValueError("xoxb-sensitive-value")]))
    assert "xoxb-sensitive-value" not in json.dumps(app.store.all("SELECT * FROM audit"))


def test_daily_budget_blocks_probe_and_is_shared_with_questions(app):
    cfg = replace(app.cfg.engine, daily_budget_usd=0.5)
    engine = FakeEngine([BotError("engine_timeout"), result(app)])
    with pytest.raises(BotError):
        check_engine(cfg, app.store, engine)
    with pytest.raises(BotError, match="daily_budget_exceeded"):
        check_engine(cfg, app.store, engine)
    assert len(engine.calls) == 1
    with pytest.raises(BotError, match="daily_budget_exceeded"):
        app.store.reserve(None, 3, 0.5, False)


@pytest.mark.parametrize(
    "output",
    [{}, {"ready": False}, {"ready": 1}, {"ready": True, "extra": "ignored?"}, None, "ready"],
)
def test_model_result_must_be_exact(app, output):
    value = result(app)
    value.output = output
    with pytest.raises(BotError, match="engine_check"):
        check_engine(app.cfg.engine, app.store, FakeEngine([value]))


def reader_cfg(app):
    return replace(
        app.cfg.engine, allowed_tools=["Skill", "Read", "mcp__bigquery__execute_sql_readonly"]
    )


@pytest.mark.parametrize("state", ["failed", "needs-auth", "pending", "disabled", "unknown"])
def test_actual_configured_optional_connector_requires_explicit_choice(app, state):
    connection = [{"name": "bigquery", "status": state}]
    engine = FakeEngine([result(app, connections=connection), result(app, connections=connection)])
    with pytest.raises(BotError, match="optional_connections"):
        check_engine(reader_cfg(app), app.store, engine)
    blocked = latest_report(app.store)
    assert blocked["connections"] == connection
    assert not blocked["continued_without_verification"]
    ready = check_engine(reader_cfg(app), app.store, engine, allow_unverified_readers=True)
    assert ready["continued_without_verification"]
    assert len(engine.calls) == 2  # A fresh startup checks again; no stale success cache.


@pytest.mark.parametrize(
    "connections",
    [
        None,
        [],
        [{"name": "other", "status": "failed"}],
        [{"name": "bigquery", "status": "connected"}],
    ],
)
def test_absent_default_or_unrelated_connections_do_not_block(app, connections):
    value = check_engine(
        reader_cfg(app), app.store, FakeEngine([result(app, connections=connections)])
    )
    assert value["phase"] == "ready" and not value["continued_without_verification"]
    assert "실제 읽기 권한" in value["scope"]


def test_init_metadata_not_model_output_defines_connection_status(app):
    data = [
        {
            "type": "system",
            "subtype": "init",
            "mcp_servers": [{"name": "bigquery", "status": "failed", "error": "xoxb-secret"}],
        },
        {
            "type": "result",
            "subtype": "success",
            "session_id": SESSION,
            "total_cost_usd": 0.01,
            "structured_output": {
                "ready": True,
                "mcp_servers": [{"name": "bigquery", "status": "connected"}],
            },
        },
    ]
    parsed = parse_trace("\n".join(json.dumps(item) for item in data).encode())
    assert parsed.connections == [{"name": "bigquery", "status": "failed"}]
    assert "xoxb-secret" not in repr(parsed)


def test_connection_snapshot_sanitizes_names_statuses_and_duplicate_health():
    raw = [
        None,
        {"name": "xoxb-secret", "status": "failed"},
        {"name": "https://private/secret", "status": "connected"},
        {"name": "bigquery", "status": "failed", "config": "password=secret"},
        {"name": "bigquery", "status": "connected"},
        {"name": "slack", "status": "error: xoxb-secret"},
    ]
    assert connection_snapshot(raw) == [
        {"name": "bigquery", "status": "failed"},
        {"name": "slack", "status": "unknown"},
    ]
    assert connection_snapshot({}) is None


@pytest.mark.parametrize(
    "name,args",
    [
        ("Read", {"file_path": "/business/file"}),
        ("Grep", {"path": "/business"}),
        ("Glob", {"path": "/business"}),
        ("Edit", {"file_path": "~/.dougle/knowledge.md"}),
        ("Write", {"file_path": "~/.dougle/knowledge.md"}),
        ("Bash", {}),
        ("Agent", {}),
        ("ToolSearch", {}),
        ("WebFetch", {}),
        ("mcp__bigquery__execute_sql_readonly", {}),
        ("mcp__slack__send_message", {}),
        ("Skill", {"skill": "unrequested"}),
    ],
)
def test_startup_policy_cannot_query_or_modify_business_sources(app, name, args):
    policy = {"startup_probe": True, "skills": app.cfg.engine.required_skills, "allowed": [name]}
    assert not permitted(policy, name, args)


def test_probe_command_and_guard_preserve_alias_cwd_and_normal_policy(app):
    cfg = replace(app.cfg.engine, claude_bin="claude-work")
    probe = ClaudeCliEngine(cfg, app.cfg.home, startup_probe=True)
    args = probe.command(None, 0.5, app.cfg.home / "policy.json")
    assert args[:5] == ["/bin/zsh", "-lic", 'claude-work "$@"', "dougle-launch", "-p"]
    assert args[args.index("--tools") + 1] == "Skill"
    assert "--max-turns" not in args
    assert "--resume" not in args
    assert json.loads(args[args.index("--json-schema") + 1])["required"] == ["ready"]
    policy = {"startup_probe": True, "skills": cfg.required_skills}
    assert all(permitted(policy, "Skill", {"skill": name}) for name in cfg.required_skills)
    assert permitted(policy, "StructuredOutput", {})
    normal = ClaudeCliEngine(cfg, app.cfg.home).command(SESSION, 3, app.cfg.home / "policy.json")
    assert "Read" in normal[normal.index("--tools") + 1] and "--resume" in normal


def test_status_is_read_only_and_exposes_blocker_without_another_probe(app, monkeypatch):
    path = app.cfg.home / "config.toml"
    save(app.cfg, path)
    report(app.store, "required_skills")
    monkeypatch.setattr(main, "doctor", lambda *a, **k: pytest.fail("status called doctor"))
    value = main.status(app.cfg.home, path)
    assert value["state"] == "blocked" and not value["healthy"]
    assert value["startup"]["phase"] == "required_skills"
    assert not app.store.all("SELECT * FROM engine_runs")


def test_live_checking_is_not_healthy_even_with_recent_heartbeat(app):
    path = app.cfg.home / "config.toml"
    save(app.cfg, path)
    app.now[0] = time.time()
    app.store.begin_run(123, "TTEST", "UBOT")
    app.store.heartbeat(True)
    report(app.store, "checking")
    with runtime.InstanceLock(app.cfg.home / "runtime.lock"):
        value = main.status(app.cfg.home, path)
    assert value["state"] == "checking" and not value["healthy"]


def install_fake_runtime(app, monkeypatch, outcome, *, slack_failure=False, socket_failure=False):
    import slack_bolt.adapter.socket_mode.builtin as socket_module

    events = []
    monkeypatch.setattr(runtime, "apple_silicon", lambda: True)
    monkeypatch.setattr(runtime.signal, "signal", lambda *a: None)

    class Gateway:
        def __init__(self, token):
            pass

        def identity(self):
            if slack_failure:
                raise BotError("invalid_auth")
            return {"team_id": "TTEST", "user_id": "UBOT"}

        def call(self, method, **kw):
            events.append("channel_check")

    class Handler:
        def __init__(self, *a, **k):
            self.client = self

        def connect(self):
            events.append("socket")
            if socket_failure:
                raise BotError("invalid_auth")

        def is_connected(self):
            return True

        def close(self):
            events.append("close")

    class Worker:
        def __init__(self, store, *a):
            self.store = store

        def notify_recovered(self):
            events.append("notify")

        def run_once(self):
            events.append("work")
            self.store.stop()
            return True

    def engine(config, home, **kwargs):
        if kwargs.get("startup_probe"):
            events.append("probe")
            return FakeEngine([outcome])
        events.append("normal_engine")
        return object()

    monkeypatch.setattr(runtime, "BoltSlackGateway", Gateway)
    monkeypatch.setattr(runtime, "ClaudeCliEngine", engine)
    monkeypatch.setattr(runtime, "Worker", Worker)
    monkeypatch.setattr(runtime, "Actions", lambda *a: None)
    monkeypatch.setattr(runtime, "build_app", lambda *a: events.append("app"))
    monkeypatch.setattr(socket_module, "SocketModeHandler", Handler)
    return events


@pytest.mark.parametrize("failure", ["engine_failed", "engine_timeout", "bot_stopped"])
def test_direct_run_cannot_bypass_preflight_or_start_intake_on_failure(app, monkeypatch, failure):
    events = install_fake_runtime(app, monkeypatch, BotError(failure))
    with pytest.raises(BotError):
        runtime.serve(app.cfg, FakeSecretStore({"bot": "xoxb-synthetic", "app": "xapp-synthetic"}))
    assert events == ["channel_check", "channel_check", "probe"]
    assert not runtime.running(app.cfg.home)
    assert app.store.control()["desired"] == "stopped"
    assert latest_report(app.store)["phase"] in ("engine_check", "bot_stopped")


def test_slack_auth_failure_never_calls_ai(app, monkeypatch):
    events = install_fake_runtime(app, monkeypatch, result(app), slack_failure=True)
    with pytest.raises(BotError, match="slack_check"):
        runtime.serve(app.cfg, FakeSecretStore({"bot": "xoxb-synthetic", "app": "xapp-synthetic"}))
    assert not events
    assert latest_report(app.store)["phase"] == "slack_check"


def test_socket_failure_after_probe_does_not_process_questions(app, monkeypatch):
    events = install_fake_runtime(app, monkeypatch, result(app), socket_failure=True)
    with pytest.raises(BotError, match="slack_check"):
        runtime.serve(app.cfg, FakeSecretStore({"bot": "xoxb-synthetic", "app": "xapp-synthetic"}))
    assert "work" not in events and "notify" not in events and events[-1] == "close"
    assert latest_report(app.store)["phase"] == "slack_check"


def test_success_checks_before_listening_and_work_and_cleans_up(app, monkeypatch):
    events = install_fake_runtime(app, monkeypatch, result(app, connections=[]))
    runtime.serve(app.cfg, FakeSecretStore({"bot": "xoxb-synthetic", "app": "xapp-synthetic"}))
    assert (
        events.index("probe")
        < events.index("socket")
        < events.index("notify")
        < events.index("work")
    )
    assert events[-1] == "close" and not runtime.running(app.cfg.home)
    assert latest_report(app.store)["phase"] == "connected"
    assert app.store.one("SELECT COUNT(*) n FROM engine_runs")["n"] == 1


def test_start_flag_is_explicit_transient_and_forwarded(app, monkeypatch):
    path = app.cfg.home / "config.toml"
    save(app.cfg, path)
    original = path.read_bytes()
    monkeypatch.setattr(
        main,
        "doctor",
        lambda *a, **k: dict(
            bot_token="present",
            app_token="present",
            engine="found",
            slack_connection="read_verified",
            channels="ok",
            macos="ok",
            apple_silicon="ok",
        ),
    )
    calls = []
    monkeypatch.setattr(
        main.subprocess,
        "Popen",
        lambda args, **k: calls.append(args) or SimpleNamespace(poll=lambda: 1),
    )
    for extra in ([], ["--allow-unverified-readers"]):
        args = main.parser().parse_args(["--home", str(app.cfg.home), "start", *extra])
        value = main.execute(args, FakeSecretStore())
        assert value["started"] is False
        assert ("--allow-unverified-readers" in calls[-1]) == bool(extra)
    assert path.read_bytes() == original


def test_stop_during_slack_check_is_not_undone_by_begin_run(app, monkeypatch):
    events = install_fake_runtime(app, monkeypatch, result(app))
    base = runtime.BoltSlackGateway

    class StoppingGateway(base):
        def identity(self):
            app.store.stop()
            return super().identity()

    monkeypatch.setattr(runtime, "BoltSlackGateway", StoppingGateway)
    with pytest.raises(BotError, match="bot_stopped"):
        runtime.serve(app.cfg, FakeSecretStore({"bot": "xoxb-synthetic", "app": "xapp-synthetic"}))
    assert "probe" not in events and "socket" not in events
    assert app.store.control()["desired"] == "stopped"


def test_stop_and_start_transition_compare_atomically(app):
    app.store.begin_startup(123)
    app.store.stop()
    with pytest.raises(BotError, match="bot_stopped"):
        app.store.begin_run(123, "TTEST", "UBOT", require_starting=True)
    assert app.store.control()["desired"] == "stopped"


def test_probe_through_owned_fake_process_policy_and_stream(app, tmp_path):
    from test_boundaries import fake_cli

    events = [
        {
            "type": "system",
            "subtype": "init",
            "mcp_servers": [{"name": "bigquery", "status": "connected"}],
        }
    ]
    for number, skill in enumerate(app.cfg.engine.required_skills):
        ident = f"skill-{number}"
        events.extend(
            [
                {
                    "type": "assistant",
                    "message": {
                        "content": [
                            {
                                "type": "tool_use",
                                "id": ident,
                                "name": "Skill",
                                "input": {"skill": skill},
                            }
                        ]
                    },
                },
                {
                    "type": "user",
                    "message": {
                        "content": [
                            {
                                "type": "tool_result",
                                "tool_use_id": ident,
                                "content": "Launching skill: " + skill,
                            }
                        ]
                    },
                },
            ]
        )
    events.append(
        {
            "type": "result",
            "subtype": "success",
            "session_id": SESSION,
            "total_cost_usd": 0.03,
            "structured_output": {"ready": True},
        }
    )
    payload = "\n".join(json.dumps(item) for item in events)
    script = fake_cli(
        tmp_path,
        'import sys\ntext=sys.stdin.read()\nassert "시작 점검" in text\nassert text not in sys.argv\nprint('
        + repr(payload)
        + ")\n",
    )
    cfg = replace(reader_cfg(app), claude_bin=script)
    captured = []

    def launch(args, **kwargs):
        settings = Path(args[args.index("--settings") + 1])
        policy = settings.parent / "tools.json"
        assert settings.stat().st_mode & 0o777 == 0o600
        assert policy.stat().st_mode & 0o777 == 0o600
        captured.append((settings, json.loads(policy.read_text())))
        return subprocess.Popen(args, **kwargs)

    engine = ClaudeCliEngine(cfg, app.cfg.home, startup_probe=True, popen=launch)
    checked = check_engine(cfg, app.store, engine)
    assert checked["phase"] == "ready"
    assert len(captured) == 1
    settings, policy = captured[0]
    assert not settings.exists()
    assert policy["startup_probe"] is True
    assert not permitted(policy, "Read", {"file_path": "/business/private"})
    assert app.store.one("SELECT * FROM engine_runs")["cost_usd"] == 0.03


def test_optional_blocker_prevents_socket_and_next_start_has_no_saved_bypass(app, monkeypatch):
    cfg = replace(app.cfg, engine=reader_cfg(app))
    value = result(app, connections=[{"name": "bigquery", "status": "failed"}])
    events = install_fake_runtime(app, monkeypatch, value)
    secrets = FakeSecretStore({"bot": "xoxb-synthetic", "app": "xapp-synthetic"})
    with pytest.raises(BotError, match="optional_connections"):
        runtime.serve(cfg, secrets)
    assert "socket" not in events
    runtime.serve(cfg, secrets, allow_unverified_readers=True)
    assert latest_report(app.store)["continued_without_verification"]
    events.clear()
    with pytest.raises(BotError, match="optional_connections"):
        runtime.serve(cfg, secrets)
    assert "socket" not in events


@pytest.mark.parametrize(
    "name", ["claude_ai_Google_Cloud_BigQuery", "claude.ai Google Cloud BigQuery"]
)
def test_cloud_connector_name_maps_to_allowed_tool_prefix(app, name):
    engine = FakeEngine([result(app, connections=[{"name": name, "status": "needs-auth"}])])
    with pytest.raises(BotError, match="optional_connections"):
        check_engine(app.cfg.engine, app.store, engine)
    assert latest_report(app.store)["connections"] == [{"name": name, "status": "needs-auth"}]
