"""Synthetic operational cases. No company Slack, data, model or credentials."""

import json
from dataclasses import replace

import pytest
from slack_bolt.request import BoltRequest
from test_boundaries import bolt
from test_developer_handoff import failed, metadata, pending
from test_evidence import output, result
from test_runtime import event
from test_scenario_catalog import automatic

from dougle_bot import cards
from dougle_bot.handoff import lookup_policy
from dougle_bot.handoff_notice import NOTICE
from dougle_bot.intake import receive
from dougle_bot.presentation import chat_text
from dougle_bot.prompts import validate_report


@pytest.mark.parametrize("action", ["lookup", "assist"])
def test_new_modal_submits_without_time_through_bolt(app, action):
    if action == "lookup":
        row = pending(app, output("current_state", ("live",), ("live",), status="needs_lookup"))
        meta = metadata(app, row, action)
    else:
        row, meta = failed(app)
    row = {**row, **json.loads(meta)}
    view = cards.modal(action, row, app.store.current(row))
    assert [b["block_id"] for b in view["blocks"]] == ["content"]
    built, _ = bolt(app)
    response = built.dispatch(
        BoltRequest(
            body={
                "type": "view_submission",
                "team": {"id": "TTEST"},
                "user": {"id": "UAPPROVER"},
                "view": {
                    "type": "modal",
                    "callback_id": "dougle_" + action,
                    "private_metadata": meta,
                    "state": {
                        "values": {
                            "content": {"value": {"value": "대상 환경의 활성 항목은 3개입니다."}}
                        }
                    },
                },
            },
            mode="socket_mode",
        )
    )
    assert response.status == 200
    assert app.store.inquiry(row["id"])["status"] == "queued"
    job = app.store.one("SELECT * FROM jobs WHERE status='queued'")
    report = json.loads(job["payload"])["lookup"]
    assert report["observed_at"] is None and report["submitted_at"]
    assert report["evidence_kinds"] == (["live"] if action == "lookup" else [])


def test_receipt_time_does_not_pretend_to_be_observation_time():
    assert validate_report("조회 결과는 3건입니다.", None, 1000) == {
        "summary": "조회 결과는 3건입니다.",
        "observed_at": None,
        "submitted_at": "1970-01-01T00:16:40+00:00",
    }


def test_result_without_time_resumes_and_posts_with_attribution(app):
    automatic(app)
    row = pending(app, output("current_state", ("live",), ("live",), status="needs_lookup"))
    app.actions.submit(
        "UAPPROVER", metadata(app, row, "lookup"), "lookup", "대상 환경의 활성 항목은 3개입니다."
    )
    value = output(
        "current_state",
        ("live",),
        checks=[dict(kind="live", receipt="developer_report", source_index=0)],
    )
    value["body"] = "개발자 조회 결과 기준, 활성 항목은 3개입니다."
    app.engine.results = [result(value)]
    app.worker.run_once()
    assert app.store.inquiry(row["id"])["status"] == "posted"
    assert '"observed_at": null' in app.engine.calls[-1][0]
    assert "receipt time, NEVER the query time" in app.engine.calls[-1][0]


@pytest.mark.parametrize(
    "sql,verified",
    [
        ("DELETE FROM demo", True),
        ("SELECT * FROM demo", True),
        ("SELECT * FROM demo LIMIT 3", False),
        ("SELECT * FROM demo LIMIT 3;", True),
    ],
)
def test_unsafe_query_withheld_but_actionable_private_request_survives(app, sql, verified):
    automatic(app)
    value = output("current_state", ("live",), ("live",), status="needs_lookup")
    value["lookup"].update(
        target="테스트 DB / demo",
        sql=sql,
        schema_verified=verified,
        reason="막힌 이유: 대상 테이블을 확인하지 못했습니다.\n\n확인할 내용: 활성 여부 컬럼을 확인해 주세요.\n\n필요한 결과: 활성 항목 수만 알려 주세요.",
    )
    row = pending(app, value)
    assert row["status"] == "needs_lookup"
    draft = app.store.current(row)
    assert json.loads(draft["lookup_json"])["sql"] == ""
    rendered = json.dumps(
        app.slack.cards[(row["card_channel_id"], row["card_ts"])], ensure_ascii=False
    )
    assert sql not in rendered and "unsafe_lookup_sql" not in rendered
    assert "활성 항목 수" in rendered and "조회문은 안전성" in rendered
    assert "*막힌 이유*" in rendered and "*필요한 결과*" in rendered
    assert len(app.engine.calls) == 1
    assert [c[3] for c in app.slack.calls if c[0] == "post"] == [NOTICE]
    assert ("CTEST", row["trigger_ts"], "speech_balloon") in app.slack.reactions
    assert ("CTEST", row["trigger_ts"], "loadingg") not in app.slack.reactions


