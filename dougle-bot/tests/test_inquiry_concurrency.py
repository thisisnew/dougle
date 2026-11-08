"""P01–P15/R07–R08 acceptance: production code preceded these tests.

Real worker/SQLite and controlled fake engines/Slack. The subprocess test executes
ClaudeCliEngine.run against a local synthetic CLI, never a model or company service.
"""

import json
import sqlite3
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from dataclasses import replace
from pathlib import Path

import pytest
from test_answer_style import READS, SKILLS, answer, call, outcome
from test_boundaries import fake_cli
from test_evidence import output, read_event
from test_runtime import event
from test_startup import install_fake_runtime, result

from dougle_bot import memory_worker, runtime
from dougle_bot.config import load, save
from dougle_bot.engine import ClaudeCliEngine, EngineResult
from dougle_bot.intake import receive
from dougle_bot.safety import BotError
from dougle_bot.secrets import FakeSecretStore
from dougle_bot.slack_gateway import SlackFailure
from dougle_bot.store import SCHEMA, Store


def enqueue(app, number, label, *, root=None):
    kwargs = {"thread_ts": root} if root else {}
    return receive(
        app.store,
        app.cfg,
        event(
            ts=f"1001.{number:06d}", text=f"<@UBOT> 입고는 언제 저장하나요? case_{label}", **kwargs
        ),
        "UBOT",
    )


def marker(text):
    payload = json.loads(text.split("DATA_JSON_START\n", 1)[1].split("\nDATA_JSON_END", 1)[0])
    return payload["question"].split("case_", 1)[1]


def engine_result(label, session=None, value=None):
    return EngineResult(
        session or str(uuid.uuid4()),
        value or answer(f"case_{label} 확인했습니다."),
        0.02,
        SKILLS,
        READS,
    )


def wait_for(predicate):
    deadline = time.monotonic() + 8
    while not predicate():
        assert time.monotonic() < deadline, "concurrent operation did not complete"
        threading.Event().wait(0.01)


def automatic(app):
    app.cfg = replace(app.cfg, slack=replace(app.cfg.slack, require_approval=False))
    app.worker.config = app.cfg


@contextmanager
def lanes(app, limit=2):
    app.store.configure_processing(limit)
    stop = threading.Event()
    with ThreadPoolExecutor(max_workers=limit) as pool:
        futures = [
            pool.submit(runtime.process_inquiries, app.worker, app.store, stop)
            for _ in range(limit)
        ]
        try:
            yield stop
        finally:
            stop.set()
            app.store.stop()
            for future in futures:
                future.result(timeout=10)


@pytest.mark.parametrize("limit", [1, 2, 4, 6])
def test_r07_limit_is_shared_by_connections_and_explicit_config_is_not_migrated(app, limit):
    cfg = replace(app.cfg, engine=replace(app.cfg.engine, max_parallel=limit))
    path = app.cfg.home / "config.toml"
    save(cfg, path)
    before = path.read_bytes()
    assert b"parallelism_version = 1\n" in before
    assert load(path).engine.max_parallel == limit
    app.store.configure_processing(load(path).engine.max_parallel)
    observer = Store(app.store.path, migrate=False)
    try:
        assert observer.processing_status() == {"max_parallel": limit, "active": 0, "queued": 0}
    finally:
        observer.close()
    assert path.read_bytes() == before
    path.write_text(path.read_text().replace(f"max_parallel = {limit}\n", ""))
    assert load(path).engine.max_parallel == 4


def test_r07_legacy_saved_one_upgrades_in_memory_without_changing_file(app):
    path = app.cfg.home / "config.toml"
    legacy = replace(app.cfg, engine=replace(app.cfg.engine, max_parallel=1))
    save(legacy, path)
    path.write_text(path.read_text().replace("parallelism_version = 1\n", ""))
    before, info = path.read_bytes(), path.stat()
    for _ in range(2):
        upgraded = load(path)
        assert upgraded.engine.max_parallel == 4
        assert upgraded.engine.parallelism_version == 1
        assert replace(upgraded.engine, max_parallel=1) == legacy.engine
        assert upgraded.slack == legacy.slack and upgraded.home == legacy.home
    after = path.stat()
    assert path.read_bytes() == before
    assert (after.st_ino, after.st_mtime_ns, after.st_mode) == (
        info.st_ino,
        info.st_mtime_ns,
        info.st_mode,
    )
    app.store.configure_processing(upgraded.engine.max_parallel)
    assert app.store.processing_status()["max_parallel"] == 4
    # A later explicit user save may select serial mode; it must survive all reloads.
    serial = replace(upgraded, engine=replace(upgraded.engine, max_parallel=1))
    save(serial, path)
    assert b"parallelism_version = 1\n" in path.read_bytes()
    assert load(path).engine.max_parallel == 1
    save(load(path), path)
    assert load(path).engine.max_parallel == 1


