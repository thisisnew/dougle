import json
import os
import re
import selectors
import shlex
import signal
import subprocess
import sys
import time
import uuid
from contextlib import suppress
from dataclasses import dataclass, field
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Protocol

from .config import BUILTINS, private_dir
from .evidence import read_receipts
from .progress import StreamProgress
from .prompts import OUTPUT_SCHEMA, valid_cost
from .safety import BotError


@dataclass
class EngineResult:
    session_id: str
    output: dict
    cost_usd: float | None
    activated: set[str]
    reads: dict[str, set[str]] = field(default_factory=dict)
    connections: list[dict[str, str]] | None = None
    usage: dict[str, int | None] | None = None
    memory_batch: str | None = None
    memory_error: str | None = None


def token_usage(value):
    """Keep provider counters only; absent/invalid values are unknown, not zero.

    Do not add per-model usage to aggregate usage: providers may report both for
    the same work. These are reported counts, not billable-token estimates.
    """
    if not isinstance(value, dict):
        return None
    return {
        key: value[key] if type(value.get(key)) is int and 0 <= value[key] <= 10**12 else None
        for key in (
            "input_tokens",
            "output_tokens",
            "cache_read_input_tokens",
            "cache_creation_input_tokens",
        )
    }


class Engine(Protocol):
    def run(
        self,
        text: str,
        session: str | None,
        budget: float,
        *,
        images=(),
        on_progress=None,
        timeout_seconds=None,
        cancel_requested=None,
    ) -> EngineResult: ...


def parse_trace(raw: bytes):
    calls = {}
    successful = set()
    result = None
    connections = None
    for line in raw.splitlines():
        try:
            item = json.loads(line)
        except (ValueError, UnicodeError):
            continue  # Shell startup banners are never logged or treated as results.
        if not isinstance(item, dict):
            continue
        if item.get("type") == "system" and item.get("subtype") == "init":
            from .startup import connection_snapshot

            connections = connection_snapshot(item.get("mcp_servers"))
        if item.get("type") == "result":
            result = item
        message = item.get("message", {})
        content = message.get("content", []) if isinstance(message, dict) else []
        if (
            item.get("type") == "user"
            and isinstance(content, list)
            and any(
                isinstance(block, dict)
                and block.get("type") == "text"
                and str(block.get("text", "")).startswith("[Image could not be processed:")
                for block in content
            )
        ):
            raise BotError("image_input_failed")
        if not isinstance(content, list):
            continue
        for block in content:
            if not isinstance(block, dict):
                continue
            if (
                item.get("type") == "assistant"
                and block.get("type") == "tool_use"
                and block.get("name") == "Skill"
            ):
                ident, args = block.get("id"), block.get("input")
                if (
                    not isinstance(ident, str)
                    or not ident
                    or not isinstance(args, dict)
                    or not isinstance(args.get("skill"), str)
                ):
                    raise BotError("invalid_skill_trace")
                if ident in calls:
                    raise BotError("invalid_skill_trace")
                calls[ident] = args["skill"]
            if (
                item.get("type") == "user"
                and block.get("type") == "tool_result"
                and isinstance(block.get("tool_use_id"), str)
                and block["tool_use_id"] in calls
            ):
                rendered = json.dumps(block.get("content", ""), ensure_ascii=False)
                if (
                    block.get("content")
                    and not block.get("is_error")
                    and not re.search(
                        r"Unknown skill|not found|denied|not available|could not|failed|disabled|거부|실패|찾을 수 없",
                        rendered,
                        re.I,
                    )
                ):
                    successful.add(calls[block["tool_use_id"]])
    if not result:
        raise BotError("invalid_engine_output")
    if result.get("is_error") or result.get("subtype", "success") != "success":
        errors = str(result.get("result", "")) + " " + str(result.get("errors", ""))
        if re.search(
            r"image.{0,80}(?:invalid|decode|process|too large|exceed)|(?:invalid|decode).{0,40}image",
            errors,
            re.I,
        ):
            raise BotError("image_input_failed")
        if re.search(r"(?:No conversation found|session.{0,40}not found)", errors, re.I):
            raise BotError("session_not_found")
        raise BotError("engine_failed")
    session = result.get("session_id")
    try:
        uuid.UUID(session)
    except (ValueError, TypeError, AttributeError):
        raise BotError("invalid_session") from None
    return EngineResult(
        session,
        result.get("structured_output"),
        valid_cost(result.get("total_cost_usd")),
        successful,
        read_receipts(raw),
        connections,
        token_usage(result.get("usage")),
    )


