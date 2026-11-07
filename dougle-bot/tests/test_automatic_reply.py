"""Automatic customer replies; only exceptions reach the developer DM."""

import json
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace

import pytest
from test_answer_style import READS, SESSION, SKILLS, answer
from test_developer_handoff import AT, failed, metadata, pending, submit
from test_evidence import button_ids, output, result
from test_runtime import event

from dougle_bot.config import SlackConfig, load, save
from dougle_bot.engine import EngineResult
from dougle_bot.intake import receive
from dougle_bot.safety import BotError
from dougle_bot.slack_gateway import SlackFailure
from dougle_bot.worker import Worker


@pytest.fixture
def auto(app):
    cfg = replace(app.cfg, slack=replace(app.cfg.slack, require_approval=False))
    app.cfg = app.worker.config = app.actions.config = cfg
    return app


def test_default_and_old_config_without_setting_use_automatic_replies(app):
    assert SlackConfig().require_approval is False
    path = app.cfg.home / "config.toml"
    save(app.cfg, path)
    path.write_text(path.read_text().replace("require_approval = true\n", ""))
    assert load(path).slack.require_approval is False
    path.write_text(path.read_text() + "\n")


def test_normal_answer_posts_once_without_opening_a_dm(auto):
    ident = receive(auto.store, auto.cfg, event(), "UBOT")
    auto.worker.notify(ident)
    auto.worker.run_once()
    assert auto.store.inquiry(ident)["status"] == "posted"
    assert len(auto.engine.calls) == 1
    assert not auto.slack.cards
    assert not any(c[0] in ("open_dm", "card") for c in auto.slack.calls)
    assert len([c for c in auto.slack.calls if c[0] == "post"]) == 1
    assert auto.store.one("SELECT actor FROM audit WHERE action='auto_post'")["actor"] == "system"
    assert not auto.worker.run_once()


def test_only_exception_opens_dm_and_result_posts_back_to_original_thread(auto):
    row = pending(auto, output("current_state", ("live",), ("live",), status="needs_lookup"))
    assert row["status"] == "needs_lookup" and len(auto.slack.cards) == 1
    assert button_ids(auto, row) == ["lookup", "reject"]
    assert not any(c[0] == "post" for c in auto.slack.calls)
    submit(auto, metadata(auto, row, "lookup"), "lookup", "해당 건의 처리가 완료되었습니다.")
    value = output(
        "current_state",
        ("live",),
        checks=[dict(kind="live", receipt="developer_report", source_index=0)],
    )
    value["body"] = f"개발자 조회 결과 기준 ({AT}) 처리가 완료되었습니다."
    auto.engine.results = [result(value)]
    auto.worker.run_once()
    current = auto.store.inquiry(row["id"])
    assert current["status"] == "posted" and current["card_ts"] == row["card_ts"]
    assert button_ids(auto, current) == []
    posts = [c for c in auto.slack.calls if c[0] == "post"]
    assert len(posts) == 1 and posts[0][1:3] == (row["channel_id"], row["thread_ts"])


def test_internal_failure_fix_requires_research_then_posts_without_confirmation(auto):
    row, meta = failed(auto)
    submit(auto, meta)
    auto.engine.results = [EngineResult(SESSION, answer("확인한 답변입니다."), 0.01, SKILLS, READS)]
    auto.worker.run_once()
    assert auto.store.inquiry(row["id"])["status"] == "posted"
    assert len(auto.slack.cards) == 1
    assert len(auto.engine.calls) == 2


@pytest.mark.parametrize("uncertain", [False, True])
def test_delivery_failure_is_private_and_never_automatically_repeated(auto, uncertain):
    auto.slack.failures["post_before"] = SlackFailure(
        "slack_connection_failed", uncertain=uncertain
    )
    ident = receive(auto.store, auto.cfg, event(), "UBOT")
    auto.worker.run_once()
    row = auto.store.inquiry(ident)
    assert row["status"] == ("post_unknown" if uncertain else "failed")
    assert len(auto.slack.cards) == 1
    assert not auto.worker.run_once()
    auto.store.recover()
    assert not auto.worker.run_once()
    assert "post" not in button_ids(auto, row)
    if not uncertain:
        assert button_ids(auto, row) == ["assist"]


