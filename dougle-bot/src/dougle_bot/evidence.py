"""Check declared research obligations against current-call read receipts.

This verifies execution, not whether a model chose the right obligations or
interpreted the returned code/data correctly. No raw tool results are retained.
"""

import json
import posixpath
import re
from pathlib import PurePosixPath
from urllib.parse import parse_qsl, unquote, urlencode, urlsplit

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
        "required": {
            "type": "array",
            "maxItems": 4,
            "uniqueItems": True,
            "items": {"enum": sorted(KINDS)},
        },
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
        "missing": {
            "type": "array",
            "maxItems": 4,
            "uniqueItems": True,
            "items": {"enum": sorted(KINDS)},
        },
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
timestamped manual result (only its declared evidence_kinds; attribute it).
After a successful read, a Dougle PostToolUse reminder supplies the exact receipt and supported kinds.
Copy that receipt verbatim. Do not infer it from a filename, invent toolu IDs, use citation numbers,
or reuse a previous call's ID. If no receipt reminder is available, report the missing verification
rather than guessing. For code, Read the exact original file; directory searches locate files but do
not replace reading the relevant code. A receipt only proves the corresponding read, not its meaning.
Read/Grep content outside .dougle on source code files, or WebFetch on original source code URLs,
can support code. Glob, filename-only Grep, skill instructions, topic summaries and schema metadata
cannot establish original code/live behavior. A successful data query can support live; SELECT 1,
schema and INFORMATION_SCHEMA queries cannot. Query execution time is not a data watermark, and
a read receipt does not establish that a replica is current or is the requested production environment.
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


class ReadReceipt(set):
    """Set-compatible capabilities plus runtime-owned metadata, never model assertions.

    Old fake engines and callers may still supply plain sets. Copy/deepcopy preserves metadata.
    Metadata stays with this call's successful tool result; it contains no result body or SQL.
    """

    def __init__(self, kinds=(), *, metadata=None):
        super().__init__(kinds)
        self.metadata = dict(metadata or {})


def sql_reads_data(args):
    """Conservative query-shape check, NOT a SQL sandbox or freshness verification.

    Require a physical relation, ignoring literals, comments, CTE names and table functions.
    Unrecognized/metadata-only queries retain knowledge capability but cannot satisfy live.
    """
    if not isinstance(args, dict):
        return False
    sql = next((args[k] for k in ("query", "sql", "sql_query") if isinstance(args.get(k), str)), "")
    if not sql or len(sql) > 100_000:
        return False
    tokens = re.findall(
        r"--[^\n]*|/\*[\s\S]*?\*/|'(?:''|\\.|[^'\\])*'|"
        r'"(?:""|\\.|[^"\\])*"|`[^`]*`|[A-Za-z_][\w$-]*|[^\s]',
        sql,
    )
    # Mask quoted identifiers while parsing SQL syntax. This preserves PostgreSQL's
    # double-quoted relations and BigQuery backticks without treating identifier text
    # such as `delete` or `order-export` as a modifying statement.
    prefix = "__dougle_identifier_"
    while prefix in sql:
        prefix += "_"
    quoted, identifiers, parts = {}, {}, []
    for token in tokens:
        if token.startswith(("--", "/*", "'")):
            continue
        if token.startswith(('"', "`")):
            name = token[1:-1].replace(token[0] * 2, token[0])
            placeholder = quoted.setdefault(name, f"{prefix}{len(quoted)}")
            identifiers[placeholder] = name
            parts.append(placeholder)
        else:
            parts.append(token)
    text = " ".join(parts).rstrip(" ;")
    if not re.match(r"^(?:SELECT|WITH)\b", text, re.I) or ";" in text:
        return False
    if any(
        token.upper()
        in {"INSERT", "UPDATE", "DELETE", "MERGE", "CREATE", "DROP", "ALTER", "CALL", "EXPORT"}
        for token in parts
    ):
        return False
    identifier = r"[A-Za-z_][\w$-]*(?:\s*\.\s*[A-Za-z_][\w$-]*)*"

    def relation_name(value):
        return ".".join(
            identifiers.get(part.strip(), part.strip()) for part in value.split(".")
        ).lower()

    ctes = {
        relation_name(name)
        for name in re.findall(
            r"(?:\b|(?=`))([A-Za-z_][\w$-]*|`[^`]+`)(?:\s*\([^()]*\))?\s+AS\s*\(",
            text,
            re.I,
        )
    }
    relations = []
    for match in re.finditer(r"\b(?:FROM|JOIN)\s+(" + identifier + r")", text, re.I):
        relation = relation_name(match[1])
        if re.match(r"\s*\(", text[match.end() :]) or relation in ctes:
            continue
        if any(
            part in {"information_schema", "pg_catalog", "sys", "sqlite_master", "sqlite_schema"}
            or part.startswith(("__tables", "__partitions", "pg_"))
            for part in relation.split(".")
        ):
            continue  # A mixed data + metadata query still read the business relation.
        relations.append(relation)
    return bool(relations)


