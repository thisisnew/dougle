"""Durable, bounded public handoff notices; all services and data are synthetic."""

import json
import sqlite3
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace

import pytest
from test_automatic_reply import auto as auto
from test_developer_handoff import AT, failed, metadata, pending, submit
from test_evidence import output, result
from test_runtime import event

from dougle_bot.handoff_notice import NOTICE
from dougle_bot.intake import receive
from dougle_bot.safety import BotError
from dougle_bot.slack_gateway import SlackFailure, history_hash
from dougle_bot.store import Store
from dougle_bot.worker import Worker


def lookup(app):
    return pending(app, output("current_state", ("live",), ("live",), status="needs_lookup"))


def posts(app):
    return [c for c in app.slack.calls if c[0] == "post"]


def audits(app, action):
    return app.store.all("SELECT * FROM audit WHERE action=?", ("handoff_notice_" + action,))


def finish(app, row):
    submit(app, metadata(app, row, "lookup"), "lookup", "해당 건의 처리가 완료되었습니다.")
    value = output(
        "current_state",
        ("live",),
        checks=[dict(kind="live", receipt="developer_report", source_index=0)],
    )
    value["body"] = f"개발자 조회 결과 기준 ({AT}) 처리가 완료되었습니다."
    app.engine.results = [result(value)]
    app.worker.run_once()
    return app.store.inquiry(row["id"])


@pytest.mark.parametrize("kind", ["lookup", "failed"])
def test_confirmed_dm_precedes_exact_safe_notice_and_preserves_pending_state(auto, kind):
    row = lookup(auto) if kind == "lookup" else failed(auto, "required_read_not_verified")[0]
    assert row["status"] == ("needs_lookup" if kind == "lookup" else "failed")
    assert posts(auto)[0][1:4] == (row["channel_id"], row["thread_ts"], NOTICE)
    assert len(posts(auto)) == len(audits(auto, "intent")) == len(audits(auto, "sent")) == 1
    kinds = [c[0] for c in auto.slack.calls]
    assert kinds.index("card") < kinds.index("post")
    assert not auto.store.all("SELECT * FROM deliveries")  # Final-answer receipts stay separate.
    assert row["posted_ts"] is None
    for _ in range(4):
        auto.worker.show_card(row["id"])
        auto.worker.notify_recovered()
    assert len(posts(auto)) == 1 and not auto.worker.run_once()


@pytest.mark.parametrize("stage", ["dm", "card_before", "card_after"])
@pytest.mark.parametrize("uncertain", [False, True])
def test_dm_failure_never_claims_notice_until_a_confirmed_request(auto, stage, uncertain):
    auto.slack.failures[stage] = SlackFailure("slack_failed", uncertain=uncertain)
    row = lookup(auto)
    assert not posts(auto) and not audits(auto, "intent")
    auto.store.recover()
    auto.worker.notify_recovered()
    if stage != "dm" and uncertain:
        assert auto.store.inquiry(row["id"])["card_state"] == "unknown"
        assert not posts(auto)
    else:
        assert len(posts(auto)) == 1 and posts(auto)[0][3] == NOTICE


@pytest.mark.parametrize(
    "fault,uncertain", [("post_before", False), ("post_before", True), ("post_after", True)]
)
def test_notice_failure_is_held_across_reopen_and_final_answer_still_posts(auto, fault, uncertain):
    auto.slack.failures[fault] = SlackFailure("slack_connection_failed", uncertain=uncertain)
    row = lookup(auto)
    assert row["status"] == "needs_lookup" and row["error"] is None
    assert len(audits(auto, "unknown" if uncertain else "not_sent")) == 1
    count = len(posts(auto))
    reopened = Store(auto.store.path, clock=auto.store.clock)
    try:
        reopened.recover()
        worker = Worker(reopened, auto.cfg, auto.engine, auto.slack)
        worker.notify_recovered()
        worker.show_card(row["id"])
        assert len(posts(auto)) == count
        assert finish(auto, row)["status"] == "posted"
        assert len(posts(auto)) == count + 1
        assert posts(auto)[-1][1:3] == (row["channel_id"], row["thread_ts"])
    finally:
        reopened.close()


def test_unexpected_send_exception_is_also_ambiguous(auto, monkeypatch):
    original = auto.slack.post

    def send(*args):
        original(*args)
        raise RuntimeError("synthetic transport error")

    monkeypatch.setattr(auto.slack, "post", send)
    row = lookup(auto)
    assert len(audits(auto, "unknown")) == 1
    auto.worker.show_card(row["id"])
    assert len(posts(auto)) == 1 and row["status"] == "needs_lookup"


