"""Lazy per-call memory proposals and a serialized, recoverable save queue.

finish seals a proposal; only the validating worker may accept it. Locks serialize
cooperating bot writers, not arbitrary external editors. Hash checks detect observed
external changes; an uncooperative writer can still race a check and replacement.
Base and proposed bytes survive conflicts and partial commits for reconciliation.
"""

import difflib
import fcntl
import hashlib
import json
import os
import re
import shutil
import stat
import tempfile
import uuid
from contextlib import contextmanager
from pathlib import Path

from .safety import BotError, check_content

MAX_BYTES = 4 * 1024 * 1024
ID = re.compile(r"[0-9a-f]{32}")
# Preserve the evidence classifier's memory marker for JSON and code-suffix notes too.
STAGED_FILES = "files/.dougle"


def safe_path(root, relative):
    """Reject traversal and every existing symlink below a trusted root."""
    root = Path(root)
    relative = Path(relative)
    if (
        any(parent.is_symlink() for parent in (root, *root.parents))
        or relative.is_absolute()
        or not relative.parts
    ):
        raise BotError("unsafe_memory_path")
    if any(part in {".", ".."} for part in relative.parts):
        raise BotError("unsafe_memory_path")
    target = root
    for part in relative.parts:
        target = target / part
        if target.is_symlink():
            raise BotError("unsafe_memory_path")
    return target


def _root(home):
    home = Path(home).expanduser()
    if home.is_symlink():
        raise BotError("unsafe_memory_path")
    home = home.resolve()
    home.mkdir(mode=0o700, parents=True, exist_ok=True)
    root = safe_path(home, "memory-staging")
    root.mkdir(mode=0o700, exist_ok=True)
    return root


def _batch(home, batch_id):
    if not isinstance(batch_id, str) or not ID.fullmatch(batch_id):
        raise BotError("invalid_memory_batch")
    return safe_path(_root(home), batch_id)


def _queue(home):
    path = safe_path(_root(home), "ready")
    path.mkdir(mode=0o700, exist_ok=True)
    _sync_dir(path.parent)
    return path


def _dequeue(queue, batch_id):
    safe_path(queue, batch_id).unlink(missing_ok=True)
    _sync_dir(queue)


@contextmanager
def _lock(path, *, blocking=True):
    fd = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | (0 if blocking else fcntl.LOCK_NB))
        except BlockingIOError:
            yield False
            return
        yield True
    finally:
        os.close(fd)


def _sync_dir(path):
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _write(path, data):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd, temp = tempfile.mkstemp(prefix=".memory-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, path)
        _sync_dir(path.parent)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)


def _save(area, record):
    _write(safe_path(area, "manifest.json"), json.dumps(record, sort_keys=True).encode())


def _load(area):
    return json.loads(safe_path(area, "manifest.json").read_text())


def _read(path):
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except FileNotFoundError:
        return None
    with os.fdopen(fd, "rb") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size > MAX_BYTES:
            raise BotError("unsafe_memory_file")
        data = stream.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise BotError("memory_file_too_large")
    return data


def _hash(data):
    return None if data is None else hashlib.sha256(data).hexdigest()


def _content(data):
    if data is None:
        return
    try:
        text = data.decode("utf-8")
    except UnicodeError:
        raise BotError("invalid_memory_text") from None
    check_content(text, personal=True)


def _target(record, relative):
    target = safe_path(Path(record["memory_root"]), relative)
    # A proposal cannot target runtime state even if memory contains the bot home.
    if target.is_relative_to(Path(record["home"])):
        raise BotError("unsafe_memory_path")
    if any(
        part.startswith(".env")
        or part
        in {
            ".ssh",
            ".aws",
            "Keychains",
            ".credentials.json",
            "auth.json",
            ".netrc",
            ".npmrc",
            ".pypirc",
            ".git-credentials",
        }
        for part in Path(relative).parts
    ):
        raise BotError("unsafe_memory_path")
    return target


