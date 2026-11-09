"""Real stream parser + worker + temporary DB; synthetic services, no model calls."""

import copy
import json

import pytest
from test_answer_style import SESSION, SKILLS, answer
from test_developer_handoff import metadata
from test_evidence import output
from test_memory_worker import batch
from test_runtime import event
from test_scenario_catalog import automatic

from dougle_bot.engine import parse_trace
from dougle_bot.intake import receive
from dougle_bot.memory import drain_one
from dougle_bot.memory_worker import dispatch_batches


def response(kind, tool, args, content, source):
    value = answer("조건을 충족한 경우 등록됩니다.")
    value["sources"][0]["location"] = source
    value["verification"] = {
        "question_kind": {"code": "implementation", "live": "current_state"}.get(kind, "domain"),
        "required": [kind],
        "missing": [],
        "checks": [{"kind": kind, "receipt": "read1", "source_index": 0}],
    }
    events = [
        {
            "type": "assistant",
            "message": {
                "content": [
                    {
                        "type": "tool_use",
                        "id": "read1",
                        "name": tool,
                        "input": args,
                    }
                ]
            },
        },
        {
            "type": "user",
            "message": {
                "content": [
                    {
                        "type": "tool_result",
                        "tool_use_id": "read1",
                        "content": content,
                    }
                ]
            },
        },
        {"type": "result", "session_id": SESSION, "structured_output": value},
    ]
    parsed = parse_trace("\n".join(json.dumps(row) for row in events).encode())
    parsed.activated = SKILLS
    return parsed


def process(app, results, text):
    app.engine.results = results
    ident = receive(app.store, app.cfg, event(text="<@UBOT> " + text), "UBOT")
    app.worker.run_once()
    return app.store.inquiry(ident)


def test_mismatched_code_source_cannot_be_saved_as_verified_answer(app):
    bad = response(
        "code",
        "Read",
        {"file_path": "/synthetic/Other.java"},
        "class Other {}",
        "/synthetic/Order.java",
    )
    row = process(app, [bad, copy.deepcopy(bad)], "실제 코드에서 등록 조건을 확인해주세요")
    assert row["status"] == "failed"
    assert app.store.current(row) is None
    assert not app.store.one("SELECT id FROM audit WHERE action='evidence_checks_verified'")


def test_source_mismatch_can_recover_after_reading_the_correct_file(app):
    bad = response(
        "code",
        "Read",
        {"file_path": "/synthetic/Other.java"},
        "class Other {}",
        "/synthetic/Order.java",
    )
    good = response(
        "code",
        "Read",
        {"file_path": "/synthetic/Order.java"},
        "class Order {}",
        "/synthetic/Order.java",
    )
    row = process(app, [bad, good], "실제 코드에서 등록 조건을 확인해주세요")
    assert row["status"] == "awaiting_approval"
    assert len(app.engine.calls) == 2
    assert app.store.current(row) is not None


def test_explicit_live_question_cannot_downgrade_to_schema_only_on_first_attempt(app):
    bad = response(
        "knowledge",
        "mcp__example__get_table_info",
        {"table": "demo.orders"},
        '{"columns":["id","status"]}',
        "demo.orders",
    )
    row = process(app, [bad, copy.deepcopy(bad)], "현재 운영에서 처리되지 않은 주문은 몇 건인가요?")
    assert row["status"] == "failed"
    assert app.store.current(row) is None
    assert '"live"' in app.engine.calls[0][0]


def test_successful_connectivity_sql_does_not_complete_live_investigation(app):
    bad = response(
        "live",
        "mcp__example__execute_sql_readonly",
        {"query": "SELECT 1"},
        '[{"value":1}]',
        "demo.orders",
    )
    row = process(app, [bad, copy.deepcopy(bad)], "현재 운영 주문은 몇 건인가요?")
    assert row["status"] == "failed"
    assert app.store.current(row) is None


@pytest.mark.parametrize(
    "question",
    [
        "레코드 조회 조건을 설명해 주세요.",
        "현재 주문은 한 번에 몇 개까지 취소할 수 있나요?",
        "현재 주문은 최대 몇 개 취소할 수 있나요?",
        "코드는 읽지 말고 전략 패턴 개념을 설명해 주세요.",
        "회송폐기 이메일은 언제 보내나요?",
    ],
)
def test_supported_domain_answers_do_not_gain_unrequested_research_requirements(app, question):
    good = response(
        "knowledge",
        "Read",
        {"file_path": "/synthetic/.dougle/topic.md"},
        "확인된 업무 규칙입니다.",
        "https://example.test/original",
    )
    row = process(app, [good], question)
    assert row["status"] == "awaiting_approval"
    assert len(app.engine.calls) == 1


