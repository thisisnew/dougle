"""Bounded, content-free progress; activity is not evidence or liveness proof."""

import hashlib
import json
import math
import re
import threading
import time
import uuid

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

MAX_METRIC = 10**12
MAX_TOOL_IDS = 4096
MAX_ATTEMPTS = 64
USAGE_FIELDS = (
    "input_tokens",
    "output_tokens",
    "cache_read_input_tokens",
    "cache_creation_input_tokens",
)
TOOL_METRICS = (
    "started",
    "finished",
    "active",
    "peak_active",
    "incomplete",
    "unmatched_results",
    "dropped_events",
    "active_ms",
    "total_ms",
)


def bounded(value):
    """Clamp generated metrics; validation of persisted data remains strict."""
    if type(value) is int:
        return min(MAX_METRIC, max(0, value))
    if type(value) is not float or not math.isfinite(value):
        return 0
    return min(MAX_METRIC, max(0, int(value)))


def valid_number(value):
    return type(value) is int and 0 <= value <= MAX_METRIC


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
        self.prompt_bytes = self.prompt_bytes_samples = 0
        self.usage_reports = 0
        self.token_usage = {key: {"total": None, "samples": 0} for key in USAGE_FIELDS}
        self._seen_tools = set()
        self._active_tools = {}
        self._active_since = None
        self._tool_active_ms = self._tool_total_ms = 0.0
        self._tool_counts = dict.fromkeys(TOOL_METRICS[:7], 0)
        self.first_output_ms = self.first_tool_ms = None
        self._attempt_timings = []
        self._current_attempt = None
        self.lock = threading.RLock()

    def _transition(self, stage):
        now = self.clock()
        self.stage_ms[self.stage] = min(
            MAX_METRIC, self.stage_ms[self.stage] + max(0, now - self.changed_at) * 1000
        )
        self.changed_at = now
        self.stage = stage

    def sizes(self, prompt=0, output=0, *, prompt_bytes=None):
        with self.lock:
            self.prompt_characters = bounded(self.prompt_characters + bounded(prompt))
            self.output_characters = bounded(self.output_characters + bounded(output))
            if type(prompt_bytes) is int and prompt_bytes >= 0:
                self.prompt_bytes = bounded(self.prompt_bytes + prompt_bytes)
                self.prompt_bytes_samples = bounded(self.prompt_bytes_samples + 1)

    def usage(self, value):
        """One report per engine attempt; missing/invalid fields are not zero samples.

        Compare each field's samples with profile.attempts before calling a sum complete.
        A failed attempt with no report remains missing, even if all returned reports are valid.
        """
        with self.lock:
            self.usage_reports = bounded(self.usage_reports + 1)
            for key, metric in self.token_usage.items():
                item = value.get(key) if isinstance(value, dict) else None
                if type(item) is int and item >= 0:
                    metric["total"] = bounded((metric["total"] or 0) + item)
                    metric["samples"] = bounded(metric["samples"] + 1)

    def retry(self, reason):
        with self.lock:
            if reason in self.retries:
                self.retries[reason] = bounded(self.retries[reason] + 1)

    def _first(self, kind, now):
        key = "first_" + kind + "_ms"
        if getattr(self, key) is None:
            setattr(self, key, bounded((now - self.started) * 1000))
        if self._current_attempt and self._current_attempt[key] is None:
            self._current_attempt[key] = bounded((now - self._current_attempt["start"]) * 1000)

    def finish_attempt(self):
        """Close observed intervals on failure/cancel too; never invent tool results."""
        with self.lock:
            now = self.clock()
            if self._current_attempt:
                self._current_attempt["end"] = now
                self._current_attempt = None
            if self._active_tools:
                self._tool_active_ms = min(
                    MAX_METRIC, self._tool_active_ms + max(0, now - self._active_since) * 1000
                )
                self._tool_total_ms = min(
                    MAX_METRIC,
                    self._tool_total_ms
                    + sum(max(0, now - start) * 1000 for _, start in self._active_tools.values()),
                )
                self._tool_counts["incomplete"] = bounded(
                    self._tool_counts["incomplete"] + len(self._active_tools)
                )
                self._active_tools.clear()
                self._active_since = None

    def _tool(self, event, now):
        kind, ident = event.get("type"), event.get("id")
        if (
            kind not in ("tool_start", "tool_end")
            or not isinstance(ident, str)
            or not re.fullmatch(r"[0-9a-f]{64}", ident)
        ):
            return False
        stage = event.get("stage")
        if kind == "tool_start" and (not isinstance(stage, str) or stage not in STAGES):
            return False
        if ident not in self._seen_tools:
            if len(self._seen_tools) >= MAX_TOOL_IDS:
                self._tool_counts["dropped_events"] = bounded(
                    self._tool_counts["dropped_events"] + 1
                )
                return False
            self._seen_tools.add(ident)
            if kind == "tool_end":
                # A late start cannot establish a duration after its result was observed.
                self._tool_counts["unmatched_results"] += 1
                self.completed_tools = bounded(self.completed_tools + 1)
                return True
            self._first("tool", now)
            if not self._active_tools:
                self._active_since = now
            self._active_tools[ident] = (stage, now)
            self._tool_counts["started"] += 1
            self._tool_counts["peak_active"] = max(
                self._tool_counts["peak_active"], len(self._active_tools)
            )
        elif kind == "tool_end" and ident in self._active_tools:
            _, start = self._active_tools.pop(ident)
            self._tool_total_ms = min(MAX_METRIC, self._tool_total_ms + max(0, now - start) * 1000)
            self._tool_counts["finished"] += 1
            self.completed_tools = bounded(self.completed_tools + 1)
            if not self._active_tools:
                self._tool_active_ms = min(
                    MAX_METRIC, self._tool_active_ms + max(0, now - self._active_since) * 1000
                )
                self._active_since = None
        else:
            return False  # Duplicate start/end, or a start following an unmatched end.
        self._transition(
            next(reversed(self._active_tools.values()))[0] if self._active_tools else "thinking"
        )
        return True

    def profile(self, queue_ms=0):
        with self.lock:
            self._transition(self.stage)
            now = self.clock()
            active_ms = self._tool_active_ms
            if self._active_since is not None:
                active_ms += max(0, now - self._active_since) * 1000
            total_ms = self._tool_total_ms + sum(
                max(0, now - start) * 1000 for _, start in self._active_tools.values()
            )
            return dict(
                version=1,
                queue_ms=bounded(queue_ms),
                processing_ms=bounded((now - self.started) * 1000),
                observed_stage_ms={key: int(value) for key, value in self.stage_ms.items()},
                attempts=self.attempt,
                tool_responses=self.completed_tools,
                prompt_characters=self.prompt_characters,
                output_characters=self.output_characters,
                retries=dict(self.retries),
                prompt_bytes=self.prompt_bytes if self.prompt_bytes_samples else None,
                prompt_bytes_samples=self.prompt_bytes_samples,
                usage_reports=self.usage_reports,
                usage={key: dict(metric) for key, metric in self.token_usage.items()},
                tool_timing={
                    **self._tool_counts,
                    "active": len(self._active_tools),
                    "active_ms": bounded(active_ms),
                    "total_ms": bounded(total_ms),
                },
                first_output_ms=self.first_output_ms,
                first_tool_ms=self.first_tool_ms,
                attempt_timings=[
                    dict(
                        attempt=row["attempt"],
                        elapsed_ms=bounded(
                            ((row["end"] if row["end"] is not None else now) - row["start"]) * 1000
                        ),
                        first_output_ms=row["first_output_ms"],
                        first_tool_ms=row["first_tool_ms"],
                    )
                    for row in self._attempt_timings
                ],
            )

    def _notify(self):
        if self.observer:
            try:
                self.observer(self.snapshot())
            except Exception:
                pass

    def phase(self, stage, *, attempt=None):
        with self.lock:
            if attempt is not None and valid_number(attempt) and attempt > self.attempt:
                self.finish_attempt()
                self.attempt = attempt
                if len(self._attempt_timings) < MAX_ATTEMPTS:
                    self._current_attempt = dict(
                        attempt=attempt,
                        start=self.clock(),
                        end=None,
                        first_output_ms=None,
                        first_tool_ms=None,
                    )
                    self._attempt_timings.append(self._current_attempt)
            elif stage not in ("engine", "retry"):
                self.finish_attempt()
            self._transition(stage if stage in STAGES else "engine")
            self.last_activity = self.clock()
            self._notify()

    def activity(self, event):
        with self.lock:
            if isinstance(event, dict):
                if not self._tool(event, self.clock()):
                    return
            elif not isinstance(event, str):
                return
            elif event == "first_output":
                self._first("output", self.clock())
            elif event == "tool_result":
                self.completed_tools = bounded(self.completed_tools + 1)
                self._transition(
                    next(reversed(self._active_tools.values()))[0]
                    if self._active_tools
                    else "thinking"
                )
            elif event in STAGES:
                self._transition(
                    next(reversed(self._active_tools.values()))[0] if self._active_tools else event
                )
            elif event != "activity":
                return
            self.last_activity = self.clock()
            if event not in ("activity", "first_output"):
                self._notify()

    def snapshot(self):
        with self.lock:
            now = self.clock()
            return dict(
                stage=self.stage,
                elapsed_seconds=bounded(now - self.started),
                quiet_seconds=bounded(now - self.last_activity),
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
        self._salt = uuid.uuid4().bytes
        self._output_seen = False

    def _tool_event(self, kind, ident, stage=None):
        # IDs are untrusted strings too. A stream-specific opaque digest prevents
        # raw text leakage and keeps reused provider IDs distinct across retries.
        if not isinstance(ident, str) or not 0 < len(ident) <= 512:
            return
        digest = hashlib.blake2s(
            ident.encode("utf-8", errors="surrogatepass"), key=self._salt
        ).hexdigest()
        event = {"type": kind, "id": digest}
        if stage is not None:
            event["stage"] = stage
        self.emit(event)

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
                block_type = block.get("type")
                has_output = (
                    block_type == "tool_use"
                    and isinstance(block.get("name"), str)
                    and bool(block["name"])
                ) or (
                    block_type in ("text", "thinking")
                    and isinstance(block.get(block_type), str)
                    and bool(block[block_type])
                )
                if kind == "assistant" and has_output and not self._output_seen:
                    self._output_seen = True
                    # First parsed assistant content, NOT first network byte/token.
                    self.emit("first_output")
                if kind == "assistant" and block.get("type") == "tool_use":
                    name = block.get("name", "")
                    if not isinstance(name, str):
                        continue
                    kinds = read_kinds(name, block.get("input"))
                    # Display attempted work, independently of whether its output
                    # can later qualify as verified live evidence.
                    if "live" in kinds or (
                        name.startswith("mcp__") and name.endswith("__execute_sql_readonly")
                    ):
                        stage = "live"
                    elif "code" in kinds:
                        stage = "code"
                    elif name == "Skill":
                        stage = "skill"
                    elif name in {"Edit", "Write"}:
                        stage = "memory"
                    elif name in {"Glob", "ToolSearch"}:
                        stage = "search"
                    elif name in {"Read", "Grep"}:
                        args = block.get("input")
                        target = (
                            (args.get("file_path") or args.get("path"))
                            if isinstance(args, dict)
                            else None
                        )
                        stage = (
                            "search"
                            if isinstance(target, str) and "/.dougle/" in target
                            else "lookup"
                        )
                    elif name in {"WebFetch"} or (
                        isinstance(name, str) and name.startswith("mcp__")
                    ):
                        stage = "lookup"
                    else:
                        stage = "thinking"
                    if "id" in block:
                        self._tool_event("tool_start", block["id"], stage)
                    else:
                        self.emit(stage)  # Compatibility with older/id-less traces.
                elif kind == "user" and block.get("type") == "tool_result":
                    if "tool_use_id" in block:
                        self._tool_event("tool_end", block["tool_use_id"])
                    else:
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
        if any(not valid_number(n) for n in numbers):
            return None
        for key in ("prompt_bytes", "first_output_ms", "first_tool_ms"):
            if key in value:
                if value[key] is not None and not valid_number(value[key]):
                    return None
                result[key] = value[key]
        for key in ("prompt_bytes_samples", "usage_reports"):
            if key in value:
                if not valid_number(value[key]):
                    return None
                result[key] = value[key]
        if "usage" in value:
            if not isinstance(value["usage"], dict) or "usage_reports" not in result:
                return None
            usage = {}
            for key in USAGE_FIELDS:
                metric = value["usage"][key]
                total, samples = metric["total"], metric["samples"]
                if not valid_number(samples) or samples > result["usage_reports"]:
                    return None
                if (samples == 0 and total is not None) or (
                    samples > 0 and not valid_number(total)
                ):
                    return None
                usage[key] = dict(total=total, samples=samples)
            result["usage"] = usage
        if "tool_timing" in value:
            timing = {key: value["tool_timing"][key] for key in TOOL_METRICS}
            if any(not valid_number(n) for n in timing.values()):
                return None
            if (
                timing["started"] != timing["finished"] + timing["incomplete"] + timing["active"]
                or timing["active_ms"] > timing["total_ms"]
            ):
                return None
            result["tool_timing"] = timing
        if "attempt_timings" in value:
            if (
                not isinstance(value["attempt_timings"], list)
                or len(value["attempt_timings"]) > MAX_ATTEMPTS
            ):
                return None
            attempts = []
            previous = 0
            for row in value["attempt_timings"]:
                item = {
                    key: row[key]
                    for key in ("attempt", "elapsed_ms", "first_output_ms", "first_tool_ms")
                }
                if (
                    not valid_number(item["attempt"])
                    or not previous < item["attempt"] <= result["attempts"]
                    or not valid_number(item["elapsed_ms"])
                ):
                    return None
                for key in ("first_output_ms", "first_tool_ms"):
                    if item[key] is not None and (
                        not valid_number(item[key]) or item[key] > item["elapsed_ms"]
                    ):
                        return None
                previous = item["attempt"]
                attempts.append(item)
            result["attempt_timings"] = attempts
        return result
    except (ValueError, TypeError, KeyError):
        return None