class MemorySession:
    """One engine call. No shared memory is copied until an exact file is read."""

    def __init__(self, home, memory_root):
        self.home = Path(home).expanduser().resolve()
        memory_root = Path(memory_root).expanduser()
        if memory_root.is_symlink():
            raise BotError("unsafe_memory_path")
        self.memory_root = memory_root.resolve()
        if self.memory_root.is_relative_to(self.home):
            raise BotError("unsafe_memory_path")
        self.batch_id = uuid.uuid4().hex
        self.path = _batch(home, self.batch_id)
        self.path.mkdir(mode=0o700)
        for name in ("base", "files", STAGED_FILES):
            (self.path / name).mkdir(mode=0o700)
        _save(
            self.path,
            {
                "version": 1,
                "home": str(self.home),
                "memory_root": str(self.memory_root),
                "state": "open",
                "dependencies": {},
                "changes": {},
                "applied": [],
                "untracked_reads": [],
            },
        )
        _sync_dir(self.path.parent)

    def policy_fields(self):
        return {"memory_session": str(self.path)}

    def finish(self):
        """Seal metadata only. File reads, hashes, diffs and no-op cleanup run in drain_one."""
        with _lock(safe_path(self.path, "session.lock")):
            record = _load(self.path)
            if record["state"] != "open":
                raise BotError("memory_session_closed")
            record["state"] = "pending"
            _save(self.path, record)
        return self.batch_id

    def discard(self):
        discard_batch(self.home, self.batch_id)


def session_area(policy):
    """Validate the policy's owned session; never accept another session's path."""
    home = Path(policy["runtime_home"]).expanduser().resolve()
    raw = Path(policy["memory_session"])
    area = _batch(home, raw.name)
    if raw != area:
        raise BotError("unsafe_memory_path")
    record = _load(area)
    memory = Path(policy["memory"]).expanduser()
    if (
        memory.is_symlink()
        or record["home"] != str(home)
        or record["memory_root"] != str(memory.resolve())
        or record["state"] != "open"
    ):
        raise BotError("unsafe_memory_path")
    return area


def redirect(policy, name, args):
    """Only new shared snapshots wait for a save/recovery, never an engine invocation."""
    area = session_area(policy)
    raw = Path(args["file_path"]).expanduser()
    files = area / STAGED_FILES
    root = files if raw.is_relative_to(files) else Path(policy["memory"]).expanduser().resolve()
    relative = raw.relative_to(root).as_posix()
    with _lock(safe_path(area, "session.lock")):
        captured = relative in _load(area)["dependencies"]
    if captured:
        return _redirect(policy, name, args)
    # Lock order is save -> session everywhere; release the probe's session lock first.
    with _lock(safe_path(_root(policy["runtime_home"]), "save.lock")):
        try:
            _recover_committing(policy["runtime_home"])
        except Exception:
            # An unresolved partial commit must never fall through to an original read.
            raise BotError("memory_recovery_required") from None
        return _redirect(policy, name, args)


