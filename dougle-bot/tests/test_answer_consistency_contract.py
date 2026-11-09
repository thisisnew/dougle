"""Repeated-answer contract with synthetic questions and fake model/source results.

Passing cases protect evidence, scope, and live-result boundaries. Strict xfails
describe stable-answer behavior until a verified reuse path exists. No company
source, network, Slack account, or paid model is used.
"""

import json
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace

import pytest
from test_answer_style import READS, SKILLS, answer
from test_runtime import event

from dougle_bot.engine import EngineResult
from dougle_bot.evidence import read_receipts
from dougle_bot.intake import receive

QUESTION = "입고는 언제 저장하나요?"
SESSION_A = "11111111-2222-4333-8444-555555555555"
SESSION_B = "11111111-2222-4333-8444-666666666666"
SESSION_C = "11111111-2222-4333-8444-777777777777"


def _automatic(app):
    config = replace(app.cfg, slack=replace(app.cfg.slack, require_approval=False))
    app.cfg = app.worker.config = app.actions.config = config


def _enqueue(app, ts, *, question=QUESTION, channel="CTEST", thread=None, user="UASKER"):
    body = event(
        ts=ts,
        text=f"<@UBOT> {question}",
        channel=channel,
        user=user,
        **({"thread_ts": thread} if thread else {}),
    )
    ident = receive(app.store, app.cfg, body, "UBOT")
    assert ident is not None
    key = (channel, thread or ts)
    app.slack.messages.setdefault(key, []).append(
        {"ts": ts, "user": user, "text": body["event"]["text"]}
    )
    return ident


def _draft(app, ident):
    return app.store.current(app.store.inquiry(ident))


def _storage_timing(body):
    """Tiny golden-fact grader for this synthetic policy, not a product parser."""
    if "확정 전" in body:
        return "before_confirmation"
    if "확정 뒤" in body or "확정 후" in body:
        return "after_confirmation"
    return "unresolved"


def _domain_result(body, session, *, reads=READS):
    return EngineResult(session, answer(body), 0.01, SKILLS, reads)


def _local_result(body, source, session):
    """Attach an actual synthetic source path and a current-call read receipt."""
    events = [
        {
            "type": "assistant",
            "message": {"content": [{
                "type": "tool_use",
                "id": "policy_read",
                "name": "Read",
                "input": {"file_path": str(source)},
            }]},
        },
        {
            "type": "user",
            "message": {"content": [{
                "type": "tool_result",
                "tool_use_id": "policy_read",
                "content": source.read_text(),
            }]},
        },
    ]
    reads = read_receipts("\n".join(json.dumps(row) for row in events))
    output = answer(body)
    output["sources"] = [{"title": "가상 입고 정책", "location": str(source)}]
    output["verification"]["checks"][0]["receipt"] = "policy_read"
    return EngineResult(session, output, 0.01, SKILLS, reads)


def _live_result(body, rows, session):
    query = "SELECT COUNT(*) AS count FROM synthetic.replica.centers"
    events = [
        {
            "type": "assistant",
            "message": {"content": [{
                "type": "tool_use",
                "id": "query",
                "name": "mcp__bigquery__execute_sql_readonly",
                "input": {"query": query},
            }]},
        },
        {
            "type": "user",
            "message": {"content": [{
                "type": "tool_result",
                "tool_use_id": "query",
                "content": json.dumps({"rows": rows}),
            }]},
        },
    ]
    reads = read_receipts("\n".join(json.dumps(row) for row in events))
    output = {
        "status": "answered",
        "body": body,
        "sources": [{"title": "가상 BigQuery 조회", "location": "synthetic.replica.centers"}],
        "lookup": None,
        "verification": {
            "question_kind": "current_state",
            "required": ["knowledge", "live"],
            "missing": [],
            "checks": [
                {"kind": kind, "receipt": "query", "source_index": 0}
                for kind in ("knowledge", "live")
            ],
        },
    }
    return EngineResult(session, output, 0.01, SKILLS, reads)


def _full_table_result(count, session):
    """A complete, unfiltered query for the exact table named by the user."""
    query = "SELECT COUNT(*) AS count FROM `synthetic.replica.centers`"
    events = [
        {"type": "assistant", "message": {"content": [{
            "type": "tool_use", "id": "query", "name": "mcp__bigquery__execute_sql_readonly",
            "input": {"query": query},
        }]}},
        {"type": "user", "message": {"content": [{
            "type": "tool_result", "tool_use_id": "query",
            "content": json.dumps({
                "jobComplete": True, "totalRows": "1", "rows": [{"count": count}],
            }),
        }]}},
    ]
    reads = read_receipts("\n".join(json.dumps(row) for row in events))
    output = {
        "status": "answered",
        "body": "모델이 제안한 값은 게시하지 않습니다.",
        "sources": [{"title": "센터 테이블", "location": "synthetic.replica.centers"}],
        "lookup": None,
        "verification": {
            "question_kind": "current_state", "required": ["live"], "missing": [],
            "checks": [{"kind": "live", "receipt": "query", "source_index": 0}],
        },
    }
    return EngineResult(session, output, 0.01, SKILLS, reads)


