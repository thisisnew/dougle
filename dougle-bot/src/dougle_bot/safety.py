"""No raw provider exceptions or credentials cross the runtime boundary."""

import re
from urllib.parse import unquote, urlsplit

SECRET = re.compile(
    r"(?:xox[baprs]-[A-Za-z0-9-]+|xapp-[A-Za-z0-9-]+|sk-[A-Za-z0-9_-]{12,}|"
    r"gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+|"
    r"AKIA[A-Z0-9]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----|"
    r"Bearer\s+[^\s\"']+|(?:password|passwd|api[_-]?key|secret|token)\s*[=:]\s*[^\s,;}]+)",
    re.I,
)
PII = re.compile(
    r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}|\b01[016789][- ]?\d{3,4}[- ]?\d{4}\b|\b\d{6}-[1-8]\d{6}\b"
)


class BotError(Exception):
    """code must be an internal constant; never pass an upstream error as code."""

    def __init__(self, code: str):
        self.code = code if re.fullmatch(r"[a-z_]{1,80}", code) else "operation_failed"
        super().__init__(self.code)


def redact(value):
    if isinstance(value, dict):
        return {
            redact(str(k)): "[비공개]"
            if re.search(r"token|secret|password|authorization", str(k), re.I)
            else redact(v)
            for k, v in value.items()
        }
    if isinstance(value, (tuple, list)):
        return [redact(v) for v in value]
    return SECRET.sub("[비공개]", value) if isinstance(value, str) else value


def check_content(text: str, *, personal: bool = False) -> None:
    for _ in range(3):
        text = unquote(text)
    if SECRET.search(text) or (personal and PII.search(text)):
        raise BotError("sensitive_content")
    if any(ord(c) < 32 and c not in "\n\r\t" for c in text):
        raise BotError("invalid_text")


def safe_url(value: str) -> bool:
    try:
        p = urlsplit(value)
        return (
            p.scheme in ("https", "http")
            and bool(p.hostname)
            and not p.username
            and not p.password
            and not SECRET.search(value)
        )
    except ValueError:
        return False


def polite(text: str) -> bool:
    """Detect known casual Korean narration; this is not a complete language classifier."""
    # Preserve verbatim code and visibly attributed quotes, not headings or emphasis.
    text = re.sub(r"```[^\n]*\n.*?(?:```|\Z)|~~~[^\n]*\n.*?(?:~~~|\Z)", "", text, flags=re.DOTALL)
    text = re.sub(r"`[^`\n]*`", "", text)
    text = re.sub(r'“[^”\n]*”|「[^」\n]*」|"[^"\n]*"', "", text)
    casual = re.compile(
        r"(?:이야|거야|해봐|해 봐|해라|해줘|해 줘|알겠어|없어|있어|맞아|아니야|"
        r"하면 돼|보면 돼|했어|할게|하자|할까|볼까|겠지|이지|거지|해줄게|"
        r"한다|된다|했다|됐다|있다|없다|이다|해|봐|줘|됐네|이네|구나)"
        r"(?=[.!?。！？…]|$)"
    )
    for line in text.splitlines():
        line = line.strip()
        if line.startswith(">") or not line:
            continue
        line = re.sub(r"[*_~]", "", line)
        if casual.search(line):
            return False
        if re.search(r"(?:^|[.!?]\s*)(?:응|그래|야)(?=[,.!\s]|$)", line):
            return False
    return True


def escape_slack(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