def _redirect(policy, name, args):
    """Called by PreToolUse after permission checks. Returns updated tool input."""
    area = session_area(policy)
    if name in {"Write", "Edit"}:
        field = "content" if name == "Write" else "new_string"
        value = args.get(field)
        if not isinstance(value, str) or len(value.encode()) > MAX_BYTES:
            raise BotError("invalid_memory_text")
        check_content(value, personal=True)
    raw = Path(args["file_path"]).expanduser()
    memory = Path(policy["memory"]).expanduser().resolve()
    files = safe_path(area, STAGED_FILES)
    root = files if raw.is_relative_to(files) else memory
    relative = raw.relative_to(root).as_posix()
    safe_path(root, relative)
    with _lock(safe_path(area, "session.lock")):
        record = _load(area)
        if record["state"] != "open":
            raise BotError("memory_session_closed")
        target = _target(record, relative)
        if name in {"Write", "Edit"}:
            check_content(relative, personal=True)
            if relative in record.get("untracked_reads", []):
                raise BotError("memory_read_required")
        staged = safe_path(area, STAGED_FILES + "/" + relative)
        if relative not in record["dependencies"]:
            if len(record["dependencies"]) >= 1000:
                raise BotError("memory_session_too_large")
            try:
                data = _read(target)
                if name == "Read" and data is not None:
                    text = data.decode("utf-8")
                    if any(ord(char) < 32 and char not in "\n\r\t" for char in text):
                        raise UnicodeError()
            except (BotError, UnicodeError) as exc:
                if name != "Read" or (
                    isinstance(exc, BotError)
                    and exc.code
                    not in {
                        "unsafe_memory_file",
                        "memory_file_too_large",
                    }
                ):
                    raise
                if relative not in record.setdefault("untracked_reads", []):
                    record["untracked_reads"].append(relative)
                    _save(area, record)
                # No copy or editable baseline for binary/oversized originals. The
                # ordinary authorized reader retains its own file/size handling.
                return {**args, "file_path": str(target)}
            if name != "Read" and data is not None:
                raise BotError("memory_read_required")
            if data is None and name != "Write":
                raise BotError("memory_file_missing")
            if data is not None:
                _write(safe_path(area, "base/" + relative), data)
            _write(staged, data or b"")
            record["dependencies"][relative] = _hash(data)
            _save(area, record)
        elif _read(staged) is None:
            raise BotError("memory_staged_file_missing")
        return {**args, "file_path": str(staged)}


def accept_batch(home, batch_id):
    """Only call after worker validation and cancellation checks. Idempotent."""
    area = _batch(home, batch_id)
    with _lock(safe_path(area, "session.lock")):
        record = _load(area)
        if record["state"] == "pending":
            # Write the queue marker first. A crash before the state transition leaves
            # pending data held; replay of the durable worker audit completes acceptance.
            _write(safe_path(_queue(home), batch_id), b"")
            record["state"] = "ready"
            _save(area, record)
        elif record["state"] in {"ready", "committing", "rolling_back"}:
            _write(safe_path(_queue(home), batch_id), b"")
        elif record["state"] not in {"committed", "conflict"}:
            raise BotError("memory_batch_not_pending")


def read_input(policy, args):
    """A snapshot I/O failure disables saving, not an otherwise authorized original read."""
    try:
        return redirect(policy, "Read", args)
    except OSError:
        area = _batch(policy["runtime_home"], Path(policy["memory_session"]).name)
        if str(area) != policy["memory_session"]:
            raise BotError("unsafe_memory_path") from None
        # Renaming requires no copy of the failed snapshot. Keep the old record for
        # cleanup/inspection but prevent finish/accept from treating an untracked read
        # as covered. Subsequent source reads can still proceed without a session.
        with _lock(safe_path(area, "session.lock")):
            manifest = safe_path(area, "manifest.json")
            if manifest.exists():
                os.replace(manifest, safe_path(area, "blocked-manifest.json"))
                _sync_dir(area)
        return args


def discard_batch(home, batch_id):
    """Discard only unaccepted proposals; accepted/conflicting batches are retained."""
    area = _batch(home, batch_id)
    if not area.exists():
        return
    with _lock(safe_path(area, "session.lock")):
        if safe_path(area, "blocked-manifest.json").exists():
            record = json.loads(safe_path(area, "blocked-manifest.json").read_text())
        else:
            record = _load(area)
        if record["state"] not in {"open", "pending"}:
            raise BotError("memory_batch_already_accepted")
        _dequeue(_queue(home), batch_id)
        shutil.rmtree(area)