def test_same_text_does_not_reuse_a_previous_inquirys_read_receipt(app):
    """A second inquiry still needs its own authorized read or a new cache proof."""
    _automatic(app)
    app.engine.results = [
        _domain_result("확정 뒤 저장됩니다.", SESSION_A),
        _domain_result("확정 뒤 저장됩니다.", SESSION_B, reads={}),
        _domain_result("확정 뒤 저장됩니다.", SESSION_C, reads={}),
    ]
    first = _enqueue(app, "1001.000001")
    assert app.worker.run_once()
    assert app.store.inquiry(first)["status"] == "posted"
    second = _enqueue(app, "1002.000001")
    assert app.worker.run_once()
    assert app.store.inquiry(second)["status"] == "failed"
    assert _draft(app, second) is None


def test_identical_question_text_preserves_different_thread_scope(app):
    """The same short wording can refer to different services in each thread."""
    _automatic(app)
    app.engine.results = [
        _domain_result("RMS에서는 확정 뒤 저장됩니다.", SESSION_A),
        _domain_result("IMS에서는 검수 뒤 저장됩니다.", SESSION_B),
    ]
    app.slack.messages[("CTEST", "1001.000001")] = [
        {"ts": "1001.000001", "user": "UASKER", "text": "RMS 입고를 묻겠습니다."}
    ]
    app.slack.messages[("CTEST", "1003.000001")] = [
        {"ts": "1003.000001", "user": "UASKER", "text": "IMS 입고를 묻겠습니다."}
    ]
    first = _enqueue(app, "1002.000001", thread="1001.000001")
    assert app.worker.run_once()
    second = _enqueue(app, "1004.000001", thread="1003.000001")
    assert app.worker.run_once()
    assert "RMS" in app.engine.calls[0][0]
    assert "IMS" in app.engine.calls[1][0]
    assert _draft(app, first)["body"] != _draft(app, second)["body"]


def test_same_static_question_and_fixed_fake_evidence_returns_same_body(app):
    """A supported explicit source rule overrides drifting model wording."""
    _automatic(app)
    source = app.cfg.home / "synthetic_policy.md"
    source.write_text("입고는 확정 뒤 저장됩니다.\n")
    app.engine.results = [
        _local_result("확정 뒤 저장됩니다.", source, SESSION_A),
        _local_result("확정 전에도 저장됩니다.", source, SESSION_B),
    ]
    first = _enqueue(app, "1001.000001")
    assert app.worker.run_once()
    second = _enqueue(app, "1002.000001")
    assert app.worker.run_once()
    assert _draft(app, first)["body"] == _draft(app, second)["body"]


def test_repeated_static_answers_preserve_the_golden_fact_and_source(app):
    """A matching citation must support the claimed timing on every repeat."""
    _automatic(app)
    source = app.cfg.home / "synthetic_policy.md"
    source.write_text("입고는 확정 뒤 저장됩니다.\n")
    app.engine.results = [
        _local_result("입고는 확정 뒤 저장됩니다.", source, SESSION_A),
        _local_result("입고는 확정 전 저장됩니다.", source, SESSION_B),
    ]
    first = _enqueue(app, "1001.000001")
    assert app.worker.run_once()
    second = _enqueue(app, "1002.000001")
    assert app.worker.run_once()
    for ident in (first, second):
        draft = _draft(app, ident)
        sources = json.loads(draft["sources_json"])
        assert sources[0]["location"] == str(source)
        assert _storage_timing(draft["body"]) == "after_confirmation"


def test_changed_original_must_not_reuse_the_old_static_answer(app):
    """Even the same wording must reflect changed source content."""
    _automatic(app)
    source = app.cfg.home / "synthetic_policy.md"
    source.write_text("입고는 확정 뒤 저장됩니다.\n")
    app.engine.results = [_local_result("확정 뒤 저장됩니다.", source, SESSION_A)]
    first = _enqueue(app, "1001.000001")
    assert app.worker.run_once()
    source.write_text("입고는 검수 뒤 저장됩니다.\n")
    app.engine.results = [_local_result("검수 뒤 저장됩니다.", source, SESSION_B)]
    second = _enqueue(app, "1002.000001")
    assert app.worker.run_once()
    assert app.store.inquiry(first)["status"] == "posted"
    assert app.store.inquiry(second)["status"] == "posted"
    assert _draft(app, first)["body"] != _draft(app, second)["body"]