def test_history_change_does_not_publish_an_outdated_answer_or_loop(auto, monkeypatch):
    original = auto.engine.run

    def run(*args, **kwargs):
        value = original(*args, **kwargs)
        auto.slack.messages[("CTEST", "1001.000001")] = [
            {"ts": "1002.000001", "user": "UASKER", "text": "다른 센터입니다."}
        ]
        return value

    monkeypatch.setattr(auto.engine, "run", run)
    ident = receive(auto.store, auto.cfg, event(), "UBOT")
    auto.worker.run_once()
    assert auto.store.inquiry(ident)["status"] == "failed"
    assert auto.store.inquiry(ident)["error"] == "thread_changed_during_answer"
    assert not any(c[0] == "post" for c in auto.slack.calls)
    assert not auto.worker.run_once() and len(auto.engine.calls) == 1


def test_durable_ready_answer_survives_crash_without_second_model_call(auto, monkeypatch):
    original = auto.worker.publish_ready
    monkeypatch.setattr(auto.worker, "publish_ready", lambda: False)
    ident = receive(auto.store, auto.cfg, event(), "UBOT")
    auto.worker.run_once()
    assert auto.store.inquiry(ident)["status"] == "awaiting_approval"
    assert not auto.slack.cards
    auto.store.recover()
    monkeypatch.setattr(auto.worker, "publish_ready", original)
    auto.worker.run_once()
    assert auto.store.inquiry(ident)["status"] == "posted"
    assert len(auto.engine.calls) == 1


def test_parallel_auto_delivery_claim_posts_once(auto, monkeypatch):
    monkeypatch.setattr(auto.worker, "publish_ready", lambda: False)
    ident = receive(auto.store, auto.cfg, event(), "UBOT")
    auto.worker.run_once()
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(lambda _: auto.actions.post_automatic(ident), range(10)))
    assert len([c for c in auto.slack.calls if c[0] == "post"]) == 1


def test_old_review_draft_does_not_auto_post_after_upgrade(app):
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.run_once()
    cfg = replace(app.cfg, slack=replace(app.cfg.slack, require_approval=False))
    worker = Worker(app.store, cfg, app.engine, app.slack)
    assert not worker.run_once()
    assert app.store.inquiry(ident)["status"] == "awaiting_approval"
    assert not any(c[0] == "post" for c in app.slack.calls)


def test_crash_after_auto_claim_without_send_requires_developer(auto, monkeypatch):
    monkeypatch.setattr(auto.worker, "publish_ready", lambda: False)
    ident = receive(auto.store, auto.cfg, event(), "UBOT")
    auto.worker.run_once()
    row = auto.store.inquiry(ident)
    assert auto.store.claim_auto_reply(ident, row["current_draft_id"])
    auto.store.recover()
    assert auto.store.inquiry(ident)["status"] == "failed"
    auto.worker.show_card(ident)
    assert button_ids(auto, auto.store.inquiry(ident)) == ["assist"]
    assert not any(c[0] == "post" for c in auto.slack.calls)


def test_insufficient_developer_report_waits_again_not_an_infinite_loop(auto):
    row = pending(auto, output("current_state", ("live",), ("live",), status="needs_lookup"))
    oldmeta = metadata(auto, row, "lookup")
    submit(auto, oldmeta, "lookup", "아직 해당 건은 확인하지 못했습니다.")
    auto.engine.results = [
        result(output("current_state", ("live",), ("live",), status="needs_lookup"))
    ]
    auto.worker.run_once()
    assert auto.store.inquiry(row["id"])["status"] == "needs_lookup"
    assert len(auto.slack.cards) == 1 and not auto.worker.run_once()
    with pytest.raises(BotError, match="stale_draft"):
        submit(auto, oldmeta, "lookup")


