"""Cases M07-M09/R05: tests written after worker/spool integration implementation."""

import copy
import threading
from pathlib import Path

from test_answer_style import READS, SESSION, SKILLS, answer
from test_runtime import event

from dougle_bot.engine import EngineResult
from dougle_bot.guard import decision
from dougle_bot.intake import receive
from dougle_bot.memory import MemorySession, drain_one
from dougle_bot.memory_worker import dispatch_batches, run_memory_worker


def batch(app):
    root = app.cfg.home.parent / (app.cfg.home.name + "-knowledge")
    root.mkdir()
    source = root / "topic.md"
    source.write_text("이전 안내입니다.\n")
    session = MemorySession(app.cfg.home, root)
    policy = {
        "memory": str(root),
        "runtime_home": str(app.cfg.home),
        "allowed": ["Read", "Write"],
        "skills": [],
        **session.policy_fields(),
    }
    read = decision(policy, {"tool_name": "Read", "tool_input": {"file_path": str(source)}})
    assert read["hookSpecificOutput"]["permissionDecision"] == "allow"
    write = decision(
        policy,
        {
            "tool_name": "Write",
            "tool_input": {"file_path": str(source), "content": "새 안내입니다.\n"},
        },
    )
    result = write["hookSpecificOutput"]
    assert result["permissionDecision"] == "allow"
    Path(result["updatedInput"]["file_path"]).write_text("새 안내입니다.\n")
    return source, session.finish()


def run(app, results):
    app.engine.results = results
    ident = receive(app.store, app.cfg, event(), "UBOT")
    assert app.worker.run_once()
    return app.store.inquiry(ident)


def test_validated_answer_enqueues_memory_without_committing_on_answer_path(app, monkeypatch):
    source, ident = batch(app)
    result = EngineResult(SESSION, answer(), 0.01, SKILLS, READS, memory_batch=ident)
    row = run(app, [result])
    assert row["status"] == "awaiting_approval"
    assert source.read_text() == "이전 안내입니다.\n"
    assert (
        app.store.one("SELECT detail FROM audit WHERE action='memory_batch_ready'")["detail"]
        == ident
    )
    assert not drain_one(app.cfg.home)  # Pending alone is not authorized to commit.
    assert dispatch_batches(app.store, app.cfg.home)
    assert drain_one(app.cfg.home)
    assert source.read_text() == "새 안내입니다.\n"
    assert not dispatch_batches(app.store, app.cfg.home)
    assert not drain_one(app.cfg.home)


def test_failed_inquiry_never_accepts_its_staged_knowledge(app):
    source, ident = batch(app)
    result = EngineResult(SESSION, answer(), 0.01, SKILLS, {}, memory_batch=ident)
    row = run(app, [result, EngineResult(SESSION, answer(), 0.01, SKILLS, {})])
    assert row["status"] == "failed"
    assert not app.store.one("SELECT id FROM audit WHERE action='memory_batch_ready'")
    assert dispatch_batches(app.store, app.cfg.home)
    assert not drain_one(app.cfg.home)
    assert source.read_text() == "이전 안내입니다.\n"


def test_record_repair_accepts_the_original_validated_proof_batch(app):
    source, ident = batch(app)
    first = answer()
    second = copy.deepcopy(first)
    first["verification"]["checks"][0]["source_index"] = 1
    row = run(
        app,
        [
            EngineResult(SESSION, first, 0.01, SKILLS, READS, memory_batch=ident),
            EngineResult(SESSION, second, 0.01, SKILLS, {}),
        ],
    )
    assert row["status"] == "awaiting_approval"
    assert dispatch_batches(app.store, app.cfg.home)
    assert drain_one(app.cfg.home)
    assert source.read_text() == "새 안내입니다.\n"


def test_cancel_after_validation_before_dispatch_discards_proposal(app):
    source, ident = batch(app)
    row = run(app, [EngineResult(SESSION, answer(), 0.01, SKILLS, READS, memory_batch=ident)])
    with app.store.tx() as db:
        db.execute("UPDATE inquiries SET status='cancelled' WHERE id=?", (row["id"],))
    dispatch_batches(app.store, app.cfg.home)
    assert not drain_one(app.cfg.home)
    assert source.read_text() == "이전 안내입니다.\n"


def test_dispatch_filesystem_failure_does_not_change_answer_and_remains_retryable(app, monkeypatch):
    import dougle_bot.memory as memory

    source, ident = batch(app)
    row = run(app, [EngineResult(SESSION, answer(), 0.01, SKILLS, READS, memory_batch=ident)])
    accept = memory.accept_batch

    def fail(*args):
        raise OSError("synthetic unavailable storage")

    monkeypatch.setattr(memory, "accept_batch", fail)
    assert not dispatch_batches(app.store, app.cfg.home)
    assert app.store.inquiry(row["id"])["status"] == "awaiting_approval"
    assert not app.store.one("SELECT id FROM audit WHERE action='memory_batch_dispatched'")
    monkeypatch.setattr(memory, "accept_batch", accept)
    assert dispatch_batches(app.store, app.cfg.home)
    assert drain_one(app.cfg.home)
    assert source.read_text() == "새 안내입니다.\n"


def test_save_loop_respects_stop_without_running_commit(app, monkeypatch):
    import dougle_bot.memory as memory

    def unexpected(*args):
        raise AssertionError("must not commit after stop")

    monkeypatch.setattr(memory, "drain_one", unexpected)
    stop = threading.Event()
    stop.set()
    run_memory_worker(app.store, app.cfg.home, stop)


def test_memory_error_is_recorded_without_rejecting_a_valid_answer(app):
    result = EngineResult(
        SESSION, answer(), 0.01, SKILLS, READS, memory_error="memory_staging_unavailable"
    )
    row = run(app, [result])
    assert row["status"] == "awaiting_approval"
    record = app.store.one("SELECT detail FROM audit WHERE action='memory_batch_failed'")
    assert record["detail"] == "memory_staging_unavailable"
    assert not app.store.one("SELECT id FROM audit WHERE action='memory_batch_ready'")
