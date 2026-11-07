"""Check declared research obligations against current-call read receipts.

This verifies execution, not whether a model chose the right obligations or
interpreted the returned code/data correctly. No raw tool results are retained.
"""

import json
import re
from pathlib import PurePosixPath
from urllib.parse import urlsplit

from .safety import BotError

KINDS = {"knowledge", "image", "code", "live"}
QUESTION_KINDS = {
    "visual": {"image"},
    "domain": {"knowledge"},
    "implementation": {"code"},
    "current_state": {"live"},
    "incident": {"code"},
    "clarification": set(),
}
VERIFICATION_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["question_kind", "required", "checks", "missing"],
    "properties": {
        "question_kind": {"enum": list(QUESTION_KINDS)},
        "required": {"type": "array", "maxItems": 4, "items": {"enum": sorted(KINDS)}},
        "checks": {
            "type": "array",
            "maxItems": 30,
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["kind", "receipt", "source_index"],
                "properties": {
                    "kind": {"enum": sorted(KINDS)},
                    "receipt": {"type": "string", "minLength": 1, "maxLength": 200},
                    "source_index": {"type": "integer", "minimum": 0, "maximum": 29},
                },
            },
        },
        "missing": {"type": "array", "maxItems": 4, "items": {"enum": sorted(KINDS)}},
    },
}

RESEARCH_POLICY = """RESEARCH CONTRACT — complete this before shortening the public body.
Classify the latest question in verification.question_kind by meaning and thread context:
visual = visible labels/layout only; domain = stable business knowledge;
implementation = actual behavior/conditions in implementation; current_state = actual records/live status;
incident = explain a malfunction/cause; clarification = the intended case cannot yet be identified.
Screenshots do not turn implementation/incident questions into visual descriptions.
For implementation/incident, locate the original code via Dougle sources and inspect the relevant
condition/call path during THIS call, even if a topic note is recent. Include relevant config/docs.
Add live to verification.required when actual records/configuration/runtime state determines the answer;
use available authorized read-only DB tools when needed. Code alone cannot prove an actual delivery/record.
Do not query DB for a visual or stable conceptual question solely because a screenshot is attached.
Start with the exact source locator and authorized available reader. If the required source/tool is
unavailable or denied, return needs_lookup/cannot_answer promptly with the specific missing check;
do not repeatedly search unrelated sources, refresh the whole store or try the same denied reader.
Do not label this a permission issue unless the reader actually reported denial.
Read enough evidence, then apply audience/length formatting. Hide unnecessary code details in body,
not from the investigation. A disclaimer that code/DB was not checked cannot replace a required check.

Return verification with question_kind, required kinds, checks and missing kinds.
Kinds: knowledge (read topic/source content), image (attached visible evidence), code (original code),
live (fresh actual state). Include EVERY needed kind; implementation/incident always require code,
current_state always requires live. Mixed questions may need both. Clarification cannot be answered.
Each check links a zero-based sources index and a receipt: actual successful tool_use ID for a content
read in THIS call; image:<file_id> for a currently supplied image; developer_report for the supplied
timestamped manual result (only its declared evidence_kinds; attribute it). Never invent a tool ID or reuse a previous call's ID.
Read/Grep content outside .dougle on source code files, or WebFetch on original source code URLs,
can support code. Glob, filename-only Grep, skill instructions, topic summaries and schema metadata
cannot establish original code/live behavior. execute_sql_readonly can support live; table metadata cannot.
Bind checks to the relevant source and actual inspected environment/version/record scope.
No raw query rows, tool result bodies, hidden reasoning or secrets belong in verification.
answered requires all required kinds verified and missing empty. If a required read cannot be completed,
return needs_lookup with concrete minimal read-only checks, or cannot_answer for missing access/clarification.
Do not label an unverified causal/current-state answer answered with a caveat. On validation retry,
perform the missing reads or return unresolved; do not remove a requirement merely to pass validation.
"""

CODE_SUFFIXES = {
    ".java",
    ".kt",
    ".kts",
    ".py",
    ".js",
    ".ts",
    ".tsx",
    ".jsx",
    ".go",
    ".rs",
    ".c",
    ".h",
    ".cpp",
    ".cs",
    ".rb",
    ".php",
    ".swift",
    ".scala",
    ".sql",
    ".gradle",
}