def test_sql_withholding_does_not_bypass_fabricated_code_receipt(app):
    value = output("incident", ("code", "live"), ("live",), status="needs_lookup")
    value["lookup"].update(sql="DELETE FROM demo", schema_verified=True)
    app.engine.results = [result(value)] * 2
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.run_once()
    assert app.store.inquiry(ident)["status"] == "failed"
    assert not any(c[0] == "post" for c in app.slack.calls)


@pytest.mark.parametrize(
    "kind,category,reads",
    [
        ("knowledge", "domain", {}),
        ("code", "implementation", {}),
        ("live", "current_state", {}),
        ("code", "implementation", {"read1": {"knowledge"}}),
        ("live", "current_state", {"read1": {"knowledge"}}),
    ],
)
def test_concise_answers_still_require_matching_actual_reads(app, kind, category, reads):
    automatic(app)
    value = output(category, (kind,))
    value["body"] = "활성 항목은 3개입니다."
    app.engine.results = [result(value, reads)] * 2
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.run_once()
    assert app.store.inquiry(ident)["status"] == "failed"
    assert [c[3] for c in app.slack.calls if c[0] == "post"] == [NOTICE]


@pytest.mark.parametrize(
    "kind,category",
    [("knowledge", "domain"), ("code", "implementation"), ("live", "current_state")],
)
def test_short_answers_keep_live_proof_boundary(app, kind, category):
    automatic(app)
    value = output(category, (kind,))
    value["body"] = "활성 항목은 3개입니다."
    app.engine.results = [result(value, {"read1": {kind}})]
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.run_once()
    assert app.store.inquiry(ident)["status"] == (
        "needs_lookup" if kind == "live" else "posted"
    )


def test_lookup_policy_uses_actual_config_and_does_not_enable_shell(app):
    policy = lookup_policy(app.cfg.engine)
    assert "BigQuery__execute_sql_readonly" in policy
    assert "ToolSearch first" in policy and "delayed replica" in policy
    engine = replace(app.cfg.engine, disallowed_tools=app.cfg.engine.allowed_tools[:])
    assert "None configured." in lookup_policy(engine)
    assert "BigQuery__execute_sql_readonly" not in lookup_policy(engine)


def test_inline_service_emphasis_does_not_leak_markers_or_change_code():
    assert (
        chat_text("*mail-service*에 요청합니다. `*x*에`", 0) == "mail-service에 요청합니다. `*x*에`"
    )
    assert chat_text("*발송 방식*\n매일 발송합니다.", 0) == "*발송 방식*\n매일 발송합니다."


def test_dm_question_fence_keeps_record_identifier(app):
    row, _ = failed(app)
    row["question_text"] = "확인 부탁드립니다. ```ORDER_SYNTHETIC_1\nITEM_SYNTHETIC_2```"
    _, blocks = cards.render(row, None)
    question = blocks[3]["text"]["text"]
    assert "ORDER_SYNTHETIC_1" in question and "ITEM_SYNTHETIC_2" in question
    assert "```" not in question


def test_long_query_and_many_paragraphs_do_not_drop_the_request_or_truncate_sql(app):
    value = output("current_state", ("live",), ("live",), status="needs_lookup")
    value["lookup"].update(
        sql="SELECT '" + "a" * 3000 + "' FROM demo LIMIT 1",
        schema_verified=True,
        reason="확인해 주세요.\n\n" * 100,
    )
    row = pending(app, value)
    card = app.slack.cards[(row["card_channel_id"], row["card_ts"])]
    rendered = json.dumps(card, ensure_ascii=False)
    assert "조회문이 길어" in rendered and "SELECT" not in rendered
    assert len(card["blocks"]) < 20
    assert row["status"] == "needs_lookup"
