"""Status-only failure diagnostics using synthetic rows and fake engine results.

No live Slack, model, Keychain or user knowledge. Production status code was
implemented before these regression tests.
"""

import json

import pytest
from test_answer_style import READS, SESSION, SKILLS, answer
from test_runtime import event

from dougle_bot import main
from dougle_bot.config import save
from dougle_bot.engine import EngineResult
from dougle_bot.intake import receive

BASE = dict(
    successful_reads=2, available_kinds=["knowledge"], unknown_receipts=1, unsupported_kinds=0
)


@pytest.fixture
def inspect_status(app, monkeypatch):
    path = app.cfg.home / "config.toml"
    save(app.cfg, path)

    def forbidden(*args, **kwargs):
        pytest.fail("status must not contact an engine, Slack or Keychain")

    for name in ("doctor", "serve", "BoltSlackGateway", "KeychainSecretStore"):
        monkeypatch.setattr(main, name, forbidden)
    return lambda: main.status(app.cfg.home, path)


def inquiry(app, number=1, state="failed", error="required_read_not_verified"):
    ident = receive(app.store, app.cfg, event(ts=f"1001.{number:06d}"), "UBOT")
    with app.store.tx() as db:
        db.execute("UPDATE inquiries SET status=?,error=? WHERE id=?", (state, error, ident))
    return ident


def read_failure(app, ident, detail):
    # Insert hostile fixtures verbatim, bypassing audit redaction to test the reader boundary.
    with app.store.tx() as db:
        db.execute(
            "INSERT INTO audit(inquiry_id,actor,action,detail,at) VALUES(?,'system','read_verification_failed',?,?)",
            (ident, detail if isinstance(detail, str) else json.dumps(detail), app.now[0]),
        )


def test_recent_inquiry_failures_are_bounded_newest_inquiry_first_with_only_safe_fields(
    app, inspect_status
):
    ids = [inquiry(app, n) for n in range(1, 16)]
    with app.store.tx() as db:
        db.execute(
            "UPDATE inquiries SET question_text='SELECT private_rows FROM /private/source.sql'"
        )
    rows = inspect_status()["recent_inquiry_failures"]
    assert rows == [
        {"inquiry_id": ident, "status": "failed", "error": "required_read_not_verified"}
        for ident in reversed(ids[-10:])
    ]
    assert "private_rows" not in json.dumps(inspect_status())
    assert "/private/source.sql" not in json.dumps(inspect_status())


@pytest.mark.parametrize(
    "code",
    [
        "required_read_not_verified",
        "invalid_verification",
        "verification_requirements_missing",
        "engine_timeout",
        "engine_failed",
        "required_skill_not_activated",
        "image_access_denied",
        "daily_budget_exceeded",
        "sensitive_content",
        "delivery_uncertain",
        "card_delivery_failed",
        "missing_scope",
        "invalid_auth",
        "automatic_reply_interrupted",
        "repair_changed_evidence",
    ],
)
def test_status_retains_known_final_failure_codes(app, inspect_status, code):
    ident = inquiry(app, error=code)
    assert inspect_status()["recent_inquiry_failures"] == [
        {"inquiry_id": ident, "status": "failed", "error": code}
    ]


@pytest.mark.parametrize(
    "raw",
    [
        "xoxb-synthetic-private",
        "/private/customer/Source.java",
        "SELECT password FROM private_rows",
        "private_customer_name",
        "engine_timeout\n/private/source.sql",
        "",
        "x" * 100_000,
        b"engine_timeout",
    ],
)
def test_status_never_echoes_arbitrary_persisted_error_even_if_snake_case(app, inspect_status, raw):
    ident = inquiry(app, error=raw)
    assert inspect_status()["recent_inquiry_failures"] == [
        {"inquiry_id": ident, "status": "failed", "error": "operation_failed"}
    ]


@pytest.mark.parametrize(
    "state,error,shown",
    [
        ("failed", None, True),
        ("post_unknown", "delivery_uncertain", True),
        ("needs_lookup", "card_delivery_failed", True),
        ("awaiting_approval", "missing_scope", True),
        ("needs_lookup", None, False),
        ("awaiting_approval", None, False),
        ("posted", "session_restored_from_thread", False),
        ("cancelled", None, False),
        ("drafting", None, False),
        ("queued", None, False),
    ],
)
def test_failure_statuses_and_missing_legacy_codes_are_not_confused_with_success(
    app, inspect_status, state, error, shown
):
    ident = inquiry(app, state=state, error=error)
    assert inspect_status()["recent_inquiry_failures"] == (
        [{"inquiry_id": ident, "status": state, "error": error}] if shown else []
    )


