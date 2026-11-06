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
from dataclasses import dataclass, field
from pathlib import Path
from typing import Protocol

from .config import BUILTINS, private_dir
from .evidence import read_receipts
from .prompts import OUTPUT_SCHEMA, valid_cost
from .safety import BotError


@dataclass
class EngineResult:
    session_id: str
    output: dict
    cost_usd: float | None
    activated: set[str]
    reads: dict[str, set[str]] = field(default_factory=dict)


class Engine(Protocol):
    def run(self, text: str, session: str | None, budget: float, *, images=()) -> EngineResult: ...


def parse_trace(raw: bytes):
    calls = {}
    successful = set()
    result = None
    for line in raw.splitlines():
        try:
            item = json.loads(line)
        except (ValueError, UnicodeError):
            continue  # Shell startup banners are never logged or treated as results.
        if not isinstance(item, dict):
            continue
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
    )


class ClaudeCliEngine:
    def __init__(self, config, home, *, cancelled=lambda: False, popen=subprocess.Popen):
        self.config, self.home, self.cancelled, self.popen = config, Path(home), cancelled, popen

    def command(self, session, budget, settings, *, images=False):
        cfg = self.config
        args = [
            "-p",
            "--output-format",
            "stream-json",
            "--verbose",
            "--json-schema",
            json.dumps(OUTPUT_SCHEMA),
            "--permission-mode",
            "dontAsk",
            "--settings",
            str(settings),
            "--tools",
            ",".join(sorted(BUILTINS | {"ToolSearch"})),
            "--allowedTools",
            ",".join([*cfg.allowed_tools, "StructuredOutput", "ToolSearch"]),
            "--disallowedTools",
            ",".join(sorted(set(cfg.disallowed_tools) | {"Bash", "Agent", "Task"})),
            "--max-turns",
            str(cfg.max_turns),
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

    def run(self, text, session, budget, *, images=()):
        cwd = private_dir(Path(self.config.workdir).expanduser())
        policy_dir = private_dir(self.home / "policy")
        policy = policy_dir / "tools.json"
        if policy.is_symlink() or (policy_dir / "settings.json").is_symlink():
            raise BotError("unsafe_policy_path")
        policy.write_text(
            json.dumps(
                {
                    "allowed": self.config.allowed_tools,
                    "skills": list(self.config.required_skills),
                    "memory": str(Path.home() / ".dougle"),
                    "runtime_home": str(self.home.resolve()),
                }
            )
        )
        policy.chmod(0o600)
        settings = policy_dir / "settings.json"
        settings.write_text(
            json.dumps(
                {
                    "hooks": {
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
                        ]
                    }
                }
            )
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
                if self.cancelled():
                    raise BotError("bot_stopped")
                if time.monotonic() - start > self.config.timeout_seconds:
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

    def run(self, text, session, budget, *, images=()):
        self.calls.append((text, session, budget))
        self.image_calls.append(tuple(images))
        result = self.results.pop(0)
        if isinstance(result, Exception):
            raise result
        return result
