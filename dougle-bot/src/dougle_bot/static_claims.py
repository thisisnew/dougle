"""Small, automatic extractive proof for explicit Korean storage-time rules.

This module never accepts the model's interpretation as a fact. It reads only a
source already read in this engine call, verifies its complete current bytes,
and renders a fixed answer for a deliberately small grammar. Other prose is
reported as unverified instead of being guessed from a nearby quote.
"""

import copy
import os
import re
import stat
from pathlib import Path

from .evidence import ReadReceipt

_MAX_SOURCE = 131_072
_QUESTION = re.compile(
    r"\s*(?P<subject>입고|입하)(?:는|가)?\s*(?:언제\s*저장(?:되나요|하나요)|저장\s*시점은\s*언제인가요)\?\s*"
)
_POSITIVE = re.compile(
    r"(?P<subject>입고|입하)는\s*(?:(?P<condition>[가-힣 ]{2,40})\s*경우\s*)?"
    r"(?P<event>확정|검수)\s*(?P<order>전|뒤|후)(?:에|에도)?\s*"
    r"저장(?:됩니다|합니다|된다|한다)\."
)
_NEGATIVE = re.compile(
    r"(?P<subject>입고|입하)는\s*(?P<event>확정|검수)\s*(?P<order>전|뒤|후)(?:에는|에)?\s*"
    r"저장되지\s*않습니다\."
)
_EXCEPTION = re.compile(r"다만\s*(?P<subject>[가-힣 ]{2,40})(?:은|는)\s*저장되지\s*않습니다\.")
_NUMBERED = re.compile(r"\s*(\d{1,9})→(.*)")


def _complete_local_text(receipt: ReadReceipt, location: str) -> str | None:
    """Compare the tool result with the same regular local file, including all lines."""
    metadata = receipt.metadata
    locator = metadata.get("locator")
    if (
        metadata.get("tool") != "Read"
        or not isinstance(locator, dict)
        or locator.get("type") != "file"
        or locator.get("target") != location
        or not isinstance(receipt.snapshot, str)
        or ".dougle" in Path(location).parts
    ):
        return None
    path = Path(location)
    if not path.is_absolute() or any(item.is_symlink() for item in (path, *path.parents)):
        return None
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, "rb") as stream:
            before = os.fstat(stream.fileno())
            if not stat.S_ISREG(before.st_mode) or not 0 < before.st_size <= _MAX_SOURCE:
                return None
            content = stream.read(_MAX_SOURCE + 1)
            after = os.fstat(stream.fileno())
        if len(content) != before.st_size or (before.st_ino, before.st_mtime_ns, before.st_size) != (
            after.st_ino, after.st_mtime_ns, after.st_size
        ):
            return None
        current = content.decode("utf-8")
    except (OSError, UnicodeError):
        return None
    observed = receipt.snapshot
    if observed == current:
        return current
    lines = observed.splitlines()
    numbered = [_NUMBERED.fullmatch(line) for line in lines]
    if not lines or any(match is None for match in numbered):
        return None
    numbers = [int(match.group(1)) for match in numbered]
    if numbers != list(range(1, len(numbers) + 1)):
        return None
    return current if [match.group(2) for match in numbered] == current.splitlines() else None


def _render(text: str, subject: str) -> str | None:
    """Parse the full relevant rule set; reject unknown or conflicting clauses."""
    positive = []
    negative = []
    exceptions = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith("#"):
            if any(word in line for word in ("저장", "확정", "검수", "예외", "조건")):
                return None
            continue
        if re.match(r"(?:다만|단[, ]|예외|제외)", line) and "저장" not in line:
            return None
        if "저장" not in line:
            if any(word in line for word in ("확정", "검수", "예외", "조건")):
                return None
            continue
        clauses = [part.strip() + "." for part in line.rstrip(".").split(". ")]
        for clause in clauses:
            if match := _POSITIVE.fullmatch(clause):
                positive.append(match.groupdict())
            elif match := _NEGATIVE.fullmatch(clause):
                negative.append(match.groupdict())
            elif match := _EXCEPTION.fullmatch(clause):
                exceptions.append(match.group("subject").strip())
            else:
                return None
    if (
        len(positive) != 1
        or len(set(exceptions)) != len(exceptions)
        or positive[0]["subject"] != subject
        or any(item["subject"] != subject for item in negative)
    ):
        return None
    rule = positive[0]
    event, order = rule["event"], rule["order"]
    order = "뒤" if order in {"뒤", "후"} else "전"
    if any(
        item["event"] != event
        or ("뒤" if item["order"] in {"뒤", "후"} else "전") == order
        for item in negative
    ):
        return None
    condition = rule["condition"]
    first = (
        f"{subject}는 {condition.strip()} 경우 {event} {order} 저장됩니다."
        if condition else f"{subject}는 {event} {order} 저장됩니다."
    )
    rest = [
        f"{item['event']} {'뒤' if item['order'] in {'뒤', '후'} else '전'}에는 저장되지 않습니다."
        for item in negative
    ]
    rest.extend(f"다만 {item}은 저장되지 않습니다." for item in exceptions)
    return " ".join([first, *rest])


def scope_static_timing(output, reads, *, question: str, report=False, context_clear=True):
    """Return a deterministic answer or a bounded automatic uncertainty message."""
    question_match = _QUESTION.fullmatch(question) if isinstance(question, str) else None
    if question_match is None:
        return output
    if output["status"] != "answered" or report or not context_clear:
        return output
    verification = output["verification"]
    if verification["question_kind"] != "domain" or set(verification["required"]) != {"knowledge"}:
        return output
    if not any(isinstance(receipt, ReadReceipt) for receipt in reads.values()):
        # Legacy fake/indirect evidence has no current source bytes to interpret.
        return output
    value = copy.deepcopy(output)
    body = None
    checks = verification["checks"]
    if len(checks) == 1 and len(output["sources"]) == 1 and len(reads) == 1:
        check = checks[0]
        receipt = reads.get(check["receipt"])
        location = output["sources"][0]["location"]
        if check["kind"] == "knowledge" and isinstance(receipt, ReadReceipt):
            current = _complete_local_text(receipt, location)
            if current is not None:
                body = _render(current, question_match.group("subject"))
    value["body"] = body or (
        f"이번 문의에서 {question_match.group('subject')} 저장 시점과 적용 조건을 "
        "원본 근거로 확정하지 못했습니다."
    )
    return value
