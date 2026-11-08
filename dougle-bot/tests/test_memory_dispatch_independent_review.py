"""Independent post-implementation regressions; temporary data and fake services only."""

import json

import pytest
from test_runtime import event

from dougle_bot.intake import receive
from dougle_bot.memory import MemorySession
from dougle_bot.memory_worker import dispatch_batches, run_memory_worker


@pytest.mark.parametrize("damage", ["missing", "invalid_json", "invalid_shape"])
def test_bad_dispatch_manifest_does_not_starve_later_healthy_batch(app, tmp_path, damage):
    home, root = tmp_path / "isolated-runtime", tmp_path / "knowledge"
    root.mkdir()
    ident = receive(app.store, app.cfg, event(), "UBOT")
    bad, good = MemorySession(home, root), MemorySession(home, root)
    bad.finish()
    good.finish()
    manifest = bad.path / "manifest.json"
    if damage == "missing":
        manifest.unlink()
    elif damage == "invalid_json":
        manifest.write_text("{invalid-json", encoding="utf-8")
    else:
        manifest.write_text("[]", encoding="utf-8")
    for batch in (bad, good):
        app.store.audit(ident, "system", "memory_batch_ready", batch.batch_id)

    # Repeated sweeps must make progress past permanently damaged entries.
    for _ in range(3):
        dispatch_batches(app.store, home)
    state = json.loads((good.path / "manifest.json").read_text())["state"]
    assert state == "ready"
    isolated = app.store.all("SELECT detail FROM audit WHERE action='memory_batch_isolated'")
    assert len(isolated) == 1
    assert app.store.one(
        "SELECT id FROM audit WHERE action='memory_batch_dispatched' AND detail=?",
        (isolated[0]["detail"],),
    )
    assert not dispatch_batches(app.store, home)


@pytest.mark.parametrize("failing_count", [1, 17, 33])
def test_transient_failures_rotate_past_page_limit_and_can_recover(
    app, tmp_path, monkeypatch, failing_count
):
    import dougle_bot.memory as memory

    home, root = tmp_path / "isolated-runtime", tmp_path / "knowledge"
    root.mkdir()
    ident = receive(app.store, app.cfg, event(), "UBOT")
    batches = [MemorySession(home, root) for _ in range(failing_count + 1)]
    for batch in batches:
        batch.finish()
        app.store.audit(ident, "system", "memory_batch_ready", batch.batch_id)
    failed = {batch.batch_id for batch in batches[:-1]}
    accept = memory.accept_batch
    attempts = []

    def flaky(home, batch_id):
        attempts.append(batch_id)
        if batch_id in failed:
            raise OSError("private storage path must not be logged")
        return accept(home, batch_id)

    monkeypatch.setattr(memory, "accept_batch", flaky)
    results = []
    for _ in range(failing_count // 16 + 1):
        before = len(attempts)
        results.append(dispatch_batches(app.store, home))
        assert len(attempts) - before <= 16
    assert results == [False] * (len(results) - 1) + [True]
    assert json.loads((batches[-1].path / "manifest.json").read_text())["state"] == "ready"
    assert all(
        json.loads((batch.path / "manifest.json").read_text())["state"] == "pending"
        for batch in batches[:-1]
    )
    assert len(app.store.all("SELECT id FROM audit WHERE action='memory_batch_dispatched'")) == 1
    assert not app.store.all("SELECT id FROM audit WHERE action='memory_batch_isolated'")
    assert not dispatch_batches(app.store, home)  # Only retry failures: back off.
    failed.clear()
    for _ in range(failing_count // 16 + 1):
        dispatch_batches(app.store, home)
    assert all(
        json.loads((batch.path / "manifest.json").read_text())["state"] == "ready"
        for batch in batches
    )
    assert not dispatch_batches(app.store, home)
    assert "private storage" not in repr(app.store.all("SELECT detail FROM audit"))


def test_save_loop_backs_off_when_every_dispatch_is_retryable(app, tmp_path, monkeypatch):
    import dougle_bot.memory as memory

    home, root = tmp_path / "isolated-runtime", tmp_path / "knowledge"
    root.mkdir()
    ident = receive(app.store, app.cfg, event(), "UBOT")
    batch = MemorySession(home, root)
    batch.finish()
    app.store.audit(ident, "system", "memory_batch_ready", batch.batch_id)
    attempts = []

    def unavailable(*args):
        attempts.append(1)
        assert len(attempts) <= 3, "retryable dispatches must not busy-loop"
        raise OSError("temporarily unavailable")

    class Stop:
        waits = 0

        def is_set(self):
            return self.waits >= 3

        def wait(self, seconds):
            assert seconds == 1
            self.waits += 1
            return self.is_set()

    monkeypatch.setattr(memory, "accept_batch", unavailable)
    monkeypatch.setattr(memory, "drain_one", lambda home: False)
    stop = Stop()
    run_memory_worker(app.store, home, stop)
    assert stop.waits == len(attempts) == 3
    assert not app.store.all("SELECT id FROM audit WHERE action='memory_batch_dispatched'")
