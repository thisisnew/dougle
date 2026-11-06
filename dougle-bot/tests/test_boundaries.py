import json
import sys
from dataclasses import replace
from pathlib import Path

import pytest
from slack_bolt.request import BoltRequest
from slack_sdk import WebClient
from test_answer_style import READS, SESSION, SKILLS, answer, call, outcome, trace
from test_runtime import event, post, ready

from dougle_bot.engine import ClaudeCliEngine
from dougle_bot.guard import permitted
from dougle_bot.prompts import validate_output, validate_report
from dougle_bot.runtime import build_app
from dougle_bot.safety import BotError
from dougle_bot.slack_gateway import BoltSlackGateway


class Deferred:
    def __init__(self):
        self.calls = []

    def submit(self, *args):
        self.calls.append(args)


def bolt(app):
    gw = BoltSlackGateway("xoxb-synthetic", WebClient(token="xoxb-synthetic", retry_handlers=[]))

    # Fail loudly if a test accidentally attempts network access.
    def forbidden(*args, **kwargs):
        pytest.fail("unexpected Slack network call")

    gw.client.api_call = forbidden
    executor = Deferred()
    built = build_app(
        gw,
        "xoxb-synthetic",
        {"team_id": "TTEST", "user_id": "UBOT", "bot_id": "BTEST"},
        app.store,
        app.cfg,
        app.worker,
        app.actions,
        executor,
    )
    return built, executor


def request():
    body = event()
    body.update(type="event_callback", event_id="EvTEST", api_app_id="ATEST")
    return BoltRequest(body=body, mode="socket_mode")


def test_actual_bolt_ack_follows_durable_intake(app):
    built, executor = bolt(app)
    response = built.dispatch(request())
    assert response.status == 200
    assert len(app.store.all("SELECT * FROM inquiries")) == 1
    assert len(executor.calls) == 1  # reaction deferred, not before ack


def test_actual_bolt_does_not_ack_persistence_failure(app, monkeypatch):
    built, _ = bolt(app)

    def disk_full(*args):
        raise OSError("synthetic disk failure")

    monkeypatch.setattr(app.store, "intake", disk_full)
    response = built.dispatch(request())
    assert response.status != 200
    assert not app.store.all("SELECT * FROM processed_events")


@pytest.mark.parametrize(
    "name,args",
    [
        ("Bash", {"command": "git push"}),
        ("mcp__Slack__slack_send_message", {}),
        ("Skill", {"skill": "unknown"}),
        ("Write", {"file_path": "/tmp/business.txt"}),
        ("Edit", {"file_path": "/etc/hosts"}),
        ("Read", {"file_path": "~/.claude/.credentials.json"}),
        ("Read", {"file_path": "~/.codex/auth.json"}),
        ("Read", {"file_path": "~/.ssh/id_ed25519"}),
        ("Grep", {"path": "~/.aws/credentials", "pattern": "."}),
        ("Glob", {}),
    ],
)
def test_hook_blocks_unapproved_tools_and_sensitive_paths(tmp_path, name, args):
    policy = {
        "allowed": ["Skill", "Read", "Grep", "Glob", "Write", "Edit"],
        "skills": list(SKILLS),
        "memory": str(tmp_path / "memory"),
        "runtime_home": str(tmp_path / "runtime"),
    }
    assert not permitted(policy, name, args)


def test_hook_allows_only_memory_writes_and_rejects_symlink_escape(tmp_path):
    memory = tmp_path / "memory"
    memory.mkdir()
    outside = tmp_path / "original"
    outside.mkdir()
    (memory / "link").symlink_to(outside, target_is_directory=True)
    policy = {
        "allowed": ["Write", "Edit", "Skill"],
        "skills": list(SKILLS),
        "memory": str(memory),
        "runtime_home": str(tmp_path / "runtime"),
    }
    assert permitted(policy, "Write", {"file_path": str(memory / "topics" / "safe.md")})
    assert not permitted(policy, "Write", {"file_path": str(memory / "link" / "code.py")})
    assert permitted(policy, "Skill", {"skill": "dougle:i-have-adhd"})


