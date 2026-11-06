import json
import sqlite3
from dataclasses import replace
from types import SimpleNamespace

import pytest
from test_answer_style import SESSION, SKILLS, answer

from dougle_bot import cards
from dougle_bot.config import channel_id, load, save
from dougle_bot.engine import EngineResult
from dougle_bot.intake import receive
from dougle_bot.main import execute, parser
from dougle_bot.recovery import reconcile
from dougle_bot.runtime import InstanceLock, running
from dougle_bot.safety import BotError, redact
from dougle_bot.secrets import FakeSecretStore, KeychainSecretStore
from dougle_bot.slack_gateway import BoltSlackGateway, SlackFailure


def event(ts="1001.000001", **updates):
    ev = {
        "type": "app_mention",
        "channel": "CTEST",
        "user": "UASKER",
        "ts": ts,
        "text": "<@UBOT> 입고는 언제 저장하나요?",
    }
    ev.update(updates)
    return {"team_id": "TTEST", "event": ev}


def ready(app, **updates):
    ident = receive(app.store, app.cfg, event(**updates), "UBOT")
    assert ident
    assert app.worker.run_once()
    inquiry = app.store.inquiry(ident)
    assert inquiry["status"] == "awaiting_approval"
    value = json.dumps({"inquiry_id": ident, "draft_id": inquiry["current_draft_id"]})
    return ident, value, inquiry["card_channel_id"], inquiry["card_ts"]


def post(app, ref):
    _, value, channel, ts = ref
    app.actions.post("UAPPROVER", value, channel, ts)


@pytest.mark.parametrize(
    "changes",
    [
        {"user": "UBOT"},
        {"bot_id": "BOTHER"},
        {"channel": "CNOPE"},
        {"user": "not-user"},
        {"type": "message"},
        {"text": "멘션 없는 질문"},
        {"text": "<@UBOT> "},
        {"ts": "999.999999"},
        {"ts": "99999.000001"},
        {"ts": "NaN"},
        {"thread_ts": "bad"},
        {"text": "<@UBOT> xoxb-synthetic-secret"},
        {"text": "<@UBOT> test@example.test"},
    ],
)
def test_intake_ignores_invalid_or_unapproved_messages(app, changes):
    assert receive(app.store, app.cfg, event(**changes), "UBOT") is None
    assert not app.store.all("SELECT * FROM inquiries")


def test_duplicate_intake_is_atomic_and_cross_channel_is_distinct(app):
    first = receive(app.store, app.cfg, event(), "UBOT")
    assert first
    assert receive(app.store, app.cfg, event(), "UBOT") is None
    second = receive(app.store, app.cfg, event(channel="COTHER"), "UBOT")
    assert second != first
    assert len(app.store.all("SELECT * FROM jobs")) == 2
    assert len(app.store.all("SELECT * FROM threads")) == 2


def test_other_workspace_is_ignored(app):
    body = event()
    body["team_id"] = "TOTHER"
    assert receive(app.store, app.cfg, body, "UBOT") is None


def test_stopped_interval_is_never_replayed(app):
    original = receive(app.store, app.cfg, event(), "UBOT")
    app.store.stop()
    assert receive(app.store, app.cfg, event(ts="1002.000001"), "UBOT") is None
    app.now[0] = 1010
    app.store.begin_run(2, "TTEST")
    assert receive(app.store, app.cfg, event(ts="1002.000001"), "UBOT") is None
    assert receive(app.store, app.cfg, event(ts="1011.000001"), "UBOT")
    assert app.store.inquiry(original)["status"] == "queued"
    assert len(app.store.all("SELECT * FROM inquiries")) == 2


def test_stop_blocks_post_and_does_not_delete_state(app):
    ref = ready(app)
    app.store.stop()
    with pytest.raises(BotError, match="bot_stopped"):
        post(app, ref)
    assert app.store.inquiry(ref[0])["status"] == "awaiting_approval"
    assert not any(c[0] == "post" for c in app.slack.calls)


