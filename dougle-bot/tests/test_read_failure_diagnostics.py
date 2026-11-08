"""Actual validator failures and fake-worker retries, without external services."""

import copy
import json

import pytest
from test_answer_style import SESSION, SKILLS
from test_evidence_binding import observed, output
from test_runtime import event

from dougle_bot.engine import EngineResult
from dougle_bot.evidence import (
    failure_summary,
    safe_failure_summary,
    safe_verification_diagnostics,
    validate_verification,
)
from dougle_bot.intake import receive
from dougle_bot.safety import BotError

LEGACY_KEYS = {"successful_reads", "available_kinds", "unknown_receipts", "unsupported_kinds"}


def failure_case(case):
    value, reads, options = output(), observed(), {}
    field = "verification.checks[0]"
    if case == "source_mismatch":
        reads = observed(args={"file_path": "/synthetic/Other.java"})
    elif case == "source_identity_unverified":
        value["sources"][0]["location"] = "https://example.test/main/Order.java"
    elif case == "line_range_mismatch":
        reads = observed(body="10→class Order {\n11→}")
        value["sources"][0]["location"] = "/synthetic/main/Order.java:12"
    elif case == "unknown_receipt":
        value["verification"]["checks"][0]["receipt"] = "SECRET-unobserved-id"
    elif case == "unsupported_kind":
        reads = observed(args={"file_path": "/synthetic/.dougle/topic.md"})
    elif case == "missing_check":
        value["verification"]["checks"] = []
        field = "verification.checks"
    elif case == "missing_conflicts_with_verified":
        value["verification"]["missing"] = ["code"]
        field = "verification.missing"
    elif case == "unresolved_answer":
        value["verification"].update(checks=[], missing=["code"])
        field = "verification.missing"
    elif case == "image_not_attached":
        value = output("knowledge", category="visual")
        value["verification"].update(
            required=["image"],
            checks=[{"kind": "image", "receipt": "image:FOLD", "source_index": 0}],
        )
        options["image_ids"] = {"FNEW"}
    elif case == "developer_evidence_missing":
        value = output("live")
        value["verification"]["checks"][0]["receipt"] = "developer_report"
    else:
        raise AssertionError(case)
    return value, reads, options, {"field": field, "reason": case}


@pytest.mark.parametrize(
    "reason",
    [
        "source_mismatch",
        "source_identity_unverified",
        "line_range_mismatch",
        "unknown_receipt",
        "unsupported_kind",
        "missing_check",
        "missing_conflicts_with_verified",
        "unresolved_answer",
        "image_not_attached",
        "developer_evidence_missing",
    ],
)
def test_real_failure_exposes_only_allowlisted_field_and_reason(reason):
    value, reads, options, issue = failure_case(reason)
    with pytest.raises(BotError, match="^required_read_not_verified$") as caught:
        validate_verification(value, reads, **options)
    assert caught.value.issues == [issue]
    assert safe_verification_diagnostics(caught.value.issues) == [issue]
    summary = failure_summary(value, reads, issues=caught.value.issues, attempt=2, run_id=36)
    assert summary["issues"] == [issue]
    assert safe_failure_summary(json.dumps(summary)) == summary
    assert not any(secret in json.dumps(summary) for secret in ("SECRET", "/synthetic", "read1"))


def test_source_failure_can_have_zero_old_counters_but_precise_new_reason():
    value, reads, options, issue = failure_case("source_mismatch")
    with pytest.raises(BotError) as caught:
        validate_verification(value, reads, **options)
    old = failure_summary(value, reads)
    assert old == {
        "successful_reads": 1,
        "available_kinds": ["code", "knowledge"],
        "unknown_receipts": 0,
        "unsupported_kinds": 0,
    }
    assert failure_summary(value, reads, issues=None) == old
    assert set(old) == LEGACY_KEYS
    new = failure_summary(value, reads, issues=caught.value.issues, attempt=1, run_id=36)
    assert new == {**old, "issues": [issue], "attempt": 1, "run_id": 36}


