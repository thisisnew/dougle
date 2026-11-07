"""Private assistance lifecycle with fake Slack/engine and real SQLite/Bolt dispatch."""

import json
import sqlite3
from concurrent.futures import ThreadPoolExecutor

import pytest
from slack_bolt.request import BoltRequest
from test_answer_style import SESSION, answer
from test_boundaries import bolt
from test_evidence import button_ids, output, result
from test_runtime import event
from test_simple_approval import button_request

from dougle_bot.evidence import validate_verification
from dougle_bot.intake import receive
from dougle_bot.safety import BotError

AT = "1970-01-01T00:16:00+00:00"


def pending(app, value):
    app.engine.results = [result(value)]
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.run_once()
    return app.store.inquiry(ident)


def failed(app, error="engine_timeout"):
    app.engine.results = [BotError(error)]
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.run_once()
    row = app.store.inquiry(ident)
    return row, metadata(app, row, "assist")


def metadata(app, row, action):
    card = app.slack.cards[(row["card_channel_id"], row["card_ts"])]
    value = next(
        item["value"]
        for block in card["blocks"]
        if block["type"] == "actions"
        for item in block["elements"]
        if item["action_id"] == action
    )
    return json.dumps(
        {**json.loads(value), "channel": row["card_channel_id"], "ts": row["card_ts"]}
    )


def submit(app, meta, action="assist", summary="자료 연결을 복구했습니다."):
    return app.actions.submit("UAPPROVER", meta, action, summary, AT)


@pytest.mark.parametrize("kind,category", [("live", "current_state"), ("code", "implementation")])
def test_unavailable_required_reads_route_to_private_developer_not_public_failure(
    app, kind, category
):
    value = output(category, (kind,), (kind,), status="cannot_answer")
    value["body"] = "현재 처리 여부를 확인하지 못했습니다."
    row = pending(app, value)
    assert row["status"] == "needs_lookup"
    assert button_ids(app, row) == ["lookup", "take_over"]
    assert len(app.engine.calls) == 1
    assert not app.worker.run_once()  # Wait for an actual developer result, not a timer/retry loop.
    assert not any(call[0] == "post" for call in app.slack.calls)


@pytest.mark.parametrize("category,required", [("clarification", ()), ("domain", ("knowledge",))])
def test_customer_clarification_and_missing_material_remain_reviewable_answers(
    app, category, required
):
    value = output(category, required, required, status="cannot_answer")
    value["body"] = (
        "어느 센터인지 알려주시겠어요?"
        if not required
        else "해당 조건은 확인한 자료에서 찾지 못했습니다."
    )
    row = pending(app, value)
    assert row["status"] == "awaiting_approval"
    assert "post" in button_ids(app, row)
    assert not any(c[0] == "post" for c in app.slack.calls)


@pytest.mark.parametrize(
    "kind,category",
    [("live", "current_state"), ("code", "implementation"), ("knowledge", "domain")],
)
def test_developer_result_resumes_same_inquiry_and_requires_final_approval(app, kind, category):
    row = pending(app, output(category, (kind,), (kind,), status="needs_lookup"))
    meta = metadata(app, row, "lookup")
    submit(app, meta, "lookup", "테스트 환경의 해당 조건을 확인했습니다.")
    value = output(
        category, (kind,), checks=[dict(kind=kind, receipt="developer_report", source_index=0)]
    )
    value["body"] = f"개발자 조회 결과 기준 ({AT}) 해당 조건에서는 처리가 가능합니다."
    value["sources"] = [dict(title="담당자 확인", location=f"문의 #{row['id']} 개발자 보고 {AT}")]
    app.engine.results = [result(value)]
    app.worker.run_once()
    current = app.store.inquiry(row["id"])
    assert current["status"] == "awaiting_approval"
    assert current["card_ts"] == row["card_ts"] and len(app.slack.cards) == 1
    assert app.engine.calls[-1][1] == SESSION
    assert app.store.current(current)["revision"] == 2
    assert not any(c[0] == "post" for c in app.slack.calls)
    postmeta = json.loads(metadata(app, current, "post"))
    app.actions.post(
        "UAPPROVER", json.dumps(postmeta), current["card_channel_id"], current["card_ts"]
    )
    assert app.store.inquiry(row["id"])["status"] == "posted"
    posts = [c for c in app.slack.calls if c[0] == "post"]
    assert len(posts) == 1 and posts[0][1:3] == ("CTEST", row["thread_ts"])
    assert "SELECT" not in posts[0][3]


@pytest.mark.parametrize(
    "error",
    [
        "engine_timeout",
        "daily_budget_exceeded",
        "processing_failed",
        "image_access_denied",
        "sensitive_content",
        "required_skill_not_activated",
        "required_read_not_verified",
    ],
)
def test_internal_failures_have_one_private_action_and_never_post_or_rerun(app, error):
    row, _ = failed(app, error)
    assert row["status"] == "failed" and app.store.current(row) is None
    assert button_ids(app, row) == ["assist", "take_over"]
    assert not app.worker.run_once()
    assert len(app.engine.calls) == 1
    assert not any(c[0] == "post" for c in app.slack.calls)
    app.store.recover()
    app.worker.show_card(row["id"])
    assert len(app.slack.cards) == 1 and button_ids(app, row) == ["assist", "take_over"]
    assert not app.worker.run_once()