def test_missing_send_receipt_is_ambiguous_and_never_retried(auto, monkeypatch):
    original = auto.slack.post

    def send(*args):
        original(*args)
        return None

    monkeypatch.setattr(auto.slack, "post", send)
    row = lookup(auto)
    auto.worker.show_card(row["id"])
    assert len(posts(auto)) == len(audits(auto, "unknown")) == 1


def test_new_failed_attempt_cannot_authorize_notice_from_old_dm(auto, monkeypatch):
    original = auto.slack.card

    def card(*args, **kwargs):
        ts = original(*args, **kwargs)
        with auto.store.tx() as db:
            db.execute(
                "INSERT INTO jobs(inquiry_id,kind,payload,status,created_at) "
                "SELECT id,'lookup_result','','failed',1000 FROM inquiries"
            )
        return ts

    monkeypatch.setattr(auto.slack, "card", card)
    row, _ = failed(auto)
    assert not posts(auto) and not audits(auto, "intent")
    monkeypatch.setattr(auto.slack, "card", original)
    auto.worker.show_card(row["id"])
    assert len(posts(auto)) == 1


@pytest.mark.parametrize("stage", ["dm", "ledger"])
@pytest.mark.parametrize("change", ["stop", "removed", "queued", "rejected", "cancelled"])
def test_rechecks_authorization_and_lifecycle_after_io(auto, monkeypatch, stage, change):
    def changed():
        if change == "stop":
            auto.store.stop()
        elif change == "removed":
            auto.cfg.slack.allowed_channel_ids.clear()
        else:
            with auto.store.tx() as db:
                db.execute("UPDATE inquiries SET status=?", (change,))

    owner, method = (auto.slack, "card") if stage == "dm" else (auto.store, "record_post_intent")
    original = getattr(owner, method)

    def io(*args, **kwargs):
        value = original(*args, **kwargs)
        changed()
        return value

    monkeypatch.setattr(owner, method, io)
    lookup(auto)
    assert not posts(auto)


def test_ledger_failure_prevents_send_and_retry(auto, monkeypatch):
    def broken(_):
        raise OSError("synthetic disk full")

    monkeypatch.setattr(auto.store, "record_post_intent", broken)
    row = lookup(auto)
    assert len(audits(auto, "not_sent")) == 1
    auto.worker.show_card(row["id"])
    assert not posts(auto)


class Crash(BaseException):
    pass


@pytest.mark.parametrize("stage", ["intent", "ledger", "send", "receipt"])
def test_crash_window_never_replays_notice(auto, monkeypatch, stage):
    original_ledger = auto.store.record_post_intent
    original_post = auto.slack.post
    original_audit = auto.store.audit

    def ledger(ident):
        if stage == "ledger":
            original_ledger(ident)
        raise Crash()

    def post(*args):
        original_post(*args)
        raise Crash()

    def audit(ident, actor, action, detail=None):
        if action == "handoff_notice_sent":
            raise Crash()
        original_audit(ident, actor, action, detail)

    with monkeypatch.context() as patch:
        if stage in ("intent", "ledger"):
            patch.setattr(auto.store, "record_post_intent", ledger)
        elif stage == "send":
            patch.setattr(auto.slack, "post", post)
        else:
            patch.setattr(auto.store, "audit", audit)
        with pytest.raises(Crash):
            lookup(auto)
    count = len(posts(auto))
    reopened = Store(auto.store.path, clock=auto.store.clock)
    try:
        reopened.recover()
        Worker(reopened, auto.cfg, auto.engine, auto.slack).notify_recovered()
        assert len(posts(auto)) == count
        assert len(audits(auto, "intent")) == 1
    finally:
        reopened.close()


def test_restoring_only_database_cannot_erase_a_notice_send(auto, tmp_path):
    backup = sqlite3.connect(tmp_path / "backup.db")
    auto.store.db.backup(backup)
    lookup(auto)
    backup.backup(auto.store.db)
    backup.close()
    with pytest.raises(BotError, match="restored_database_requires_review"):
        Store(auto.store.path)


