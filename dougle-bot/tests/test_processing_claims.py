"""Single inquiry worker and concurrent approval actions, with fake services only.

Production remains one lane. Contending database connections test atomic guards;
they do not enable or demonstrate safe parallel model execution.
"""

import sqlite3
import threading
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace

import pytest
from test_answer_style import answer
from test_runtime import event, ready
from test_startup import install_fake_runtime, result

from dougle_bot import runtime
from dougle_bot.actions import Actions
from dougle_bot.config import load, save
from dougle_bot.intake import receive
from dougle_bot.safety import BotError
from dougle_bot.secrets import FakeSecretStore
from dougle_bot.store import SCHEMA, Store


def enqueue(app, number, *, root=None):
    updates = {"thread_ts": root} if root else {}
    ident = receive(app.store, app.cfg, event(ts=f"1001.{number:06d}", **updates), "UBOT")
    assert ident is not None
    return ident


def test_single_lane_config_roundtrip_and_direct_serve_rejects_two(app):
    assert app.cfg.engine.max_parallel == 1
    path = app.cfg.home / "config.toml"
    save(app.cfg, path)
    assert load(path).engine.max_parallel == 1
    cfg = replace(app.cfg, engine=replace(app.cfg.engine, max_parallel=2))
    with pytest.raises(BotError, match="invalid_integer_setting"):
        runtime.serve(cfg, FakeSecretStore({}))


def test_contending_connections_claim_only_one_job_then_continue_in_order(app):
    first = enqueue(app, 1)
    following = enqueue(app, 2, root="1001.000001")
    enqueue(app, 3)
    barrier = threading.Barrier(8)

    def claim(_):
        store = Store(app.store.path, clock=app.store.clock)
        try:
            barrier.wait(timeout=5)
            return store.claim_job()
        finally:
            store.close()

    with ThreadPoolExecutor(max_workers=8) as pool:
        jobs = [job for job in pool.map(claim, range(8)) if job]
    assert len(jobs) == 1 and jobs[0]["inquiry_id"] == first
    app.store.save_draft(first, answer(), "engine", "hash", "1001.000001", job_id=jobs[0]["id"])
    assert app.store.claim_job()["inquiry_id"] == following


@pytest.mark.parametrize("state", ["pending", "attempted", "posting", "post_unknown"])
def test_same_thread_delivery_blocks_claim_but_other_thread_can_proceed(app, state):
    first = enqueue(app, 1)
    following = enqueue(app, 2, root="1001.000001")
    other = enqueue(app, 3)
    job = app.store.claim_job()
    draft = app.store.save_draft(
        first, answer(), "engine", "hash", "1001.000001", job_id=job["id"], auto_reply=True
    )
    if state != "pending":
        assert app.store.claim_auto_reply(first, draft)
    if state in ("posting", "post_unknown"):
        app.store.claim_post(first, draft, ["확인했습니다."], "system")
    if state == "post_unknown":
        app.store.fail_auto_reply(first, "delivery_uncertain")
    assert app.store.claim_job()["inquiry_id"] == other
    assert app.store.inquiry(following)["status"] == "queued"


@pytest.mark.parametrize("winner", ["worker", "approval"])
def test_worker_and_approval_claims_exclude_each_other_in_either_order(app, winner):
    ident, *_ = ready(app)
    following = enqueue(app, 2, root="1001.000001")
    draft = app.store.inquiry(ident)["current_draft_id"]
    if winner == "worker":
        job = app.store.claim_job()
        assert job["inquiry_id"] == following
        with pytest.raises(BotError, match="already_processing"):
            app.store.claim_post(ident, draft, ["확인했습니다."], "UAPPROVER")
        assert app.store.all("SELECT * FROM deliveries") == []
        app.store.fail_job(job, "engine_failed")
        app.store.claim_post(ident, draft, ["확인했습니다."], "UAPPROVER")
    else:
        app.store.claim_post(ident, draft, ["확인했습니다."], "UAPPROVER")
        assert app.store.claim_job() is None
        assert app.store.inquiry(following)["status"] == "queued"


def test_worker_and_approval_contend_atomically_on_separate_connections(app):
    ident, *_ = ready(app)
    following = enqueue(app, 2, root="1001.000001")
    draft = app.store.inquiry(ident)["current_draft_id"]
    barrier = threading.Barrier(2)

    def compete(action):
        store = Store(app.store.path, clock=app.store.clock)
        try:
            barrier.wait(timeout=5)
            if action == "work":
                job = store.claim_job()
                return bool(job)
            try:
                store.claim_post(ident, draft, ["확인했습니다."], "UAPPROVER")
                return True
            except BotError as exc:
                assert exc.code == "already_processing"
                return False
        finally:
            store.close()

    with ThreadPoolExecutor(max_workers=2) as pool:
        worked, posted = list(pool.map(compete, ["work", "post"]))
    assert worked != posted
    assert app.store.inquiry(following)["status"] == ("drafting" if worked else "queued")
    assert app.store.inquiry(ident)["status"] == ("posting" if posted else "awaiting_approval")