def fake_cli(tmp_path, code):
    script = tmp_path / "fake-claude"
    script.write_text("#!" + sys.executable + "\n" + code)
    script.chmod(0o700)
    return str(script)


def test_real_subprocess_fake_cli_uses_stdin_and_stream_parser(app, tmp_path):
    events = [call(name, f"t{i}") for i, name in enumerate(sorted(SKILLS))] + [
        outcome(f"t{i}", content=f"Launching skill: {name}")
        for i, name in enumerate(sorted(SKILLS))
    ]
    payload = trace(events).decode()
    script = fake_cli(
        tmp_path,
        'import sys\ntext=sys.stdin.read()\nassert text=="synthetic prompt"\nassert "synthetic prompt" not in sys.argv\nprint('
        + repr(payload)
        + ")\n",
    )
    cfg = replace(app.cfg.engine, claude_bin=script)
    result = ClaudeCliEngine(cfg, app.cfg.home).run("synthetic prompt", None, 1.0)
    assert result.activated == SKILLS
    assert result.session_id == SESSION
    assert (app.cfg.home / "policy" / "settings.json").stat().st_mode & 0o777 == 0o600


def test_subprocess_timeout_is_bounded_and_does_not_leak_stderr(app, tmp_path):
    script = fake_cli(
        tmp_path,
        'import sys,time\nsys.stderr.write("xoxb-synthetic-secret")\nsys.stderr.flush()\ntime.sleep(20)\n',
    )
    cfg = replace(app.cfg.engine, claude_bin=script, timeout_seconds=0.15)
    with pytest.raises(BotError, match="engine_timeout") as exc:
        ClaudeCliEngine(cfg, app.cfg.home).run("prompt", None, 1)
    assert "xoxb" not in str(exc.value)


def test_cancelled_engine_does_not_return_draft(app, tmp_path):
    script = fake_cli(tmp_path, "import time\ntime.sleep(20)\n")
    cfg = replace(app.cfg.engine, claude_bin=script)
    with pytest.raises(BotError, match="bot_stopped"):
        ClaudeCliEngine(cfg, app.cfg.home, cancelled=lambda: True).run("prompt", None, 1)


def test_resume_command_keeps_alias_and_prompt_out_of_shell_program(app):
    cfg = replace(app.cfg.engine, claude_bin="claude-work")
    cmd = ClaudeCliEngine(cfg, app.cfg.home).command(SESSION, 1, Path("/tmp/settings.json"))
    assert cmd[:4] == ["/bin/zsh", "-lic", 'claude-work "$@"', "dougle-launch"]
    assert cmd[cmd.index("--resume") + 1] == SESSION
    assert "--permission-mode" in cmd and "dontAsk" in cmd
    assert {"Bash", "Agent", "Task"} <= set(cmd[cmd.index("--disallowedTools") + 1].split(","))


@pytest.mark.parametrize(
    "sql",
    [
        "DELETE FROM inbound",
        "SELECT * FROM inbound; DROP TABLE inbound",
        "SELECT * INTO OUTFILE FROM inbound LIMIT 5",
        "SELECT * FROM inbound",
        "SELECT * FROM inbound LIMIT 1 -- bypass",
        "CALL mutate()",
    ],
)
def test_lookup_never_accepts_unsafe_or_unbounded_sql(sql):
    data = {
        "verification": {
            "question_kind": "current_state",
            "required": ["live"],
            "checks": [],
            "missing": ["live"],
        },
        "status": "needs_lookup",
        "body": "운영 DB 확인이 필요합니다.",
        "sources": [],
        "lookup": {
            "target": "운영 조회",
            "reason": "조회 권한이 필요합니다.",
            "sql": sql,
            "schema_verified": True,
        },
    }
    with pytest.raises(BotError, match="unsafe_lookup_sql"):
        validate_output(data)