def test_independent_workers_race_for_one_durable_notice(auto, monkeypatch):
    # Delay the initial card, then use separate locks and SQLite connections.
    with monkeypatch.context() as patch:
        patch.setattr(auto.worker, "show_card", lambda _: None)
        row = lookup(auto)
    # Establish one DM card without consuming the public notice claim.
    review = replace(auto.cfg, slack=replace(auto.cfg.slack, require_approval=True))
    Worker(auto.store, review, auto.engine, auto.slack).show_card(row["id"])

    def notify(_):
        store = Store(auto.store.path, clock=auto.store.clock)
        try:
            Worker(store, auto.cfg, auto.engine, auto.slack).show_card(row["id"])
        finally:
            store.close()

    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(notify, range(12)))
    assert len(posts(auto)) == len(audits(auto, "intent")) == 1


def test_notice_does_not_change_thread_hash_but_customer_correction_does(auto):
    thread = ("CTEST", "1001.000001")
    original = [{"ts": thread[1], "user": "UASKER", "text": "확인 부탁드립니다."}]
    auto.slack.messages[thread] = list(original)
    row = lookup(auto)
    assert (
        history_hash(auto.slack.messages[thread]) == row["history_hash"] == history_hash(original)
    )
    auto.slack.messages[thread].append(
        {"ts": "1900000001.000001", "user": "UASKER", "text": NOTICE}
    )
    assert history_hash(auto.slack.messages[thread]) != row["history_hash"]


def test_customer_change_during_resumed_answer_still_rejects_final(auto, monkeypatch):
    row = lookup(auto)
    original = auto.engine.run

    def run(*args, **kwargs):
        value = original(*args, **kwargs)
        auto.slack.messages[(row["channel_id"], row["thread_ts"])].append(
            {"ts": "1900000001.000001", "user": "UASKER", "text": "다른 환경입니다."}
        )
        return value

    monkeypatch.setattr(auto.engine, "run", run)
    current = finish(auto, row)
    assert current["status"] == "failed" and current["error"] == "thread_changed_during_answer"
    assert len(posts(auto)) == 1 and posts(auto)[0][3] == NOTICE


def test_additional_developer_request_gets_no_second_notice(auto):
    row = lookup(auto)
    submit(auto, metadata(auto, row, "lookup"), "lookup", "추가 확인이 필요합니다.")
    auto.engine.results = [
        result(output("current_state", ("live",), ("live",), status="needs_lookup"))
    ]
    auto.worker.run_once()
    assert len(posts(auto)) == 1
    assert finish(auto, auto.store.inquiry(row["id"]))["status"] == "posted"
    assert len(posts(auto)) == 2


def test_each_inquiry_in_same_thread_has_its_own_notice(auto):
    row = lookup(auto)
    other = receive(
        auto.store, auto.cfg, event(ts="1002.000001", thread_ts=row["thread_ts"]), "UBOT"
    )
    auto.engine.results = [
        result(output("current_state", ("live",), ("live",), status="needs_lookup"))
    ]
    auto.worker.run_once()
    assert other != row["id"]
    assert len(posts(auto)) == 2 and len(audits(auto, "intent")) == 2
    assert posts(auto)[0][4] != posts(auto)[1][4]


@pytest.mark.parametrize("kind", ["lookup", "failed"])
def test_review_mode_never_posts_notice_even_on_recovery(app, kind):
    row = lookup(app) if kind == "lookup" else failed(app)[0]
    app.store.recover()
    app.worker.notify_recovered()
    app.worker.show_card(row["id"])
    assert not posts(app) and not audits(app, "intent")


def test_review_mode_after_notice_still_requires_final_approval(auto):
    row = lookup(auto)
    cfg = replace(auto.cfg, slack=replace(auto.cfg.slack, require_approval=True))
    auto.cfg = auto.worker.config = auto.actions.config = cfg
    current = finish(auto, row)
    assert current["status"] == "awaiting_approval" and len(posts(auto)) == 1
    auto.actions.post(
        "UAPPROVER",
        metadata(auto, current, "post"),
        current["card_channel_id"],
        current["card_ts"],
    )
    assert auto.store.inquiry(row["id"])["status"] == "posted"
    assert len(posts(auto)) == 2


def test_success_receipt_and_independent_ledger_share_delivery_identity(auto):
    row = lookup(auto)
    ident = posts(auto)[0][4]
    receipt = json.loads(audits(auto, "sent")[0]["detail"])
    assert receipt["id"] == audits(auto, "intent")[0]["detail"] == ident
    assert receipt["ts"] == auto.slack.messages[(row["channel_id"], row["thread_ts"])][0]["ts"]
    assert ident in (auto.cfg.home / "post-intents.log").read_text().splitlines()