@pytest.mark.parametrize("setting", ["absent", "2", "3", "4"])
def test_r07_unmarked_configuration_uses_default_or_keeps_valid_nonlegacy_limit(app, setting):
    path = app.cfg.home / "config.toml"
    save(app.cfg, path)
    content = path.read_text().replace("parallelism_version = 1\n", "")
    content = content.replace(
        "max_parallel = 4\n", "" if setting == "absent" else f"max_parallel = {setting}\n"
    )
    path.write_text(content)
    assert load(path).engine.max_parallel == (4 if setting == "absent" else int(setting))
    assert path.read_text() == content


@pytest.mark.parametrize(
    "field,value",
    [
        ("max_parallel", "true"),
        ("max_parallel", "1.0"),
        ("max_parallel", '"1"'),
        ("max_parallel", "0"),
        ("max_parallel", "-1"),
        ("parallelism_version", "true"),
        ("parallelism_version", "1.0"),
        ("parallelism_version", '"1"'),
        ("parallelism_version", "0"),
        ("parallelism_version", "2"),
    ],
)
def test_r07_migration_rejects_invalid_types_and_unknown_marker_without_rewrite(app, field, value):
    path = app.cfg.home / "config.toml"
    save(replace(app.cfg, engine=replace(app.cfg.engine, max_parallel=1)), path)
    content = path.read_text().replace(f"{field} = 1\n", f"{field} = {value}\n")
    if field == "max_parallel":
        content = content.replace("parallelism_version = 1\n", "")
    path.write_text(content)
    with pytest.raises(BotError, match="invalid_integer_setting"):
        load(path)
    assert path.read_text() == content


@pytest.mark.parametrize("limit", [0, -1, True, 1.5, "2", 2**63])
def test_r07_invalid_parallelism_rejected_without_services(app, limit):
    cfg = replace(app.cfg, engine=replace(app.cfg.engine, max_parallel=limit))
    if limit != 2**63:
        with pytest.raises(BotError, match="invalid_integer_setting"):
            cfg.validate()
    with pytest.raises(BotError, match="invalid_integer_setting"):
        app.store.configure_processing(limit)


@pytest.mark.parametrize("limit", [1, 2, 4, 6])
def test_p03_p11_atomic_global_claim_limit_across_connections(app, limit):
    app.store.configure_processing(limit)
    for number in range(1, 9):
        enqueue(app, number, str(number))
    barrier = threading.Barrier(8)

    def claim(_):
        store = Store(app.store.path, clock=app.store.clock)
        try:
            barrier.wait(timeout=5)
            with store.processing():
                return store.claim_job()
        finally:
            store.close()

    with ThreadPoolExecutor(max_workers=8) as pool:
        jobs = [job for job in pool.map(claim, range(8)) if job]
    assert len(jobs) == len({job["id"] for job in jobs}) == limit
    assert app.store.processing_status() == {
        "max_parallel": limit,
        "active": limit,
        "queued": 8 - limit,
    }


@pytest.mark.parametrize("transition", ["done", "cancelled", "failed"])
def test_p04_p08_lease_outlives_job_transition_and_prevents_slot_reuse(app, transition):
    first = enqueue(app, 1, "A")
    following = enqueue(app, 2, "A2", root="1001.000001")
    other = enqueue(app, 3, "B")
    with app.store.processing():
        job = app.store.claim_job()
        if transition == "done":
            app.store.save_draft(first, answer(), "engine", "hash", "1001.000001", job_id=job["id"])
        elif transition == "cancelled":
            app.worker.show_card(first)
            row = app.store.inquiry(first)
            app.store.cancel(first, job["id"], "UAPPROVER", row["card_channel_id"], row["card_ts"])
        else:
            app.store.fail_job(job, "engine_failed")
        peer = Store(app.store.path, clock=app.store.clock)
        try:
            with peer.processing():
                assert peer.claim_job()["inquiry_id"] == other
                assert peer.claim_job() is None
                assert peer.processing_status()["active"] == 2
                assert peer.inquiry(following)["status"] == "queued"
            assert peer.claim_job() is None  # Finished/cancelled A still occupies its slot.
        finally:
            peer.close()
    assert app.store.claim_job()["inquiry_id"] == following
    assert app.store.all("SELECT * FROM processing_claims") == []