def explicit_query_requirements(question):
    """A small floor for explicit requests, not a semantic question classifier.

    Call with trusted inquiry text, never with retrieved instructions or the model's answer.
    Ambiguous cases retain model-declared obligations; schedules and code-derived counts do
    not become live questions merely because they contain 'current', 'mail', or 'count'.
    """
    if not isinstance(question, str):
        return set()
    text = re.sub(r"```[\s\S]*?```|<@[A-Z0-9]+>", " ", question).lower()
    clauses = re.split(r"(?<=하고)\s+|(?<=말고)\s+|[,;\n]|\band\b", text)
    return set().union(*(_explicit_clause_requirements(clause) for clause in clauses))


def _explicit_clause_requirements(text):
    code_text = re.sub(
        r"(?:오류|에러|상태|상품|인증)\s*코드|\b(?:error|status|product|verification)\s+code\b",
        "identifier",
        text,
    )
    code = bool(
        re.search(
            r"(?<![가-힣a-z0-9_])(?:소스\s*코드|코드|구현|함수|메서드).{0,40}(?:확인|읽|분석|동작|조건|기준|설명)",
            code_text,
        )
        or re.search(r"(?:현재|실제)\s+(?:코드|구현)", code_text)
        or re.search(
            r"\b(?:read|inspect|check|explain)\b.{0,40}\b(?:code|implementation|function)\b",
            code_text,
        )
        or re.search(r"\bcurrent\s+(?:code|implementation)\b", code_text)
    )
    if re.search(
        r"(?:코드|소스|구현|함수|메서드).{0,15}(?:읽|확인|분석|조사).{0,3}지\s*(?:말|않)|"
        r"\b(?:do not|don't)\s+(?:read|inspect|check|analy[sz]e)\b",
        code_text,
    ):
        code = False
    temporal = bool(re.search(r"현재|지금|오늘|\b(?:now|currently|current|today)\b", text))
    database = bool(
        re.search(
            r"운영|데이터베이스|테이블|주문|레코드|"
            r"(?<![a-z0-9_])db(?![a-z0-9_])|"
            r"디비(?=$|\W|좀|봐|보|조회|확인|에서|의|를|에|로|는|가|도)|"
            r"\b(?:database|production|rows?|records?)\b",
            text,
        )
    )
    quantity = bool(
        re.search(r"몇\s*(?:건|개|명)|건수|개수|\b(?:how many|count|number of)\b", text)
    )
    state = bool(re.search(r"(?:주문|레코드|건의|record|row).{0,25}(?:상태|status)", text))
    procedure = bool(
        re.search(
            r"어떻게.{0,20}(?:계산|구현|조회|작성)|개념|의미|뜻|용어|조회하지|조회는\s*하지|계산\s*(?:방식|로직|방법)|조회\s*(?:방법|쿼리)|쿼리.*(?:작성|예시)|\b(?:how to|calculate|formula|write.*query)\b",
            text,
        )
    )
    explicit_live = bool(
        re.search(
            r"(?:실제|직접).{0,20}(?:조회|건수)|(?:운영\s*(?:db|데이터베이스)).{0,30}조회해", text
        )
    )
    live = database and (quantity or state) and (temporal or explicit_live)
    metadata_listing = bool(
        re.search(
            r"(?:데이터셋|테이블|컬럼|스키마)\s*(?:은|는|이|가|의)?\s*(?:목록|몇\s*개|개수)", text
        )
        and not re.search(r"레코드|행\s*(?:수|개수)|\b(?:rows?|records?)\b", text)
    )
    policy_limit = bool(
        re.search(
            r"(?:한\s*번에|몇\s*(?:건|개|명)까지|최대|최소|한도|상한|제한).{0,35}(?:할\s*수|가능|허용)",
            text,
        )
        or (quantity and re.search(r"(?:할|될|받을|보낼|취소할|등록할)\s*수\s*(?:있|없)", text))
    )
    if (
        procedure
        or (policy_limit and not explicit_live)
        or metadata_listing
        or (
            code
            and not explicit_live
            and not re.search(r"운영\s*(?:db|데이터베이스)|\bproduction\b", text)
        )
    ):
        live = False
    return ({"code"} if code else set()) | ({"live"} if live else set())


