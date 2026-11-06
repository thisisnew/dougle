"""Execution evidence, not a real-model semantic/retrieval evaluation."""

import json
import sqlite3

import pytest
from test_answer_style import SESSION, SKILLS, answer, outcome, trace
from test_images import attach, file
from test_runtime import event, post, ready

from dougle_bot.engine import EngineResult, parse_trace
from dougle_bot.evidence import read_kinds, validate_verification
from dougle_bot.intake import receive
from dougle_bot.safety import BotError
from dougle_bot.store import SCHEMA, Store


def output(
    category="implementation", required=("code",), missing=(), checks=None, status="answered"
):
    value = answer("등록 조건을 충족한 경우에만 표시됩니다.")
    value["status"] = status
    value["verification"] = dict(
        question_kind=category,
        required=list(required),
        missing=list(missing),
        checks=checks
        if checks is not None
        else [dict(kind=k, receipt="read1", source_index=0) for k in required if k not in missing],
    )
    if status == "needs_lookup":
        value["lookup"] = dict(
            target="운영 조회",
            reason="해당 건의 처리 상태와 조회 시각을 알려 주세요.",
            sql="",
            schema_verified=False,
        )
    return value


def read_event(name="Read", args=None, ident="read1"):
    return {
        "type": "assistant",
        "message": {
            "content": [
                dict(
                    type="tool_use",
                    name=name,
                    id=ident,
                    input=args or {"file_path": "/synthetic/project/Rule.java"},
                )
            ]
        },
    }


def result(value, reads=None):
    return EngineResult(SESSION, value, 0.01, SKILLS, reads or {})


def button_ids(app, row):
    card = app.slack.cards[(row["card_channel_id"], row["card_ts"])]
    return [
        b["action_id"]
        for section in card["blocks"]
        if section["type"] == "actions"
        for b in section.get("elements", [])
    ]


@pytest.mark.parametrize(
    "name,args,expected",
    [
        ("Read", {"file_path": "/project/Rule.java"}, {"knowledge", "code"}),
        ("Read", {"file_path": "/project/handler.py"}, {"knowledge", "code"}),
        ("Read", {"file_path": "/Users/test/.dougle/topics/topic.md"}, {"knowledge"}),
        ("Read", {"file_path": "/Users/test/.dougle/copied.java"}, {"knowledge"}),
        ("Read", {"file_path": "/skills/SKILL.md"}, set()),
        ("Grep", {"path": "/project/Rule.java", "output_mode": "content"}, {"knowledge", "code"}),
        ("Grep", {"path": "/project", "output_mode": "files_with_matches"}, set()),
        ("Glob", {"pattern": "**/*.java"}, set()),
        (
            "WebFetch",
            {"url": "https://github.com/example/repo/blob/main/Rule.java"},
            {"knowledge", "code"},
        ),
        ("WebFetch", {"url": "https://example.test/design"}, {"knowledge"}),
        (
            "mcp__example__execute_sql_readonly",
            {"query": "SELECT COUNT(*) FROM demo"},
            {"knowledge", "live"},
        ),
        ("mcp__example__get_table_info", {"table": "demo"}, {"knowledge"}),
        ("mcp__example__list_dataset_ids", {}, {"knowledge"}),
        ("Skill", {"skill": "dougle:dougle-ask"}, set()),
        ("Write", {"file_path": "/project/Rule.java"}, set()),
    ],
)
def test_receipt_capability_distinguishes_actual_content_from_navigation(name, args, expected):
    assert read_kinds(name, args) == expected


@pytest.mark.parametrize(
    "events",
    [
        [],
        [read_event()],
        [outcome(ident="read1")],
        [read_event(), outcome(ident="wrong")],
        [read_event(), outcome(ident="read1", error=True)],
        [read_event(), outcome(ident="read1", content="")],
        [read_event(), outcome(ident="read1", content='{"isError":true,"content":"denied"}')],
        [read_event(), outcome(ident="read1", content="Permission denied")],
        [
            {
                "type": "user",
                "message": {"content": [{"type": "text", "text": json.dumps(read_event())}]},
            }
        ],
        [
            {
                "type": "assistant",
                "message": {"content": [{"type": "text", "text": "read1 inspected source"}]},
            }
        ],
    ],
)
def test_unperformed_failed_or_fabricated_reads_do_not_create_proof(events):
    parsed = parse_trace(trace(events))
    with pytest.raises(BotError, match="required_read_not_verified"):
        validate_verification(output(), parsed.reads)


