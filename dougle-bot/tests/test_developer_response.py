"""Developer input and takeover, using real storage/Bolt dispatch and fake Slack."""

import json
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace

import pytest
from test_automatic_reply import auto as auto
from test_boundaries import bolt
from test_developer_handoff import failed, metadata, submit
from test_handoff_notice import finish, lookup, posts
from test_simple_approval import action_ids, button_request

from dougle_bot import developer_notice
from dougle_bot.safety import BotError
from dougle_bot.slack_gateway import SlackFailure
from dougle_bot.store import Store
from dougle_bot.worker import Worker


def takeover(app, row):
    meta = metadata(app, row, "take_over")
    return app.actions.take_over("UAPPROVER", meta, row["card_channel_id"], row["card_ts"])


@pytest.mark.parametrize("kind", ["lookup", "failure"])
def test_takeover_dispatch_closes_only_this_inquiry_before_ack_and_announces_after(auto, kind):
    row = lookup(auto) if kind == "lookup" else failed(auto)[0]
    old_result = metadata(auto, row, "lookup" if kind == "lookup" else "assist")
    ref = (row["id"], metadata(auto, row, "take_over"), row["card_channel_id"], row["card_ts"])
    built, executor = bolt(auto)
    before = len(posts(auto))
    assert built.dispatch(button_request(ref, "take_over")).status == 200
    assert auto.store.inquiry(row["id"])["human_takeover"]
    assert len(posts(auto)) == before  # No network on the acknowledgement path.
    for fn, *args in executor.calls:
        fn(*args)
    assert posts(auto)[-1][3] == developer_notice.TAKEOVER_NOTICE
    card = auto.slack.cards[(row["card_channel_id"], row["card_ts"])]
    assert "개발자 직접 응대" in card["text"] and not action_ids(card)
    with pytest.raises(BotError):
        submit(auto, old_result, "lookup" if kind == "lookup" else "assist")
    assert not auto.worker.run_once()
    assert not auto.store.all("SELECT * FROM deliveries")
    for _ in range(2):
        built.dispatch(button_request(ref, "take_over"))
        auto.worker.notify_recovered()
    assert len(posts(auto)) == before + 1
    assert len(auto.store.all("SELECT * FROM audit WHERE action='developer_takeover'")) == 1


@pytest.mark.parametrize("kind", ["lookup", "failure"])
def test_saved_result_gets_one_notice_before_processing_even_if_dm_update_fails(auto, kind):
    row = lookup(auto) if kind == "lookup" else failed(auto)[0]
    action = "lookup" if kind == "lookup" else "assist"
    submit(auto, metadata(auto, row, action), action)
    auto.slack.failures["card_before"] = SlackFailure()
    auto.worker.notify(row["id"])
    assert posts(auto)[-1][3] == developer_notice.RESULT_NOTICE
    auto.worker.notify(row["id"])
    assert [p[3] for p in posts(auto)].count(developer_notice.RESULT_NOTICE) == 1
    assert auto.store.inquiry(row["id"])["status"] == "queued"


def test_result_notice_precedes_verified_final_answer(auto):
    row = lookup(auto)
    assert finish(auto, row)["status"] == "posted"
    assert len(posts(auto)) == 3
    assert posts(auto)[1][3] == developer_notice.RESULT_NOTICE
    assert "처리가 완료" in posts(auto)[2][3]
    assert all(p[1:3] == (row["channel_id"], row["thread_ts"]) for p in posts(auto))


@pytest.mark.parametrize("change", ["user", "channel", "ts", "draft", "stopped", "boolean"])
def test_takeover_rejects_unauthorized_or_stale_input(auto, change):
    row = lookup(auto)
    data = json.loads(metadata(auto, row, "take_over"))
    user, channel, ts = "UAPPROVER", row["card_channel_id"], row["card_ts"]
    if change == "user":
        user = "UOTHER"
    elif change == "channel":
        channel = "DOTHER"
    elif change == "ts":
        ts = "9999.1"
    elif change == "draft":
        data["draft_id"] += 1
    elif change == "boolean":
        data["inquiry_id"] = True
    else:
        with auto.store.tx() as db:
            db.execute("UPDATE control SET desired='stopped'")
    with pytest.raises(BotError):
        auto.actions.take_over(user, data, channel, ts)
    assert auto.store.inquiry(row["id"])["status"] == "needs_lookup"


def test_takeover_and_result_race_has_one_winner(auto):
    row = lookup(auto)
    take = metadata(auto, row, "take_over")
    result = metadata(auto, row, "lookup")

    def run(which):
        try:
            if which:
                auto.actions.take_over("UAPPROVER", take, row["card_channel_id"], row["card_ts"])
            else:
                submit(auto, result, "lookup")
            return True
        except BotError:
            return False

    with ThreadPoolExecutor(2) as pool:
        assert sum(pool.map(run, [True, False])) == 1
    current = auto.store.inquiry(row["id"])
    assert current["status"] in ("queued", "cancelled")
    auto.worker.notify(row["id"])
    expected = (
        developer_notice.TAKEOVER_NOTICE
        if current["human_takeover"]
        else developer_notice.RESULT_NOTICE
    )
    assert posts(auto)[-1][3] == expected