def test_p01_p02_p03_real_overlap_short_answer_first_third_waits_without_reservation(app):
    """P01/P02: B posts while A remains gated; P03/P15: C waits without a reservation."""
    automatic(app)
    ids = {label: enqueue(app, n, label) for n, label in enumerate("ABC", 1)}
    started = {label: threading.Event() for label in "ABC"}
    release = {label: threading.Event() for label in "AB"}
    calls, intervals, lock = [], {}, threading.Lock()

    class Engine:
        def run(self, text, session, budget, **kwargs):
            label = marker(text)
            with lock:
                calls.append((label, session, kwargs["timeout_seconds"]))
                intervals[label] = [time.monotonic(), None]
            started[label].set()
            if label in release:
                assert release[label].wait(8)
            intervals[label][1] = time.monotonic()
            return engine_result(label)

    app.worker.engine = Engine()
    with lanes(app):
        try:
            assert started["A"].wait(5) and started["B"].wait(5)
            assert not started["C"].is_set()
            assert app.store.inquiry(ids["C"])["status"] == "queued"
            assert app.store.one("SELECT COUNT(*) n FROM engine_runs")["n"] == 2
            app.now[0] += 120  # C's queue wait is not a model attempt or its timeout.
            release["B"].set()
            wait_for(lambda: app.store.inquiry(ids["B"])["status"] == "posted")
            assert not release["A"].is_set() and intervals["A"][1] is None
            posted = [row for row in app.slack.calls if row[0] == "post"]
            assert (
                sum(row[2] == "1001.000002" and row[3] == "case_B 확인했습니다." for row in posted)
                == 1
            )
            assert not any(row[2] == "1001.000001" for row in posted)
            assert app.store.inquiry(ids["A"])["status"] == "drafting"
            assert started["C"].wait(5)
            wait_for(lambda: app.store.inquiry(ids["C"])["status"] == "posted")
            release["A"].set()
            wait_for(lambda: app.store.inquiry(ids["A"])["status"] == "posted")
        finally:
            for gate in release.values():
                gate.set()
    assert intervals["A"][0] < intervals["B"][1] < intervals["A"][1]
    assert intervals["B"][0] < intervals["A"][1]
    assert all(session is None and 899 < timeout <= 900 for _, session, timeout in calls)
    timing = app.store.one(
        "SELECT detail FROM audit WHERE action='answer_timing' AND inquiry_id=?", (ids["C"],)
    )
    assert json.loads(timing["detail"])["queue_ms"] == 120_000
    assert app.store.processing_status()["active"] == 0
    assert len({row["engine_session_id"] for row in app.store.all("SELECT * FROM threads")}) == 3


def test_p04_p05_p13_same_thread_waits_for_post_and_resumes_own_session(app, monkeypatch):
    automatic(app)
    first = enqueue(app, 1, "A")
    following = enqueue(app, 2, "A2", root="1001.000001")
    other = enqueue(app, 3, "B")
    posting, release = threading.Event(), threading.Event()
    original_post, calls = app.slack.post, {}

    def post(channel, root, body, delivery):
        if body.startswith("case_A "):
            posting.set()
            assert release.wait(8)
        return original_post(channel, root, body, delivery)

    class Engine:
        def run(self, text, session, budget, **kwargs):
            label = marker(text)
            result = engine_result(label, session)
            calls[label] = (session, result.session_id)
            return result

    monkeypatch.setattr(app.slack, "post", post)
    app.worker.engine = Engine()
    with lanes(app):
        try:
            assert posting.wait(5)
            wait_for(lambda: app.store.inquiry(other)["status"] == "posted")
            assert app.store.inquiry(first)["status"] == "posting"
            assert app.store.inquiry(following)["status"] == "queued"
            assert "A2" not in calls
            release.set()
            wait_for(lambda: app.store.inquiry(following)["status"] == "posted")
        finally:
            release.set()
    assert calls["A2"] == (calls["A"][1], calls["A"][1])
    assert calls["A"][1] != calls["B"][1]
    posts = [row for row in app.slack.calls if row[0] == "post"]
    assert [row[3] for row in posts if row[2] == "1001.000001"] == [
        "case_A 확인했습니다.",
        "case_A2 확인했습니다.",
    ]