def test_same_wording_with_a_correction_must_not_reuse_the_old_answer(app):
    """A user correction changes the evidence context without changing the wording."""
    _automatic(app)
    source = app.cfg.home / "synthetic_policy.md"
    source.write_text("입고 저장 시점은 업무 정책에 따라 확인합니다.\n")
    app.engine.results = [
        _local_result("확정 뒤 저장됩니다.", source, SESSION_A),
        _local_result("정정 내용 기준으로 검수 뒤 저장됩니다.", source, SESSION_B),
    ]
    first = _enqueue(app, "1001.000001")
    assert app.worker.run_once()
    app.slack.messages[("CTEST", "1002.000001")] = [
        {
            "ts": "1002.000001",
            "user": "UASKER",
            "text": "앞선 설명을 정정합니다. 저장은 검수 뒤입니다.",
        }
    ]
    second = _enqueue(app, "1003.000001", thread="1002.000001")
    assert app.worker.run_once()
    assert "정정합니다" in app.engine.calls[1][0]
    assert _draft(app, first)["body"] != _draft(app, second)["body"]


def test_named_whole_table_zero_repeats_with_fresh_results(app):
    _automatic(app)
    question = "`synthetic.replica.centers` 테이블 전체에 데이터가 있나요?"
    app.engine.results = [
        _full_table_result(0, SESSION_A), _full_table_result(0, SESSION_B),
    ]
    first = _enqueue(app, "1001.000001", question=question)
    assert app.worker.run_once()
    second = _enqueue(app, "1002.000001", question=question)
    assert app.worker.run_once()
    assert app.store.inquiry(first)["status"] == "posted"
    assert app.store.inquiry(second)["status"] == "posted"
    assert _draft(app, first)["body"] == _draft(app, second)["body"]
    assert "결과가 없습니다" in _draft(app, first)["body"]


def test_named_whole_table_zero_to_one_uses_new_result(app):
    _automatic(app)
    question = "`synthetic.replica.centers` 테이블 전체에 데이터가 있나요?"
    app.engine.results = [
        _full_table_result(0, SESSION_A), _full_table_result(1, SESSION_B),
    ]
    first = _enqueue(app, "1001.000001", question=question)
    assert app.worker.run_once()
    second = _enqueue(app, "1002.000001", question=question)
    assert app.worker.run_once()
    assert app.store.inquiry(first)["status"] == "posted"
    assert app.store.inquiry(second)["status"] == "posted"
    assert "결과가 없습니다" in _draft(app, first)["body"]
    assert "1건" in _draft(app, second)["body"]


@pytest.mark.xfail(strict=True, reason="Automatic BigQuery answers await verified query-plan and claim mapping")
def test_repeated_empty_live_result_posts_the_same_bounded_answer(app):
    """Each live result is fresh; identical empty results get identical wording."""
    _automatic(app)
    body = "현재 BigQuery 조회 결과가 없습니다. 잠시 후 다시 문의해 주세요."
    app.engine.results = [
        _live_result(body, [], SESSION_A),
        _live_result(body, [], SESSION_B),
    ]
    first = _enqueue(app, "1001.000001", question="현재 입하 이슈는 몇 건인가요?")
    assert app.worker.run_once()
    second = _enqueue(app, "1002.000001", question="현재 입하 이슈는 몇 건인가요?")
    assert app.worker.run_once()
    assert app.store.inquiry(first)["status"] == "posted"
    assert app.store.inquiry(second)["status"] == "posted"
    assert _draft(app, first)["body"] == _draft(app, second)["body"]


@pytest.mark.xfail(strict=True, reason="Automatic BigQuery answers await verified query-plan and claim mapping")
def test_changed_live_result_changes_the_answer(app):
    """A prior zero result must never hide the next fresh nonzero result."""
    _automatic(app)
    app.engine.results = [
        _live_result("현재 BigQuery 조회 기준 0건입니다.", [{"count": 0}], SESSION_A),
        _live_result("현재 BigQuery 조회 기준 1건입니다.", [{"count": 1}], SESSION_B),
    ]
    first = _enqueue(app, "1001.000001", question="현재 입하 이슈는 몇 건인가요?")
    assert app.worker.run_once()
    second = _enqueue(app, "1002.000001", question="현재 입하 이슈는 몇 건인가요?")
    assert app.worker.run_once()
    assert app.store.inquiry(first)["status"] == "posted"
    assert app.store.inquiry(second)["status"] == "posted"
    assert _draft(app, first)["body"] != _draft(app, second)["body"]


def test_concurrent_same_static_question_converges_on_one_answer(app):
    """Concurrent questions sharing a verified snapshot must not diverge."""
    _automatic(app)
    source = app.cfg.home / "synthetic_policy.md"
    source.write_text("입고는 확정 뒤 저장됩니다.\n")
    app.engine.results = [
        _local_result("확정 뒤 저장됩니다.", source, SESSION_A),
        _local_result("확정 전에도 저장됩니다.", source, SESSION_B),
    ]
    first = _enqueue(app, "1001.000001")
    second = _enqueue(app, "1002.000001")
    with ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(lambda _: app.worker.run_once(), range(2)))
    assert app.store.inquiry(first)["status"] == "posted"
    assert app.store.inquiry(second)["status"] == "posted"
    assert _draft(app, first)["body"] == _draft(app, second)["body"]