def test_post_only_after_exact_approval_and_double_click_safe(app):
    ref = ready(app)
    assert not any(c[0] == "post" for c in app.slack.calls)
    post(app, ref)
    assert app.store.inquiry(ref[0])["status"] == "posted"
    with pytest.raises(BotError, match="stale_draft"):
        post(app, ref)
    posted = [c for c in app.slack.calls if c[0] == "post"]
    assert len(posted) == 1
    assert posted[0][1:3] == ("CTEST", "1001.000001")
    assert posted[0][3].endswith("\n\n✦ dougle")
    assert "https://example.test/inbound" in posted[0][3]


@pytest.mark.parametrize("wrong", ["user", "card_channel", "card_ts", "draft", "inquiry"])
def test_exact_approval_binding(app, wrong):
    ident, value, channel, ts = ready(app)
    user = "UAPPROVER"
    if wrong == "user":
        user = "UINTRUDER"
    if wrong == "card_channel":
        channel = "DOTHER"
    if wrong == "card_ts":
        ts = "1003.000000"
    if wrong in ("draft", "inquiry"):
        data = json.loads(value)
        data[wrong + "_id"] += 100
        value = json.dumps(data)
    with pytest.raises(BotError):
        app.actions.post(user, value, channel, ts)
    assert not any(c[0] == "post" for c in app.slack.calls)
    assert app.store.inquiry(ident)["status"] == "awaiting_approval"


def test_manual_edit_invalidates_old_button_and_preserves_published_text(app):
    ref = ready(app)
    metadata = json.dumps({**json.loads(ref[1]), "channel": ref[2], "ts": ref[3]})
    app.actions.submit("UAPPROVER", metadata, "edit", "수정한 설명입니다. [1]")
    with pytest.raises(BotError, match="stale_draft"):
        post(app, ref)
    assert not any(c[0] == "post" for c in app.slack.calls)
    inquiry = app.store.inquiry(ref[0])
    assert app.store.current(inquiry)["revision"] == 2
    value = json.dumps({"inquiry_id": ref[0], "draft_id": inquiry["current_draft_id"]})
    app.actions.post("UAPPROVER", value, ref[2], ref[3])
    assert app.slack.messages[("CTEST", "1001.000001")][0]["text"].startswith("수정한 설명입니다.")


def test_thread_change_requires_regeneration_and_new_approval(app):
    ref = ready(app)
    app.slack.messages[("CTEST", "1001.000001")] = [
        {"ts": "1002.000001", "user": "UASKER", "text": "입고가 아니라 출고입니다."}
    ]
    with pytest.raises(BotError, match="thread_changed_regenerating"):
        post(app, ref)
    assert app.store.inquiry(ref[0])["status"] == "queued"
    assert not any(c[0] == "post" for c in app.slack.calls)
    app.worker.run_once()
    assert app.store.current(app.store.inquiry(ref[0]))["revision"] == 2


@pytest.mark.parametrize("after", [False, True])
def test_unknown_delivery_never_blindly_resends(app, after):
    ref = ready(app)
    app.slack.failures["post_after" if after else "post_before"] = SlackFailure(
        "slack_connection_failed", uncertain=True
    )
    post(app, ref)
    assert app.store.inquiry(ref[0])["status"] == "post_unknown"
    with pytest.raises(BotError):
        post(app, ref)
    assert reconcile(app.store, app.slack, ref[0]) is after
    assert len([c for c in app.slack.calls if c[0] == "post"]) == int(after)


def test_definite_unsent_failure_can_be_manually_retried(app):
    ref = ready(app)
    app.slack.failures["post_before"] = SlackFailure("ratelimited")
    post(app, ref)
    assert app.store.inquiry(ref[0])["status"] == "awaiting_approval"
    assert not any(c[0] == "post" for c in app.slack.calls)
    post(app, ref)
    assert app.store.inquiry(ref[0])["status"] == "posted"


