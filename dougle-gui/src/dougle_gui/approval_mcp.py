"""Stdio MCP server that forwards Claude permission prompts to the GUI.

Claude CLI runs this as `--permission-prompt-tool mcp__dougle_gui__approve`.
When a tool is neither allowed nor denied by rules, Claude calls `approve`;
this server asks the GUI (which waits for the user's click) and returns the
decision. Deny rules are evaluated by Claude first, so blocked tools never
reach here. Any failure answers deny.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

from . import __version__

SERVER_NAME = "dougle_gui"
TOOL_NAME = "approve"
PROMPT_TOOL = f"mcp__{SERVER_NAME}__{TOOL_NAME}"
DEFAULT_PROTOCOL = "2025-06-18"

TOOL_SPEC = {
    "name": TOOL_NAME,
    "description": "Ask the dougle GUI user to allow or deny a tool call.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "tool_name": {"type": "string"},
            "input": {"type": "object"},
            "tool_use_id": {"type": "string"},
        },
        "required": ["tool_name", "input"],
    },
}


def decision_text(decision: dict, tool_input: dict) -> str:
    if decision.get("behavior") == "allow":
        return json.dumps({"behavior": "allow", "updatedInput": tool_input}, ensure_ascii=False)
    message = decision.get("message") or "사용자가 GUI에서 거부했습니다."
    return json.dumps({"behavior": "deny", "message": message}, ensure_ascii=False)


def ask_gui(tool_name: str, tool_input: dict) -> dict:
    url = os.environ.get("DOUGLE_GUI_APPROVAL_URL", "")
    body = json.dumps(
        {
            "run": os.environ.get("DOUGLE_GUI_RUN", ""),
            "secret": os.environ.get("DOUGLE_GUI_SECRET", ""),
            "tool": tool_name,
            "input": tool_input,
        }
    ).encode()
    timeout = float(os.environ.get("DOUGLE_GUI_APPROVAL_TIMEOUT", "600")) + 30
    request = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read() or b"{}")
    except (urllib.error.URLError, OSError, ValueError):
        return {"behavior": "deny", "message": "GUI에 승인 요청을 보내지 못해 거부했습니다."}


def handle(message: dict, decide=ask_gui) -> dict | None:
    """Return the JSON-RPC response for one message (None for notifications)."""
    method = message.get("method")
    msg_id = message.get("id")
    if msg_id is None:
        return None
    if method == "initialize":
        params = message.get("params") or {}
        result = {
            "protocolVersion": params.get("protocolVersion") or DEFAULT_PROTOCOL,
            "capabilities": {"tools": {}},
            "serverInfo": {"name": SERVER_NAME, "version": __version__},
        }
    elif method == "ping":
        result = {}
    elif method == "tools/list":
        result = {"tools": [TOOL_SPEC]}
    elif method == "tools/call":
        params = message.get("params") or {}
        if params.get("name") != TOOL_NAME:
            return {"jsonrpc": "2.0", "id": msg_id, "error": {"code": -32602, "message": "unknown tool"}}
        arguments = params.get("arguments") or {}
        tool_input = arguments.get("input") if isinstance(arguments.get("input"), dict) else {}
        decision = decide(str(arguments.get("tool_name", "")), tool_input)
        result = {"content": [{"type": "text", "text": decision_text(decision, tool_input)}]}
    else:
        return {"jsonrpc": "2.0", "id": msg_id, "error": {"code": -32601, "message": "method not found"}}
    return {"jsonrpc": "2.0", "id": msg_id, "result": result}


def main() -> int:
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue
        if not isinstance(message, dict):
            continue
        response = handle(message)
        if response is not None:
            sys.stdout.write(json.dumps(response, ensure_ascii=False) + "\n")
            sys.stdout.flush()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
