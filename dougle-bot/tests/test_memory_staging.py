"""Memory staging acceptance cases; temporary files and hook inputs only."""

import json
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from pathlib import Path
from threading import Barrier, Event

import pytest

from dougle_bot import memory
from dougle_bot.evidence import read_kinds, read_metadata
from dougle_bot.guard import decision, permitted
from dougle_bot.memory import MemorySession, accept_batch, discard_batch, drain_one
from dougle_bot.safety import BotError


@pytest.fixture
def space(tmp_path):
    home, root = tmp_path / "runtime", tmp_path / "knowledge"
    root.mkdir()
    return home, root


def policy(session):
    return {
        "runtime_home": str(session.home),
        "memory": str(session.memory_root),
        "allowed": ["Read", "Write", "Edit", "Grep", "Glob"],
        "skills": [],
        **session.policy_fields(),
    }


def hook(session, name, path, **args):
    return decision(
        policy(session),
        {
            "tool_name": name,
            "tool_input": {"file_path": str(path), **args},
        },
    )["hookSpecificOutput"]


def read(session, path):
    result = hook(session, "Read", path)
    assert result["permissionDecision"] == "allow", result
    return Path(result["updatedInput"]["file_path"])


def write(session, path, content):
    result = hook(session, "Write", path, content=content)
    assert result["permissionDecision"] == "allow", result
    staged = Path(result["updatedInput"]["file_path"])
    staged.write_text(content)
    return staged


def manifest(session):
    return json.loads((session.path / "manifest.json").read_text())


def test_lazy_snapshot_read_own_changes_and_validation_gate(space):
    home, root = space
    target = root / "topic.md"
    target.write_text("base\n")
    (root / "unrelated.md").write_text("unrelated\n")
    session = MemorySession(home, root)
    assert not list((session.path / memory.STAGED_FILES).iterdir())
    staged = read(session, target)
    assert staged.read_text() == "base\n"
    assert list(manifest(session)["dependencies"]) == ["topic.md"]
    assert write(session, target, "proposed\n") == staged
    assert read(session, target).read_text() == "proposed\n"
    assert read(session, staged) == staged
    other = MemorySession(home, root)
    assert read(other, target).read_text() == "base\n"
    batch = session.finish()
    assert manifest(session)["state"] == "pending"
    assert not drain_one(home)
    assert target.read_text() == "base\n"
    accept_batch(home, batch)
    accept_batch(home, batch)
    assert drain_one(home)
    assert target.read_text() == "proposed\n"
    assert manifest(session)["state"] == "committed"
    assert (session.path / "base/topic.md").read_text() == "base\n"
    assert "-base" in (session.path / "diffs.json").read_text()
    accept_batch(home, batch)
    assert not drain_one(home)


def test_edit_uses_snapshot_and_never_shared_target(space):
    home, root = space
    target = root / "topic.md"
    target.write_text("base")
    session = MemorySession(home, root)
    staged = read(session, target)
    result = hook(session, "Edit", target, old_string="base", new_string="updated")
    assert result["permissionDecision"] == "allow"
    assert result["updatedInput"]["file_path"] == str(staged)
    staged.write_text(staged.read_text().replace("base", "updated"))
    assert read(session, target).read_text() == "updated"
    assert target.read_text() == "base"


def test_existing_write_requires_exact_read_and_new_write_captures_absence(space):
    home, root = space
    target = root / "topic.md"
    target.write_text("base")
    session = MemorySession(home, root)
    assert hook(session, "Write", target, content="overwrite")["permissionDecision"] == "deny"
    write(session, root / "new.md", "new")
    assert manifest(session)["dependencies"] == {"new.md": None}
    batch = session.finish()
    (root / "new.md").write_text("another writer")
    accept_batch(home, batch)
    assert drain_one(home)
    assert manifest(session)["state"] == "conflict"
    assert (root / "new.md").read_text() == "another writer"
    assert (session.path / memory.STAGED_FILES / "new.md").read_text() == "new"