def test_crash_recovery_holds_post_and_requeues_draft(app):
    ref = ready(app)
    app.store.claim_post(ref[0], json.loads(ref[1])["draft_id"], ["답변입니다."], "UAPPROVER")
    other = receive(app.store, app.cfg, event(ts="1002.000001"), "UBOT")
    app.store.claim_job()
    app.store.recover()
    assert app.store.inquiry(ref[0])["status"] == "post_unknown"
    assert app.store.inquiry(other)["status"] == "queued"
    assert not any(c[0] == "post" for c in app.slack.calls)


def test_budget_reservations_are_not_zero_on_unknown_cost(app):
    ident = receive(app.store, app.cfg, event(), "UBOT")
    run, amount = app.store.reserve(ident, 3, 3, False)
    assert amount == 3
    app.store.finish_run(run, error="engine_timeout")
    with pytest.raises(BotError, match="daily_budget_exceeded"):
        app.store.reserve(ident, 3, 3, False)


def test_session_not_found_gets_only_one_fresh_retry(app):
    receive(app.store, app.cfg, event(), "UBOT")
    with app.store.tx() as db:
        db.execute("UPDATE threads SET engine_session_id=?", (SESSION,))
    app.engine.results = [
        BotError("session_not_found"),
        EngineResult(SESSION, answer(), 0.03, SKILLS),
    ]
    app.worker.run_once()
    assert [c[1] for c in app.engine.calls] == [SESSION, None]
    assert "session_restored" in app.engine.calls[-1][0]


@pytest.mark.parametrize(
    "field,value",
    [
        ("max_parallel", 2),
        ("max_parallel", True),
        ("timeout_seconds", 0),
        ("max_turns", 201),
        ("daily_budget_usd", float("nan")),
        ("max_budget_usd_per_call", -1),
        ("claude_bin", "claude; echo secret"),
        ("allowed_tools", ["Skill", "Read", "Bash"]),
        ("allowed_tools", ["Skill", "Read", "mcp__Slack__slack_send_message"]),
        ("allowed_tools", ["Read"]),
        ("style_skill", "../wrong"),
    ],
)
def test_invalid_config_fails_before_any_external_call(app, field, value):
    cfg = replace(app.cfg, engine=replace(app.cfg.engine, **{field: value}))
    with pytest.raises(BotError):
        cfg.validate()


def test_config_round_trip_permissions_and_no_secret_fields(app):
    path = app.cfg.home / "config.toml"
    save(app.cfg, path)
    assert load(path) == app.cfg
    assert path.stat().st_mode & 0o777 == 0o600
    assert "xoxb-" not in path.read_text()


@pytest.mark.parametrize("source", ["CTEST", "GTEST", "https://example.slack.com/archives/CTEST"])
def test_channel_id_or_channel_url(source):
    assert channel_id(source) in ("CTEST", "GTEST")


@pytest.mark.parametrize(
    "source",
    [
        "https://evil.test/archives/CTEST",
        "https://example.slack.com/archives/CTEST/p123",
        "CTEST;whoami",
    ],
)
def test_invalid_channel_links(source):
    with pytest.raises(BotError):
        channel_id(source)


def test_keychain_stores_without_password_argument_or_capture():
    calls = []

    def runner(argv, **kwargs):
        calls.append((argv, kwargs))
        return SimpleNamespace(returncode=0, stdout=b"xoxb-synthetic\n")

    secrets = KeychainSecretStore(runner, system=lambda: "Darwin")
    secrets.set_interactive("bot")
    args, kwargs = calls[0]
    assert args[-1] == "-w"
    assert not kwargs.get("capture_output")
    assert all("xoxb-" not in arg for argv, _ in calls for arg in argv)


@pytest.mark.parametrize("code,expected", [(44, None), (0, "xoxb-synthetic")])
def test_keychain_read(code, expected):
    store = KeychainSecretStore(
        lambda *a, **k: SimpleNamespace(returncode=code, stdout=b"xoxb-synthetic\n"),
        system=lambda: "Darwin",
    )
    assert store.get("bot") == expected