def _finalize(area, record):
    """Background-only proposal validation; unchanged source content is not screened."""
    changes, diffs = {}, {}
    for relative, base_hash in record["dependencies"].items():
        _target(record, relative)
        current = _read(safe_path(area, STAGED_FILES + "/" + relative))
        if current is None:
            raise BotError("memory_staged_file_missing")
        if _hash(current) == base_hash:
            continue
        _content(current)
        check_content(relative, personal=True)
        base = _read(safe_path(area, "base/" + relative))
        if _hash(base) != base_hash:
            raise BotError("memory_snapshot_changed")
        changes[relative] = _hash(current)
        diffs[relative] = "".join(
            difflib.unified_diff(
                (base or b"").decode().splitlines(keepends=True),
                current.decode().splitlines(keepends=True),
                fromfile="base",
                tofile="proposed",
            )
        )
    if not changes:
        # Keep a small idempotence tombstone, not per-query source snapshots.
        for name in ("base", "files"):
            path = safe_path(area, name)
            if path.exists():
                shutil.rmtree(path)
        record.update(state="committed", noop=True, dependencies={}, untracked_reads=[])
        _save(area, record)
        return
    if record.get("untracked_reads"):
        raise BotError("memory_untracked_dependency")
    _write(safe_path(area, "diffs.json"), json.dumps(diffs).encode())
    record["changes"] = changes
    record["prepared"] = True
    _save(area, record)


def _rollback(area, record):
    """Restore only our still-present proposals; preserve all distinguishable external edits."""
    if record["state"] != "rolling_back":
        targets = set(record["applied"])
        if record.get("writing"):
            targets.add(record["writing"])
        record.update(state="rolling_back", rollback_targets=sorted(targets), rollback_results={})
        _save(area, record)
    for relative in record["rollback_targets"]:
        if relative in record["rollback_results"]:
            continue
        target = _target(record, relative)
        actual = _hash(_read(target))
        base_hash = record["dependencies"][relative]
        if actual == record["changes"][relative]:
            base = _read(safe_path(area, "base/" + relative))
            if _hash(base) != base_hash:
                raise BotError("memory_snapshot_changed")
            if base is None:
                target.unlink()
                _sync_dir(target.parent)
            else:
                _write(target, base)
            outcome = "restored"
        elif actual == base_hash:
            outcome = "already_base"
        else:
            outcome = "external_change_preserved"
        record["rollback_results"][relative] = outcome
        _save(area, record)
    record["state"] = "conflict"
    _save(area, record)


def _commit(area, record):
    """Write-ahead state plus per-file hashes makes partial application resumable."""
    if record["state"] == "ready" and not record.get("prepared"):
        _finalize(area, record)
        if record["state"] == "committed":
            return
    changes, dependencies = record["changes"], record["dependencies"]

    def conflict():
        _rollback(area, record)

    # Check *all* read dependencies before modifying any shared file. During recovery,
    # journaled intent allows a file already replaced just before a crash to match new.
    recovering = record["state"] == "committing"
    for relative, base_hash in dependencies.items():
        actual = _hash(_read(_target(record, relative)))
        expected = {base_hash}
        if recovering and relative in changes:
            expected = (
                {changes[relative]}
                if relative in record["applied"]
                else {base_hash, changes[relative]}
            )
        if actual not in expected:
            conflict()
            return
    for relative, proposed_hash in changes.items():
        proposed = _read(safe_path(area, STAGED_FILES + "/" + relative))
        base = _read(safe_path(area, "base/" + relative))
        if _hash(proposed) != proposed_hash or _hash(base) != dependencies[relative]:
            conflict()
            return
        _content(proposed)
    record["state"] = "committing"
    _save(area, record)
    for relative, proposed_hash in changes.items():
        target = _target(record, relative)
        actual = _hash(_read(target))
        if actual != proposed_hash:
            if actual != dependencies[relative]:
                conflict()
                return
            # Narrow the external-editor race, without claiming an OS compare-and-swap.
            proposed = _read(safe_path(area, STAGED_FILES + "/" + relative))
            if _hash(proposed) != proposed_hash:
                conflict()
                return
            _content(proposed)
            record["writing"] = relative
            _save(area, record)
            _write(target, proposed)
        if relative not in record["applied"]:
            record["applied"].append(relative)
        record.pop("writing", None)
        _save(area, record)
    record["state"] = "committed"
    _save(area, record)