@pytest.mark.parametrize("change", ["modify", "delete"])
def test_all_read_dependencies_checked_before_any_write(space, change):
    home, root = space
    target, dependency = root / "topic.md", root / "rule.md"
    target.write_text("base")
    dependency.write_text("rule")
    session = MemorySession(home, root)
    read(session, target)
    read(session, dependency)
    write(session, target, "proposal")
    batch = session.finish()
    if change == "modify":
        dependency.write_text("corrected rule")
    else:
        dependency.unlink()
    accept_batch(home, batch)
    assert drain_one(home)
    assert target.read_text() == "base"
    assert manifest(session)["state"] == "conflict"
    assert (session.path / "base/rule.md").read_text() == "rule"


@pytest.mark.parametrize("same_file", [False, True])
def test_parallel_sessions_and_serial_saves_preserve_conflicting_proposals(space, same_file):
    home, root = space
    for name in ("a.md", "b.md"):
        (root / name).write_text("base")
    barrier = Barrier(2)

    def propose(index):
        session = MemorySession(home, root)
        target = root / ("a.md" if same_file or index == 0 else "b.md")
        read(session, target)
        barrier.wait(timeout=3)
        write(session, target, f"proposal {index}")
        accept_batch(home, session.finish())
        return session

    with ThreadPoolExecutor(max_workers=2) as pool:
        sessions = list(pool.map(propose, (0, 1)))
    with ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(lambda _: drain_one(home), (0, 1)))
    while drain_one(home):
        pass
    states = sorted(manifest(session)["state"] for session in sessions)
    assert states == (["committed", "conflict"] if same_file else ["committed", "committed"])
    assert (root / "a.md").read_text().startswith("proposal")
    for session in sessions:
        assert list((session.path / "base").iterdir())
        assert list((session.path / "files").iterdir())


def test_failed_or_cancelled_batches_discard_but_pending_crash_batch_survives(space):
    home, root = space
    session = MemorySession(home, root)
    write(session, root / "new.md", "new")
    batch = session.finish()
    assert not drain_one(home)
    assert session.path.exists()
    discard_batch(home, batch)
    discard_batch(home, batch)
    assert not session.path.exists()
    assert not (root / "new.md").exists()
    second = MemorySession(home, root)
    second.discard()
    assert not second.path.exists()


def test_accept_cannot_promote_open_session_or_discard_accepted_proposal(space):
    home, root = space
    session = MemorySession(home, root)
    with pytest.raises(BotError, match="memory_batch_not_pending"):
        accept_batch(home, session.batch_id)
    write(session, root / "new.md", "proposal")
    accept_batch(home, session.finish())
    with pytest.raises(BotError, match="memory_batch_already_accepted"):
        session.discard()


@pytest.mark.parametrize("external_change", [False, True])
def test_partial_commit_crash_recovers_or_preserves_conflict(space, monkeypatch, external_change):
    home, root = space
    session = MemorySession(home, root)
    for name in ("a.md", "b.md"):
        path = root / name
        path.write_text("base " + name)
        read(session, path)
        write(session, path, "new " + name)
    accept_batch(home, session.finish())
    original_write = memory._write

    def crash_after_first_replace(path, data):
        original_write(path, data)
        if path == root / "a.md":
            raise OSError("simulated crash after replace before progress journal")

    monkeypatch.setattr(memory, "_write", crash_after_first_replace)
    with pytest.raises(OSError):
        drain_one(home)
    assert manifest(session)["state"] == "committing"
    assert (root / "a.md").read_text() == "new a.md"
    assert (root / "b.md").read_text() == "base b.md"
    if external_change:
        (root / "b.md").write_text("external correction")
    monkeypatch.setattr(memory, "_write", original_write)
    assert drain_one(home)
    assert manifest(session)["state"] == ("conflict" if external_change else "committed")
    assert (root / "a.md").read_text() == ("base a.md" if external_change else "new a.md")
    assert (root / "b.md").read_text() == ("external correction" if external_change else "new b.md")
    assert (session.path / "base/a.md").read_text() == "base a.md"
    assert (session.path / "base/b.md").read_text() == "base b.md"
    assert (session.path / memory.STAGED_FILES / "b.md").read_text() == "new b.md"
    assert not drain_one(home)