@pytest.mark.parametrize("reason", ["cancel", "stop"])
def test_p08_p09_two_active_engines_cleanup_before_claim_release(app, reason):
    ids = {label: enqueue(app, n, label) for n, label in enumerate("AB", 1)}
    app.worker.show_card(ids["A"])
    automatic(app)
    entered = {label: threading.Event() for label in "AB"}
    winding_down, release = threading.Event(), threading.Event()
    calls = []
    stop = threading.Event()

    class Engine:
        def run(self, text, session, budget, *, cancel_requested, **kwargs):
            label = marker(text)
            calls.append(label)
            if label in entered:
                entered[label].set()
            if label == "A":
                wait_for(lambda: stop.is_set() or cancel_requested())
                winding_down.set()
                assert release.wait(8)
                raise BotError("bot_stopped" if stop.is_set() else "inquiry_cancelled")
            assert release.wait(8)
            if stop.is_set():
                raise BotError("bot_stopped")
            return engine_result(label)

    app.worker.engine = Engine()
    with lanes(app) as active_stop:
        stop = active_stop
        try:
            assert entered["A"].wait(5) and entered["B"].wait(5)
            following = enqueue(app, 3, "A2", root="1001.000001")
            if reason == "cancel":
                inquiry = app.store.inquiry(ids["A"])
                job = app.store.one("SELECT id FROM jobs WHERE inquiry_id=?", (ids["A"],))
                app.store.cancel(
                    ids["A"], job["id"], "UAPPROVER", inquiry["card_channel_id"], inquiry["card_ts"]
                )
            else:
                stop.set()
                app.store.stop()
            assert winding_down.wait(5)
            assert app.store.processing_status()["active"] == 2
            assert app.store.inquiry(following)["status"] == "queued"
            release.set()
            if reason == "cancel":
                wait_for(lambda: app.store.inquiry(following)["status"] == "posted")
                wait_for(lambda: app.store.inquiry(ids["B"])["status"] == "posted")
        finally:
            release.set()
    assert app.store.processing_status()["active"] == 0
    assert app.store.all("SELECT * FROM processing_claims") == []
    assert app.store.inquiry(ids["A"])["status"] == (
        "cancelled" if reason == "cancel" else "failed"
    )
    assert len([row for row in app.slack.calls if row[0] == "post"]) == (
        2 if reason == "cancel" else 0
    )
    assert ("A2" in calls) == (reason == "cancel")


@pytest.mark.parametrize("failed", [False, True])
def test_p06_p07_failure_or_developer_wait_releases_slot_for_other_inquiry(app, failed):
    automatic(app)
    first = enqueue(app, 1, "A")
    other = enqueue(app, 2, "B")

    class Engine:
        def run(self, text, session, budget, **kwargs):
            label = marker(text)
            if label == "A":
                if failed:
                    raise BotError("engine_failed")
                value = output("current_state", ("live",), ("live",), status="needs_lookup")
                return engine_result(label, value=value)
            return engine_result(label)

    app.worker.engine = Engine()
    with lanes(app, 1):
        wait_for(lambda: app.store.inquiry(other)["status"] == "posted")
    assert app.store.inquiry(first)["status"] == ("failed" if failed else "needs_lookup")
    assert app.store.processing_status()["active"] == 0