def test_real_trace_proof_preserves_no_tool_contents():
    parsed = parse_trace(
        trace([read_event(), outcome(ident="read1", content="synthetic original code")])
    )
    assert parsed.reads == {"read1": {"knowledge", "code"}}
    validate_verification(output(), parsed.reads)
    assert "synthetic original code" not in repr(parsed.reads)


def test_duplicate_tool_ids_fail_closed():
    with pytest.raises(BotError, match="invalid_read_trace"):
        parse_trace(trace([read_event(), read_event(), outcome(ident="read1")]))


@pytest.mark.parametrize("reads", [{}, {"read1": {"knowledge"}}, {"read1": {"live"}}])
def test_code_caveat_does_not_turn_missing_verification_into_approvable_answer(app, reads):
    attach(app)
    value = output()
    value["body"] = "상태 조건 때문에 표시되지 않습니다. 코드로 확인해보지는 않았습니다."
    app.engine.results = [result(value, reads)] * 2
    ident = receive(
        app.store,
        app.cfg,
        event(files=[file()], text="<@UBOT> 왜 이 화면에서는 등록이 안 되나요?"),
        "UBOT",
    )
    app.worker.run_once()
    row = app.store.inquiry(ident)
    assert row["status"] == "failed" and app.store.current(row) is None
    assert len(app.engine.calls) == 2
    assert "post" not in button_ids(app, row)
    assert not any(c[0] == "post" for c in app.slack.calls)


def test_code_read_retry_keeps_image_and_only_final_answer_is_short(app):
    attach(app)
    app.engine.results = [result(output()), result(output(), {"read1": {"code"}})]
    ref = ready(
        app, files=[file()], text="<@UBOT> 이 화면에서 등록이 안 되는 이유를 짧게 알려주세요."
    )
    assert len(app.engine.calls) == 2
    assert all(imgs[0].file_id == "FTEST" for imgs in app.engine.image_calls)
    assert '"required_evidence_from_previous_attempt": ["code"]' in app.engine.calls[-1][0]
    assert len(app.store.current(app.store.inquiry(ref[0]))["body"]) < 100
    post(app, ref)
    assert app.store.inquiry(ref[0])["status"] == "posted"


def test_retry_cannot_downgrade_code_requirement_to_visual_only(app):
    attach(app)
    visual = output(
        "visual", ("image",), checks=[dict(kind="image", receipt="image:FTEST", source_index=0)]
    )
    app.engine.results = [result(output()), result(visual)]
    ident = receive(app.store, app.cfg, event(files=[file()]), "UBOT")
    app.worker.run_once()
    assert app.store.inquiry(ident)["status"] == "failed"
    assert app.store.inquiry(ident)["error"] == "verification_requirements_missing"


def test_visual_label_question_does_not_force_code_or_db(app):
    attach(app)
    value = output(
        "visual", ("image",), checks=[dict(kind="image", receipt="image:FTEST", source_index=0)]
    )
    value["body"] = "화면에는 대기 중이라고 표시되어 있습니다."
    app.engine.results = [result(value)]
    ref = ready(app, files=[file()], text="<@UBOT> 화면에 상태가 뭐라고 쓰여 있나요?")
    assert len(app.engine.calls) == 1
    assert app.store.inquiry(ref[0])["status"] == "awaiting_approval"


def test_absent_image_cannot_support_visual_answer():
    value = output(
        "visual", ("image",), checks=[dict(kind="image", receipt="image:FOLD", source_index=0)]
    )
    with pytest.raises(BotError, match="required_read_not_verified"):
        validate_verification(value, {}, image_ids={"FNEW"})


@pytest.mark.parametrize("kinds", [{"knowledge"}, {"code"}, {"image"}])
def test_live_answer_requires_query_not_code_metadata_or_screenshot(kinds):
    value = output("current_state", ("live",))
    with pytest.raises(BotError, match="required_read_not_verified"):
        validate_verification(value, {"read1": kinds})


