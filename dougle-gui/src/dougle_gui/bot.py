"""Read-only status of the optional Slack bot and Slack schedules."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
from pathlib import Path

STATUS_TIMEOUT = 15


def bot_status(bot_bin: str = "dougle-bot") -> dict:
    """Run `dougle-bot status`, which only reads the bot home and its database."""
    if "/" in bot_bin:
        path = Path(bot_bin).expanduser()
        binary = str(path) if path.is_file() and os.access(path, os.X_OK) else None
    else:
        binary = shutil.which(bot_bin)
    if binary is None:
        return {"installed": False}
    try:
        done = subprocess.run(
            [binary, "status"],
            stdin=subprocess.DEVNULL,
            capture_output=True,
            text=True,
            timeout=STATUS_TIMEOUT,
        )
    except subprocess.TimeoutExpired:
        return {"installed": True, "error": "status_timeout"}
    except OSError:
        return {"installed": True, "error": "status_failed"}
    if done.returncode != 0:
        return {"installed": True, "error": "status_failed", "detail": done.stderr.strip()[-500:]}
    try:
        return {"installed": True, "status": json.loads(done.stdout)}
    except json.JSONDecodeError:
        return {"installed": True, "raw": done.stdout.strip()[-4000:]}