def test_failure_index_points_to_actual_failing_check():
    value, reads = output(), observed()
    value["verification"]["checks"].append(
        {"kind": "code", "receipt": "unknown", "source_index": 0}
    )
    with pytest.raises(BotError) as caught:
        validate_verification(value, reads)
    assert caught.value.issues == [{"field": "verification.checks[1]", "reason": "unknown_receipt"}]


def test_invalid_kind_remains_format_error_instead_of_unsupported_read():
    value = output()
    value["verification"]["checks"][0]["kind"] = "SECRET-invalid-kind"
    with pytest.raises(BotError, match="^invalid_verification$") as caught:
        validate_verification(value, observed())
    assert not getattr(caught.value, "issues", [])


@pytest.mark.parametrize("reverse", [False, True])
def test_code_local_remote_binding_is_unverified_in_both_directions(reverse):
    local, remote = "/synthetic/Order.java", "https://example.test/Order.java"
    reads = (
        observed("WebFetch", {"url": remote}) if reverse else observed(args={"file_path": local})
    )
    value = output(location=local if reverse else remote)
    with pytest.raises(BotError) as caught:
        validate_verification(value, reads)
    assert caught.value.issues[0]["reason"] == "source_identity_unverified"


def test_legacy_notes_and_legacy_plain_sets_keep_valid_knowledge_behavior():
    value = output("knowledge", "https://example.test/original")
    validate_verification(value, observed(args={"file_path": "/synthetic/.dougle/topic.md"}))
    validate_verification(output(), {"read1": {"code"}})
    validate_verification(output(location="Order.java:10-11"), observed(body="10→first\n11→last"))


@pytest.mark.parametrize("value", [None, True, False, 0, -1, 1.5, "2", {}, []])
def test_summary_does_not_emit_untrusted_attempt_or_run_id(value):
    summary = failure_summary(output(), observed(), attempt=value, run_id=value)
    assert set(summary) == LEGACY_KEYS
    assert safe_failure_summary(json.dumps(summary)) == summary


@pytest.mark.parametrize("field", ["attempt", "run_id"])
@pytest.mark.parametrize("value", [True, 0, -1, "2", None])
def test_status_parser_rejects_invalid_optional_identifiers(field, value):
    summary = failure_summary(output(), observed())
    summary[field] = value
    assert safe_failure_summary(json.dumps(summary)) is None


@pytest.mark.parametrize(
    "issue",
    [
        {"field": "verification.checks[0]", "reason": "SECRET"},
        {"field": "SECRET-path", "reason": "source_mismatch"},
        {"field": "verification.checks[30]", "reason": "source_mismatch"},
        {"field": "verification.required[4]", "reason": "missing_check"},
        {"field": "verification.checks[0]", "reason": "source_mismatch", "raw": "SECRET"},
        {"field": ["SECRET"], "reason": "source_mismatch"},
        "SECRET",
        None,
    ],
)
def test_summary_generation_filters_injection_and_status_rejects_unfiltered_issue(issue):
    good = {"field": "verification.checks[0]", "reason": "source_mismatch"}
    summary = failure_summary(output(), observed(), issues=[good, issue])
    assert summary["issues"] == [good]
    assert "SECRET" not in json.dumps(summary)
    assert safe_failure_summary(json.dumps(summary)) == summary
    summary["issues"].append(issue)
    assert safe_failure_summary(json.dumps(summary)) is None


def test_issues_are_bounded_and_optional_unknown_summary_keys_are_rejected():
    issue = {"field": "verification.checks", "reason": "missing_check"}
    summary = failure_summary(output(), observed(), issues=[issue] * 20)
    assert len(summary["issues"]) == 12
    assert safe_failure_summary(json.dumps(summary)) == summary
    assert safe_failure_summary(json.dumps({**summary, "raw": "SECRET"})) is None
    summary["issues"].append(issue)
    assert safe_failure_summary(json.dumps(summary)) is None