def _locator(value):
    """Lexical normalization only: no filesystem, user-home access or guessed revisions."""
    if not isinstance(value, str) or not value.strip() or len(value) > 4096:
        return None
    value = value.strip().split(" · ", 1)[0]
    if quoted := re.fullmatch(r"`([^`]+)`(.*)", value):
        value = quoted[1] + quoted[2]
    if value.startswith("file://"):
        file_url = urlsplit(value)
        if file_url.netloc not in {"", "localhost"}:
            return None
        value = unquote(file_url.path) + ("#" + file_url.fragment if file_url.fragment else "")
    # Common annotations are not part of a file's identity. Unknown formats remain
    # incomparable instead of becoming a fabricated path mismatch.
    value = re.sub(r"\s+\([^()]*\)$", "", value)
    lines = None
    match = re.search(r"(?::|#L)(\d+)(?:[-:]L?(\d+))?$", value)
    if match:
        lines = (int(match[1]), int(match[2] or match[1]))
        value = value[: match.start()]
    if re.match(r"https?://", value, re.I):
        try:
            url = urlsplit(value)
            host = (url.hostname or "").lower()
            port = url.port
            if port and port != (443 if url.scheme.lower() == "https" else 80):
                host += f":{port}"
            path = posixpath.normpath(unquote(url.path))
            query = [
                (k, v)
                for k, v in parse_qsl(url.query, keep_blank_values=True)
                if k.lower()
                in {
                    "ref",
                    "revision",
                    "version",
                    "commit",
                    "sha",
                    "at",
                    "id",
                    "pageid",
                    "contentid",
                    "documentid",
                    "path",
                    "file",
                    "filepath",
                }
            ]
            if host == "raw.githubusercontent.com":
                path = (
                    "/"
                    + "/".join(path.strip("/").split("/")[:2])
                    + "/blob/"
                    + "/".join(path.strip("/").split("/")[2:])
                )
                host = "github.com"
            if host == "github.com":
                path = path.replace("/raw/", "/blob/", 1)
                query = [(k, v) for k, v in query if k != "raw"]
            return {
                "type": "url",
                "target": host + path + ("?" + urlencode(sorted(query)) if query else ""),
                "lines": lines,
            }
        except ValueError:
            return None
    value = value.replace("\\", "/")
    if not re.fullmatch(r"\.[A-Za-z0-9_-]+", PurePosixPath(value).suffix):
        return None  # A title/symbol alone is not a comparable file identity.
    return {"type": "file", "target": posixpath.normpath(value), "lines": lines}


def read_metadata(name, args, body=None):
    """Metadata for an observed read. Callers must separately require actual success.

    SQL metadata deliberately does not invent query_time, data_watermark or environment.
    Generic MCP results do not establish a production watermark or environment.
    """
    args = args if isinstance(args, dict) else {}
    metadata = {"tool": name, "content_type": "content"}
    if name in {"Read", "Grep", "WebFetch"}:
        key = "file_path" if name == "Read" else "path" if name == "Grep" else "url"
        target = args.get(key)
        locator = _locator(target)
        if locator:
            metadata["locator"] = locator
        metadata["indirect"] = isinstance(target, str) and (
            ".dougle" in PurePosixPath(target).parts
            or PurePosixPath(target).suffix.lower() in {".md", ".txt"}
        )
        if name == "Read":
            offset, limit = args.get("offset", 1), args.get("limit")
            if type(offset) is int and offset > 0 and type(limit) is int and limit > 0:
                metadata["requested_range"] = [offset, offset + limit - 1]
            # Only numbered reader output supplies actual line coverage. A requested limit
            # alone does not prove the provider returned all those lines.
            texts = (
                [body]
                if isinstance(body, str)
                else [
                    part.get("text", "")
                    for part in body or []
                    if isinstance(part, dict) and part.get("type") == "text"
                ]
                if isinstance(body, list)
                else []
            )
            numbers = [
                int(n)
                for text in texts
                if isinstance(text, str)
                for n in re.findall(r"(?m)^\s*(\d{1,9})(?:→|\t)", text)
            ]
            if numbers and all(b == a + 1 for a, b in zip(numbers, numbers[1:])):
                metadata["line_range"] = [numbers[0], numbers[-1]]
    elif name.endswith("__execute_sql_readonly"):
        metadata.update(
            content_type="data_query" if sql_reads_data(args) else "non_data_query",
            query_time=None,
            data_watermark=None,
            environment=None,
        )
    elif re.search(r"__(?:get_table_info|get_dataset_info|get_schema)$", name, re.I):
        metadata["content_type"] = "schema"
    elif re.search(r"__list", name, re.I):
        metadata["content_type"] = "locator"
    elif re.search(r"__(?:search|slack_search)", name, re.I):
        metadata["content_type"] = "search"
    return metadata