@pytest.mark.parametrize("terminal", [False, True])
def test_p10_restart_clears_leases_but_preserves_atomic_completed_job(app, terminal):
    first = enqueue(app, 1, "A")
    following = enqueue(app, 2, "A2", root="1001.000001")
    job = app.store.claim_job()
    if terminal:
        app.store.save_draft(first, answer(), "engine", "hash", "1001.000001", job_id=job["id"])
    with app.store.tx() as db:
        db.execute(
            "INSERT INTO processing_claims VALUES(?,?,?)",
            (job["thread_key"], job["id"], "dead-owner"),
        )
    with runtime.InstanceLock(app.cfg.home / "runtime.lock"):
        restarted = Store(app.store.path, clock=app.store.clock)
        try:
            restarted.recover()
            restarted.configure_processing(2)
            with restarted.processing():
                assert restarted.claim_job()["inquiry_id"] == (following if terminal else first)
                assert app.store.claim_job() is None
        finally:
            restarted.close()
    assert app.store.all("SELECT * FROM processing_claims") == []


def test_p12_simultaneous_budget_claims_cannot_overspend(app):
    barrier = threading.Barrier(8)

    def reserve(_):
        store = Store(app.store.path, clock=app.store.clock)
        try:
            barrier.wait(timeout=5)
            try:
                return store.reserve(None, 1, 1, False)[1]
            except BotError as exc:
                assert exc.code == "daily_budget_exceeded"
                return 0
        finally:
            store.close()

    with ThreadPoolExecutor(max_workers=8) as pool:
        assert sum(pool.map(reserve, range(8))) == 1
    assert app.store.one("SELECT COUNT(*) n FROM engine_runs")["n"] == 1


@pytest.mark.parametrize("version", [1, 2])
def test_r07_status_old_schema_with_migrate_false_never_creates_coordination(tmp_path, version):
    path = tmp_path / "legacy.db"
    with sqlite3.connect(path) as db:
        db.executescript(SCHEMA)
        if version == 2:
            db.execute("ALTER TABLE drafts ADD COLUMN verification_json TEXT")
        db.execute(f"PRAGMA user_version={version}")
        schema = db.execute("SELECT name,sql FROM sqlite_master ORDER BY name").fetchall()
    store = Store(path, migrate=False)
    try:
        assert store.processing_status() == {"max_parallel": None, "active": 0, "queued": 0}
        assert [
            tuple(row.values())
            for row in store.all("SELECT name,sql FROM sqlite_master ORDER BY name")
        ] == schema
    finally:
        store.close()


def test_memory_ready_and_job_done_are_one_transaction_and_batch_ids_deduplicate(app):
    ident = enqueue(app, 1, "A")
    job = app.store.claim_job()
    batches = (uuid.uuid4().hex, uuid.uuid4().hex)
    with app.store.tx() as db:
        db.execute(
            "CREATE TRIGGER fail_memory_audit BEFORE INSERT ON audit WHEN NEW.action='memory_batch_ready' BEGIN SELECT RAISE(ABORT,'test write failure'); END"
        )
    with pytest.raises(sqlite3.IntegrityError):
        app.store.save_draft(
            ident,
            answer(),
            "engine",
            "hash",
            "1001.000001",
            job_id=job["id"],
            memory_batches=batches,
        )
    assert app.store.one("SELECT status FROM jobs WHERE id=?", (job["id"],))["status"] == "running"
    assert app.store.inquiry(ident)["current_draft_id"] is None
    assert not app.store.all("SELECT * FROM drafts")
    with app.store.tx() as db:
        db.execute("DROP TRIGGER fail_memory_audit")
    app.store.save_draft(
        ident,
        answer(),
        "engine",
        "hash",
        "1001.000001",
        job_id=job["id"],
        memory_batches=(*batches, batches[0]),
    )
    observer = Store(app.store.path, migrate=False)
    try:
        assert observer.one("SELECT status FROM jobs WHERE id=?", (job["id"],))["status"] == "done"
        assert {
            row["detail"]
            for row in observer.all("SELECT detail FROM audit WHERE action='memory_batch_ready'")
        } == set(batches)
        assert observer.inquiry(ident)["current_draft_id"] is not None
    finally:
        observer.close()


@pytest.mark.parametrize("batch", ["/tmp/path", "../escape", "a" * 31, "A" * 32, None])
def test_memory_ready_rejects_paths_or_invalid_identity_without_partial_save(app, batch):
    ident = enqueue(app, 1, "A")
    job = app.store.claim_job()
    with pytest.raises(BotError, match="invalid_memory_batch"):
        app.store.save_draft(
            ident,
            answer(),
            "engine",
            "hash",
            "1001.000001",
            job_id=job["id"],
            memory_batches=(batch,),
        )
    assert app.store.one("SELECT status FROM jobs WHERE id=?", (job["id"],))["status"] == "running"
    assert not app.store.all("SELECT * FROM drafts")