def test_symlinks_traversal_foreign_sessions_and_internal_metadata_denied(space):
    home, root = space
    session, other = MemorySession(home, root), MemorySession(home, root)
    outside = root.parent / "source.md"
    outside.write_text("business code")
    (root / "link.md").symlink_to(outside)
    (root / "dir").symlink_to(root.parent, target_is_directory=True)
    for target in (
        root / "link.md",
        root / "dir/source.md",
        root / "../source.md",
        outside,
        session.path / "manifest.json",
        other.path / "files/topic.md",
    ):
        assert hook(session, "Write", target, content="bad")["permissionDecision"] == "deny"
    for target in (session.path / "manifest.json", other.path / "files/topic.md", root / "link.md"):
        assert hook(session, "Read", target)["permissionDecision"] == "deny"
    staged = write(session, root / "owned.md", "safe")
    staged.unlink()
    staged.symlink_to(outside)
    assert hook(session, "Read", staged)["permissionDecision"] == "deny"
    assert outside.read_text() == "business code"


@pytest.mark.parametrize("content", ["token=private_value", "customer@example.com"])
def test_sensitive_content_denied_before_staging_and_checked_again_in_background(space, content):
    home, root = space
    session = MemorySession(home, root)
    assert (
        hook(session, "Write", root / "secret.md", content=content)["permissionDecision"] == "deny"
    )
    assert manifest(session)["dependencies"] == {}
    staged = write(session, root / "safe.md", "safe")
    staged.write_text(content)
    accept_batch(home, session.finish())
    assert drain_one(home)
    assert manifest(session)["error"] == "sensitive_content"
    assert manifest(session)["state"] == "conflict"
    assert not (root / "safe.md").exists()


def test_sealed_proposal_cannot_be_changed_or_read_through_hook(space):
    home, root = space
    session = MemorySession(home, root)
    staged = write(session, root / "new.md", "new")
    accept_batch(home, session.finish())
    assert not permitted(policy(session), "Read", {"file_path": str(staged)})
    assert hook(session, "Write", staged, content="later")["permissionDecision"] == "deny"
    assert drain_one(home)
    assert manifest(session)["state"] == "committed"
    assert (root / "new.md").read_text() == "new"


def test_hook_without_session_never_authorizes_direct_memory_write(space):
    home, root = space
    session = MemorySession(home, root)
    config = policy(session)
    config.pop("memory_session")
    result = decision(
        config,
        {
            "tool_name": "Write",
            "tool_input": {"file_path": str(root / "new.md"), "content": "new"},
        },
    )
    assert result["hookSpecificOutput"]["permissionDecision"] == "deny"


@pytest.mark.parametrize("suffix", [".md", ".json", ".py", ".sql"])
def test_staged_memory_keeps_indirect_knowledge_identity(space, suffix):
    home, root = space
    session = MemorySession(home, root)
    target = root / ("note" + suffix)
    target.write_text("curated knowledge")
    staged = read(session, target)
    args = {"file_path": str(staged)}
    assert read_kinds("Read", args) == {"knowledge"}
    assert read_metadata("Read", args)["indirect"] is True


def test_memory_grep_exact_file_tracks_dependency_but_directory_content_requires_read(space):
    home, root = space
    session = MemorySession(home, root)
    target = root / "topic.md"
    target.write_text("base")
    event = {
        "tool_name": "Grep",
        "tool_input": {
            "path": str(target),
            "pattern": "base",
            "output_mode": "content",
        },
    }
    result = decision(policy(session), event)["hookSpecificOutput"]
    assert result["permissionDecision"] == "allow"
    assert result["updatedInput"]["path"] == str(read(session, target))
    assert "topic.md" in manifest(session)["dependencies"]
    event["tool_input"]["path"] = str(root)
    assert decision(policy(session), event)["hookSpecificOutput"]["permissionDecision"] == "deny"
    event["tool_input"]["output_mode"] = "files_with_matches"
    assert decision(policy(session), event)["hookSpecificOutput"]["permissionDecision"] == "allow"


