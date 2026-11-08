"""Bounded startup checks. Never infer credentials or query business data."""

import json
import re
import time

from .safety import BotError, redact

MESSAGES = {
    "slack_checking": "Slack 인증과 채널 연결을 확인하고 있습니다.",
    "checking": "Claude 실행과 필수 스킬을 확인하고 있습니다. 최대 90초가 걸릴 수 있습니다.",
    "ready": "시작 점검을 마쳤습니다. Slack 연결을 확인하고 있습니다.",
    "connected": "시작 점검을 마쳤고 Slack에 연결되었습니다.",
    "optional_connections": "자료 연결 중 확인이 필요한 항목이 있습니다. 연결을 복구하거나, 이번에 한해 미확인 연결을 둔 채 시작할 수 있습니다.",
    "required_skills": "필수 스킬 실행을 확인하지 못했습니다. Dougle 플러그인을 업데이트하고 설정한 스킬 이름을 확인한 뒤 다시 시작해 주세요.",
    "engine_check": "Claude 실행 점검을 마치지 못했습니다. 설정한 Claude를 같은 작업 폴더에서 열어 로그인·네트워크·사용 한도를 확인한 뒤 다시 시작해 주세요.",
    "daily_budget_exceeded": "오늘 AI 예산이 소진되어 시작 점검을 할 수 없습니다. 예산 설정을 확인해 주세요.",
    "bot_stopped": "시작 점검을 중지했습니다.",
    "slack_check": "Slack 연결을 확인하지 못했습니다. 키체인의 봇·앱 토큰, 채널 초대와 앱 권한을 확인한 뒤 다시 시작해 주세요.",
}


def connection_snapshot(raw):
    """Keep only bounded names/status from CLI init, never URLs/config/errors."""
    if not isinstance(raw, list):
        return None
    found = {}
    for item in raw[:200]:
        if not isinstance(item, dict):
            continue
        name = item.get("name")
        if (
            not isinstance(name, str)
            or not re.fullmatch(r"[\w .-]{1,120}", name)
            or redact(name) != name
        ):
            continue
        state = item.get("status")
        if state not in ("connected", "failed", "needs-auth", "pending", "disabled"):
            state = "unknown"
        # Contradictory entries cannot manufacture a healthy connection.
        if name not in found or state != "connected":
            found[name] = state
    return [{"name": name, "status": state} for name, state in sorted(found.items())]


def report(store, phase, *, connections=None, continued=False):
    value = {
        "phase": phase,
        "message": MESSAGES[phase],
        "connections": connections,
        "continued_without_verification": continued,
        "scope": "연결 상태만 확인합니다. 코드·DB의 실제 읽기 권한과 근거는 질문마다 검증합니다.",
    }
    store.audit(None, "system", "startup_check", json.dumps(value, ensure_ascii=False))
    return value


def latest_report(store):
    row = store.one(
        "SELECT detail FROM audit WHERE action='startup_check' AND at>=? ORDER BY id DESC LIMIT 1",
        (store.control()["started"],),
    )
    if not row:
        return None
    try:
        value = json.loads(row["detail"])
        phase = value["phase"]
        if phase not in MESSAGES:
            return None
        return {
            "phase": phase,
            "message": MESSAGES[phase],
            "connections": connection_snapshot(value.get("connections")),
            "continued_without_verification": value.get("continued_without_verification") is True,
            "scope": "연결 상태 점검이며 실제 코드·DB 조회 성공을 의미하지 않습니다.",
        }
    except (ValueError, KeyError, TypeError):
        return None


def check_engine(config, store, engine, *, allow_unverified_readers=False):
    report(store, "checking")
    run_id = None
    result = None
    started = time.monotonic()
    try:
        run_id, budget = store.reserve(
            None, min(0.5, config.max_budget_usd_per_call), config.daily_budget_usd, False
        )
        prompt = (
            "Dougle 시작 점검입니다. 업무 질문이 아닙니다. 아래 스킬을 Skill 도구로 각각 "
            "한 번 로드하여 사용 가능 여부만 확인하세요. 스킬 본문의 업무 조사 절차는 실행하지 "
            "마세요. 업무 자료·파일·DB를 읽거나 쓰지 마세요. 도구로 연결을 시험하지 마세요. "
            "모두 로드하면 ready=true, 불가능하면 ready=false를 구조화 결과로 반환하세요.\n"
            + json.dumps(list(config.required_skills), ensure_ascii=False)
        )
        result = engine.run(prompt, None, budget, timeout_seconds=90)
        if not set(config.required_skills).issubset(result.activated):
            raise BotError("required_skills")
        if (
            not isinstance(result.output, dict)
            or set(result.output) != {"ready"}
            or result.output["ready"] is not True
        ):
            raise BotError("engine_check")
    except Exception as exc:
        phase = exc.code if isinstance(exc, BotError) else "engine_check"
        if phase not in ("required_skills", "daily_budget_exceeded", "bot_stopped"):
            phase = "engine_check"
        report(store, phase)
        if run_id is not None:
            store.finish_run(
                run_id,
                cost=result.cost_usd if result else None,
                error=phase,
                duration=int((time.monotonic() - started) * 1000),
            )
        raise BotError(phase) from None
    store.finish_run(
        run_id,
        cost=result.cost_usd,
        session=result.session_id,
        ok=True,
        duration=int((time.monotonic() - started) * 1000),
    )
    snapshot = connection_snapshot(result.connections)
    # Defaults in allowed_tools are NOT evidence that a connector is registered.
    relevant = (
        None
        if snapshot is None
        else [
            item
            for item in snapshot
            if any(
                tool.startswith("mcp__" + item["name"] + "__")
                or tool.startswith("mcp__" + re.sub(r"[^a-zA-Z0-9_-]", "_", item["name"]) + "__")
                for tool in config.allowed_tools
            )
        ]
    )
    unverified = any(item["status"] != "connected" for item in relevant or [])
    if unverified and not allow_unverified_readers:
        report(store, "optional_connections", connections=relevant)
        raise BotError("optional_connections")
    return report(
        store,
        "ready",
        connections=relevant,
        continued=unverified and allow_unverified_readers,
    )