@pytest.mark.parametrize("limit", [1, 2, 4, 6])
def test_runtime_starts_exact_lanes_shares_worker_and_joins_memory_and_inquiries(
    app, monkeypatch, limit
):
    install_fake_runtime(app, monkeypatch, result(app, connections=[]))
    cfg = replace(app.cfg, engine=replace(app.cfg.engine, max_parallel=limit))
    barrier = threading.Barrier(limit + 1)
    workers, finished, threads, errors = [], [], [], []
    original_close = Store.close

    def inquiry(worker, store, stop):
        try:
            workers.append(worker)
            threads.append(threading.current_thread())
            barrier.wait(timeout=5)
            stop.set()
            store.stop()
            store.audit(None, "system", "test_inquiry_cleanup")
            finished.append("inquiry")
        except BaseException as exc:
            errors.append(exc)
            stop.set()

    def memory(store, home, stop):
        threads.append(threading.current_thread())
        barrier.wait(timeout=5)
        assert stop.wait(5)
        store.audit(None, "system", "test_memory_cleanup")
        finished.append("memory")

    def close(store):
        assert len(finished) == limit + 1
        assert all(not thread.is_alive() for thread in threads)
        original_close(store)

    monkeypatch.setattr(runtime, "process_inquiries", inquiry)
    monkeypatch.setattr(memory_worker, "run_memory_worker", memory)
    monkeypatch.setattr(Store, "close", close)
    runtime.serve(cfg, FakeSecretStore({"bot": "xoxb-synthetic", "app": "xapp-synthetic"}))
    assert errors == [] and len(workers) == limit
    assert len({id(worker) for worker in workers}) == 1
    assert app.store.processing_status()["max_parallel"] == limit


def test_p01_p02_actual_claude_cli_run_overlaps_using_only_synthetic_local_processes(
    app, tmp_path, monkeypatch
):
    """P01/P02: actual ClaudeCliEngine.run overlaps through two runtime inquiry lanes.

    Both child start files must exist before either child may finish. B must post
    through FakeSlack before A's release file exists, while A's engine run is open.
    The lanes share the production Worker; only the CLI and Slack are synthetic.
    """
    automatic(app)
    first, second = enqueue(app, 1, "A"), enqueue(app, 2, "B")
    isolated_home = tmp_path / "synthetic-user"
    topic = isolated_home / ".dougle" / "topics" / "receiving.md"
    topic.parent.mkdir(parents=True)
    topic.write_text("Synthetic receiving evidence.")
    monkeypatch.setattr(Path, "home", classmethod(lambda cls: isolated_home))
    gates = tmp_path / "gates"
    gates.mkdir()
    events = [call(name, f"s{i}") for i, name in enumerate(sorted(SKILLS))]
    events += [
        outcome(f"s{i}", f"Launching skill: {name}") for i, name in enumerate(sorted(SKILLS))
    ]
    events += [
        read_event(args={"file_path": str(topic)}),
        outcome("read1", "Synthetic receiving evidence."),
    ]
    outputs = {}
    for label in "AB":
        outputs[label] = [
            *events,
            {
                "type": "result",
                "session_id": str(uuid.uuid4()),
                "structured_output": answer(f"case_{label} 확인했습니다."),
                "total_cost_usd": 0.02,
            },
        ]
    script = fake_cli(
        tmp_path,
        f"""
import json, sys, time
from pathlib import Path
text = sys.stdin.read()
payload = json.loads(text.split("DATA_JSON_START\\n", 1)[1].split("\\nDATA_JSON_END", 1)[0])
label = payload["question"].split("case_", 1)[1]
gates = Path({str(gates)!r})
(gates / (label + ".started")).write_text("started")
deadline = time.monotonic() + 8
while not (gates / (label + ".release")).exists():
    if time.monotonic() > deadline:
        sys.exit(7)
    time.sleep(0.01)
for item in {outputs!r}[label]:
    print(json.dumps(item, ensure_ascii=False), flush=True)
""",
    )
    engine_cfg = replace(app.cfg.engine, claude_bin=script)
    app.worker.engine = ClaudeCliEngine(engine_cfg, tmp_path / "engine-runtime")
    with lanes(app):
        try:
            wait_for(lambda: all((gates / f"{label}.started").exists() for label in "AB"))
            assert app.store.processing_status()["active"] == 2
            assert not any((gates / f"{label}.release").exists() for label in "AB")
            (gates / "B.release").touch()
            wait_for(lambda: app.store.inquiry(second)["status"] == "posted")
            assert not (gates / "A.release").exists()
            posted = [row for row in app.slack.calls if row[0] == "post"]
            assert [row[1:4] for row in posted] == [
                ("CTEST", "1001.000002", "case_B 확인했습니다.")
            ]
            assert (
                app.store.one("SELECT finished FROM engine_runs WHERE inquiry_id=?", (first,))[
                    "finished"
                ]
                == 0
            )
            assert app.store.inquiry(first)["status"] == "drafting"
            (gates / "A.release").touch()
            wait_for(lambda: app.store.inquiry(first)["status"] == "posted")
        finally:
            for label in "AB":
                (gates / f"{label}.release").touch()
    assert (
        app.store.inquiry(first)["engine_session_id"]
        != app.store.inquiry(second)["engine_session_id"]
    )
    assert app.store.one("SELECT COUNT(*) n FROM engine_runs WHERE ok=1 AND finished=1")["n"] == 2