def test_status_accepts_old_and_new_summaries_and_omits_injected_payload(app):
    from dougle_bot.config import save
    from dougle_bot.main import status

    path = app.cfg.home / "config.toml"
    save(app.cfg, path)
    legacy = failure_summary(output(), observed())
    new = failure_summary(
        output(),
        observed(),
        attempt=2,
        run_id=36,
        issues=[{"field": "verification.checks[0]", "reason": "source_mismatch"}],
    )
    for summary in (legacy, new, {**new, "raw": "SECRET"}):
        app.store.audit(None, "system", "read_verification_failed", json.dumps(summary))
    failures = status(app.cfg.home, path)["recent_read_failures"]
    assert failures == [{"inquiry_id": None, **new}, {"inquiry_id": None, **legacy}]


def engine_result(value, reads):
    return EngineResult(SESSION, value, 0.01, SKILLS, reads)


def run_worker(app, results):
    app.engine.results = results
    ident = receive(app.store, app.cfg, event(text="<@UBOT> 실제 코드를 확인해 주세요."), "UBOT")
    app.worker.run_once()
    return app.store.inquiry(ident)


def prompt_payload(text):
    return json.loads(text.split("DATA_JSON_START\n", 1)[1].rsplit("\nDATA_JSON_END", 1)[0])


@pytest.mark.parametrize("fixed", [False, True])
def test_worker_retry_receives_exact_failure_reason_and_each_attempt_is_audited(app, fixed):
    value, reads, _, issue = failure_case("source_mismatch")
    row = run_worker(
        app,
        [
            engine_result(copy.deepcopy(value), reads),
            engine_result(copy.deepcopy(value), observed() if fixed else copy.deepcopy(reads)),
        ],
    )
    assert len(app.engine.calls) == 2
    payload = prompt_payload(app.engine.calls[1][0])
    assert payload["validation_correction"] == "required_read_not_verified"
    assert payload["validation_correction_detail"] == [issue]
    assert payload["required_evidence_from_previous_attempt"] == ["code"]
    assert "original_answer" not in payload  # Missing proof requires research, not record repair.
    audits = app.store.all(
        "SELECT detail FROM audit WHERE action='read_verification_failed' ORDER BY id"
    )
    summaries = [json.loads(audit["detail"]) for audit in audits]
    assert [summary["attempt"] for summary in summaries] == ([1] if fixed else [1, 2])
    run_ids = [run["id"] for run in app.store.all("SELECT id FROM engine_runs ORDER BY id")]
    assert [summary["run_id"] for summary in summaries] == run_ids[: len(summaries)]
    for summary in summaries:
        assert summary["issues"] == [issue]
        assert summary["unknown_receipts"] == summary["unsupported_kinds"] == 0
        assert safe_failure_summary(json.dumps(summary)) == summary
    assert row["status"] == ("awaiting_approval" if fixed else "failed")
    assert (app.store.current(row) is not None) is fixed


def test_failed_record_repair_summary_uses_original_reads_like_its_validator(app):
    first = output()
    first["sources"].append({"title": "다른 합성 코드", "location": "/synthetic/Other.java"})
    first["verification"]["required"] = ["code", "code"]
    repaired = copy.deepcopy(first)
    repaired["verification"]["required"] = ["code"]
    repaired["verification"]["checks"][0]["source_index"] = 1
    row = run_worker(app, [engine_result(first, observed()), engine_result(repaired, {})])
    assert row["status"] == "failed"
    assert "original_answer" in prompt_payload(app.engine.calls[1][0])
    audit = app.store.one("SELECT detail FROM audit WHERE action='read_verification_failed'")
    summary = json.loads(audit["detail"])
    assert summary["issues"] == [{"field": "verification.checks[0]", "reason": "source_mismatch"}]
    assert summary["successful_reads"] == 1
    assert summary["unknown_receipts"] == summary["unsupported_kinds"] == 0