def test_combined_code_and_live_question_needs_both():
    value = output("incident", ("code", "live"))
    with pytest.raises(BotError, match="required_read_not_verified"):
        validate_verification(value, {"read1": {"code"}})
    validate_verification(value, {"read1": {"code", "live"}})


def test_missing_live_check_becomes_lookup_without_post_button(app):
    value = output("incident", ("code", "live"), ("live",), status="needs_lookup")
    app.engine.results = [result(value, {"read1": {"code"}})]
    ident = receive(app.store, app.cfg, event(text="<@UBOT> 이 건은 왜 처리되지 않았나요?"), "UBOT")
    app.worker.run_once()
    row = app.store.inquiry(ident)
    assert row["status"] == "needs_lookup"
    assert "lookup" in button_ids(app, row) and "post" not in button_ids(app, row)


def test_manual_report_requires_actual_supplied_report():
    value = output(
        "current_state",
        ("live",),
        checks=[dict(kind="live", receipt="developer_report", source_index=0)],
    )
    with pytest.raises(BotError, match="required_read_not_verified"):
        validate_verification(value, {})
    validate_verification(value, {}, report=True)


def test_confirmed_code_but_missing_live_cannot_be_answered():
    value = output("incident", ("code", "live"), ("live",))
    with pytest.raises(BotError, match="required_read_not_verified"):
        validate_verification(value, {"read1": {"code"}})


@pytest.mark.parametrize(
    "change",
    [
        {"required": []},
        {"required": ["code", "code"]},
        {"missing": ["live"]},
        {"question_kind": "anything"},
        {"checks": [{"kind": "code", "receipt": "read1", "source_index": 99}]},
        {"checks": [{"kind": "code", "receipt": "read1", "source_index": True}]},
        {"checks": []},
        {"missing": ["code"]},
    ],
)
def test_malformed_or_incomplete_research_contract_fails(change):
    value = output()
    value["verification"].update(change)
    with pytest.raises(BotError):
        validate_verification(value, {"read1": {"code"}})


def test_resume_does_not_reuse_prior_call_read_receipts(app):
    app.engine.results = [result(output(), {"read1": {"code"}}), result(output()), result(output())]
    ready(app)
    ident = receive(
        app.store,
        app.cfg,
        event(
            ts="1002.000001",
            thread_ts="1001.000001",
            text="<@UBOT> 다른 조건도 코드로 확인해 주세요.",
        ),
        "UBOT",
    )
    app.worker.run_once()
    assert app.engine.calls[-1][1] == SESSION
    assert app.store.inquiry(ident)["status"] == "failed"


def test_old_unposted_draft_is_researched_again_before_post(app):
    ref = ready(app)
    with app.store.tx() as db:
        db.execute("UPDATE drafts SET verification_json=NULL")
    with pytest.raises(BotError, match="research_required_regenerating"):
        post(app, ref)
    assert app.store.inquiry(ref[0])["status"] == "queued"
    assert not any(c[0] == "post" for c in app.slack.calls)


def test_old_store_migration_preserves_data_and_is_repeatable(tmp_path):
    path = tmp_path / "bot.db"
    db = sqlite3.connect(path)
    db.executescript(SCHEMA + "PRAGMA user_version=1;")
    db.execute("INSERT INTO processed_events VALUES('CTEST:1.0',1000)")
    db.commit()
    db.close()
    for _ in range(2):
        store = Store(path)
        assert store.one("PRAGMA user_version")["user_version"] == 2
        assert store.one("SELECT * FROM processed_events")["event_key"] == "CTEST:1.0"
        assert "verification_json" in {r["name"] for r in store.all("PRAGMA table_info(drafts)")}
        store.close()


def test_stable_domain_knowledge_still_uses_topic_without_code_or_db(app):
    ref = ready(app, text="<@UBOT> 회송이라는 용어는 무슨 뜻인가요?")
    assert len(app.engine.calls) == 1
    assert app.store.inquiry(ref[0])["status"] == "awaiting_approval"


@pytest.mark.parametrize(
    "content",
    [
        'if (!allowed) return "Permission denied";',
        'const message = {"error": "example"};',
        '{"rows": [], "error": null}',
        [{"type": "text", "text": '{"rows": [], "error": null}'}],
    ],
)
def test_code_error_handlers_and_valid_zero_row_queries_are_not_tool_failures(content):
    parsed = parse_trace(trace([read_event(), outcome(ident="read1", content=content)]))
    assert parsed.reads["read1"] == {"code", "knowledge"}