@pytest.mark.parametrize(
    "question",
    [
        "현재 운영 DB 주문 건수를 조회하고 상태 코드의 의미도 설명해 주세요.",
        "지금 센터 몇개냐??디비좀봐봐",
        "현재 운영 DB에서 취소할 수 있는 주문은 몇 건인가요? 실제 조회해 주세요.",
    ],
)
def test_explicit_data_request_survives_mixed_explanatory_clause(app, question):
    bad = response(
        "knowledge",
        "mcp__example__get_table_info",
        {"table": "demo.orders"},
        '{"columns":["id","status"]}',
        "demo.orders",
    )
    row = process(app, [bad, copy.deepcopy(bad)], question)
    assert row["status"] == "failed"
    assert app.store.current(row) is None


@pytest.mark.parametrize(
    "query",
    [
        'SELECT COUNT(*) FROM "public"."orders"',
        "SELECT COUNT(*) FROM `order-export.analytics.orders`",
        "SELECT COUNTIF(`delete`) FROM `project.ds.orders`",
        "SELECT COUNT(*) FROM `project.ds.orders` JOIN `project.ds.INFORMATION_SCHEMA.TABLES` ON TRUE",
    ],
)
def test_successful_business_queries_keep_capability_but_need_claim_review(app, query):
    from dougle_bot.evidence import sql_data_relations

    good = response(
        "live",
        "mcp__example__execute_sql_readonly",
        {"query": query},
        '[{"count":3}]',
        sql_data_relations({"query": query})[0],
    )
    assert good.reads["read1"] == {"live", "knowledge"}
    assert good.reads["read1"].metadata["content_type"] == "data_query"
    row = process(app, [good], "현재 운영 주문은 몇 건인가요?")
    assert row["status"] == "needs_lookup"
    draft = app.store.current(row)
    assert draft["engine_status"] == "needs_lookup"
    assert "문의 조건" in draft["body"]
    verification = json.loads(draft["verification_json"])
    assert verification["checks"] == []
    assert verification["missing"] == ["live"]
    assert good.reads["read1"] == {"live", "knowledge"}
    assert len(app.engine.calls) == 1


def test_business_query_used_for_stable_knowledge_remains_answerable(app):
    good = response(
        "knowledge", "mcp__example__execute_sql_readonly",
        {"query": "SELECT status, description FROM demo.status_codes LIMIT 10"},
        '[{"status":"OPEN","description":"접수"}]', "demo.status_codes",
    )
    row = process(app, [good], "주문 상태 코드의 의미를 설명해 주세요.")
    assert row["status"] == "awaiting_approval"
    assert good.reads["read1"] == {"live", "knowledge"}
    assert json.loads(app.store.current(row)["verification_json"])["checks"] == (
        good.output["verification"]["checks"]
    )


def test_original_code_answer_remains_answerable(app):
    good = response(
        "code", "Read", {"file_path": "/synthetic/Order.java"},
        "class Order {}", "/synthetic/Order.java",
    )
    row = process(app, [good], "실제 코드에서 등록 조건을 설명해 주세요.")
    assert row["status"] == "awaiting_approval"
    assert json.loads(app.store.current(row)["verification_json"])["checks"] == (
        good.output["verification"]["checks"]
    )