def read_kinds(name, args):
    """Conservative receipt capabilities; semantic relevance remains the model's job."""
    if not isinstance(args, dict):
        return set()
    if name == "Read":
        target = args.get("file_path", "")
    elif name == "Grep" and args.get("output_mode") == "content":
        target = args.get("path", "")
    elif name == "WebFetch":
        target = args.get("url", "")
    elif name.startswith("mcp__"):
        # Only tools actually allowed by the runtime are usable. Metadata is knowledge, not live data.
        if name.endswith("__execute_sql_readonly"):
            return {"knowledge", "live"}
        if re.search(r"__(?:get|search|list|slack_read|slack_search)", name, re.I):
            return {"knowledge"}
        return set()
    else:
        return set()
    if not isinstance(target, str) or not target:
        return set()
    path = PurePosixPath(urlsplit(target).path if name == "WebFetch" else target)
    if path.name == "SKILL.md" or ".claude" in path.parts or ".agents" in path.parts:
        return set()
    kinds = {"knowledge"}
    if ".dougle" not in path.parts and path.suffix.lower() in CODE_SUFFIXES:
        kinds.add("code")
    return kinds


def read_receipts(raw):
    pending, completed, seen = {}, {}, set()
    for line in raw.splitlines():
        try:
            item = json.loads(line)
        except (ValueError, UnicodeError):
            continue
        if not isinstance(item, dict) or not isinstance(item.get("message"), dict):
            continue
        content = item["message"].get("content", [])
        if not isinstance(content, list):
            continue
        for block in content:
            if not isinstance(block, dict):
                continue
            ident = block.get("id")
            if item.get("type") == "assistant" and block.get("type") == "tool_use":
                if not isinstance(ident, str) or not ident:
                    continue
                if ident in seen:
                    raise BotError("invalid_read_trace")
                seen.add(ident)
                pending[ident] = read_kinds(str(block.get("name", "")), block.get("input"))
            ident = block.get("tool_use_id")
            if item.get("type") == "user" and block.get("type") == "tool_result":
                if not isinstance(ident, str) or ident not in pending:
                    continue
                kinds = pending.pop(ident)
                body = block.get("content")
                # Provider/connector errors may appear as structured content without is_error.
                if kinds and body and not block.get("is_error") and not failed_read(body):
                    completed[ident] = kinds
    return completed


def failed_read(body):
    if isinstance(body, list):
        return any(failed_read(part) for part in body)
    if isinstance(body, dict):
        if body.get("isError") or body.get("is_error") or body.get("error"):
            return True
        return body.get("type") == "text" and failed_read(body.get("text", ""))
    if isinstance(body, str):
        try:
            parsed = json.loads(body)
        except ValueError:
            parsed = None
        if isinstance(parsed, (dict, list)):
            return failed_read(parsed)
        # Only diagnostic responses, not an error-handling string inside source code.
        return bool(
            re.match(
                r"\s*(?:Error:|Permission denied\b|Access denied\b|No matches found\b|"
                r"File (?:does not exist|not found)\b|Tool .{0,50}(?:failed|unavailable)\b)",
                body,
                re.I,
            )
        )
    return False


def validate_verification(output, reads, *, image_ids=(), report=False, required_floor=()):
    value = output.get("verification")
    if not isinstance(value, dict) or set(value) != set(VERIFICATION_SCHEMA["required"]):
        raise BotError("invalid_verification")
    category = value["question_kind"]
    if not isinstance(category, str) or category not in QUESTION_KINDS:
        raise BotError("invalid_verification")
    for key in ("required", "missing"):
        items = value[key]
        if (
            not isinstance(items, list)
            or len(items) > 4
            or any(not isinstance(x, str) or x not in KINDS for x in items)
            or len(set(items)) != len(items)
        ):
            raise BotError("invalid_verification")
    required, missing = set(value["required"]), set(value["missing"])
    if not (QUESTION_KINDS[category] | set(required_floor)) <= required or not missing <= required:
        raise BotError("verification_requirements_missing")
    checks = value["checks"]
    if not isinstance(checks, list) or len(checks) > 30:
        raise BotError("invalid_verification")
    verified = set()
    for check in checks:
        if not isinstance(check, dict) or set(check) != {"kind", "receipt", "source_index"}:
            raise BotError("invalid_verification")
        kind, receipt, source = check["kind"], check["receipt"], check["source_index"]
        if (
            not isinstance(kind, str)
            or kind not in required
            or not isinstance(receipt, str)
            or not 1 <= len(receipt) <= 200
            or type(source) is not int
            or not 0 <= source < len(output["sources"])
        ):
            raise BotError("invalid_verification")
        valid = kind in reads.get(receipt, set())
        if receipt.startswith("image:"):
            valid = kind == "image" and receipt[6:] in image_ids
        if receipt == "developer_report":
            valid = (
                kind in report.get("evidence_kinds", ["live"])
                if isinstance(report, dict)
                else kind == "live" and report
            )
        if not valid:
            raise BotError("required_read_not_verified")
        verified.add(kind)
    if required - verified != missing or missing & verified:
        raise BotError("required_read_not_verified")
    if output["status"] == "answered" and (missing or not required or category == "clarification"):
        raise BotError("required_read_not_verified")
    return required
