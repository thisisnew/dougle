"""Bounded, content-free progress; activity is not evidence or liveness proof."""

import json
import threading
import time

from .evidence import read_kinds

STAGES = {
    "search": "관련 지식 찾기",
    "prepare": "질문 입력 준비",
    "persist": "답변 기록 저장",
    "delivery": "답변 게시·내부 알림 처리",
    "code": "원본 코드 확인",
    "live": "운영 데이터 조회",
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
    def __init__(self, clock=time.monotonic, observer=None):
        self.clock = clock
        self.observer = observer
        self.started = self.last_activity = clock()
        self.stage = "thread"
        self.changed_at = self.started
        self.stage_ms = {key: 0.0 for key in STAGES}
        self.prompt_characters = self.output_characters = 0
        self.retries = {
            key: 0 for key in ("presentation", "research", "activation", "schema", "session")
        }
        self.attempt = self.completed_tools = 0
        self.lock = threading.RLock()

    def _transition(self, stage):
        now = self.clock()
        self.stage_ms[self.stage] += max(0, now - self.changed_at) * 1000
        self.changed_at = now
        self.stage = stage

    def sizes(self, prompt=0, output=0):
        with self.lock:
            self.prompt_characters += max(0, prompt)
            self.output_characters += max(0, output)

    def retry(self, reason):
        with self.lock:
            if reason in self.retries:
                self.retries[reason] += 1

    def profile(self, queue_ms=0):
        with self.lock:
            self._transition(self.stage)
            return dict(
                version=1,
                queue_ms=max(0, int(queue_ms)),
                processing_ms=max(0, int((self.clock() - self.started) * 1000)),
                observed_stage_ms={key: int(value) for key, value in self.stage_ms.items()},
                attempts=self.attempt,
                tool_responses=self.completed_tools,
                prompt_characters=self.prompt_characters,
                output_characters=self.output_characters,
                retries=dict(self.retries),
            )

    def _notify(self):
        if self.observer:
            try:
                self.observer(self.snapshot())
            except Exception:
                pass

    def phase(self, stage, *, attempt=None):
        with self.lock:
            self._transition(stage if stage in STAGES else "engine")
            self.last_activity = self.clock()
            if attempt is not None:
                self.attempt = attempt
            self._notify()

    def activity(self, event):
        with self.lock:
            if event == "tool_result":
                self.completed_tools += 1
                self._transition("thinking")
            elif event in STAGES:
                self._transition(event)
            elif event != "activity":
                return
            self.last_activity = self.clock()
            if event != "activity":
                self._notify()

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
                    kinds = read_kinds(name, block.get("input"))
                    if "live" in kinds:
                        self.emit("live")
                    elif "code" in kinds:
                        self.emit("code")
                    elif name == "Skill":
                        self.emit("skill")
                    elif name in {"Edit", "Write"}:
                        self.emit("memory")
                    elif name in {"Glob", "ToolSearch"}:
                        self.emit("search")
                    elif name in {"Read", "Grep"}:
                        args = block.get("input")
                        target = (
                            (args.get("file_path") or args.get("path"))
                            if isinstance(args, dict)
                            else None
                        )
                        self.emit(
                            "search"
                            if isinstance(target, str) and "/.dougle/" in target
                            else "lookup"
                        )
                    elif name in {"WebFetch"} or (
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


def safe_profile(raw):
    """Only return bounded numeric metrics from a stored record, never arbitrary log text."""
    try:
        value = json.loads(raw)
        if not isinstance(value, dict) or value.get("version") != 1:
            return None
        keys = (
            "version",
            "queue_ms",
            "processing_ms",
            "attempts",
            "tool_responses",
            "prompt_characters",
            "output_characters",
        )
        result = {key: value[key] for key in keys}
        for field, names in (
            ("observed_stage_ms", STAGES),
            ("retries", ("presentation", "research", "activation", "schema", "session")),
        ):
            result[field] = {
                key: value[field].get(key, 0)
                if field == "observed_stage_ms" and key in {"code", "live"}
                else value[field][key]
                for key in names
            }
        numbers = (
            [result[k] for k in keys]
            + list(result["observed_stage_ms"].values())
            + list(result["retries"].values())
        )
        if any(type(n) is not int or not 0 <= n <= 10**12 for n in numbers):
            return None
        return result
    except (ValueError, TypeError, KeyError):
        return None