def test_needs_lookup_card_has_no_post_button(app):
    from dougle_bot.engine import EngineResult

    app.engine.results = [
        EngineResult(
            SESSION,
            {
                "verification": {
                    "question_kind": "current_state",
                    "required": ["live"],
                    "checks": [],
                    "missing": ["live"],
                },
                "status": "needs_lookup",
                "body": "운영 DB 확인이 필요합니다.",
                "sources": [],
                "lookup": {
                    "target": "운영 조회",
                    "reason": "VDI에서 확인해 주세요.",
                    "sql": "SELECT status FROM inbound LIMIT 5",
                    "schema_verified": True,
                },
            },
            0.02,
            SKILLS,
            READS,
        )
    ]
    from dougle_bot.intake import receive

    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.run_once()
    row = app.store.inquiry(ident)
    assert row["status"] == "needs_lookup"
    card = app.slack.cards[(row["card_channel_id"], row["card_ts"])]
    ids = [e["action_id"] for b in card["blocks"] if b["type"] == "actions" for e in b["elements"]]
    assert "lookup" in ids and "post" not in ids and "edit" not in ids


@pytest.mark.parametrize(
    "observed", ["yesterday", "2026-11-06T15:00:00", "2999-01-01T00:00:00Z", None]
)
def test_lookup_time_requires_explicit_zone_and_not_future(observed):
    with pytest.raises(BotError):
        validate_report("미처리 2건입니다.", observed, 2000)


def test_developer_evidence_requires_attribution():
    with pytest.raises(BotError, match="lookup_attribution_required"):
        validate_output(answer(), lookup_basis=True)
    assert validate_output(
        answer("개발자 조회 결과 기준으로 미처리 2건입니다. [1]"), lookup_basis=True
    )


def test_partial_multimessage_failure_cannot_repost_first_part(app):
    from dougle_bot.engine import EngineResult

    app.engine.results = [
        EngineResult(SESSION, answer("확정 후 저장됩니다. [1]\n" * 400), 0.02, SKILLS, READS)
    ]
    ref = ready(app)
    real = app.slack.post
    count = [0]

    def failing(*args):
        from dougle_bot.slack_gateway import SlackFailure

        count[0] += 1
        if count[0] == 2:
            raise SlackFailure("ratelimited")
        return real(*args)

    app.slack.post = failing
    post(app, ref)
    assert app.store.inquiry(ref[0])["status"] == "post_unknown"
    with pytest.raises(BotError):
        post(app, ref)
    assert len([c for c in app.slack.calls if c[0] == "post"]) == 1


def test_internal_result_and_discovery_tools_do_not_unblock_writes(tmp_path):
    policy = {
        "allowed": ["Skill", "Read"],
        "skills": list(SKILLS),
        "memory": str(tmp_path / "memory"),
        "runtime_home": str(tmp_path / "runtime"),
    }
    assert permitted(policy, "StructuredOutput", answer())
    assert permitted(policy, "ToolSearch", {"query": "slack send"})
    assert not permitted(policy, "mcp__Slack__slack_send_message", {"text": "send"})


def test_parallel_duplicate_events_have_exactly_one_job(app):
    from concurrent.futures import ThreadPoolExecutor

    from dougle_bot.intake import receive

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(lambda _: receive(app.store, app.cfg, event(), "UBOT"), range(100)))
    assert sum(x is not None for x in results) == 1
    assert len(app.store.all("SELECT * FROM inquiries")) == 1
    assert len(app.store.all("SELECT * FROM jobs")) == 1


def test_late_engine_result_does_not_revive_rejected_inquiry(app):
    from dougle_bot.engine import EngineResult

    ref = ready(app)
    row = app.store.inquiry(ref[0])
    # A previously queued job (e.g. after changed thread) can complete after rejection.
    app.store.enqueue(ref[0], row["current_draft_id"], "regenerated", "{}")

    class LateEngine:
        def run(self, *args, **kwargs):
            app.actions.reject("UAPPROVER", ref[1], ref[2], ref[3])
            return EngineResult(SESSION, answer(), 0.02, SKILLS, READS)

    app.worker.engine = LateEngine()
    app.worker.run_once()
    assert app.store.inquiry(ref[0])["status"] == "rejected"
    assert app.store.current(app.store.inquiry(ref[0]))["revision"] == 1
    assert not any(c[0] == "post" for c in app.slack.calls)


