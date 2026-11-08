"""Acceptance P01/P02/P14/M08: local fake processes, after production implementation."""

import json
import shlex
import subprocess
import sys
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from pathlib import Path
from types import ModuleType, SimpleNamespace

import pytest

from dougle_bot.config import EngineConfig
from dougle_bot.engine import ClaudeCliEngine, EngineResult, FakeEngine
from dougle_bot.guard import decision
from dougle_bot.safety import BotError

SESSION = "11111111-2222-4333-8444-555555555555"


@pytest.fixture
def isolated(tmp_path, monkeypatch):
    user_home = tmp_path / "user"
    user_home.mkdir()
    monkeypatch.setattr(Path, "home", classmethod(lambda cls: user_home))
    sessions, calls = [], []

    class MemorySession:
        def __init__(self, home, memory_root):
            assert memory_root == user_home / ".dougle"
            self.root = Path(home) / "stages" / uuid.uuid4().hex
            self.root.mkdir(parents=True)
            self.batch = self.root.name
            self.finished = self.discarded = False
            sessions.append(self)

        def policy_fields(self):
            return {"memory_session": str(self.root)}

        def check_process_done(self):
            for call in calls:
                if call["policy"].get("memory_session") == str(self.root) and "proc" in call:
                    proc = call["proc"]
                    assert proc.poll() is not None
                    assert all(stream.closed for stream in (proc.stdin, proc.stdout, proc.stderr))
                    assert call["settings_path"].exists()

        def finish(self):
            self.check_process_done()
            self.finished = True
            return self.batch  # Deliberately no mark_ready method.

        def discard(self):
            self.check_process_done()
            self.discarded = True

    module = ModuleType("dougle_bot.memory")
    module.MemorySession = MemorySession
    monkeypatch.setitem(sys.modules, "dougle_bot.memory", module)

    def launch(args, **kwargs):
        settings_path = Path(args[args.index("--settings") + 1])
        settings = json.loads(settings_path.read_text())
        hook = settings["hooks"]["PreToolUse"][0]["hooks"][0]["command"]
        policy_path = Path(shlex.split(hook)[-1])
        assert settings_path.stat().st_mode & 0o777 == 0o600
        assert policy_path.stat().st_mode & 0o777 == 0o600
        assert settings_path.parent.stat().st_mode & 0o777 == 0o700
        call = {
            "args": args,
            "cwd": kwargs["cwd"],
            "settings_path": settings_path,
            "policy_path": policy_path,
            "policy": json.loads(policy_path.read_text()),
        }
        calls.append(call)
        call["proc"] = subprocess.Popen(args, **kwargs)
        return call["proc"]

    def engine(code, **options):
        script = tmp_path / f"fake-engine-{uuid.uuid4().hex}"
        script.write_text(f"#!{sys.executable}\n" + code)
        script.chmod(0o700)
        cfg = replace(EngineConfig(), claude_bin=str(script), workdir=str(tmp_path / "cwd"))
        return ClaudeCliEngine(cfg, tmp_path / "runtime", popen=launch, **options)

    return SimpleNamespace(
        engine=engine, sessions=sessions, calls=calls, memory_type=MemorySession, root=tmp_path
    )


def success_code():
    return (
        "import json,sys\ntext=sys.stdin.read()\n"
        f"print(json.dumps({{'type':'result','session_id':{SESSION!r},"
        "'structured_output':{'body':text},'subtype':'success'}))\n"
    )


def knowledge_trace_code():
    from test_answer_style import outcome, trace

    raw = trace(
        [
            {
                "type": "assistant",
                "message": {
                    "content": [
                        {
                            "type": "tool_use",
                            "id": "read1",
                            "name": "Read",
                            "input": {"file_path": "/synthetic/.dougle/topic.md"},
                        }
                    ]
                },
            },
            outcome("read1", content="합성 업무 지식입니다."),
        ]
    )
    return "import sys\nsys.stdin.read()\nsys.stdout.buffer.write(" + repr(raw) + ")\n"


