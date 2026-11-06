"""Bounded, content-free progress; activity is not evidence or liveness proof."""

import json
import threading
import time

STAGES = {
    "thread": "질문과 스레드 확인",
    "attachments": "첨부 자료 확인",
    "engine": "AI 응답 대기",
    "skill": "답변 규칙 적용",
    "lookup": "자료 조회",
    "memory": "확인한 지식 저장",
    "thinking": "조회 결과를 바탕으로 답변 준비",
    "validation": "답변 근거와 형식 검증",
    "retry": "누락된 근거·형식 보완",
}


class Progress:
    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.started = self.last_activity = clock()
        self.stage = "thread"
        self.attempt = self.completed_tools = 0
        self.lock = threading.Lock()

    def phase(self, stage, *, attempt=None):
        with self.lock:
            self.stage = stage if stage in STAGES else "engine"
            self.last_activity = self.clock()
            if attempt is not None:
                self.attempt = attempt

    def activity(self, event):
        with self.lock:
            if event == "tool_result":
                self.completed_tools += 1
                self.stage = "thinking"
            elif event in STAGES:
                self.stage = event
            elif event != "activity":
                return
            self.last_activity = self.clock()

    def snapshot(self):
        with self.lock:
            now = self.clock()
            return dict(
                stage=self.stage,
                elapsed_seconds=max(0, int(now - self.started)),
                quiet_seconds=max(0, int(now - self.last_activity)),
                attempt=self.attempt,
                completed_tools=self.completed_tools,
            )

    def remaining(self, limit):
        return max(0.0, limit - (self.clock() - self.started))


class StreamProgress:
    """Read protocol envelopes only. Never emit arguments, text, filenames or results."""

    def __init__(self, emit):
        self.emit = emit
        self.buffer = bytearray()

    def feed(self, chunk):
        self.buffer.extend(chunk)
        while b"\n" in self.buffer:
            line, _, tail = self.buffer.partition(b"\n")
            self.buffer = bytearray(tail)
            try:
                item = json.loads(line)
            except (ValueError, UnicodeError):
                continue
            if not isinstance(item, dict):
                continue
            kind = item.get("type")
            if not isinstance(kind, str) or kind not in {"assistant", "user", "system", "result"}:
                continue
            self.emit("activity")
            message = item.get("message")
            content = message.get("content", []) if isinstance(message, dict) else []
            if not isinstance(content, list):
                continue
            for block in content:
                if not isinstance(block, dict):
                    continue
                if kind == "assistant" and block.get("type") == "tool_use":
                    name = block.get("name", "")
                    if not isinstance(name, str):
                        continue
                    if name == "Skill":
                        self.emit("skill")
                    elif name in {"Edit", "Write"}:
                        self.emit("memory")
                    elif name in {"Read", "Grep", "Glob", "WebFetch", "ToolSearch"} or (
                        isinstance(name, str) and name.startswith("mcp__")
                    ):
                        self.emit("lookup")
                    else:
                        self.emit("thinking")
                elif kind == "user" and block.get("type") == "tool_result":
                    self.emit("tool_result")
        # Engine's total-output bound is authoritative; this also bounds a banner with no LF.
        if len(self.buffer) > 4_000_000:
            self.buffer.clear()


def progress_text(value):
    if (
        not isinstance(value, dict)
        or not isinstance(value.get("stage"), str)
        or value["stage"] not in STAGES
    ):
        return "진행 정보를 확인하고 있습니다."
    fields = ("elapsed_seconds", "quiet_seconds", "attempt", "completed_tools")
    if any(type(value.get(k)) is not int or value[k] < 0 for k in fields):
        return "진행 정보를 확인하고 있습니다."
    elapsed, quiet = value["elapsed_seconds"], value["quiet_seconds"]
    text = f"*{STAGES[value['stage']]}* · 처리 시작 후 {elapsed // 60}분 {elapsed % 60}초"
    text += f"\n도구 응답 {value['completed_tools']}건 · 마지막 진행 신호 {quiet}초 전"
    if value["attempt"] > 1:
        text += f"\n확인 보완 중입니다. AI 실행 {value['attempt']}회째입니다."
    if quiet >= 120:
        text += "\n한동안 새 진행 신호를 받지 못했습니다. 외부 응답 대기 또는 정체 여부를 확인해야 합니다."
    text += "\n마지막 갱신 기준입니다."
    return text