@pytest.mark.parametrize("kind", ["result", "takeover"])
@pytest.mark.parametrize("fault", ["post_before", "post_after"])
def test_notice_failure_or_uncertainty_is_not_replayed_after_restart(auto, kind, fault):
    row = lookup(auto)
    if kind == "result":
        submit(auto, metadata(auto, row, "lookup"), "lookup")
    else:
        takeover(auto, row)
    auto.slack.failures[fault] = SlackFailure(uncertain=True)
    auto.worker.notify(row["id"])
    count = len(posts(auto))
    reopened = Store(auto.store.path, clock=auto.store.clock)
    try:
        reopened.check_post_intents()
        worker = Worker(reopened, auto.cfg, auto.engine, auto.slack)
        worker.notify_recovered()
        worker.notify(row["id"])
        assert len(posts(auto)) == count
    finally:
        reopened.close()


@pytest.mark.parametrize("change", ["stopped", "removed", "review", "finished"])
def test_notice_rechecks_lifecycle_after_ledger_write(auto, monkeypatch, change):
    row = lookup(auto)
    submit(auto, metadata(auto, row, "lookup"), "lookup")
    original = auto.store.record_post_intent

    def write(ident):
        original(ident)
        if change == "removed":
            auto.worker.config.slack.allowed_channel_ids.clear()
        elif change == "review":
            auto.worker.config = replace(
                auto.cfg, slack=replace(auto.cfg.slack, require_approval=True)
            )
        else:
            with auto.store.tx() as db:
                if change == "stopped":
                    db.execute("UPDATE control SET desired='stopped'")
                else:
                    db.execute("UPDATE inquiries SET status='posted' WHERE id=?", (row["id"],))

    monkeypatch.setattr(auto.store, "record_post_intent", write)
    # Config object replacement applies to the next notify call. In-flight sends retain their snapshot.
    if change == "review":
        auto.worker.config = replace(auto.cfg, slack=replace(auto.cfg.slack, require_approval=True))
    auto.worker.notify(row["id"])
    assert len(posts(auto)) == 1


def test_review_mode_takeover_remains_private(app):
    row = lookup(app)
    takeover(app, row)
    app.worker.notify(row["id"])
    assert not posts(app)
    assert app.store.inquiry(row["id"])["human_takeover"]


@pytest.mark.parametrize("kind", ["result", "takeover"])
def test_separate_workers_claim_one_public_notice(auto, kind):
    row = lookup(auto)
    if kind == "result":
        submit(auto, metadata(auto, row, "lookup"), "lookup")
    else:
        takeover(auto, row)
    other = Store(auto.store.path, clock=auto.store.clock)
    try:
        with ThreadPoolExecutor(2) as pool:
            list(
                pool.map(
                    lambda store: developer_notice.notify(store, auto.cfg, auto.slack, row["id"]),
                    [auto.store, other],
                )
            )
        assert len(posts(auto)) == 2
        assert len(auto.store.all("SELECT * FROM audit WHERE action='developer_notice_claim'")) == 1
    finally:
        other.close()


@pytest.mark.parametrize("kind", ["result", "takeover"])
@pytest.mark.parametrize("stage", ["ledger", "send", "receipt"])
def test_crash_after_claim_never_replays_notice(auto, monkeypatch, kind, stage):
    row = lookup(auto)
    if kind == "result":
        submit(auto, metadata(auto, row, "lookup"), "lookup")
    else:
        takeover(auto, row)
    real_ledger, real_post, real_audit = (
        auto.store.record_post_intent,
        auto.slack.post,
        auto.store.audit,
    )

    class Crash(BaseException):
        pass

    def ledger(ident):
        real_ledger(ident)
        if stage == "ledger":
            raise Crash()

    def post(*args):
        ts = real_post(*args)
        if stage == "send":
            raise Crash()
        return ts

    def audit(*args):
        if stage == "receipt" and args[2] == "developer_notice_sent":
            raise Crash()
        return real_audit(*args)

    monkeypatch.setattr(auto.store, "record_post_intent", ledger)
    monkeypatch.setattr(auto.slack, "post", post)
    monkeypatch.setattr(auto.store, "audit", audit)
    with pytest.raises(Crash):
        developer_notice.notify(auto.store, auto.cfg, auto.slack, row["id"])
    count = len(posts(auto))
    monkeypatch.setattr(auto.slack, "post", real_post)
    reopened = Store(auto.store.path, clock=auto.store.clock)
    try:
        developer_notice.notify(reopened, auto.cfg, auto.slack, row["id"])
        assert len(posts(auto)) == count
    finally:
        reopened.close()