@pytest.mark.parametrize("error", [OSError("SECRET staging path"), BotError("secret_detail")])
def test_initialization_failure_keeps_reads_and_explicitly_denies_all_writes(
    isolated, monkeypatch, error
):
    from dougle_bot.evidence import validate_verification

    def fail(*args):
        raise error

    monkeypatch.setattr(sys.modules["dougle_bot.memory"], "MemorySession", fail)
    result = isolated.engine(knowledge_trace_code()).run("synthetic", None, 1)
    assert result.memory_batch is None
    assert result.memory_error == "memory_staging_unavailable"
    assert "SECRET" not in repr(result)
    assert validate_verification(result.output, result.reads) == {"knowledge"}
    policy = isolated.calls[0]["policy"]
    assert policy["memory_writes_denied"] is True
    assert "memory_session" not in policy
    for name in ("Write", "Edit"):
        for path in (Path(policy["memory"]) / "topic.md", isolated.root / "original.md"):
            response = decision(
                policy,
                {
                    "tool_name": name,
                    "tool_input": {"file_path": str(path), "content": "new", "new_string": "new"},
                },
            )["hookSpecificOutput"]
            assert response["permissionDecision"] == "deny"
            assert "updatedInput" not in response
    response = decision(
        policy,
        {
            "tool_name": "Read",
            "tool_input": {"file_path": str(isolated.root / "original.md")},
        },
    )["hookSpecificOutput"]
    assert response["permissionDecision"] == "allow"
    assert not isolated.calls[0]["settings_path"].exists()


def test_readonly_fallback_never_bypasses_policy_setup_failure(isolated, monkeypatch):
    def staging_failure(*args):
        raise OSError("staging unavailable")

    original_open = Path.open

    def policy_failure(path, *args, **kwargs):
        if path.name == "settings.json":
            raise OSError("policy unavailable")
        return original_open(path, *args, **kwargs)

    monkeypatch.setattr(sys.modules["dougle_bot.memory"], "MemorySession", staging_failure)
    monkeypatch.setattr(Path, "open", policy_failure)
    with pytest.raises(OSError, match="policy unavailable"):
        isolated.engine(success_code()).run("synthetic", None, 1)
    assert isolated.calls == []
    assert list((isolated.root / "runtime/policy").iterdir()) == []


def test_success_stages_only_after_process_cleanup_and_keeps_resume_cwd(isolated):
    engine = isolated.engine(success_code())
    first = engine.run("first", None, 1)
    second = engine.run("second", first.session_id, 1)
    assert first.memory_batch == isolated.sessions[0].batch
    assert second.memory_batch == isolated.sessions[1].batch
    assert first.memory_batch != second.memory_batch
    assert all(item.finished and not item.discarded for item in isolated.sessions)
    a, b = isolated.calls
    assert a["cwd"] == b["cwd"] == isolated.root / "cwd"
    assert b["args"][b["args"].index("--resume") + 1] == first.session_id
    assert a["settings_path"] != b["settings_path"]
    for call in isolated.calls:
        assert not call["settings_path"].parent.exists()
        assert {"Bash", "Agent", "Task"} <= set(
            call["args"][call["args"].index("--disallowedTools") + 1].split(",")
        )
    assert list((isolated.root / "runtime/policy").iterdir()) == []


