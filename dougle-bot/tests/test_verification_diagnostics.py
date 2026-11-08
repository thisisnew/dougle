"""Format repair diagnostics are synthetic and never stand in for successful reads."""

import copy
import json
import re

import pytest
from test_answer_style import answer

from dougle_bot.evidence import (
    VERIFICATION_SCHEMA,
    validate_verification,
    verification_diagnostics,
)
from dougle_bot.prompts import OUTPUT_SCHEMA, prompt, validate_output
from dougle_bot.safety import BotError


@pytest.mark.parametrize(
    "change,field,reason",
    [
        ({"question_kind": "SECRET"}, "question_kind", "invalid_enum"),
        ({"question_kind": []}, "question_kind", "invalid_enum"),
        ({"required": "knowledge"}, "required", "invalid_type"),
        ({"required": ["knowledge", "knowledge"]}, "required", "duplicate_items"),
        ({"required": ["knowledge"] * 5}, "required", "too_many_items"),
        ({"required": [{}]}, "required[0]", "invalid_enum"),
        ({"missing": None}, "missing", "invalid_type"),
        ({"missing": ["knowledge", "knowledge"]}, "missing", "duplicate_items"),
        ({"missing": ["SECRET"]}, "missing[0]", "invalid_enum"),
        ({"checks": {}}, "checks", "invalid_type"),
        ({"checks": [None]}, "checks[0]", "invalid_type"),
        ({"checks": [None] * 31}, "checks", "too_many_items"),
    ],
)
def test_invalid_contract_field_is_identified_without_weakening_validation(change, field, reason):
    value = answer()
    value["verification"].update(change)
    original = copy.deepcopy(value)
    assert {"field": f"verification.{field}", "reason": reason} in verification_diagnostics(value)
    assert value == original
    with pytest.raises(BotError, match="^invalid_verification$"):
        validate_verification(value, {"read1": {"knowledge"}})


@pytest.mark.parametrize(
    "change,field,reason",
    [
        ({"kind": []}, "kind", "invalid_enum"),
        ({"kind": "SECRET"}, "kind", "invalid_enum"),
        ({"kind": "code"}, "kind", "not_required"),
        ({"receipt": None}, "receipt", "invalid_type"),
        ({"receipt": ""}, "receipt", "invalid_length"),
        ({"receipt": "SECRET" * 40}, "receipt", "invalid_length"),
        ({"source_index": True}, "source_index", "invalid_type"),
        ({"source_index": "0"}, "source_index", "invalid_type"),
        ({"source_index": 0.0}, "source_index", "invalid_type"),
        ({"source_index": -1}, "source_index", "out_of_range"),
        ({"source_index": 1}, "source_index", "out_of_range"),
        ({"source_index": 30}, "source_index", "out_of_range"),
    ],
)
def test_invalid_check_field_is_identified(change, field, reason):
    value = answer()
    value["verification"]["checks"][0].update(change)
    assert verification_diagnostics(value) == [
        {"field": f"verification.checks[0].{field}", "reason": reason}
    ]
    with pytest.raises(BotError, match="^invalid_verification$"):
        validate_verification(value, {"read1": {"knowledge", "code"}})


@pytest.mark.parametrize("field", VERIFICATION_SCHEMA["required"])
def test_missing_verification_fields_are_named(field):
    value = answer()
    del value["verification"][field]
    assert {"field": f"verification.{field}", "reason": "missing_field"} in (
        verification_diagnostics(value)
    )
    with pytest.raises(BotError, match="^invalid_verification$"):
        validate_verification(value, {"read1": {"knowledge"}})


@pytest.mark.parametrize("field", ["kind", "receipt", "source_index"])
def test_missing_check_fields_are_named(field):
    value = answer()
    del value["verification"]["checks"][0][field]
    assert verification_diagnostics(value) == [
        {"field": f"verification.checks[0].{field}", "reason": "missing_field"}
    ]
    with pytest.raises(BotError, match="^invalid_verification$"):
        validate_verification(value, {"read1": {"knowledge"}})