def test_p06_retry_keeps_own_session_and_does_not_block_other_thread(app):
    automatic(app)
    first, second = enqueue(app, 1, "A"), enqueue(app, 2, "B")
    retrying, release = threading.Event(), threading.Event()
    calls = []
    first_session = str(uuid.uuid4())

    class Engine:
        def run(self, text, session, budget, **kwargs):
            label = marker(text)
            if label == "B":
                return engine_result(label)
            calls.append(session)
            result = engine_result(label, first_session)
            if len(calls) == 1:
                result.activated = set()  # Actual worker correction path, one retry.
            else:
                retrying.set()
                assert release.wait(8)
            return result

    app.worker.engine = Engine()
    with lanes(app):
        try:
            assert retrying.wait(5)
            wait_for(lambda: app.store.inquiry(second)["status"] == "posted")
            assert app.store.inquiry(first)["status"] == "drafting"
            release.set()
            wait_for(lambda: app.store.inquiry(first)["status"] == "posted")
        finally:
            release.set()
    assert calls == [None, first_session]
    assert (
        app.store.one("SELECT COUNT(*) n FROM engine_runs WHERE inquiry_id=?", (first,))["n"] == 2
    )


def test_p10_p13_uncertain_send_holds_thread_across_restart_without_duplicate(app, monkeypatch):
    automatic(app)
    first, other = enqueue(app, 1, "A"), enqueue(app, 2, "B")
    original_post = app.slack.post

    def uncertain(channel, root, text, delivery):
        ts = original_post(channel, root, text, delivery)
        if root == "1001.000001":
            raise SlackFailure("slack_connection_failed", uncertain=True)
        return ts

    class Engine:
        def run(self, text, session, budget, **kwargs):
            return engine_result(marker(text), session)

    monkeypatch.setattr(app.slack, "post", uncertain)
    app.worker.engine = Engine()
    with lanes(app):
        wait_for(lambda: app.store.inquiry(first)["status"] == "post_unknown")
        wait_for(lambda: app.store.inquiry(other)["status"] == "posted")
        following = enqueue(app, 3, "A2", root="1001.000001")
        independent = enqueue(app, 4, "C")
        wait_for(lambda: app.store.inquiry(independent)["status"] == "posted")
        assert app.store.inquiry(following)["status"] == "queued"
    count = len([row for row in app.slack.calls if row[0] == "post"])
    with runtime.InstanceLock(app.cfg.home / "runtime.lock"):
        app.store.recover()
        app.store.begin_run(2, "TTEST", "UBOT")
        with app.store.processing():
            assert not app.worker.run_once()
    assert app.store.inquiry(following)["status"] == "queued"
    assert len([row for row in app.slack.calls if row[0] == "post"]) == count