def test_followup_resumes_and_includes_published_manual_edit(app):
    from dougle_bot.intake import receive

    ref = ready(app)
    metadata = json.dumps({**json.loads(ref[1]), "channel": ref[2], "ts": ref[3]})
    app.actions.submit("UAPPROVER", metadata, "edit", "취소 후에는 저장하지 않습니다. [1]")
    row = app.store.inquiry(ref[0])
    app.worker.show_card(ref[0])
    app.actions.post(
        "UAPPROVER",
        json.dumps({"inquiry_id": ref[0], "draft_id": row["current_draft_id"]}),
        ref[2],
        ref[3],
    )
    ident = receive(
        app.store,
        app.cfg,
        event(ts="1002.000001", thread_ts="1001.000001", text="<@UBOT> 취소일 때는요?"),
        "UBOT",
    )
    app.worker.run_once()
    assert app.store.inquiry(ident)["status"] == "awaiting_approval"
    assert app.engine.calls[-1][1] == SESSION
    assert "취소 후에는 저장하지 않습니다." in app.engine.calls[-1][0]


@pytest.mark.parametrize(
    "target,reason",
    [
        ("운영 DB", "VDI에서 확인해 주세요."),
        ("Kafka UI", "대상 그룹의 지연 건수와 확인 시각을 읽기만 해서 알려 주세요."),
    ],
)
def test_lookup_result_returns_new_draft_with_timestamp_and_requires_approval(app, target, reason):
    from dougle_bot.engine import EngineResult
    from dougle_bot.intake import receive

    pending = {
        "verification": {
            "question_kind": "current_state",
            "required": ["live"],
            "checks": [],
            "missing": ["live"],
        },
        "status": "needs_lookup",
        "body": "운영 확인이 필요합니다.",
        "sources": [],
        "lookup": {
            "target": target,
            "reason": reason,
            "sql": "",
            "schema_verified": False,
        },
    }
    stamp = "1970-01-01T00:16:00+00:00"
    completed = answer("개발자 조회 결과 기준 (" + stamp + ") 미처리 2건입니다. [1]")
    completed["verification"] = {
        "question_kind": "current_state",
        "required": ["live"],
        "missing": [],
        "checks": [{"kind": "live", "receipt": "developer_report", "source_index": 0}],
    }
    app.engine.results = [
        EngineResult(SESSION, pending, 0.02, SKILLS, READS),
        EngineResult(
            SESSION,
            completed,
            0.02,
            SKILLS,
            READS,
        ),
    ]
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.run_once()
    row = app.store.inquiry(ident)
    card = app.slack.cards[(row["card_channel_id"], row["card_ts"])]
    assert reason in json.dumps(card, ensure_ascii=False)
    assert "스키마를 확인한 뒤" not in json.dumps(card, ensure_ascii=False)
    assert not any(
        button.get("action_id") == "post"
        for block in card["blocks"]
        for button in block.get("elements", [])
    )
    metadata = json.dumps(
        {
            "inquiry_id": ident,
            "draft_id": row["current_draft_id"],
            "channel": row["card_channel_id"],
            "ts": row["card_ts"],
        }
    )
    app.actions.submit("UAPPROVER", metadata, "lookup", "미처리 2건입니다.", stamp)
    app.worker.run_once()
    row = app.store.inquiry(ident)
    assert row["status"] == "awaiting_approval"
    assert app.store.current(row)["revision"] == 2
    assert not any(c[0] == "post" for c in app.slack.calls)
    app.actions.post(
        "UAPPROVER",
        json.dumps({"inquiry_id": ident, "draft_id": row["current_draft_id"]}),
        row["card_channel_id"],
        row["card_ts"],
    )
    assert app.store.inquiry(ident)["status"] == "posted"


