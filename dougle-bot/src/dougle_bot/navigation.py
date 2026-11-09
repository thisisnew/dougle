"""Read a bounded local topic index as navigation for compact engine calls.

The index is untrusted and never counts as a read receipt or source evidence.
"""

import os
import stat
from pathlib import Path

from .memory import safe_path
from .safety import BotError

MAX_INDEX_BYTES = 32_768


def read_navigation_index(root: Path) -> str | None:
    try:
        path = safe_path(root, "index.md")
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    except (OSError, BotError):
        return None
    try:
        info = os.fstat(fd)
        if (
            not stat.S_ISREG(info.st_mode)
            or info.st_uid != os.getuid()
            or info.st_size < 1
            or info.st_size > MAX_INDEX_BYTES
        ):
            return None
        raw = os.read(fd, MAX_INDEX_BYTES + 1)
        if not raw or len(raw) != info.st_size:
            return None
        return raw.decode("utf-8")
    except (OSError, UnicodeDecodeError):
        return None
    finally:
        os.close(fd)
