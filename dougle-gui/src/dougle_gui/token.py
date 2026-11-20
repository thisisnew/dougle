"""Persistent access token so the GUI address can be bookmarked.

The token lives in a private file (0600 inside a 0700 directory). It is created
once and reused until the user rotates it with --new-token.
"""

from __future__ import annotations

import os
import re
import secrets
import stat
from pathlib import Path

_VALID = re.compile(r"[A-Za-z0-9_-]{32,128}")


class TokenError(Exception):
    pass


def _private_dir(path: Path) -> None:
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = path.lstat()
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
        raise TokenError("unsafe_token_directory")
    if info.st_uid != os.getuid():
        raise TokenError("unsafe_token_directory")
    if stat.S_IMODE(info.st_mode) & 0o077:
        os.chmod(path, 0o700)


def _write_new(path: Path) -> str:
    value = secrets.token_urlsafe(32)
    tmp = path.with_name(f".{path.name}.{secrets.token_hex(4)}")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(value + "\n")
        os.replace(tmp, path)
    finally:
        if tmp.exists():
            tmp.unlink()
    return value


def load_or_create(home: Path, *, rotate: bool = False) -> str:
    """Return the saved token, creating (or rotating) it when needed."""
    home = Path(home).expanduser()
    _private_dir(home)
    path = home / "token"
    if not rotate and os.path.lexists(path):
        info = path.lstat()
        if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
            raise TokenError("unsafe_token_file")
        if stat.S_IMODE(info.st_mode) & 0o077:
            os.chmod(path, 0o600)
        value = path.read_text(encoding="utf-8").strip()
        if _VALID.fullmatch(value):
            return value
    return _write_new(path)