def test_two_cli_calls_overlap_and_finishing_one_preserves_the_other_policy(isolated):
    barrier = isolated.root / "barrier"
    barrier.mkdir()
    code = f"""import json,sys,time
from pathlib import Path
root=Path({str(barrier)!r})
label=sys.stdin.read()
settings=Path(sys.argv[sys.argv.index('--settings')+1])
policy=settings.parent/'tools.json'
(root/(label+'.started')).touch()
deadline=time.monotonic()+8
while not all((root/(x+'.started')).exists() for x in ('A','B')):
    assert time.monotonic()<deadline
    time.sleep(.01)
if label=='A':
    while not (root/'release-A').exists():
        assert time.monotonic()<deadline
        time.sleep(.01)
assert settings.exists() and policy.exists()
print(json.dumps({{'type':'result','session_id':{SESSION!r},'structured_output':{{'body':label}}}}))
"""
    engine = isolated.engine(code)
    with ThreadPoolExecutor(max_workers=2) as pool:
        a = pool.submit(engine.run, "A", None, 1)
        b = pool.submit(engine.run, "B", None, 1)
        try:
            result_b = b.result(timeout=6)
            assert result_b.output == {"body": "B"}
            assert not a.done()
            assert len(isolated.calls) == 2
            assert len({item["settings_path"] for item in isolated.calls}) == 2
            active = [item for item in isolated.calls if item["proc"].poll() is None]
            assert len(active) == 1 and active[0]["settings_path"].exists()
        finally:
            (barrier / "release-A").touch()
        assert a.result(timeout=6).output == {"body": "A"}
    assert all(not call["settings_path"].exists() for call in isolated.calls)
    assert all(item.finished for item in isolated.sessions)


@pytest.mark.parametrize(
    "code,options,run_options,error",
    [
        ("import sys\nsys.stdin.read()\nprint('not a trace')", {}, {}, "invalid_engine_output"),
        ("import sys\nsys.exit(2)", {}, {}, "engine_failed"),
        ("import time\ntime.sleep(10)", {}, {"timeout_seconds": 0.05}, "engine_timeout"),
        ("import time\ntime.sleep(10)", {"cancelled": lambda: True}, {}, "bot_stopped"),
        (
            "import time\ntime.sleep(10)",
            {},
            {"cancel_requested": lambda: True},
            "inquiry_cancelled",
        ),
    ],
)
def test_failed_call_discards_memory_after_process_cleanup(
    isolated, code, options, run_options, error
):
    engine = isolated.engine(code, **options)
    with pytest.raises(BotError, match=f"^{error}$"):
        engine.run("synthetic", None, 1, **run_options)
    assert isolated.sessions[0].discarded and not isolated.sessions[0].finished
    assert not isolated.calls[0]["settings_path"].parent.exists()


def test_launch_failure_discards_memory_and_policy(isolated):
    engine = isolated.engine(success_code())
    Path(engine.config.claude_bin).unlink()
    with pytest.raises(BotError, match="^engine_launch_failed$"):
        engine.run("synthetic", None, 1)
    assert isolated.sessions[0].discarded
    assert not isolated.calls[0]["settings_path"].parent.exists()


@pytest.mark.parametrize(
    "error", [OSError("SECRET path"), BotError("secret_detail"), RuntimeError("SECRET")]
)
@pytest.mark.parametrize("discard_fails", [False, True])
def test_finish_failure_preserves_answer_with_constant_error(
    isolated, monkeypatch, error, discard_fails
):
    def failed_finish(self):
        self.check_process_done()
        raise error

    original_discard = isolated.memory_type.discard

    def discard(self):
        original_discard(self)
        if discard_fails:
            raise OSError("SECRET cleanup failure")

    monkeypatch.setattr(isolated.memory_type, "finish", failed_finish)
    monkeypatch.setattr(isolated.memory_type, "discard", discard)
    result = isolated.engine(success_code()).run("synthetic", None, 1)
    assert result.output == {"body": "synthetic"}
    assert result.session_id == SESSION
    assert result.memory_batch is None
    assert result.memory_error == "memory_finalize_failed"
    assert "SECRET" not in repr(result)
    assert isolated.sessions[0].discarded
    assert not isolated.calls[0]["settings_path"].parent.exists()