def test_environment_repair_queues_once_and_requires_actual_reads(app):
    row, meta = failed(app)
    submit(app, meta)
    app.engine.results = [result(answer(), {"read1": {"knowledge"}})]
    app.engine.results[0].output["body"] = (
        f"개발자 조회 결과 기준 ({AT}) 연결 복구 후 확인한 내용입니다."
    )
    app.worker.run_once()
    current = app.store.inquiry(row["id"])
    assert current["status"] == "awaiting_approval"
    assert current["card_ts"] == row["card_ts"]
    assert '"evidence_kinds": []' in app.engine.calls[-1][0]
    with pytest.raises(BotError, match="stale_job"):
        submit(app, meta)


def test_double_modal_submissions_create_one_job(app):
    row, meta = failed(app)

    def attempt(_):
        try:
            return submit(app, meta)
        except BotError:
            return None

    with ThreadPoolExecutor(max_workers=4) as pool:
        values = list(pool.map(attempt, range(12)))
    assert values.count(row["id"]) == 1
    assert len(app.store.all("SELECT * FROM jobs WHERE status='queued'")) == 1
    assert len(app.store.all("SELECT * FROM audit WHERE action='developer_result_received'")) == 1


@pytest.mark.parametrize("change", ["user", "channel", "ts", "inquiry_id", "job_id", "stopped"])
def test_assistance_rejects_wrong_identity_or_stale_binding(app, change):
    row, meta = failed(app)
    data = json.loads(meta)
    user = "UAPPROVER"
    if change == "user":
        user = "UOTHER"
    elif change == "stopped":
        app.store.stop()
    elif change in ("job_id", "inquiry_id"):
        data[change] += 500
    else:
        data[change] = "different"
    with pytest.raises(BotError):
        app.actions.submit(user, json.dumps(data), "assist", "확인했습니다.", AT)
    assert not app.store.all("SELECT * FROM jobs WHERE status='queued'")
    assert app.store.inquiry(row["id"])["status"] == "failed"


@pytest.mark.parametrize(
    "summary,at",
    [
        ("", AT),
        ("password=synthetic", AT),
        ("test@example.test", AT),
        ("확인했습니다.", "no-time"),
        ("확인했습니다.", "2099-01-01T00:00:00Z"),
    ],
)
def test_invalid_or_sensitive_results_do_not_change_state(app, summary, at):
    row, meta = failed(app)
    with pytest.raises(BotError):
        app.actions.submit("UAPPROVER", meta, "assist", summary, at)
    assert app.store.inquiry(row["id"])["status"] == "failed"


def test_old_failure_card_cannot_resubmit_after_a_new_failure(app):
    row, meta = failed(app)
    submit(app, meta)
    app.engine.results = [BotError("processing_failed")]
    app.worker.run_once()
    with pytest.raises(BotError, match="stale_job"):
        submit(app, meta)
    fresh = metadata(app, app.store.inquiry(row["id"]), "assist")
    assert json.loads(fresh)["job_id"] != json.loads(meta)["job_id"]
    assert len(app.slack.cards) == 1


def test_failed_submission_transaction_rolls_back(app):
    row, meta = failed(app)
    with app.store.tx() as db:
        db.execute(
            "CREATE TRIGGER reject_job BEFORE INSERT ON jobs BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END"
        )
    with pytest.raises(sqlite3.IntegrityError):
        submit(app, meta)
    assert app.store.inquiry(row["id"])["status"] == "failed"
    assert not app.store.all("SELECT * FROM audit WHERE action='developer_result_received'")


def test_repair_report_is_not_code_or_live_evidence():
    for kind, category in [("code", "implementation"), ("live", "current_state")]:
        value = output(
            category, (kind,), checks=[dict(kind=kind, receipt="developer_report", source_index=0)]
        )
        for report in ({"evidence_kinds": []}, {"evidence_kinds": ["knowledge"]}):
            with pytest.raises(BotError, match="required_read_not_verified"):
                validate_verification(value, {}, report=report)


def test_failed_code_requirement_survives_developer_repair(app):
    app.engine.results = [result(output())] * 2
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.run_once()
    row = app.store.inquiry(ident)
    submit(app, metadata(app, row, "assist"))
    downgrade = answer(f"개발자 조회 결과 기준 ({AT}) 정상입니다.")
    app.engine.results = [result(downgrade, {"read1": {"knowledge"}})] * 2
    app.worker.run_once()
    assert app.store.inquiry(ident)["status"] == "failed"
    assert app.store.inquiry(ident)["error"] == "verification_requirements_missing"


def test_actual_bolt_assistance_button_and_modal_queue_durably_before_ack(app):
    row, meta = failed(app)
    built, executor = bolt(app)
    ref = (row["id"], meta, row["card_channel_id"], row["card_ts"])
    response = built.dispatch(button_request(ref, "assist", trigger_id="trigger"))
    assert response.status == 200 and len(executor.calls) == 1
    fn, *args = executor.calls.pop()
    fn(*args)
    assert app.slack.calls[-1][0] == "modal"
    body = {
        "type": "view_submission",
        "team": {"id": "TTEST"},
        "user": {"id": "UAPPROVER"},
        "view": {
            "type": "modal",
            "callback_id": "dougle_assist",
            "private_metadata": meta,
            "state": {
                "values": {
                    "content": {"value": {"value": "연결을 복구했습니다."}},
                    "observed_at": {"value": {"value": AT}},
                }
            },
        },
    }
    response = built.dispatch(BoltRequest(body=body, mode="socket_mode"))
    assert response.status == 200
    assert app.store.inquiry(row["id"])["status"] == "queued"
    assert len(app.engine.calls) == 1  # Modal ack does not run the engine synchronously.