def _queued(home):
    """Only inspect queue markers. Corrupt held data must not starve healthy batches."""
    candidates = []
    queue = _queue(home)
    for marker in queue.iterdir():
        if not ID.fullmatch(marker.name) or marker.is_symlink() or not marker.is_file():
            continue
        try:
            area = _batch(home, marker.name)
            with _lock(safe_path(area, "session.lock")):
                record = _load(area)
                state = record["state"]
                if state in {"ready", "committing", "rolling_back"}:
                    if not all(
                        isinstance(record.get(key), dict) for key in ("dependencies", "changes")
                    ):
                        raise ValueError()
                    if (
                        not isinstance(record.get("applied"), list)
                        or not isinstance(record.get("memory_root"), str)
                        or record.get("home") != str(Path(home).expanduser().resolve())
                        or not all(
                            isinstance(key, str)
                            and (
                                value is None
                                or (isinstance(value, str) and re.fullmatch(r"[a-f0-9]{64}", value))
                            )
                            for key, value in record["dependencies"].items()
                        )
                        or not set(record["changes"]) <= set(record["dependencies"])
                    ):
                        raise ValueError()
                    if (
                        not all(
                            isinstance(value, str) and re.fullmatch(r"[a-f0-9]{64}", value)
                            for value in record["changes"].values()
                        )
                        or not all(
                            isinstance(key, str) and key in record["changes"]
                            for key in record["applied"]
                        )
                        or (
                            record.get("writing") is not None
                            and record["writing"] not in record["changes"]
                        )
                    ):
                        raise ValueError()
                    if state == "rolling_back" and (
                        not isinstance(record.get("rollback_targets"), list)
                        or not isinstance(record.get("rollback_results"), dict)
                        or not all(
                            isinstance(key, str) and key in record["changes"]
                            for key in record["rollback_targets"]
                        )
                        or not set(record["rollback_results"]) <= set(record["rollback_targets"])
                    ):
                        raise ValueError()
                    candidates.append((state == "ready", area))
                elif state in {"committed", "conflict"}:
                    _dequeue(queue, marker.name)
                elif state != "pending":
                    raise ValueError()
        except (FileNotFoundError, ValueError, TypeError, KeyError, BotError):
            isolated = safe_path(_root(home), "isolated")
            isolated.mkdir(mode=0o700, exist_ok=True)
            _write(safe_path(isolated, marker.name), b'{"error":"invalid_memory_batch"}')
            _dequeue(queue, marker.name)
    return sorted(candidates)


def _process(home, area):
    with _lock(safe_path(area, "session.lock")):
        record = _load(area)
        try:
            if record["state"] == "rolling_back":
                _rollback(area, record)
            else:
                _commit(area, record)
        except BotError as exc:
            if record["state"] == "rolling_back":
                raise  # Keep recovery pending; do not hide an incomplete rollback.
            record["error"] = exc.code
            _rollback(area, record)
        if record["state"] in {"committed", "conflict"}:
            _dequeue(_queue(home), area.name)


def _recover_committing(home):
    # Called under save.lock. Never start an unrelated ready batch in an inquiry hook.
    recovered = False
    for ready, area in _queued(home):
        if not ready:
            _process(home, area)
            recovered = True
    return recovered


def recover_committing(home):
    """Optional startup recovery; only interrupted commits/rollbacks, not ready proposals."""
    with _lock(safe_path(_root(home), "save.lock")):
        return _recover_committing(home)


def drain_one(home):
    """Process one accepted batch. False means no work or another saver owns it."""
    with _lock(safe_path(_root(home), "save.lock"), blocking=False) as acquired:
        if not acquired:
            return False
        candidates = _queued(home)
        if not candidates:
            return False
        _process(home, candidates[0][1])
        return True