def test_startup_probe_never_constructs_or_stages_memory(isolated, monkeypatch):
    def forbidden(*args):
        raise AssertionError("probe must not construct memory session")

    monkeypatch.setattr(sys.modules["dougle_bot.memory"], "MemorySession", forbidden)
    result = isolated.engine(success_code(), startup_probe=True).run("probe", None, 1)
    assert result.memory_batch is None
    assert isolated.sessions == []
    assert isolated.calls[0]["policy"]["startup_probe"] is True
    assert "memory_session" not in isolated.calls[0]["policy"]
    assert not isolated.calls[0]["settings_path"].parent.exists()


def test_finish_failure_preserves_successful_receipts_for_strict_validation(isolated, monkeypatch):
    from test_answer_style import answer, outcome, trace

    from dougle_bot.evidence import validate_verification

    raw = trace(
        [
            {
                "type": "assistant",
                "message": {
                    "content": [
                        {
                            "type": "tool_use",
                            "id": "read1",
                            "name": "Read",
                            "input": {"file_path": "/synthetic/.dougle/topic.md"},
                        }
                    ],
                },
            },
            outcome("read1", content="합성 업무 지식입니다."),
        ]
    )

    def fail(self):
        raise OSError("synthetic disk failure")

    monkeypatch.setattr(isolated.memory_type, "finish", fail)
    result = isolated.engine(
        "import sys\nsys.stdin.read()\nsys.stdout.buffer.write(" + repr(raw) + ")\n"
    ).run("synthetic", None, 1)
    assert result.output == answer()
    assert result.reads["read1"] == {"knowledge"}
    assert result.reads["read1"].metadata["locator"]["target"] == "/synthetic/.dougle/topic.md"
    assert result.memory_error == "memory_finalize_failed"
    assert validate_verification(result.output, result.reads) == {"knowledge"}


def test_engine_result_and_fake_engine_keep_positional_compatibility():
    old = EngineResult(SESSION, {}, None, set(), {}, [], {})
    assert old.memory_batch is None
    assert old.memory_error is None
    assert FakeEngine([old]).run("synthetic", None, 1) is old


@pytest.mark.parametrize("succeeds", [True, False])
def test_real_memory_session_only_seals_successful_proposal(tmp_path, monkeypatch, succeeds):
    from dougle_bot.memory import STAGED_FILES, redirect

    user_home = tmp_path / "user"
    user_home.mkdir()
    monkeypatch.setattr(Path, "home", classmethod(lambda cls: user_home))
    target = user_home / ".dougle/topics/synthetic.md"
    script = tmp_path / "fake-memory-cli"
    script.write_text(
        f"#!{sys.executable}\n"
        + (success_code() if succeeds else "import sys\nsys.stdin.read()\nsys.exit(2)\n")
    )
    script.chmod(0o700)
    captures = []

    def launch(args, **kwargs):
        settings = Path(args[args.index("--settings") + 1])
        policy = json.loads((settings.parent / "tools.json").read_text())
        changed = redirect(
            policy, "Write", {"file_path": str(target), "content": "합성 지식입니다."}
        )
        Path(changed["file_path"]).write_text(changed["content"])
        captures.append(policy)
        return subprocess.Popen(args, **kwargs)

    cfg = replace(EngineConfig(), claude_bin=str(script), workdir=str(tmp_path / "cwd"))
    engine = ClaudeCliEngine(cfg, tmp_path / "runtime", popen=launch)
    if succeeds:
        result = engine.run("synthetic", None, 1)
        area = Path(captures[0]["memory_session"])
        manifest = json.loads((area / "manifest.json").read_text())
        assert result.memory_batch == area.name
        assert manifest["state"] == "pending"
        assert manifest["changes"] == {}  # Hashes/diffs are deferred to the save worker.
        assert "topics/synthetic.md" in manifest["dependencies"]
        assert (area / STAGED_FILES / "topics/synthetic.md").read_text() == "합성 지식입니다."
    else:
        with pytest.raises(BotError, match="^engine_failed$"):
            engine.run("synthetic", None, 1)
        assert not Path(captures[0]["memory_session"]).exists()
    assert not target.exists()
    assert list((tmp_path / "runtime/policy").iterdir()) == []