@pytest.mark.parametrize("content", ["customer@example.com", "token=existing_value"])
def test_existing_sensitive_knowledge_can_be_read_and_noop_is_discarded(space, content):
    home, root = space
    session = MemorySession(home, root)
    target = root / "existing.md"
    target.write_text(content)
    assert read(session, target).read_text() == content
    accept_batch(home, session.finish())
    assert drain_one(home)
    assert manifest(session)["noop"] is True
    assert not (session.path / "files").exists()
    assert not drain_one(home)


def test_unchanged_sensitive_dependency_does_not_block_a_clean_proposal(space):
    home, root = space
    session = MemorySession(home, root)
    target = root / "existing.md"
    target.write_text("customer@example.com")
    read(session, target)
    write(session, root / "summary.md", "general rule without personal data")
    accept_batch(home, session.finish())
    assert drain_one(home)
    assert manifest(session)["state"] == "committed"
    assert target.read_text() == "customer@example.com"


@pytest.mark.parametrize("kind", ["binary", "large"])
def test_binary_or_large_original_reads_are_not_copied_or_editable(space, monkeypatch, kind):
    home, root = space
    session = MemorySession(home, root)
    target = root / "original.data"
    monkeypatch.setattr(memory, "MAX_BYTES", 32)
    target.write_bytes(b"\xff\x00binary" if kind == "binary" else b"x" * 33)
    assert read(session, target) == target
    assert not list((session.path / "base").iterdir())
    assert not list((session.path / memory.STAGED_FILES).iterdir())
    assert not manifest(session)["dependencies"]
    assert hook(session, "Write", target, content="replace")["permissionDecision"] == "deny"
    accept_batch(home, session.finish())
    assert drain_one(home)
    assert manifest(session)["noop"] is True
    assert not (session.path / "files").exists()


def test_read_without_snapshot_withholds_save_instead_of_claiming_dependency_coverage(space):
    home, root = space
    session = MemorySession(home, root)
    target = root / "binary.data"
    target.write_bytes(b"\xff\x00")
    assert read(session, target) == target
    write(session, root / "new.md", "new")
    accept_batch(home, session.finish())
    assert drain_one(home)
    assert manifest(session)["error"] == "memory_untracked_dependency"
    assert manifest(session)["state"] == "conflict"
    assert (session.path / memory.STAGED_FILES / "new.md").read_text() == "new"


def test_empty_session_cleanup_runs_in_background(space):
    home, root = space
    session = MemorySession(home, root)
    batch = session.finish()
    assert batch and session.path.exists()
    accept_batch(home, batch)
    assert drain_one(home)
    assert manifest(session)["noop"] is True
    assert not (session.path / "files").exists()
    accept_batch(home, batch)
    assert not drain_one(home)


def test_drainer_only_scans_ready_markers_not_historical_or_held_batches(space, monkeypatch):
    home, root = space
    historical = []
    for index in range(3):
        session = MemorySession(home, root)
        write(session, root / f"old{index}.md", "old")
        accept_batch(home, session.finish())
        assert drain_one(home)
        historical.append(session.path)
    held = MemorySession(home, root)
    write(held, root / "held.md", "held")
    held.finish()
    original_load = memory._load
    original_iterdir = Path.iterdir

    def load(area):
        assert area not in historical and area != held.path
        return original_load(area)

    def iterdir(path):
        assert path != home / "memory-staging", "must not enumerate historical batch directories"
        return original_iterdir(path)

    monkeypatch.setattr(memory, "_load", load)
    monkeypatch.setattr(Path, "iterdir", iterdir)
    assert not drain_one(home)
    fresh = MemorySession(home, root)
    write(fresh, root / "fresh.md", "fresh")
    accept_batch(home, fresh.finish())
    assert drain_one(home)
    assert not drain_one(home)


def test_acceptance_crash_marker_does_not_promote_pending_but_replay_recovers(space, monkeypatch):
    home, root = space
    session = MemorySession(home, root)
    write(session, root / "new.md", "new")
    batch = session.finish()
    original_save = memory._save

    def crash_before_ready(area, record):
        if record["state"] == "ready":
            raise OSError("simulated acceptance crash")
        original_save(area, record)

    monkeypatch.setattr(memory, "_save", crash_before_ready)
    with pytest.raises(OSError):
        accept_batch(home, batch)
    assert manifest(session)["state"] == "pending"
    assert not drain_one(home)
    assert not (root / "new.md").exists()
    monkeypatch.setattr(memory, "_save", original_save)
    accept_batch(home, batch)
    assert drain_one(home)
    assert (root / "new.md").read_text() == "new"