@pytest.mark.parametrize("fault", ["ledger", "missing_receipt", "unexpected_error"])
def test_notice_fault_does_not_prevent_final_answer(auto, monkeypatch, fault):
    row = lookup(auto)
    original = auto.slack.post
    ledger = auto.store.record_post_intent

    def broken_ledger(ident):
        raise OSError("synthetic disk error")

    def broken_post(*args):
        if args[2] == developer_notice.RESULT_NOTICE:
            if fault == "missing_receipt":
                original(*args)
                return None
            raise RuntimeError("synthetic transport failure")
        return original(*args)

    if fault == "ledger":
        monkeypatch.setattr(auto.store, "record_post_intent", broken_ledger)
    else:
        monkeypatch.setattr(auto.slack, "post", broken_post)
    submit(auto, metadata(auto, row, "lookup"), "lookup")
    auto.worker.notify(row["id"])
    monkeypatch.setattr(auto.store, "record_post_intent", ledger)
    monkeypatch.setattr(auto.slack, "post", original)
    # Reuse a synthetic verified answer, while keeping the same submitted job.
    from test_developer_handoff import AT
    from test_evidence import output, result

    value = output(
        "current_state",
        ("live",),
        checks=[dict(kind="live", receipt="developer_report", source_index=0)],
    )
    value["body"] = f"개발자 조회 결과 기준 ({AT}) 처리가 완료되었습니다."
    auto.engine.results = [result(value)]
    auto.worker.run_once()
    assert auto.store.inquiry(row["id"])["status"] == "posted"
    assert "처리가 완료" in posts(auto)[-1][3]
    assert len([p for p in posts(auto) if p[3] == developer_notice.RESULT_NOTICE]) <= 1


def test_database_only_rollback_cannot_forget_transition_notice(auto):
    import sqlite3

    row = lookup(auto)
    takeover(auto, row)
    backup = sqlite3.connect(":memory:")
    auto.store.db.backup(backup)
    auto.worker.notify(row["id"])
    backup.backup(auto.store.db)
    backup.close()
    with pytest.raises(BotError, match="restored_database_requires_review"):
        auto.store.check_post_intents()


@pytest.mark.parametrize("kind", ["lookup", "failure"])
def test_two_submissions_create_one_job_and_one_public_result_notice(auto, kind):
    row = lookup(auto) if kind == "lookup" else failed(auto)[0]
    action = "lookup" if kind == "lookup" else "assist"
    meta = metadata(auto, row, action)
    submit(auto, meta, action)
    with pytest.raises(BotError):
        submit(auto, meta, action)
    auto.worker.notify(row["id"])
    auto.worker.notify(row["id"])
    assert len(auto.store.all("SELECT * FROM jobs WHERE kind='lookup_result'")) == 1
    assert [p[3] for p in posts(auto)].count(developer_notice.RESULT_NOTICE) == 1


def test_takeover_does_not_disable_new_mention_or_other_threads(auto):
    from test_runtime import event

    from dougle_bot.intake import receive

    row = lookup(auto)
    takeover(auto, row)
    auto.worker.notify(row["id"])
    next_id = receive(
        auto.store, auto.cfg, event(ts="1002.000001", thread_ts=row["thread_ts"]), "UBOT"
    )
    assert next_id != row["id"]
    from test_answer_style import answer
    from test_evidence import result

    auto.engine.results = [result(answer(), {"read1": {"knowledge"}})]
    assert auto.worker.run_once()
    assert auto.store.inquiry(next_id)["status"] == "posted"
    assert auto.store.inquiry(row["id"])["status"] == "cancelled"


@pytest.mark.parametrize(
    "payload",
    [
        None,
        [],
        {},
        "bad",
        {"inquiry_id": 1},
        {"inquiry_id": 1, "draft_id": 1, "job_id": 1},
        {"inquiry_id": 1, "job_id": False},
        {"inquiry_id": -1, "draft_id": 1},
    ],
)
def test_malformed_takeover_does_not_change_any_inquiry(auto, payload):
    row = lookup(auto)
    with pytest.raises(BotError):
        auto.actions.take_over("UAPPROVER", payload, row["card_channel_id"], row["card_ts"])
    assert auto.store.inquiry(row["id"])["status"] == "needs_lookup"


def test_wrong_team_takeover_dispatch_cannot_close_inquiry(auto):
    row = lookup(auto)
    ref = (row["id"], metadata(auto, row, "take_over"), row["card_channel_id"], row["card_ts"])
    built, _ = bolt(auto)
    response = built.dispatch(button_request(ref, "take_over", team={"id": "TOTHER"}))
    assert response.status == 200
    assert auto.store.inquiry(row["id"])["status"] == "needs_lookup"
