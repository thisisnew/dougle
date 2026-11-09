"""Narrow proof for a user-requested, whole-table BigQuery row count.

The parsers return only a table identity or an integer. They never retain SQL,
rows, credentials, or a model-written interpretation of the result.
"""

import json
import re

_PROJECT = r"[A-Za-z][A-Za-z0-9_-]*"
_DATASET = r"[A-Za-z_][A-Za-z0-9_]*"
_TABLE = r"[A-Za-z_][A-Za-z0-9_]*"
_RELATION = rf"{_PROJECT}\.{_DATASET}\.{_TABLE}"
_SQL = re.compile(
    rf"\A\s*SELECT\s+COUNT\s*\(\s*\*\s*\)\s+AS\s+count\s+FROM\s+"
    rf"(?:`(?P<quoted>{_RELATION})`|(?P<plain>{_RELATION}))\s*;?\s*\Z",
    re.IGNORECASE,
)
_QUESTION_ENDINGS = (
    r"(?:테이블(?:의)?\s+)?전체\s+(?:행\s*수|건수)(?:는|가)?\s+몇\s*(?:건|개)(?:인가요|입니까)\?",
    r"테이블(?:에|에는|의)?\s+(?:전체\s+)?데이터가\s+(?:있나요|없나요|존재하나요)\?",
    r"테이블\s+전체에\s+데이터가\s+(?:있나요|없나요|존재하나요)\?",
    r"테이블(?:에|에는)?\s+행이\s+(?:있나요|없나요)\?",
)
_COUNT_TEXT = re.compile(r"(?:0|[1-9][0-9]*)\Z")
_MAX_COUNT = (1 << 63) - 1


def parse_count_all_sql(sql: str) -> str | None:
    """Return one exact three-part relation for a bare COUNT(*) query only."""
    if not isinstance(sql, str) or len(sql) > 100_000:
        return None
    match = _SQL.fullmatch(sql)
    return (match.group("quoted") or match.group("plain")) if match else None


def question_asks_full_table_count(question: str, relation: str) -> bool:
    """Accept only an explicit physical table and an unqualified whole-table ask."""
    if (
        not isinstance(question, str)
        or not isinstance(relation, str)
        or not re.fullmatch(_RELATION, relation)
    ):
        return False
    escaped = re.escape(relation)
    name = rf"(?:`{escaped}`|{escaped})"
    return any(
        re.fullmatch(rf"\s*{name}\s+{ending}\s*", question) is not None
        for ending in _QUESTION_ENDINGS
    )


def _result_object(body: object) -> dict | None:
    if isinstance(body, str):
        if len(body) > 131_072:
            return None
        try:
            body = json.loads(body)
        except (ValueError, UnicodeError):
            return None
    if isinstance(body, list) and len(body) == 1:
        part = body[0]
        if isinstance(part, dict) and part.get("type") == "text":
            return _result_object(part.get("text"))
    return body if isinstance(body, dict) else None


def parse_complete_count_result(body: object) -> int | None:
    """Return a nonnegative count from one explicitly completed, unpaged row."""
    result = _result_object(body)
    if result is None or not (
        result.get("jobComplete") is True or result.get("job_complete") is True
    ):
        return None
    if any(result.get(key) is False for key in ("jobComplete", "job_complete")):
        return None
    if result.get("error") or result.get("errors"):
        return None
    if any(result.get(key) for key in ("nextPageToken", "next_page_token", "pageToken")):
        return None
    rows = result.get("rows")
    if not isinstance(rows, list) or len(rows) != 1:
        return None
    row = rows[0]
    if not isinstance(row, dict) or set(row) != {"count"}:
        return None
    total = result.get("totalRows", result.get("total_rows", 1))
    if total not in (1, "1") or isinstance(total, bool):
        return None
    value = row["count"]
    if type(value) is int:
        return value if 0 <= value <= _MAX_COUNT else None
    if isinstance(value, str) and len(value) <= 19 and _COUNT_TEXT.fullmatch(value):
        number = int(value)
        return number if number <= _MAX_COUNT else None
    return None


def verified_full_table_count(question: str, sql: str, body: object) -> int | None:
    """Combine the independent question, executed-SQL, and result checks."""
    relation = parse_count_all_sql(sql)
    if relation is None or not question_asks_full_table_count(question, relation):
        return None
    return parse_complete_count_result(body)