def test_snapshot_io_failure_allows_original_read_but_invalidates_saving(space, monkeypatch):
    home, root = space
    session = MemorySession(home, root)
    target = root / "existing.md"
    target.write_text("authorized knowledge")
    original_write = memory._write

    def disk_full(path, data):
        if path.is_relative_to(session.path / "base"):
            raise OSError("simulated snapshot write failure")
        original_write(path, data)

    monkeypatch.setattr(memory, "_write", disk_full)
    assert read(session, target) == target
    assert read(session, target) == target
    assert target.read_text() == "authorized knowledge"
    assert hook(session, "Write", target, content="unsafe")["permissionDecision"] == "deny"
    with pytest.raises(OSError):
        session.finish()
    assert not drain_one(home)
    session.discard()
    assert not session.path.exists()


def test_finish_seals_without_file_reads_hashes_diffs_or_directory_scan(space, monkeypatch):
    home, root = space
    session = MemorySession(home, root)
    write(session, root / "new.md", "new")

    def forbidden(*args, **kwargs):
        raise AssertionError("expensive finalization on the answer path")

    with monkeypatch.context() as scoped:
        scoped.setattr(memory, "_read", forbidden)
        scoped.setattr(memory, "_hash", forbidden)
        scoped.setattr(memory.difflib, "unified_diff", forbidden)
        scoped.setattr(Path, "iterdir", forbidden)
        batch = session.finish()
    assert batch == session.batch_id
    assert not (session.path / "diffs.json").exists()
    accept_batch(home, batch)
    assert drain_one(home)
    assert (session.path / "diffs.json").exists()


@pytest.mark.parametrize("damage", ["absent", "json", "shape", "rollback"])
def test_invalid_manifest_isolated_without_stalling_healthy_batch(space, damage):
    home, root = space
    bad = MemorySession(home, root)
    write(bad, root / "bad.md", "bad")
    accept_batch(home, bad.finish())
    good = MemorySession(home, root)
    write(good, root / "good.md", "good")
    accept_batch(home, good.finish())
    path = bad.path / "manifest.json"
    if damage == "absent":
        path.unlink()
    elif damage == "rollback":
        record = manifest(bad)
        record["state"] = "rolling_back"
        path.write_text(json.dumps(record))
    else:
        path.write_text("{" if damage == "json" else '{"state":"ready"}')
    assert drain_one(home)
    assert (root / "good.md").read_text() == "good"
    assert (home / "memory-staging/isolated" / bad.batch_id).exists()
    assert not (home / "memory-staging/ready" / bad.batch_id).exists()
    assert not (root / "bad.md").exists()
    assert not drain_one(home)


def test_first_snapshot_waits_for_multi_file_commit(space, monkeypatch):
    home, root = space
    writer = MemorySession(home, root)
    for name in ("a.md", "b.md"):
        (root / name).write_text("base")
        read(writer, root / name)
        write(writer, root / name, "new")
    accept_batch(home, writer.finish())
    reader = MemorySession(home, root)
    partial, release, reading = Event(), Event(), Event()
    original_write = memory._write
    original_lock = memory._lock

    @contextmanager
    def observed_lock(path, **kwargs):
        if path.name == "save.lock" and partial.is_set():
            reading.set()
        with original_lock(path, **kwargs) as acquired:
            yield acquired

    def pause(path, data):
        original_write(path, data)
        if path == root / "a.md":
            partial.set()
            assert release.wait(timeout=3)

    def snapshot():
        return read(reader, root / "b.md").read_text()

    monkeypatch.setattr(memory, "_write", pause)
    monkeypatch.setattr(memory, "_lock", observed_lock)
    with ThreadPoolExecutor(max_workers=2) as pool:
        saver = pool.submit(drain_one, home)
        try:
            assert partial.wait(timeout=3)
            capture = pool.submit(snapshot)
            assert reading.wait(timeout=3)
            assert not capture.done()
        finally:
            release.set()
        assert saver.result(timeout=3)
        assert capture.result(timeout=3) == "new"