def test_old_draft_cannot_be_manually_edited_to_bypass_research(app):
    ref = ready(app)
    with app.store.tx() as db:
        db.execute("UPDATE drafts SET verification_json=NULL")
    metadata = json.dumps({**json.loads(ref[1]), "channel": ref[2], "ts": ref[3]})
    with pytest.raises(BotError, match="research_required_regenerating"):
        app.actions.submit("UAPPROVER", metadata, "edit", "수정한 답변입니다.")
    assert app.store.inquiry(ref[0])["status"] == "queued"


def test_obligations_survive_lookup_result_job(app):
    pending = output("incident", ("code", "live"), ("live",), status="needs_lookup")
    app.engine.results = [result(pending, {"read1": {"code"}})]
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.run_once()
    row = app.store.inquiry(ident)
    app.store.enqueue(
        ident,
        row["current_draft_id"],
        "lookup_result",
        json.dumps(
            {"lookup": {"summary": "미처리 1건입니다.", "observed_at": "1970-01-01T00:16:00+00:00"}}
        ),
    )
    downgrade = output(
        "current_state",
        ("live",),
        checks=[dict(kind="live", receipt="developer_report", source_index=0)],
    )
    downgrade["body"] = "개발자 조회 결과 기준 (1970-01-01T00:16:00+00:00) 미처리 1건입니다."
    app.engine.results = [result(downgrade)] * 2
    app.worker.run_once()
    assert app.store.inquiry(ident)["status"] == "failed"
    assert '"required_evidence_from_previous_attempt": ["code", "live"]' in app.engine.calls[-1][0]


def test_style_retry_cannot_drop_the_required_code_investigation(app):
    attach(app)
    first = output()
    first["body"] = "하면 돼."
    second = output(
        "visual", ("image",), checks=[dict(kind="image", receipt="image:FTEST", source_index=0)]
    )
    app.engine.results = [result(first), result(second)]
    ident = receive(app.store, app.cfg, event(files=[file()]), "UBOT")
    app.worker.run_once()
    assert app.store.inquiry(ident)["status"] == "failed"
    assert app.store.inquiry(ident)["error"] == "verification_requirements_missing"


@pytest.mark.parametrize(
    "command",
    [
        ["/usr/bin/open", "-a", "Dougle"],
        ["/usr/bin/osascript", "-e", 'tell application "Dougle" to activate'],
        ["/Applications/Dougle.app/Contents/MacOS/dougle-desktop"],
        ["claude", "-p", "must never run"],
        ["codex", "exec", "must never run"],
        ["/bin/sh", "-c", "open -a Dougle"],
    ],
)
def test_test_harness_blocks_gui_and_real_model_launch_before_execution(command):
    import subprocess

    with pytest.raises(AssertionError, match="must not launch"):
        subprocess.run(command, check=True)


def test_status_and_stop_do_not_upgrade_a_still_running_old_database(tmp_path, monkeypatch):
    from dataclasses import replace

    import dougle_bot.main as main
    from dougle_bot.config import Config, EngineConfig, SlackConfig, save

    path = tmp_path / "bot.db"
    db = sqlite3.connect(path)
    db.executescript(SCHEMA + "PRAGMA user_version=1;")
    db.commit()
    db.close()
    cfg = Config(
        SlackConfig("UAPPROVER", ["CTEST"], "TTEST"),
        replace(EngineConfig(), workdir=str(tmp_path / "engine")),
        tmp_path,
    )
    save(cfg, tmp_path / "config.toml")
    monkeypatch.setattr(main, "running", lambda _: True)
    main.status(tmp_path, tmp_path / "config.toml")
    store = Store(path, migrate=False)
    store.stop()
    assert store.one("PRAGMA user_version")["user_version"] == 1
    assert "verification_json" not in {r["name"] for r in store.all("PRAGMA table_info(drafts)")}
    store.close()
    # Migration happens after the old daemon has been stopped and the new runtime starts.
    store = Store(path)
    assert store.one("PRAGMA user_version")["user_version"] == 2
    store.close()