@pytest.mark.parametrize(
    "location",
    [
        "javascript://alert(1)",
        "https://user:password@example.test/doc",
        "https://example.test/?token%3Dsynthetic",
    ],
)
def test_unsafe_source_url_blocked(location):
    value = answer()
    value["sources"][0]["location"] = location
    with pytest.raises(BotError):
        validate_output(value)


def test_sensitive_thread_never_reaches_engine_or_draft(app):
    from dougle_bot.intake import receive

    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.slack.messages[("CTEST", "1001.000001")] = [
        {"ts": "1001.000001", "text": "password=synthetic-value"}
    ]
    app.worker.run_once()
    assert not app.engine.calls
    assert app.store.inquiry(ident)["error"] == "sensitive_content"
    assert app.store.current(app.store.inquiry(ident)) is None


def test_database_rejects_cross_inquiry_draft_link(app):
    import sqlite3

    from dougle_bot.intake import receive

    ref = ready(app)
    other = receive(app.store, app.cfg, event(ts="1002.000001"), "UBOT")
    with pytest.raises(sqlite3.IntegrityError), app.store.tx() as db:
        db.execute(
            "UPDATE inquiries SET current_draft_id=? WHERE id=?",
            (json.loads(ref[1])["draft_id"], other),
        )
    assert app.store.inquiry(other)["current_draft_id"] is None


def test_memory_root_symlink_cannot_authorize_business_writes(tmp_path):
    business = tmp_path / "business"
    business.mkdir()
    memory = tmp_path / "memory"
    memory.symlink_to(business, target_is_directory=True)
    policy = {
        "allowed": ["Write"],
        "skills": list(SKILLS),
        "memory": str(memory),
        "runtime_home": str(tmp_path / "runtime"),
    }
    assert not permitted(policy, "Write", {"file_path": str(memory / "source.py")})


def test_other_bot_cannot_supply_positive_delivery_evidence(app):
    from dougle_bot.recovery import reconcile
    from dougle_bot.slack_gateway import SlackFailure

    ref = ready(app)
    app.slack.failures["post_after"] = SlackFailure("slack_connection_failed", uncertain=True)
    post(app, ref)
    app.slack.messages[("CTEST", "1001.000001")][0]["user"] = "UOTHERBOT"
    assert not reconcile(app.store, app.slack, ref[0])
    assert app.store.inquiry(ref[0])["status"] == "post_unknown"


def test_slack_secret_in_unexpected_environment_name_is_not_inherited(app, tmp_path, monkeypatch):
    monkeypatch.setenv("UNRELATED_SETTING", "xoxb-synthetic-env")
    script = fake_cli(
        tmp_path,
        'import os,sys\nassert "UNRELATED_SETTING" not in os.environ\nsys.stdin.read()\nprint('
        + repr(trace().decode())
        + ")\n",
    )
    cfg = replace(app.cfg.engine, claude_bin=script)
    assert ClaudeCliEngine(cfg, app.cfg.home).run("prompt", None, 1).session_id == SESSION


def test_old_database_snapshot_cannot_hide_a_later_post(app):
    import sqlite3

    from dougle_bot.store import Store

    ref = ready(app)
    backup_path = app.cfg.home / "old.db"
    backup = sqlite3.connect(backup_path)
    app.store.db.backup(backup)
    backup.close()
    post(app, ref)
    with pytest.raises(BotError, match="restored_database_requires_review"):
        Store(backup_path)
    assert backup_path.exists()


def test_definite_failure_retry_preserves_external_send_identity(app):
    from dougle_bot.slack_gateway import SlackFailure

    ref = ready(app)
    app.slack.failures["post_before"] = SlackFailure("ratelimited")
    post(app, ref)
    before = app.store.one("SELECT id FROM deliveries")["id"]
    post(app, ref)
    assert app.store.one("SELECT id FROM deliveries")["id"] == before
    app.store.check_post_intents()
    assert app.store.inquiry(ref[0])["status"] == "posted"
