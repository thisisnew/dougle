"""Small rotating, content-free terminal progress log. Never part of answer correctness."""

import os
import re
import stat
import threading
import time
from pathlib import Path

from .progress import STAGES

STATES = {
    "queued": "문의 접수 · 처리 대기",
    "posted": "답변 게시 완료",
    "failed": "처리 실패 · 상태 확인 또는 개발자 DM을 확인해 주세요",
    "needs_lookup": "개발자 확인 대기",
    "awaiting_approval": "게시 승인 대기",
    "post_unknown": "전송 여부 확인 필요",
    "cancelled": "문의 처리 중단",
    "rejected": "게시하지 않음",
}


def recent_activity(home, limit=10):
    """Bounded snapshot of our content-free log, not an indefinite log follower.

    Only accept the known log grammar. Never expose arbitrary file contents to the host AI.
    The displayed clock/quiet duration belongs to the observation, not the present moment.
    """
    if type(limit) is not int or not 1 <= limit <= 50:
        return []
    path = Path(home) / "progress.log"
    if path.parent.is_symlink():
        return []
    labels = set(STAGES.values()) | set(STATES.values())
    pattern = re.compile(
        r"([0-2][0-9]:[0-5][0-9]:[0-5][0-9]) 문의 #([1-9][0-9]{0,12}) \| "
        r"([^|\n]+) \| ([0-9]{1,9})분 ([0-5][0-9])초 \| "
        r"도구 응답 ([0-9]{1,10})건 \| 마지막 신호 ([0-9]{1,10})초 전"
        r"(?: \| AI 실행 ([0-9]{1,10})회째)?"
        r"(?: \| 새 진행 신호 없음 · 응답 대기 또는 정체 가능)?"
    )
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, "rb") as stream:
            info = os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode):
                return []
            start = max(0, info.st_size - 65536)
            stream.seek(start)
            raw = stream.read(65536)
        lines = raw.split(b"\n")
        if start:
            lines = lines[1:]  # The first line may start midway through UTF-8.
        lines = lines[:-1]  # Do not show an incomplete concurrent append.
        result, seen = [], set()
        for line in reversed(lines):
            match = pattern.fullmatch(line.decode("utf-8", errors="replace"))
            if not match or match[3] not in labels or int(match[1][:2]) > 23:
                continue
            ident = int(match[2])
            if ident in seen:
                continue
            seen.add(ident)
            result.append(
                dict(
                    inquiry_id=ident,
                    observed_clock=match[1],
                    label=match[3],
                    elapsed_seconds=int(match[4]) * 60 + int(match[5]),
                    tool_responses=int(match[6]),
                    quiet_seconds_at_observation=int(match[7]),
                    attempt=int(match[8] or 1),
                )
            )
            if len(result) == limit:
                break
        return result
    except (OSError, ValueError):
        return []


class Monitor:
    max_bytes = 1_048_576

    def __init__(self, home, clock=time.monotonic):
        self.path = Path(home) / "progress.log"
        self.clock = clock
        self.last = {}
        self.lock = threading.Lock()

    def emit(self, ident, snapshot, state=None):
        try:
            if type(ident) is not int or ident <= 0 or not isinstance(snapshot, dict):
                return
            stage = snapshot.get("stage")
            fields = ("elapsed_seconds", "quiet_seconds", "attempt", "completed_tools")
            if not isinstance(stage, str) or stage not in STAGES:
                return
            if any(
                type(snapshot.get(k)) is not int or not 0 <= snapshot[k] <= 10**9 for k in fields
            ):
                return
            if state is not None and (not isinstance(state, str) or state not in STATES):
                state = "failed"  # Never print arbitrary errors or user-supplied status text.
            key = (stage, snapshot["attempt"], snapshot["completed_tools"])
            with self.lock:
                now = self.clock()
                previous = self.last.get(ident)
                if state is None and previous:
                    delay = 10 if previous[1] == key else 1
                    if now - previous[0] < delay:
                        return
                elapsed, quiet = snapshot["elapsed_seconds"], snapshot["quiet_seconds"]
                label = STATES[state] if state else STAGES[stage]
                line = (
                    f"{time.strftime('%H:%M:%S')} 문의 #{ident} | {label} | "
                    f"{elapsed // 60}분 {elapsed % 60:02}초 | "
                    f"도구 응답 {snapshot['completed_tools']}건 | 마지막 신호 {quiet}초 전"
                )
                if snapshot["attempt"] > 1:
                    line += f" | AI 실행 {snapshot['attempt']}회째"
                if quiet >= 120 and state is None:
                    line += " | 새 진행 신호 없음 · 응답 대기 또는 정체 가능"
                self._write((line + "\n").encode())
                if state:
                    self.last.pop(ident, None)
                else:
                    self.last[ident] = (now, key)
        except (OSError, ValueError, TypeError):
            pass  # Monitoring failure must not stop or authorize a customer reply.

    def _write(self, data):
        paths = [self.path, Path(str(self.path) + ".1"), Path(str(self.path) + ".2")]
        if self.path.parent.is_symlink() or any(p.is_symlink() for p in paths):
            return
        if self.path.exists() and self.path.stat().st_size + len(data) > self.max_bytes:
            if paths[1].exists():
                paths[1].replace(paths[2])
            paths[0].replace(paths[1])
        fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_APPEND | os.O_NOFOLLOW, 0o600)
        try:
            os.fchmod(fd, 0o600)
            os.write(fd, data)
        finally:
            os.close(fd)
