"""Expose successful read IDs to the model; the stream remains the verifier's authority."""

import json
import re
import sys

from .bq_state import query_failure
from .evidence import failed_read, github_read_metadata, read_kinds
from .evidence_binding import handle


def context(event):
    if not isinstance(event, dict) or event.get("hook_event_name") != "PostToolUse":
        return {}
    ident = event.get("tool_use_id")
    if not isinstance(ident, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,200}", ident):
        return {}
    body = event.get("tool_response")
    if not body or failed_read(body) or (
        str(event.get("tool_name", "")).endswith("__execute_sql_readonly") and query_failure(body)
    ):
        return {}
    kinds = read_kinds(str(event.get("tool_name", "")), event.get("tool_input"))
    if event.get("tool_name") == "mcp__dougle_source__read_file" and not github_read_metadata(
        event.get("tool_input"), body
    ):
        return {}
    if not kinds:
        return {}
    # No source content, paths, query rows or secrets are copied into the reminder.
    receipt = json.dumps({"receipt": ident, "kinds": sorted(kinds)})
    return {
        "hookSpecificOutput": {
            "hookEventName": "PostToolUse",
            "additionalContext": "Dougle current-call read receipt: "
            + receipt
            + ". Short receipt alias: " + handle(ident)
            + ". This identifies the tool result immediately above. Use this exact receipt "
            "in verification.checks only for a supported kind and a source this result actually "
            "establishes. It does not establish relevance, freshness, or other unread sources.",
        }
    }


def main():
    try:
        raw = sys.stdin.buffer.read(4_000_001)
        result = context(json.loads(raw)) if len(raw) <= 4_000_000 else {}
    except (ValueError, TypeError):
        result = {}
    print(json.dumps(result))


if __name__ == "__main__":
    main()