def test_two_review_approvals_for_same_thread_cannot_both_claim_post(app):
    first, *_ = ready(app)
    second, *_ = ready(app, ts="1001.000002", thread_ts="1001.000001")
    barrier = threading.Barrier(2)

    def post(ident):
        draft = app.store.inquiry(ident)["current_draft_id"]
        barrier.wait(timeout=5)
        try:
            app.store.claim_post(ident, draft, ["확인했습니다."], "UAPPROVER")
            return True
        except BotError as exc:
            assert exc.code == "already_processing"
            return False

    with ThreadPoolExecutor(max_workers=2) as pool:
        assert sum(pool.map(post, [first, second])) == 1
    assert app.store.one("SELECT COUNT(*) n FROM deliveries")["n"] == 1


def test_contending_automatic_publishers_send_once(app):
    ident, *_ = ready(app)
    draft = app.store.inquiry(ident)["current_draft_id"]
    app.store.audit(ident, "system", "auto_reply_ready", str(draft))
    cfg = replace(app.cfg, slack=replace(app.cfg.slack, require_approval=False))
    barrier = threading.Barrier(8)

    def post(_):
        actions = Actions(app.store, cfg, app.slack, app.worker)
        barrier.wait(timeout=5)
        actions.post_automatic(ident)

    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(post, range(8)))
    assert app.store.inquiry(ident)["status"] == "posted"
    assert len([call for call in app.slack.calls if call[0] == "post"]) == 1
    assert app.store.one("SELECT COUNT(*) n FROM deliveries")["n"] == 1
    assert (
        app.store.one("SELECT COUNT(*) n FROM audit WHERE action='auto_reply_attempted'")["n"] == 1
    )


def test_daily_budget_reservations_remain_atomic_across_connections(app):
    barrier = threading.Barrier(8)

    def reserve(_):
        store = Store(app.store.path, clock=app.store.clock)
        try:
            barrier.wait(timeout=5)
            try:
                return store.reserve(None, 1, 1.5, False)[1]
            except BotError as exc:
                assert exc.code == "daily_budget_exceeded"
                return 0
        finally:
            store.close()

    with ThreadPoolExecutor(max_workers=8) as pool:
        assert sorted(amount for amount in pool.map(reserve, range(8)) if amount) == [0.5, 1]


@pytest.mark.parametrize("version", [1, 2])
def test_status_stop_with_migrate_false_do_not_add_schema_or_coordination(tmp_path, version):
    path = tmp_path / "legacy.db"
    with sqlite3.connect(path) as db:
        db.executescript(SCHEMA)
        if version == 2:
            db.execute("ALTER TABLE drafts ADD COLUMN verification_json TEXT")
        db.execute(f"PRAGMA user_version={version}")
        schema = db.execute("SELECT name,sql FROM sqlite_master ORDER BY name").fetchall()
    store = Store(path, migrate=False)
    try:
        assert store.control()["desired"] == "stopped"
        store.stop()
        assert store.one("PRAGMA user_version")["user_version"] == version
        assert [
            tuple(row.values())
            for row in store.all("SELECT name,sql FROM sqlite_master ORDER BY name")
        ] == schema
    finally:
        store.close()


@pytest.mark.parametrize("terminal", [False, True])
def test_obsolete_coordination_table_is_ignored_after_recovery(app, terminal):
    """A database from the abandoned lease prototype needs no destructive downgrade."""
    first = enqueue(app, 1)
    following = enqueue(app, 2, root="1001.000001")
    job = app.store.claim_job()
    if terminal:
        app.store.save_draft(first, answer(), "engine", "hash", "1001.000001", job_id=job["id"])
    with app.store.tx() as db:
        db.execute(
            "CREATE TABLE processing_claims(thread_key TEXT PRIMARY KEY REFERENCES threads(thread_key),"
            "job_id INTEGER NOT NULL UNIQUE REFERENCES jobs(id),owner TEXT NOT NULL)"
        )
        db.execute(
            "INSERT INTO processing_claims VALUES(?,?,?)",
            (job["thread_key"], job["id"], "obsolete"),
        )
    observer = Store(app.store.path, migrate=False)
    try:
        assert observer.one("SELECT owner FROM processing_claims")["owner"] == "obsolete"
    finally:
        observer.close()
    restarted = Store(app.store.path, clock=app.store.clock)
    try:
        restarted.recover()
        assert restarted.claim_job()["inquiry_id"] == (following if terminal else first)
        assert restarted.claim_job() is None
        assert restarted.one("PRAGMA user_version")["user_version"] == 2
        # The inert historical row need not be deleted to resume work safely.
        assert restarted.one("SELECT owner FROM processing_claims")["owner"] == "obsolete"
    finally:
        restarted.close()


def test_current_store_creates_no_processing_lease_table(app):
    assert app.store.one("SELECT name FROM sqlite_master WHERE name='processing_claims'") is None
    assert app.store.one("PRAGMA user_version")["user_version"] == 2


def test_serve_runs_one_worker_and_joins_it_before_closing_store(app, monkeypatch):
    install_fake_runtime(app, monkeypatch, result(app, connections=[]))
    threads = []
    original_worker, original_close = runtime.Worker, Store.close

    class Worker(original_worker):
        def run_once(self):
            threads.append(threading.current_thread())
            return super().run_once()

    def close(store):
        assert len(threads) == 1 and not threads[0].is_alive()
        original_close(store)

    monkeypatch.setattr(runtime, "Worker", Worker)
    monkeypatch.setattr(Store, "close", close)
    runtime.serve(app.cfg, FakeSecretStore({"bot": "xoxb-synthetic", "app": "xapp-synthetic"}))
    assert len(threads) == 1
