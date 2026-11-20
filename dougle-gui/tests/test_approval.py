import json
import subprocess
import sys
import threading
import time

import pytest

from dougle_gui.approval_mcp import PROMPT_TOOL, handle
from dougle_gui.runs import RunError, Runner

from .helpers import request


def call(arguments, decide):
    return handle({"jsonrpc": "2.0", "id": 7, "method": "tools/call",
                   "params": {"name": "approve", "arguments": arguments}}, decide)


def test_mcp_handshake_and_tool_listing():
    init = handle({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-03-26"}})
    assert init["result"]["protocolVersion"] == "2025-03-26"
    assert init["result"]["capabilities"] == {"tools": {}}
    assert handle({"jsonrpc": "2.0", "method": "notifications/initialized"}) is None
    tools = handle({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})["result"]["tools"]
    assert [t["name"] for t in tools] == ["approve"]
    assert handle({"jsonrpc": "2.0", "id": 3, "method": "nope"})["error"]["code"] == -32601
    assert PROMPT_TOOL == "mcp__dougle_gui__approve"


def test_allow_returns_original_input_and_deny_returns_message():
    seen = []
    allow = call({"tool_name": "Bash", "input": {"command": "rm x"}},
                 lambda tool, data: seen.append((tool, data)) or {"behavior": "allow"})
    assert json.loads(allow["result"]["content"][0]["text"]) == {"behavior": "allow", "updatedInput": {"command": "rm x"}}
    assert seen == [("Bash", {"command": "rm x"})]
    deny = call({"tool_name": "Bash", "input": {"command": "rm x"}}, lambda tool, data: {"behavior": "deny"})
    assert json.loads(deny["result"]["content"][0]["text"])["behavior"] == "deny"


def test_unreachable_gui_denies(monkeypatch):
    monkeypatch.setenv("DOUGLE_GUI_APPROVAL_URL", "http://127.0.0.1:9/api/internal/approvals")
    monkeypatch.setenv("DOUGLE_GUI_APPROVAL_TIMEOUT", "0")
    result = call({"tool_name": "Bash", "input": {"command": "ls"}}, __import__("dougle_gui.approval_mcp").approval_mcp.ask_gui)
    assert json.loads(result["result"]["content"][0]["text"])["behavior"] == "deny"


def test_mcp_server_runs_over_stdio():
    lines = "\n".join(json.dumps(m) for m in [
        {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}},
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
    ]) + "\n"
    done = subprocess.run([sys.executable, "-m", "dougle_gui.approval_mcp"], input=lines,
                          capture_output=True, text=True, timeout=20)
    replies = [json.loads(line) for line in done.stdout.splitlines()]
    assert [r["id"] for r in replies] == [1, 2]


def test_argv_wires_permission_prompt_tool_only_when_enabled(fake_claude, tmp_path):
    binary, _ = fake_claude
    runner = Runner(claude_bin=str(binary), workdir=tmp_path)
    assert "--permission-prompt-tool" not in runner.argv("p", session=None, blocked=())
    runner.approval_url = "http://127.0.0.1:1/api/internal/approvals"
    argv = runner.argv("p", session=None, blocked=(), run_id="abc", secret="s3")
    assert argv[argv.index("--permission-prompt-tool") + 1] == PROMPT_TOOL
    config = json.loads(argv[argv.index("--mcp-config") + 1])["mcpServers"]["dougle_gui"]
    assert config["args"] == ["-m", "dougle_gui.approval_mcp"]
    assert config["env"]["DOUGLE_GUI_RUN"] == "abc" and config["env"]["DOUGLE_GUI_SECRET"] == "s3"
    assert PROMPT_TOOL in argv[argv.index("--allowedTools") + 1]


def test_publish_prepare_asks_before_gh_api_when_approvals_are_on(fake_claude, tmp_path):
    binary, calls = fake_claude
    runner = Runner(claude_bin=str(binary), workdir=tmp_path)
    runner.approval_url = "http://127.0.0.1:1/api/internal/approvals"
    run = runner.start("publish", "https://github.com/org/kb 에 receiving 지식 패키지를 공유해 주세요")
    deadline = time.time() + 10
    while not calls() and time.time() < deadline:
        time.sleep(0.05)
    argv = calls()[0]
    assert json.loads(argv[argv.index("--settings") + 1]) == {"permissions": {"ask": ["Bash(gh api:*)"]}}
    blocked = argv[argv.index("--disallowedTools") + 1].split(",")
    assert "Bash(gh api:*)" not in blocked and "Bash(git push:*)" in blocked
    runner.cancel(run.id)


def ask_in_background(app, body):
    box = {}

    def go():
        box["response"] = request(app, "POST", "/api/internal/approvals", token=None, body=body)

    thread = threading.Thread(target=go, daemon=True)
    thread.start()
    return thread, box


def wait_pending(run, count=1, timeout=10):
    deadline = time.time() + timeout
    while time.time() < deadline:
        pending = [a for a in run.approvals.values() if a["status"] == "pending"]
        if len(pending) >= count:
            return pending[-1]
        time.sleep(0.05)
    raise AssertionError("no pending approval")


def test_user_allows_then_allow_always_skips_next_prompt(server):
    app, _ = server
    run = app.runner.start("connect", "SLOW")
    body = {"run": run.id, "secret": run.secret, "tool": "Bash", "input": {"command": "rm -rf build"}}
    thread, box = ask_in_background(app, body)
    pending = wait_pending(run)
    assert run.summary()["pending_approvals"] == 1
    assert any(e["kind"] == "approval" and e["detail"] == "rm -rf build" for e in run.events)
    status, data, _ = request(app, "POST", f"/api/runs/{run.id}/approvals/{pending['id']}", body={"decision": "allow_always"})
    assert status == 200 and data["status"] == "allow_always"
    thread.join(10)
    assert box["response"][1] == {"behavior": "allow"}
    # Same request is allowed without asking again.
    status, data, _ = request(app, "POST", "/api/internal/approvals", token=None, body=body)
    assert data == {"behavior": "allow"}
    assert any(e["kind"] == "approval_auto" for e in run.events)
    assert request(app, "POST", f"/api/runs/{run.id}/approvals/{pending['id']}", body={"decision": "deny"})[0] == 409


def test_user_denies(server):
    app, _ = server
    run = app.runner.start("connect", "SLOW")
    thread, box = ask_in_background(app, {"run": run.id, "secret": run.secret, "tool": "Bash", "input": {"command": "curl x"}})
    pending = wait_pending(run)
    request(app, "POST", f"/api/runs/{run.id}/approvals/{pending['id']}", body={"decision": "deny"})
    thread.join(10)
    assert box["response"][1]["behavior"] == "deny"


def test_cancel_resolves_pending_approval_as_deny(server):
    app, _ = server
    run = app.runner.start("connect", "SLOW")
    thread, box = ask_in_background(app, {"run": run.id, "secret": run.secret, "tool": "Bash", "input": {"command": "x"}})
    wait_pending(run)
    app.runner.cancel(run.id)
    thread.join(10)
    assert box["response"][1]["behavior"] == "deny"
    assert any(e["kind"] == "approval_done" and e["reason"] == "run_ended" for e in run.events)


def test_timeout_denies(fake_claude, tmp_path):
    binary, _ = fake_claude
    runner = Runner(claude_bin=str(binary), workdir=tmp_path, approval_timeout=0.3)
    run = runner.start("connect", "SLOW")
    assert runner.request_approval(run.id, run.secret, "Bash", {"command": "x"})["behavior"] == "deny"
    assert any(e.get("reason") == "timeout" for e in run.events)
    runner.cancel(run.id)


def test_wrong_secret_or_bad_decision_is_rejected(server):
    app, _ = server
    run = app.runner.start("connect", "SLOW")
    status, data, _ = request(app, "POST", "/api/internal/approvals", token=None,
                              body={"run": run.id, "secret": "guess", "tool": "Bash", "input": {}})
    assert (status, data) == (403, {"error": "invalid_approval_request"})
    assert request(app, "POST", "/api/internal/approvals", token=None, host="evil.example",
                   body={"run": run.id, "secret": run.secret, "tool": "Bash", "input": {}})[0] == 403
    with pytest.raises(RunError):
        app.runner.decide(run.id, "deadbeef", "yes")