@pytest.mark.parametrize("value", [None, [], "SECRET", {"verification": None}])
def test_malformed_output_diagnostics_are_safe(value):
    assert verification_diagnostics(value) == [{"field": "verification", "reason": "invalid_type"}]


def test_absent_verification_is_distinguished_from_wrong_type():
    assert verification_diagnostics({}) == [{"field": "verification", "reason": "missing_field"}]


def test_diagnostics_are_bounded_and_never_echo_secret_keys_or_values():
    value = answer("SECRET body")
    value["sources"] = [{"title": "SECRET title", "location": "/SECRET/path"}]
    value["verification"]["SECRET extra field"] = "SECRET"
    value["verification"]["checks"] = [
        {"kind": "SECRET", "receipt": "SECRET" * 50, "source_index": "SECRET", "SECRET": True}
        for _ in range(100)
    ]
    diagnostics = verification_diagnostics(value)
    assert len(diagnostics) == 12
    assert "SECRET" not in json.dumps(diagnostics)
    assert {"field": "verification", "reason": "unexpected_fields"} in diagnostics
    assert {"field": "verification.checks[0]", "reason": "unexpected_fields"} in diagnostics
    for item in diagnostics:
        assert set(item) == {"field", "reason"}
        assert re.fullmatch(
            r"verification(?:\.(?:question_kind|required|missing|checks))?"
            r"(?:\[\d{1,2}\])?(?:\.(?:kind|receipt|source_index))?",
            item["field"],
        )


def test_unique_kind_schema_matches_validator_but_multiple_reads_remain_valid():
    schema = OUTPUT_SCHEMA["properties"]["verification"]
    assert schema is VERIFICATION_SCHEMA
    assert schema["properties"]["required"]["uniqueItems"] is True
    assert schema["properties"]["missing"]["uniqueItems"] is True
    value = answer()
    value["verification"]["checks"].append(
        {"kind": "knowledge", "receipt": "read2", "source_index": 0}
    )
    assert verification_diagnostics(value) == []
    validate_output(value)
    assert validate_verification(value, {"read1": {"knowledge"}, "read2": {"knowledge"}}) == {
        "knowledge"
    }


def test_format_correction_never_creates_missing_read_proof():
    value = answer()
    value["verification"]["required"].append("knowledge")
    assert verification_diagnostics(value)
    with pytest.raises(BotError, match="^invalid_verification$"):
        validate_verification(value, {})
    value["verification"]["required"] = ["knowledge"]
    assert verification_diagnostics(value) == []
    with pytest.raises(BotError, match="^required_read_not_verified$"):
        validate_verification(value, {})
    assert validate_verification(value, {"read1": {"knowledge"}}) == {"knowledge"}


def test_clean_format_does_not_bypass_obligation_floor():
    value = answer()
    assert verification_diagnostics(value) == []
    with pytest.raises(BotError, match="^verification_requirements_missing$"):
        validate_verification(value, {"read1": {"knowledge"}}, required_floor={"code"})


def test_unresolved_contract_remains_valid_without_fabricated_check():
    value = answer()
    value["status"] = "cannot_answer"
    value["sources"] = []
    value["verification"].update(checks=[], missing=["knowledge"])
    assert verification_diagnostics(value) == []
    validate_output(value)
    assert validate_verification(value, {}) == {"knowledge"}


def test_research_prompt_explains_format_without_inviting_proof_fabrication(app):
    text = prompt(
        app.cfg.engine,
        {"question_text": "절차를 알려 주세요.", "thread_key": "synthetic"},
        [],
        correction="invalid_verification",
    )
    assert "required and missing are unique lists" in text
    assert "checks may contain multiple reads of the same kind" in text
    assert "integer from 0 to len(sources)-1" in text
    assert "preserving research requirements; never invent receipts" in text
