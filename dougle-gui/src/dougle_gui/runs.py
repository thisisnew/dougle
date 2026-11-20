"""Run Dougle skills through the user's Claude CLI and stream their progress.

The GUI does not answer, connect or publish by itself. Each action starts one
`claude -p` call that invokes the matching Dougle skill, so every rule of the
skills (read-only originals, provenance, merge, sensitive-data gate) still
applies. Only one run executes at a time because skills write the shared
~/.dougle store and the protocol forbids concurrent memory writers.
"""

from __future__ import annotations

import hmac
import json
import os
import secrets
import shutil
import signal
import subprocess
import sys
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path

from .approval_mcp import PROMPT_TOOL, SERVER_NAME

KINDS = ("ask", "connect", "fetch", "publish")
MAX_INPUT = 4000
MAX_EVENTS = 5000
KEEP_RUNS = 30
APPROVAL_TIMEOUT = 600
APPROVAL_DECISIONS = ("allow", "allow_always", "deny")

# Read-only shell commands. A compound command (`cd x && diff a b`) is allowed
# only when every part matches, so common read helpers are listed together.
_READ_COMMANDS = (
    "cd", "pwd", "ls", "find", "grep", "head", "tail", "wc", "cat", "diff", "cmp",
    "sort", "uniq", "cut", "tr", "jq", "base64", "stat", "file", "du", "basename",
    "dirname", "shasum", "date", "echo", "test",
)
DEFAULT_ALLOWED_TOOLS = (
    "Skill",
    "Read",
    "Grep",
    "Glob",
    "ToolSearch",
    "WebFetch",
    "WebSearch",
    "Write(~/.dougle/**)",
    "Edit(~/.dougle/**)",
    "Bash(git:*)",
    "Bash(gh:*)",
    *(f"Bash({name}:*)" for name in _READ_COMMANDS),
)

# Remote writes are blocked while a publish is only being prepared. Read-only
# lookups such as `gh repo view` and `git ls-remote` stay available.
PUBLISH_PREPARE_BLOCKED = (
    "Bash(git push:*)",
    "Bash(git -C * push:*)",
    *(f"Bash(gh repo {action}:*)" for action in ("create", "delete", "edit", "fork", "rename", "archive", "sync")),
    "Bash(gh pr:*)",
    "Bash(gh release:*)",
)

# gh api can both read and write depending on flags, so during preparation each
# call is shown to the user for approval (or denied when approvals are off).
PUBLISH_PREPARE_ASK = ("Bash(gh api:*)",)

PUBLISH_PREPARE_NOTE = (
    "이번 실행은 게시 준비 단계입니다. 원격 저장소에 쓰지 마세요. "
    "공유할 대상 브랜치와 경로, 포함 토픽, 제외 자료, 크기와 민감정보 검사 결과까지만 보고하고 멈추세요. "
    "사용자가 GUI에서 확인하면 같은 세션에서 게시를 이어갑니다."
)
PUBLISH_CONFIRM_TEXT = "확인했습니다. 방금 보고한 범위 그대로 게시해 주세요. 범위가 달라졌다면 게시하지 말고 알려 주세요."


class RunError(Exception):
    def __init__(self, code: str, status: int = 400):
        super().__init__(code)
        self.code = code
        self.status = status


def build_prompt(kind: str, text: str) -> str:
    text = " ".join(text.split())
    if kind == "ask":
        return f"/dougle:dougle-ask {text}"
    if kind == "connect":
        return f"/dougle:dougle-connect {text} 을(를) 읽고 업무 지식으로 정리해 주세요."
    if kind == "fetch":
        return f"/dougle:dougle-fetch {text}"
    if kind == "publish":
        return f"/dougle:dougle-publish {text}"
    raise RunError("invalid_kind")