def _source_matches(receipt, source, kind):
    metadata = getattr(receipt, "metadata", {})
    # A listing is valid knowledge for questions about the listed identifiers. Its
    # capabilities still exclude code/live; metadata must not reject all listing answers.
    actual = metadata.get("locator")
    cited = _locator(source.get("location")) if isinstance(source, dict) else None
    if not actual or not cited or (kind == "knowledge" and metadata.get("indirect")):
        return True  # Legacy notes cite originals, not necessarily the note file itself.
    if actual["type"] != cited["type"]:
        return True  # Local checkout <-> remote URL requires a repository mapping we do not have.
    a, b = actual["target"], cited["target"]
    if actual["type"] == "file":
        if a != b and not (
            (not a.startswith("/") and b.endswith("/" + a))
            or (not b.startswith("/") and a.endswith("/" + b))
        ):
            return False
    elif a != b:
        return False
    actual_lines = metadata.get("line_range")
    cited_lines = cited.get("lines")
    if actual_lines and cited_lines:
        return actual_lines[0] <= cited_lines[0] <= cited_lines[1] <= actual_lines[1]
    return True


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
            return {"knowledge", "live"} if sql_reads_data(args) else {"knowledge"}
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
                name, args = str(block.get("name", "")), block.get("input")
                pending[ident] = (name, args, read_kinds(name, args))
            ident = block.get("tool_use_id")
            if item.get("type") == "user" and block.get("type") == "tool_result":
                if not isinstance(ident, str) or ident not in pending:
                    continue
                name, args, kinds = pending.pop(ident)
                body = block.get("content")
                # Provider/connector errors may appear as structured content without is_error.
                if kinds and body and not block.get("is_error") and not failed_read(body):
                    completed[ident] = ReadReceipt(kinds, metadata=read_metadata(name, args, body))
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


def verification_diagnostics(output):
    """Bounded format diagnostics for worker audit/retry; never evidence validation.

    Returns at most 12 {field, reason} objects with fixed field names, bounded array
    indices and fixed reason codes. Never copies values, unknown keys, receipts or
    source locators. Empty diagnostics do NOT prove reads or semantic correctness.
    The worker must still call validate_output and validate_verification; this helper
    neither repairs the model output nor removes research obligations.
    """
    issues = []

    def add(field, reason):
        if len(issues) < 12:
            issues.append({"field": field, "reason": reason})

    def fields(value, expected, path):
        if not isinstance(value, dict):
            add(path, "invalid_type")
            return False
        for key in expected:
            if key not in value:
                add(f"{path}.{key}", "missing_field")
        if any(key not in expected for key in value):
            add(path, "unexpected_fields")
        return True

    if not isinstance(output, dict):
        add("verification", "invalid_type")
        return issues
    if "verification" not in output:
        add("verification", "missing_field")
        return issues
    value = output["verification"]
    if not fields(value, VERIFICATION_SCHEMA["required"], "verification"):
        return issues
    if "question_kind" in value:
        category = value["question_kind"]
        if not isinstance(category, str) or category not in QUESTION_KINDS:
            add("verification.question_kind", "invalid_enum")
    required = set()
    for key in ("required", "missing"):
        if key not in value:
            continue
        items, path = value[key], f"verification.{key}"
        if not isinstance(items, list):
            add(path, "invalid_type")
            continue
        if len(items) > 4:
            add(path, "too_many_items")
        seen = set()
        for index, item in enumerate(items[:4]):
            if not isinstance(item, str) or item not in KINDS:
                add(f"{path}[{index}]", "invalid_enum")
            elif item in seen:
                add(path, "duplicate_items")
            else:
                seen.add(item)
        if key == "required":
            required = seen
    if "checks" not in value:
        return issues
    checks = value["checks"]
    if not isinstance(checks, list):
        add("verification.checks", "invalid_type")
        return issues
    if len(checks) > 30:
        add("verification.checks", "too_many_items")
    sources = output.get("sources")
    source_count = len(sources) if isinstance(sources, list) else 0
    for index, check in enumerate(checks[:30]):
        path = f"verification.checks[{index}]"
        if not fields(check, ("kind", "receipt", "source_index"), path):
            continue
        if "kind" in check:
            kind = check["kind"]
            if not isinstance(kind, str) or kind not in KINDS:
                add(f"{path}.kind", "invalid_enum")
            elif kind not in required:
                add(f"{path}.kind", "not_required")
        if "receipt" in check:
            receipt = check["receipt"]
            if not isinstance(receipt, str):
                add(f"{path}.receipt", "invalid_type")
            elif not 1 <= len(receipt) <= 200:
                add(f"{path}.receipt", "invalid_length")
        if "source_index" in check:
            source = check["source_index"]
            if type(source) is not int:
                add(f"{path}.source_index", "invalid_type")
            elif not 0 <= source < min(30, source_count):
                add(f"{path}.source_index", "out_of_range")
    return issues


