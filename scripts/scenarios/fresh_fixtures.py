"""Clock/revision/storage fixtures for question-time freshness scenarios."""

from ask_fixtures import make as make_ask


def make(c):
    variant = c["title"].split(": ")[-1]
    synthetic = dict(
        c,
        title="시점 질문 / 정확한 목차",
        steps=["“공급사 메일은 언제 나가나요?”라고 ask합니다."],
    )
    state = make_ask(synthetic)
    state.update(fixture_type="fresh", variant=variant, revision_checks=0)
    stamp = "2026-11-07T10:00:00+09:00"
    if variant == "주기 정확히 도달":
        stamp = "2026-11-07T11:00:00+09:00"
    if variant in ("주기 미도달", "현재값 질문"):
        stamp = "2026-11-07T11:30:00+09:00"
    if variant == "미래 확인 시각":
        stamp = "2026-11-07T13:00:00+09:00"
    if variant == "타임존 없는 시각":
        stamp = "2026-11-07T10:00:00"
    interval = 0 if variant == "기본 주기" else 3600
    state["files"]["~/.dougle/index.md"] = state["files"]["~/.dougle/index.md"].replace(
        "0 seconds", f"{interval} seconds"
    )
    source = state["files"]["~/.dougle/sources/S001.md"].replace(
        "2026-11-06T09:00:00+09:00", stamp
    )
    source += f"""\n## Verified coverage
coverage: rules/mail and exceptions; knowledge/T001.md C001
last_attempt_at: {stamp}
last_verified_at: {stamp}
verified_revision: v2
verification_interval_seconds: {interval}
last_check_result: unchanged
The above was previously checked locally against original Alpha evidence, not an import-time certification.
"""
    if variant == "기본 주기":
        state["files"]["~/.dougle/index.md"] = state["files"][
            "~/.dougle/index.md"
        ].replace("Default verification interval: 0 seconds\n", "")
        source = source.replace("verification_interval_seconds: 0\n", "")
    state["files"]["~/.dougle/sources/S001.md"] = source
    state["initial_files"] = dict(state["files"])
    state["old_verified_at"] = stamp
    state["fact"] = (
        state["fact"].replace("오전 9시", "오전 10시")
        if variant in ("관련 부분 변경", "저장 실패")
        else state["fact"]
    )
    if variant == "현재값 질문":
        state["user_request"] = "A환경 SYNTHETIC-418은 지금 처리됐나요?"
    state["conversation_context"] += (
        " 원본 유형: "
        + c["title"].split(": ")[0]
        + ". 환경의 check_source가 범위별 변경 여부를 제공하며 read_source는 본문/현재값을 읽습니다."
    )
    return state