@dataclass
class Run:
    id: str
    kind: str
    title: str
    phase: str
    argv: list[str]
    created: float
    status: str = "running"
    session: str | None = None
    result: str = ""
    cost: float | None = None
    turns: int | None = None
    denials: list[dict] = field(default_factory=list)
    events: list[dict] = field(default_factory=list)
    parent: str | None = None
    cancel_requested: bool = False
    closed: bool = False
    finished: float | None = None
    last_activity: float = 0.0
    secret: str = ""
    approvals: dict[str, dict] = field(default_factory=dict)
    stderr_thread: threading.Thread | None = None
    process: subprocess.Popen | None = None
    cond: threading.Condition = field(default_factory=threading.Condition)

    def emit(self, event: dict) -> None:
        with self.cond:
            self.last_activity = time.time()
            if len(self.events) < MAX_EVENTS or event["kind"] == "exit":
                self.events.append(event)
            if event["kind"] == "exit":
                self.closed = True
            self.cond.notify_all()

    def summary(self) -> dict:
        return {
            "id": self.id,
            "kind": self.kind,
            "title": self.title,
            "phase": self.phase,
            "status": self.status,
            "session": self.session,
            "result": self.result,
            "cost": self.cost,
            "turns": self.turns,
            "denials": self.denials,
            "created": self.created,
            "finished": self.finished,
            "last_activity": self.last_activity or self.created,
            "parent": self.parent,
            "events": len(self.events),
            "pending_approvals": sum(a["status"] == "pending" for a in self.approvals.values()),
        }


def _approval_detail(data: dict) -> str:
    for key in ("command", "file_path", "path", "url", "pattern", "query"):
        value = data.get(key)
        if isinstance(value, str) and value:
            return value[:2000]
    return json.dumps(data, ensure_ascii=False)[:2000]


def _tool_summary(name: str, data: dict) -> str:
    for key in ("command", "file_path", "path", "pattern", "url", "skill", "query", "description"):
        value = data.get(key)
        if isinstance(value, str) and value:
            text = " ".join(value.split())
            return text[:160] + ("…" if len(text) > 160 else "")
    return ""


def translate(line: str) -> list[dict]:
    """Convert one stream-json line from the Claude CLI into GUI events."""
    try:
        data = json.loads(line)
    except json.JSONDecodeError:
        return [{"kind": "log", "text": line[:500]}] if line.strip() else []
    if not isinstance(data, dict):
        return []
    kind = data.get("type")
    events: list[dict] = []
    if kind == "system" and data.get("subtype") == "init":
        events.append({"kind": "session", "session": data.get("session_id")})
    elif kind == "assistant":
        for part in (data.get("message") or {}).get("content") or []:
            if part.get("type") == "text" and part.get("text"):
                events.append({"kind": "text", "text": part["text"]})
            elif part.get("type") == "tool_use":
                name = str(part.get("name", ""))
                events.append(
                    {"kind": "tool", "name": name, "detail": _tool_summary(name, part.get("input") or {})}
                )
    elif kind == "user":
        for part in (data.get("message") or {}).get("content") or []:
            if isinstance(part, dict) and part.get("type") == "tool_result" and part.get("is_error"):
                content = part.get("content")
                text = content if isinstance(content, str) else json.dumps(content, ensure_ascii=False)
                events.append({"kind": "tool_error", "text": text[:300]})
    elif kind == "result":
        denials = []
        for item in data.get("permission_denials") or []:
            if isinstance(item, dict):
                denials.append(
                    {
                        "tool": str(item.get("tool_name", "")),
                        "detail": _tool_summary(str(item.get("tool_name", "")), item.get("tool_input") or {}),
                    }
                )
        events.append(
            {
                "kind": "result",
                "ok": not data.get("is_error") and data.get("subtype") == "success",
                "text": data.get("result") or "",
                "session": data.get("session_id"),
                "cost": data.get("total_cost_usd"),
                "turns": data.get("num_turns"),
                "denials": denials,
            }
        )
    return events