def safe_verification_diagnostics(value):
    """Allowlist field/code diagnostics for prompts; never echo arbitrary error details.

    Accepts the list returned by verification_diagnostics, not serialized model text.
    Invalid entries are discarded and at most 12 entries are returned as fresh dicts.
    """
    if not isinstance(value, (list, tuple)):
        return []
    reasons = {
        "invalid_type",
        "missing_field",
        "unexpected_fields",
        "invalid_enum",
        "too_many_items",
        "duplicate_items",
        "not_required",
        "invalid_length",
        "out_of_range",
    }
    field_pattern = (
        r"verification(?:\.question_kind|\.(?:required|missing)(?:\[[0-3]\])?|"
        r"\.checks(?:\[(?:[0-9]|[12][0-9])\](?:\.(?:kind|receipt|source_index))?)?)?"
    )
    safe = []
    for item in value[:12]:
        if not isinstance(item, dict) or set(item) != {"field", "reason"}:
            continue
        field, reason = item["field"], item["reason"]
        if (
            isinstance(field, str)
            and len(field) <= 80
            and re.fullmatch(field_pattern, field)
            and isinstance(reason, str)
            and reason in reasons
        ):
            safe.append({"field": field, "reason": reason})
    return safe


def validate_verification(
    output, reads, *, image_ids=(), report=False, required_floor=(), question=None
):
    """Validate actual reads and comparable citations, not semantic entailment.

    Generic MCP data freshness/environment remain unverified even when this succeeds.
    Query execution is not a production-watermark check.
    """
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
    floor = set(required_floor) | explicit_query_requirements(question)
    if not (QUESTION_KINDS[category] | floor) <= required or not missing <= required:
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
        observed = reads.get(receipt, set())
        valid = kind in observed
        if valid:
            valid = _source_matches(observed, output["sources"][source], kind)
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


def failure_summary(output, reads):
    """Bounded diagnostics without tool IDs, file paths, query rows or answer text."""
    value = output.get("verification", {}) if isinstance(output, dict) else {}
    checks = value.get("checks", []) if isinstance(value, dict) else []
    checks = checks if isinstance(checks, list) else []
    unknown = unsupported = 0
    for check in checks:
        if not isinstance(check, dict):
            continue
        receipt = check.get("receipt")
        if (
            not isinstance(receipt, str)
            or receipt.startswith("image:")
            or receipt == "developer_report"
        ):
            continue
        if receipt not in reads:
            unknown += 1
        elif check.get("kind") not in reads[receipt]:
            unsupported += 1
    return {
        "successful_reads": len(reads),
        "available_kinds": sorted({k for kinds in reads.values() for k in kinds if k in KINDS}),
        "unknown_receipts": unknown,
        "unsupported_kinds": unsupported,
    }


def safe_failure_summary(raw):
    try:
        value = json.loads(raw)
    except (ValueError, TypeError):
        return None
    keys = {"successful_reads", "available_kinds", "unknown_receipts", "unsupported_kinds"}
    if not isinstance(value, dict) or set(value) != keys:
        return None
    if any(type(value[k]) is not int or value[k] < 0 for k in keys - {"available_kinds"}):
        return None
    kinds = value["available_kinds"]
    if not isinstance(kinds, list) or any(not isinstance(k, str) or k not in KINDS for k in kinds):
        return None
    return value