def test_developer_report_is_never_shared_with_another_inquiry(auto):
    row, meta = failed(auto)
    submit(auto, meta)
    auto.engine.results = [
        BotError("processing_failed"),
        EngineResult(SESSION, answer(), 0.01, SKILLS, READS),
    ]
    auto.worker.run_once()
    other = receive(
        auto.store, auto.cfg, event(ts="1002.000001", thread_ts=row["thread_ts"]), "UBOT"
    )
    auto.worker.run_once()
    payload = json.loads(
        auto.engine.calls[-1][0].split("DATA_JSON_START\n")[1].split("\nDATA_JSON_END")[0]
    )
    assert payload["developer_report"] is None
    assert auto.store.inquiry(other)["lookup_basis"] is None


def test_review_mode_cannot_be_bypassed_by_automatic_entrypoint(app):
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.run_once()
    with pytest.raises(BotError, match="approval_required"):
        app.actions.post_automatic(ident)
    assert not any(c[0] == "post" for c in app.slack.calls)


def test_stop_before_post_preserves_ready_answer_without_sending(auto, monkeypatch):
    original = auto.store.save_draft

    def save_and_stop(*args, **kwargs):
        result = original(*args, **kwargs)
        auto.store.stop()
        return result

    monkeypatch.setattr(auto.store, "save_draft", save_and_stop)
    ident = receive(auto.store, auto.cfg, event(), "UBOT")
    auto.worker.run_once()
    assert auto.store.inquiry(ident)["status"] == "awaiting_approval"
    assert not any(c[0] == "post" for c in auto.slack.calls)
    auto.now[0] += 10
    auto.store.begin_run(2, "TTEST", "UBOT")
    auto.worker.run_once()
    assert auto.store.inquiry(ident)["status"] == "posted"
    assert len(auto.engine.calls) == 1


def test_recovery_notifies_only_issues_without_duplicate_unknown_dm(auto, monkeypatch):
    monkeypatch.setattr(auto.worker, "show_card", lambda _: None)
    auto.engine.results = [BotError("processing_failed")]
    ident = receive(auto.store, auto.cfg, event(), "UBOT")
    auto.worker.run_once()
    worker = Worker(auto.store, auto.cfg, auto.engine, auto.slack)
    worker.notify_recovered()
    assert len(auto.slack.cards) == 1
    worker.notify_recovered()
    assert len(auto.slack.cards) == 1
    with auto.store.tx() as db:
        db.execute("UPDATE inquiries SET card_state='unknown' WHERE id=?", (ident,))
    before = len(auto.slack.calls)
    worker.notify_recovered()
    assert len(auto.slack.calls) == before


@pytest.mark.parametrize("value", ["false", 0, None])
def test_approval_mode_requires_a_real_boolean(app, value):
    config = replace(app.cfg, slack=replace(app.cfg.slack, require_approval=value))
    with pytest.raises(BotError, match="invalid_approval_setting"):
        config.validate()


def test_auto_queue_uses_indexes_with_large_existing_audit_log(auto, monkeypatch):
    monkeypatch.setattr(auto.worker, "publish_ready", lambda: False)
    ident = receive(auto.store, auto.cfg, event(), "UBOT")
    auto.worker.run_once()
    with auto.store.tx() as db:
        db.executemany(
            "INSERT INTO audit(inquiry_id,actor,action,detail,at) VALUES(NULL,'system','old_event',?,0)",
            ((str(n),) for n in range(10000)),
        )
    assert auto.store.pending_auto_reply()["id"] == ident
    plan = auto.store.all(
        "EXPLAIN QUERY PLAN SELECT 1 FROM audit WHERE inquiry_id=? AND action='auto_reply_ready' AND detail=?",
        (ident, str(auto.store.inquiry(ident)["current_draft_id"])),
    )
    assert any(
        "SEARCH" in row["detail"] and "audit_inquiry_action_detail" in row["detail"] for row in plan
    )