class Runner:
    def __init__(
        self,
        claude_bin: str = "claude",
        workdir: Path | None = None,
        allowed_tools: tuple[str, ...] = DEFAULT_ALLOWED_TOOLS,
        extra_args: tuple[str, ...] = (),
        approval_timeout: float = APPROVAL_TIMEOUT,
    ):
        self.claude_bin = claude_bin
        # Set by the server once it knows its port; None means prompts are denied automatically.
        self.approval_url: str | None = None
        self.approval_timeout = approval_timeout
        self.always_allowed: set[str] = set()
        self.workdir = Path(workdir or Path.home())
        self.allowed_tools = tuple(allowed_tools)
        self.extra_args = tuple(extra_args)
        self.runs: dict[str, Run] = {}
        self.lock = threading.Lock()

    def claude_path(self) -> str | None:
        if "/" in self.claude_bin:
            path = Path(self.claude_bin).expanduser()
            return str(path) if path.is_file() and os.access(path, os.X_OK) else None
        return shutil.which(self.claude_bin)

    def argv(
        self,
        prompt: str,
        *,
        session: str | None,
        blocked: tuple[str, ...],
        ask: tuple[str, ...] = (),
        run_id: str = "",
        secret: str = "",
    ) -> list[str]:
        binary = self.claude_path()
        if binary is None:
            raise RunError("claude_not_found", 503)
        allowed = list(self.allowed_tools)
        if self.approval_url:
            allowed.append(PROMPT_TOOL)
        args = [
            binary,
            "-p",
            prompt,
            "--output-format",
            "stream-json",
            "--verbose",
            "--allowedTools",
            ",".join(allowed),
        ]
        if self.approval_url:
            config = {
                "mcpServers": {
                    SERVER_NAME: {
                        "command": sys.executable,
                        "args": ["-m", "dougle_gui.approval_mcp"],
                        "env": {
                            "DOUGLE_GUI_APPROVAL_URL": self.approval_url,
                            "DOUGLE_GUI_RUN": run_id,
                            "DOUGLE_GUI_SECRET": secret,
                            "DOUGLE_GUI_APPROVAL_TIMEOUT": str(int(self.approval_timeout)),
                        },
                    }
                }
            }
            args += ["--mcp-config", json.dumps(config), "--permission-prompt-tool", PROMPT_TOOL]
        if ask and not self.approval_url:
            # Nobody can answer a prompt, so these become plain denials.
            blocked, ask = (*blocked, *ask), ()
        if blocked:
            args += ["--disallowedTools", ",".join(blocked)]
        if ask:
            # Ask rules take precedence over allow rules and route to the approval tool.
            args += ["--settings", json.dumps({"permissions": {"ask": list(ask)}})]
        if session:
            args += ["--resume", session]
        return [*args, *self.extra_args]

    def active(self) -> Run | None:
        for run in self.runs.values():
            if run.status == "running":
                return run
        return None

    def start(self, kind: str, text: str, session: str | None = None) -> Run:
        if kind not in KINDS:
            raise RunError("invalid_kind")
        text = (text or "").strip()
        if not text:
            raise RunError("empty_input")
        if len(text) > MAX_INPUT:
            raise RunError("input_too_long")
        if session is not None and kind != "ask":
            raise RunError("resume_not_allowed")
        prompt = build_prompt(kind, text)
        blocked: tuple[str, ...] = ()
        ask: tuple[str, ...] = ()
        phase = "run"
        if kind == "publish":
            prompt = f"{prompt}\n\n{PUBLISH_PREPARE_NOTE}"
            blocked = PUBLISH_PREPARE_BLOCKED
            ask = PUBLISH_PREPARE_ASK
            phase = "prepare"
        return self._launch(kind, text, phase, prompt, session=session, blocked=blocked, ask=ask)

    def confirm_publish(self, run_id: str) -> Run:
        parent = self.runs.get(run_id)
        if parent is None:
            raise RunError("run_not_found", 404)
        if parent.kind != "publish" or parent.phase != "prepare":
            raise RunError("not_a_publish_preparation")
        if parent.status != "done" or not parent.session:
            raise RunError("preparation_not_finished", 409)
        if any(run.parent == parent.id for run in self.runs.values()):
            raise RunError("already_confirmed", 409)
        run = self._launch(
            "publish", parent.title, "confirm", PUBLISH_CONFIRM_TEXT, session=parent.session, blocked=()
        )
        run.parent = parent.id
        return run

    def _launch(self, kind, title, phase, prompt, *, session, blocked, ask=()) -> Run:
        with self.lock:
            if self.active() is not None:
                raise RunError("another_run_active", 409)
            run_id = uuid.uuid4().hex[:12]
            secret = secrets.token_urlsafe(24)
            run = Run(
                id=run_id,
                kind=kind,
                title=title[:200],
                phase=phase,
                argv=self.argv(prompt, session=session, blocked=blocked, ask=ask, run_id=run_id, secret=secret),
                created=time.time(),
                secret=secret,
            )
            # The approval tool call waits for a person; keep Claude from timing it out first.
            env = {**os.environ, "MCP_TOOL_TIMEOUT": str(int((self.approval_timeout + 60) * 1000))}
            try:
                run.process = subprocess.Popen(
                    run.argv,
                    cwd=self.workdir,
                    env=env,
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                    bufsize=1,
                    start_new_session=True,
                )
            except OSError as exc:
                raise RunError("claude_start_failed", 503) from exc
            self.runs[run.id] = run
            self._trim()
        run.stderr_thread = threading.Thread(target=self._pump_stderr, args=(run,), daemon=True)
        run.stderr_thread.start()
        threading.Thread(target=self._pump, args=(run,), daemon=True).start()
        return run

    def _trim(self) -> None:
        finished = [run for run in self.runs.values() if run.status != "running"]
        for run in sorted(finished, key=lambda r: r.created)[: max(0, len(self.runs) - KEEP_RUNS)]:
            self.runs.pop(run.id, None)

    def _pump_stderr(self, run: Run) -> None:
        assert run.process and run.process.stderr
        for line in run.process.stderr:
            if line.strip():
                run.emit({"kind": "stderr", "text": line.rstrip()[:500]})

    def _pump(self, run: Run) -> None:
        assert run.process and run.process.stdout
        for line in run.process.stdout:
            for event in translate(line):
                if event["kind"] == "session" and event.get("session"):
                    run.session = event["session"]
                if event["kind"] == "result":
                    run.result = event["text"]
                    run.cost = event.get("cost")
                    run.turns = event.get("turns")
                    run.denials = event.get("denials") or []
                    run.session = event.get("session") or run.session
                    run.status = "done" if event["ok"] else "failed"
                run.emit(event)
        code = run.process.wait()
        if run.stderr_thread is not None:
            run.stderr_thread.join(timeout=5)
        if run.status == "running":
            run.status = "cancelled" if run.cancel_requested else "failed"
        elif run.status == "done" and code != 0:
            run.status = "failed"
        run.finished = time.time()
        run.emit({"kind": "exit", "code": code, "status": run.status, "finished": run.finished})

    def cancel(self, run_id: str) -> Run:
        run = self.runs.get(run_id)
        if run is None:
            raise RunError("run_not_found", 404)
        if run.status == "running" and run.process is not None:
            run.cancel_requested = True
            try:
                os.killpg(run.process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        return run

    def request_approval(self, run_id: str, secret: str, tool: str, tool_input: dict) -> dict:
        """Called by the approval MCP server; blocks until the user decides."""
        run = self.runs.get(run_id)
        if run is None or not run.secret or not hmac.compare_digest(secret.encode(), run.secret.encode()):
            raise RunError("invalid_approval_request", 403)
        if not isinstance(tool_input, dict):
            tool_input = {}
        key = tool + "\n" + json.dumps(tool_input, sort_keys=True, ensure_ascii=False)
        approval_id = uuid.uuid4().hex[:8]
        detail = _approval_detail(tool_input)
        if key in self.always_allowed:
            run.emit({"kind": "approval_auto", "id": approval_id, "tool": tool, "detail": detail})
            return {"behavior": "allow"}
        approval = {"id": approval_id, "tool": tool, "detail": detail, "status": "pending", "key": key}
        with run.cond:
            run.approvals[approval_id] = approval
        run.emit({"kind": "approval", "id": approval_id, "tool": tool, "detail": detail})
        deadline = time.time() + self.approval_timeout
        with run.cond:
            while approval["status"] == "pending" and run.status == "running" and not run.cancel_requested:
                remaining = deadline - time.time()
                if remaining <= 0:
                    break
                run.cond.wait(min(remaining, 1.0))
            if approval["status"] == "pending":
                approval["status"] = "deny"
                reason = "timeout" if time.time() >= deadline else "run_ended"
                approval["reason"] = reason
                run.events.append({"kind": "approval_done", "id": approval_id, "decision": "deny", "reason": reason})
                run.cond.notify_all()
        if approval["status"] in ("allow", "allow_always"):
            return {"behavior": "allow"}
        message = "사용자가 GUI에서 거부했습니다."
        if approval.get("reason") == "timeout":
            message = "GUI에서 제한 시간 안에 승인하지 않아 거부했습니다."
        return {"behavior": "deny", "message": message}

    def decide(self, run_id: str, approval_id: str, decision: str) -> dict:
        run = self.runs.get(run_id)
        if run is None:
            raise RunError("run_not_found", 404)
        if decision not in APPROVAL_DECISIONS:
            raise RunError("invalid_decision")
        with run.cond:
            approval = run.approvals.get(approval_id)
            if approval is None:
                raise RunError("approval_not_found", 404)
            if approval["status"] != "pending":
                raise RunError("approval_already_decided", 409)
            approval["status"] = decision
            if decision == "allow_always":
                self.always_allowed.add(approval["key"])
        run.emit({"kind": "approval_done", "id": approval_id, "decision": decision})
        return {"id": approval_id, "status": decision}

    def shutdown(self) -> None:
        for run in list(self.runs.values()):
            if run.status == "running":
                self.cancel(run.id)