def test_old_read_summary_and_new_attempt_diagnostics_coexist_without_invented_metadata(
    app, inspect_status
):
    ident = inquiry(app)
    read_failure(app, None, BASE)
    expanded = {
        **BASE,
        "issues": [{"field": "verification.checks[0].receipt", "reason": "unknown_receipt"}],
        "attempt": 2,
        "run_id": 36,
    }
    read_failure(app, ident, expanded)
    assert inspect_status()["recent_read_failures"] == [
        {"inquiry_id": ident, **expanded},
        {"inquiry_id": None, **BASE},
    ]


@pytest.mark.parametrize(
    "extras",
    [
        {"attempt": 1},
        {"run_id": 36},
        {"issues": []},
        {
            "issues": [
                {"field": "verification.checks[0].source_index", "reason": "line_range_mismatch"}
            ]
        },
        {"issues": [{"field": "verification.checks[0]", "reason": "source_identity_unverified"}]},
        {"issues": [{"field": "verification.checks[0]", "reason": "live_freshness_unverified"}]},
    ],
)
def test_each_optional_read_failure_field_is_supported_independently(app, inspect_status, extras):
    ident = inquiry(app)
    read_failure(app, ident, {**BASE, **extras})
    assert inspect_status()["recent_read_failures"] == [{"inquiry_id": ident, **BASE, **extras}]


@pytest.mark.parametrize(
    "extras",
    [
        {"attempt": True},
        {"attempt": 0},
        {"run_id": -1},
        {"run_id": "SELECT private_rows"},
        {"issues": [{"path": "/private/source.sql", "reason": "unknown_receipt"}]},
        {"issues": [{"field": "/private/source.sql", "reason": "source_mismatch"}]},
        {"issues": [{"field": "verification.checks[0].receipt", "reason": "xoxb-private"}]},
        {"source_path": "/private/source.sql"},
        {"query_rows": ["private customer"]},
    ],
)
def test_read_failure_optional_metadata_cannot_smuggle_content(app, inspect_status, extras):
    ident = inquiry(app)
    read_failure(app, ident, {**BASE, **extras})
    assert inspect_status()["recent_read_failures"] == []


def test_read_failures_keep_five_latest_records_and_scrub_invalid_inquiry_identity(
    app, inspect_status
):
    for n in range(1, 8):
        read_failure(app, n, {**BASE, "run_id": n, "attempt": 1})
    rows = inspect_status()["recent_read_failures"]
    assert [row["run_id"] for row in rows] == [7, 6, 5, 4, 3]
    read_failure(app, "/private/source.sql", BASE)
    row = inspect_status()["recent_read_failures"][0]
    assert row == {"inquiry_id": None, **BASE}


@pytest.mark.parametrize("detail", ["{", "null", "[]", '{"private":"' + "x" * 20_000 + '"}'])
def test_malformed_or_oversized_audit_rows_are_omitted(app, inspect_status, detail):
    read_failure(app, None, detail)
    assert inspect_status()["recent_read_failures"] == []


def test_status_failure_diagnostics_are_read_only_and_do_not_create_runs(app, inspect_status):
    ident = inquiry(app)
    read_failure(app, ident, {**BASE, "attempt": 1, "run_id": 36})
    tables = ("inquiries", "jobs", "engine_runs", "audit", "control")
    before = {table: app.store.all(f"SELECT * FROM {table}") for table in tables}
    schema = app.store.all("SELECT name,sql FROM sqlite_master ORDER BY name")
    assert inspect_status()["recent_inquiry_failures"]
    assert inspect_status()["recent_read_failures"]
    assert before == {table: app.store.all(f"SELECT * FROM {table}") for table in tables}
    assert schema == app.store.all("SELECT name,sql FROM sqlite_master ORDER BY name")
    assert app.engine.calls == [] and app.slack.calls == []


def test_final_failure_code_is_distinct_from_earlier_read_failure_attempt(app, inspect_status):
    app.engine.results = [
        EngineResult(SESSION, answer(), 0.02, SKILLS, {}),
        EngineResult(SESSION, answer(), 0.02, set(), READS),
    ]
    ident = receive(app.store, app.cfg, event(), "UBOT")
    assert app.worker.run_once()
    state = inspect_status()
    assert state["recent_inquiry_failures"] == [
        {
            "inquiry_id": ident,
            "status": "failed",
            "error": "required_skill_not_activated",
        }
    ]
    runs = app.store.all("SELECT id FROM engine_runs WHERE inquiry_id=? ORDER BY id", (ident,))
    assert len(runs) == 2
    assert state["recent_read_failures"] == [
        {
            "inquiry_id": ident,
            "successful_reads": 0,
            "available_kinds": [],
            "unknown_receipts": 1,
            "unsupported_kinds": 0,
            "issues": [{"field": "verification.checks[0]", "reason": "unknown_receipt"}],
            "attempt": 1,
            "run_id": runs[0]["id"],
        }
    ]
    assert not [call for call in app.slack.calls if call[0] == "post"]