class ClaudeCliEngine:
    def __init__(
        self, config, home, *, cancelled=lambda: False, popen=subprocess.Popen, startup_probe=False
    ):
        self.config, self.home, self.cancelled, self.popen = config, Path(home), cancelled, popen
        self.startup_probe = startup_probe

    def command(self, session, budget, settings, *, images=False):
        cfg = self.config
        schema = OUTPUT_SCHEMA
        if self.startup_probe:
            schema = {
                "type": "object",
                "properties": {"ready": {"type": "boolean"}},
                "required": ["ready"],
                "additionalProperties": False,
            }
        args = [
            "-p",
            "--output-format",
            "stream-json",
            "--verbose",
            "--json-schema",
            json.dumps(schema),
            "--permission-mode",
            "dontAsk",
            "--settings",
            str(settings),
            "--tools",
            "Skill" if self.startup_probe else ",".join(sorted(BUILTINS | {"ToolSearch"})),
            "--allowedTools",
            (
                "Skill,StructuredOutput"
                if self.startup_probe
                else ",".join([*cfg.allowed_tools, "StructuredOutput", "ToolSearch"])
            ),
            "--disallowedTools",
            ",".join(sorted(set(cfg.disallowed_tools) | {"Bash", "Agent", "Task"})),
            "--max-turns",
            str(min(cfg.max_turns, 8) if self.startup_probe else cfg.max_turns),
            "--max-budget-usd",
            str(budget),
        ]
        if images:
            args += ["--input-format", "stream-json"]
        if session:
            args += ["--resume", session]
        if cfg.model:
            args += ["--model", cfg.model]
        name = cfg.claude_bin
        if "/" in name:
            return [str(Path(name).expanduser()), *args]
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]*", name):
            raise BotError("invalid_engine_path")
        # A literal configured name only. Prompt never appears in this shell program or argv.
        return ["/bin/zsh", "-lic", name + ' "$@"', "dougle-launch", *args]

    def run(
        self,
        text,
        session,
        budget,
        *,
        images=(),
        on_progress=None,
        timeout_seconds=None,
        cancel_requested=None,
    ):
        # Keep the CLI cwd stable for session resume. Only hook policy/settings and
        # staged memory are isolated; no lock serializes independent inquiries.
        policy_root = private_dir(self.home / "policy")
        with TemporaryDirectory(prefix="call-", dir=policy_root) as call_dir:
            memory = None
            try:
                if not self.startup_probe:
                    from .memory import MemorySession

                    try:
                        memory = MemorySession(self.home, Path.home() / ".dougle")
                    except (OSError, BotError):
                        # Reading remains useful without optional memory staging.
                        # _run_call must still create a policy that denies all writes.
                        memory = None
                result = self._run_call(
                    text,
                    session,
                    budget,
                    policy_dir=Path(call_dir),
                    memory=memory,
                    images=images,
                    on_progress=on_progress,
                    timeout_seconds=timeout_seconds,
                    cancel_requested=cancel_requested,
                )
                # _run_call has completed process-group and pipe cleanup. A parsed
                # result is only staged here; the worker alone may mark it ready.
                if memory is not None:
                    try:
                        result.memory_batch = memory.finish()
                    except Exception:
                        # Memory persistence is optional after a successful engine
                        # result. Keep the answer/proof for normal worker validation;
                        # report only a constant, never filesystem or content errors.
                        result.memory_batch = None
                        result.memory_error = "memory_finalize_failed"
                        with suppress(Exception):
                            memory.discard()
                elif not self.startup_probe:
                    result.memory_error = "memory_staging_unavailable"
                return result
            except BaseException:
                if memory is not None:
                    # Cleanup failure must not replace the original engine error.
                    with suppress(Exception):
                        memory.discard()
                raise

    def _run_call(
        self,
        text,
        session,
        budget,
        *,
        policy_dir,
        memory,
        images=(),
        on_progress=None,
        timeout_seconds=None,
        cancel_requested=None,
    ):
        cwd = private_dir(Path(self.config.workdir).expanduser())
        policy = policy_dir / "tools.json"
        policy_value = {
            "allowed": self.config.allowed_tools,
            "startup_probe": self.startup_probe,
            "skills": list(self.config.required_skills),
            "memory": str(Path.home() / ".dougle"),
            "runtime_home": str(self.home.resolve()),
        }
        if memory is not None:
            policy_value.update(memory.policy_fields())
        elif not self.startup_probe:
            policy_value["memory_writes_denied"] = True
        with policy.open("x") as stream:
            json.dump(policy_value, stream)
        policy.chmod(0o600)
        settings = policy_dir / "settings.json"
        with settings.open("x") as stream:
            json.dump(
                {
                    "hooks": {
                        "PostToolUse": [
                            {
                                "matcher": ".*",
                                "hooks": [
                                    {
                                        "type": "command",
                                        "command": shlex.join(
                                            [sys.executable, "-m", "dougle_bot.receipt_hook"]
                                        ),
                                    }
                                ],
                            }
                        ],
                        "PreToolUse": [
                            {
                                "matcher": ".*",
                                "hooks": [
                                    {
                                        "type": "command",
                                        "command": shlex.join(
                                            [sys.executable, "-m", "dougle_bot.guard", str(policy)]
                                        ),
                                    }
                                ],
                            }
                        ],
                    }
                },
                stream,
            )
        settings.chmod(0o600)
        env = {
            k: v
            for k, v in os.environ.items()
            if not k.startswith(("SLACK_", "DOUGLE_BOT_TOKEN", "DOUGLE_APP_TOKEN"))
            and not re.search(r"xox[baprs]-|xapp-", v)
        }
        env.pop("CLAUDECODE", None)
        try:
            proc = self.popen(
                self.command(session, budget, settings, images=bool(images)),
                cwd=cwd,
                env=env,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                start_new_session=True,
            )
        except OSError:
            raise BotError("engine_launch_failed") from None
        out = bytearray()
        err = bytearray()
        start = time.monotonic()
        streams = None
        telemetry = StreamProgress(on_progress or (lambda event: None))
        limit = (
            min(self.config.timeout_seconds, timeout_seconds)
            if timeout_seconds is not None
            else self.config.timeout_seconds
        )
        try:
            # stdin is bounded; write in its own selector to avoid full-pipe deadlock.
            streams = selectors.DefaultSelector()
            for f in (proc.stdout, proc.stderr, proc.stdin):
                os.set_blocking(f.fileno(), False)
            streams.register(proc.stdout, selectors.EVENT_READ, out)
            streams.register(proc.stderr, selectors.EVENT_READ, err)
            if images:
                content = [{"type": "text", "text": text}]
                for image in images:
                    content.extend(image.blocks())
                text = (
                    json.dumps(
                        {
                            "type": "user",
                            "message": {"role": "user", "content": content},
                            "parent_tool_use_id": None,
                        },
                        ensure_ascii=False,
                    )
                    + "\n"
                )
            pending = memoryview(text.encode())
            streams.register(proc.stdin, selectors.EVENT_WRITE, "input")
            while streams.get_map():
                if cancel_requested and cancel_requested():
                    raise BotError("inquiry_cancelled")
                if self.cancelled():
                    raise BotError("bot_stopped")
                if time.monotonic() - start >= limit:
                    raise BotError("engine_timeout")
                for key, _ in streams.select(0.2):
                    if key.data == "input":
                        try:
                            n = os.write(key.fd, pending[:32768])
                            pending = pending[n:]
                        except BrokenPipeError:
                            pending = pending[len(pending) :]
                        if not pending:
                            streams.unregister(key.fileobj)
                            key.fileobj.close()
                    else:
                        chunk = os.read(key.fd, 65536)
                        if not chunk:
                            streams.unregister(key.fileobj)
                            key.fileobj.close()
                        else:
                            key.data.extend(chunk)
                            if len(out) + len(err) > 4_000_000:
                                raise BotError("engine_output_too_large")
                            if key.data is out:
                                telemetry.feed(chunk)
            proc.wait(timeout=2)
            if proc.returncode:
                # Only classify a known error; never store raw output.
                if re.search(
                    rb"No conversation found|session[^\n]{0,40}not found",
                    bytes(out) + bytes(err),
                    re.I,
                ):
                    raise BotError("session_not_found")
                if images and re.search(
                    rb"image[^\n]{0,80}(?:invalid|decode|process|too large|exceed)|(?:invalid|decode)[^\n]{0,40}image|unknown option[^\n]{0,40}input-format",
                    bytes(out) + bytes(err),
                    re.I,
                ):
                    raise BotError("image_input_failed")
                raise BotError("engine_failed")
            return parse_trace(bytes(out))
        finally:
            if streams is not None:
                streams.close()
            # A child can retain pipes after its parent exits. Always close the owned group.
            # A second TERM can race with macOS process teardown; never mask the original error.
            try:
                os.killpg(proc.pid, signal.SIGTERM)
            except (ProcessLookupError, PermissionError):
                if proc.poll() is None:
                    try:
                        proc.terminate()
                    except ProcessLookupError:
                        pass
            try:
                proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(proc.pid, signal.SIGKILL)
                except (ProcessLookupError, PermissionError):
                    proc.kill()
                proc.wait(timeout=2)
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                pass
            for f in (proc.stdin, proc.stdout, proc.stderr):
                if f and not f.closed:
                    f.close()


class FakeEngine:
    def __init__(self, results):
        self.results = list(results)
        self.calls = []
        self.image_calls = []
        self.timeouts = []

    def run(
        self,
        text,
        session,
        budget,
        *,
        images=(),
        on_progress=None,
        timeout_seconds=None,
        cancel_requested=None,
    ):
        self.calls.append((text, session, budget))
        self.image_calls.append(tuple(images))
        self.timeouts.append(timeout_seconds)
        result = self.results.pop(0)
        if isinstance(result, Exception):
            raise result
        return result
