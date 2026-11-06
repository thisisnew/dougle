"""Wiring/retention tests; actual model answer quality is reported separately."""

import json

from test_answer_style import SESSION, SKILLS, answer
from test_runtime import ready

from dougle_bot import cards
from dougle_bot.answer_policy import ANSWER_POLICY
from dougle_bot.engine import EngineResult
from dougle_bot.prompts import prompt


def payload(text):
    return json.loads(text.split("DATA_JSON_START\n", 1)[1].rsplit("\nDATA_JSON_END", 1)[0])


def test_followup_receives_new_question_and_posted_manual_context(app):
    app.slack.messages[("CTEST", "1001.000001")] = [
        {"ts": "1001.000001", "user": "UASKER", "text": "가상 반납 절차를 알려주세요."}
    ]
    ready(app)
    app.slack.messages[("CTEST", "1001.000001")] += [
        {
            "ts": "1002.000001",
            "bot_id": "BTEST",
            "text": "수정된 실제 게시본: 식품은 품질팀에 확인합니다.",
        },
        {"ts": "1003.000001", "user": "UASKER", "text": "그럼 식품도 거기서 등록하나요?"},
    ]
    ready(
        app,
        ts="1003.000001",
        thread_ts="1001.000001",
        text="<@UBOT> 그럼 식품도 거기서 등록하나요?",
    )
    text, session, _ = app.engine.calls[-1]
    assert session == SESSION
    assert ANSWER_POLICY in text
    data = payload(text)
    assert data["question"] == "그럼 식품도 거기서 등록하나요?"
    assert len(data["messages"]) == 2
    assert "품질팀" in data["messages"][0]["text"]


def test_retry_retains_original_scope_and_material_conditions(app):
    body = "식품은 일반 화면에서 등록할 수 없습니다. 품질팀 확인 후 식품 접수 화면에서 등록합니다."
    app.engine.results = [
        EngineResult(SESSION, answer("하면 돼."), 0.01, SKILLS),
        EngineResult(SESSION, answer(body), 0.01, SKILLS),
    ]
    ref = ready(app, text="<@UBOT> 식품도 거기서 등록하나요?")
    assert len(app.engine.calls) == 2
    for text, _, _ in app.engine.calls:
        assert ANSWER_POLICY in text
        assert payload(text)["question"] == "식품도 거기서 등록하나요?"
    draft = app.store.current(app.store.inquiry(ref[0]))
    assert cards.final_text(draft) == body


def test_explicit_details_are_not_subject_to_new_character_truncation(app):
    # A long requested answer must reach preview/public splitting intact, including its final exception.
    body = "\n".join(f"{i}. 단계 {i}의 확인 조건과 결과를 설명합니다." for i in range(1, 120))
    body += "\n식품은 반드시 품질팀 확인 후 별도 화면에서 등록합니다."
    app.engine.results = [EngineResult(SESSION, answer(body), 0.01, SKILLS)]
    ref = ready(app, text="<@UBOT> 모든 단계와 예외를 빠짐없이 상세하게 설명해 주세요.")
    draft = app.store.current(app.store.inquiry(ref[0]))
    assert cards.final_text(draft) == body
    assert "품질팀" in cards.split_text(cards.final_text(draft))[-1]


def test_lookup_attribution_and_scope_coexist_in_prompt(app):
    text = prompt(
        app.cfg.engine,
        {"question_text": "오늘 발송됐나요?", "thread_key": "CTEST:1.0"},
        [],
        lookup={"summary": "2건 발송을 확인했습니다.", "observed_at": "2026-11-07T00:00:00+00:00"},
    )
    assert ANSWER_POLICY in text
    assert "개발자 조회 결과 기준" in text
    assert payload(text)["developer_report"]["observed_at"] == "2026-11-07T00:00:00+00:00"