def test_keychain_wrong_prefix_is_deleted_without_leak():
    calls = []

    def runner(argv, **kwargs):
        calls.append(argv)
        return SimpleNamespace(returncode=0, stdout=b"xapp-synthetic\n")

    store = KeychainSecretStore(runner, system=lambda: "Darwin")
    with pytest.raises(BotError, match="wrong_token_type"):
        store.set_interactive("bot")
    assert calls[-1][1] == "delete-generic-password"


def test_nonmac_keychain_never_calls_security():
    def forbidden(*args, **kwargs):
        pytest.fail("security called")

    store = KeychainSecretStore(forbidden, system=lambda: "Linux")
    with pytest.raises(BotError, match="macos_keychain_required"):
        store.get("bot")


def test_cli_first_setup_status_menu_and_channel_add(app):
    def run(*argv):
        return execute(parser().parse_args(["--home", str(app.cfg.home), *argv]), FakeSecretStore())

    assert run("status")["next"] == "setup"
    run("setup", "--approver", "UAPPROVER", "--channel", "CTEST")
    assert run("status")["menu"] == ["1. 시작", "2. 상태 확인", "3. 중지", "4. 채널 추가"]
    assert run("channel-add", "COTHER")["channels"] == ["CTEST", "COTHER"]
    assert run("channel-add", "COTHER")["channels"] == ["CTEST", "COTHER"]
    guide = run("secrets", "guide")
    assert all(c.endswith(" -w") and "dougle-bot.slack-" in c for c in guide["commands"])
    assert "토큰을 대화창에 보내지 마세요" in guide["message"]


def test_process_lock_prevents_two_runtimes(app):
    assert not running(app.cfg.home)
    with InstanceLock(app.cfg.home / "runtime.lock"):
        assert running(app.cfg.home)
        with pytest.raises(BotError, match="already_running"):
            with InstanceLock(app.cfg.home / "runtime.lock"):
                pytest.fail("acquired twice")
    assert not running(app.cfg.home)


def test_transaction_rollback_keeps_database_usable(app):
    with pytest.raises(sqlite3.IntegrityError), app.store.tx() as db:
        db.execute(
            "INSERT INTO jobs(inquiry_id,kind,payload,created_at) VALUES(999,'engine','',1000)"
        )
    assert not app.store.all("SELECT * FROM jobs")
    assert receive(app.store, app.cfg, event(), "UBOT")


def test_thread_pagination_no_silent_truncation():
    responses = iter(
        [
            {
                "ok": True,
                "messages": [{"ts": "1.000000", "text": "root"}],
                "response_metadata": {"next_cursor": "page2"},
            },
            {
                "ok": True,
                "messages": [{"ts": "2.000000", "text": "reply"}],
                "response_metadata": {},
            },
        ]
    )
    client = SimpleNamespace(conversations_replies=lambda **kw: next(responses))
    gw = BoltSlackGateway("unused", client)
    assert [m["text"] for m in gw.history("CTEST", "1.000000", 50)] == ["root", "reply"]


def test_pagination_loop_is_failure():
    client = SimpleNamespace(
        conversations_replies=lambda **kw: {
            "ok": True,
            "messages": [],
            "response_metadata": {"next_cursor": "same"},
        }
    )
    with pytest.raises(SlackFailure, match="repeated_cursor"):
        BoltSlackGateway("unused", client).history("CTEST", "1.000000", 50)


def test_large_code_reply_preserves_fences_and_signature():
    text = "설명입니다.\n```sql\n" + ("SELECT 1;\n" * 1000) + "```\n\n✦ dougle"
    parts = cards.split_text(text)
    assert all(len(p) <= 3500 for p in parts)
    assert all(p.count("```") % 2 == 0 for p in parts)
    assert sum(p.count("✦ dougle") for p in parts) == 1
    assert parts[-1].endswith("✦ dougle")


@pytest.mark.parametrize(
    "secret",
    ["xoxb-synthetic", "xapp-synthetic", "password=synthetic", "Bearer synthetic", "ghp_synthetic"],
)
def test_masking_never_returns_secret(secret):
    value = redact({"payload": [secret], "token": secret})
    assert secret not in json.dumps(value)