@pytest.mark.parametrize("external", [False, True])
def test_first_snapshot_recovers_partial_commit_or_rolls_back_without_overwriting_external(
    space, monkeypatch, external
):
    home, root = space
    writer = MemorySession(home, root)
    for name in ("a.md", "b.md"):
        (root / name).write_text("base " + name)
        read(writer, root / name)
        write(writer, root / name, "new " + name)
    accept_batch(home, writer.finish())
    unrelated = MemorySession(home, root)
    write(unrelated, root / "unrelated.md", "ready but not started")
    unrelated_batch = unrelated.finish()
    original_write = memory._write

    def crash(path, data):
        original_write(path, data)
        if path == root / "a.md":
            raise OSError("crash before file progress journal")

    monkeypatch.setattr(memory, "_write", crash)
    with pytest.raises(OSError):
        drain_one(home)
    monkeypatch.setattr(memory, "_write", original_write)
    if external:
        (root / "a.md").write_text("external correction")
    accept_batch(home, unrelated_batch)
    reader = MemorySession(home, root)
    assert read(reader, root / "b.md").read_text() == ("base b.md" if external else "new b.md")
    assert (root / "a.md").read_text() == ("external correction" if external else "new a.md")
    assert manifest(writer)["state"] == ("conflict" if external else "committed")
    if external:
        assert manifest(writer)["rollback_results"]["a.md"] == "external_change_preserved"
    assert not (root / "unrelated.md").exists()
    assert manifest(unrelated)["state"] == "ready"


def test_rollback_of_new_file_and_recovery_after_rollback_crash(space, monkeypatch):
    home, root = space
    writer = MemorySession(home, root)
    write(writer, root / "a.md", "new a")
    (root / "b.md").write_text("base b")
    read(writer, root / "b.md")
    write(writer, root / "b.md", "new b")
    accept_batch(home, writer.finish())
    original_write = memory._write

    def crash(path, data):
        original_write(path, data)
        if path == root / "a.md":
            raise OSError("partial commit")

    monkeypatch.setattr(memory, "_write", crash)
    with pytest.raises(OSError):
        drain_one(home)
    monkeypatch.setattr(memory, "_write", original_write)
    (root / "b.md").write_text("external")
    original_save = memory._save

    def crash_rollback(area, record):
        if record.get("rollback_results"):
            raise OSError("crash after rollback unlink before journal")
        original_save(area, record)

    monkeypatch.setattr(memory, "_save", crash_rollback)
    with pytest.raises(OSError):
        drain_one(home)
    assert not (root / "a.md").exists()
    assert manifest(writer)["state"] == "rolling_back"
    monkeypatch.setattr(memory, "_save", original_save)
    assert memory.recover_committing(home)
    assert manifest(writer)["state"] == "conflict"
    assert (root / "b.md").read_text() == "external"
    assert (writer.path / memory.STAGED_FILES / "a.md").read_text() == "new a"


@pytest.mark.parametrize("with_session", [False, True])
@pytest.mark.parametrize("name", ["Write", "Edit"])
def test_init_fallback_denies_writes_even_with_owned_session_but_preserves_reads(
    space, with_session, name
):
    home, root = space
    session = MemorySession(home, root)
    target = root / "existing.md"
    target.write_text("existing")
    staged = read(session, target)
    config = policy(session)
    config["memory_writes_denied"] = True
    if not with_session:
        config.pop("memory_session")
    for path in (target, staged):
        args = {
            "file_path": str(path),
            "content": "new",
            "old_string": "existing",
            "new_string": "new",
        }
        assert not permitted(config, name, args)
        result = decision(config, {"tool_name": name, "tool_input": args})["hookSpecificOutput"]
        assert result["permissionDecision"] == "deny"
        assert "updatedInput" not in result
    for path in (target, root.parent / "source.py"):
        args = {"file_path": str(path)}
        assert permitted(config, "Read", args)
        result = decision(config, {"tool_name": "Read", "tool_input": args})["hookSpecificOutput"]
        assert result["permissionDecision"] == "allow"