def test_unverified_sql_does_not_auto_post_or_persist_live_memory(app):
    automatic(app)
    source, batch_id = batch(app)
    good = response(
        "live", "mcp__example__execute_sql_readonly",
        {"query": "SELECT COUNT(*) FROM demo.centers LIMIT 1"},
        '[{"count":17,"updated_at":"2026-11-08"}]', "demo.centers",
    )
    good.output["body"] = "현재 운영 센터는 17개입니다."
    good.memory_batch = batch_id
    row = process(app, [good], "현재 운영 센터는 몇 개인가요?")
    assert row["status"] == "needs_lookup"
    public = [call[3] for call in app.slack.calls if call[0] == "post"]
    assert not any("17개" in text or "조회 결과 기준" in text for text in public)
    assert not app.store.one("SELECT id FROM audit WHERE action='auto_reply_ready'")
    assert not app.store.one("SELECT id FROM audit WHERE action='memory_batch_ready'")
    assert app.store.one(
        "SELECT id FROM audit WHERE action='memory_batch_discard' AND detail=?", (batch_id,),
    )
    assert dispatch_batches(app.store, app.cfg.home)
    assert not drain_one(app.cfg.home)
    assert source.read_text() == "이전 안내입니다.\n"
    audit = app.store.one("SELECT detail FROM audit WHERE action='evidence_checks_verified'")
    assert json.loads(audit["detail"])["missing"] == ["live"]
    assert not app.store.one("SELECT id FROM audit WHERE action='memory_batch_ready'")
    assert not dispatch_batches(app.store, app.cfg.home)
    assert not drain_one(app.cfg.home)
    assert source.read_text() == "이전 안내입니다.\n"


@pytest.mark.parametrize("query_kind", ["live", "knowledge"])
def test_manual_centers_report_and_separate_orders_query_keep_their_own_checks(app, query_kind):
    automatic(app)
    initial = response(
        "live", "mcp__example__execute_sql_readonly",
        {"query": "SELECT COUNT(*) FROM demo.centers LIMIT 1"},
        '[{"count":17}]', "demo.centers",
    )
    initial.output = output("current_state", ("live",), ("live",), status="needs_lookup")
    row = process(app, [initial], "현재 운영 센터 수와 주문 정보를 확인해 주세요.")
    assert row["status"] == "needs_lookup"
    app.actions.submit(
        "UAPPROVER", metadata(app, row, "lookup"), "lookup",
        "운영 원본에서 센터는 19개로 확인했습니다. 주문 수는 확인하지 않았습니다.",
    )
    resumed = response(
        query_kind, "mcp__example__execute_sql_readonly",
        {"query": "SELECT COUNT(*) FROM demo.orders LIMIT 1"},
        '[{"count":17}]', "demo.orders",
    )
    resumed.output["sources"].append({
        "title": "개발자 센터 조회 결과", "location": "demo.centers",
    })
    verification = resumed.output["verification"]
    verification["question_kind"] = "current_state"
    verification["required"] = list(dict.fromkeys([query_kind, "live"]))
    verification["checks"].append({
        "kind": "live", "receipt": "developer_report", "source_index": 1,
    })
    resumed.output["body"] = (
        "개발자 조회 결과 기준 센터는 19개이며, 현재 주문은 17건입니다."
        if query_kind == "live" else
        "개발자 조회 결과 기준 센터는 19개입니다. 주문 건수 조회 방법도 확인했습니다."
    )
    app.engine.results = [resumed]
    app.worker.run_once()
    final = app.store.inquiry(row["id"])
    public = [call[3] for call in app.slack.calls if call[0] == "post"]
    assert len(app.engine.calls) == 2
    assert final["status"] == ("needs_lookup" if query_kind == "live" else "posted")
    assert (
        not any("19개" in text or "17건" in text for text in public)
        if query_kind == "live" else any("19개" in text for text in public)
    )
    verification = json.loads(app.store.current(final)["verification_json"])
    assert verification["missing"] == (["live"] if query_kind == "live" else [])
    assert {check["receipt"] for check in verification["checks"]} == (
        set() if query_kind == "live" else {"read1", "developer_report"}
    )


@pytest.mark.parametrize(
    "actual,cited",
    [
        (
            "https://example.test/viewpage.action?pageId=111",
            "https://example.test/viewpage.action?pageId=222",
        ),
        (
            "https://example.test/raw/Order.java?at=refs%2Fheads%2Fmain",
            "https://example.test/raw/Order.java?at=refs%2Fheads%2Frelease",
        ),
    ],
)
def test_url_document_and_revision_selectors_cannot_be_swapped(app, actual, cited):
    kind = "code" if "Order.java" in actual else "knowledge"
    bad = response(kind, "WebFetch", {"url": actual}, "synthetic source body", cited)
    row = process(app, [bad, copy.deepcopy(bad)], "설명해 주세요.")
    assert row["status"] == "failed"
    assert app.store.current(row) is None
