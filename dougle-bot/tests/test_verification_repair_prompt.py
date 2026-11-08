"""Prompt contract tests; worker eligibility and evidence checks remain separate."""

import copy
import json

import pytest
from test_answer_style import answer

from dougle_bot.evidence import safe_verification_diagnostics, verification_diagnostics
from dougle_bot.prompts import prompt
from dougle_bot.safety import BotError


def payload(text):
    return json.loads(text.split("DATA_JSON_START\n", 1)[1].rsplit("\nDATA_JSON_END", 1)[0])


def test_repair_carries_original_answer_receipts_floor_and_safe_details(app):
    original = answer()
    original["verification"]["required"].append("knowledge")
    before = copy.deepcopy(original)
    receipts = [{"receipt": "toolu_exact_Original-ID", "kinds": ["code", "knowledge"]}]
    diagnostics = verification_diagnostics(original)
    text = prompt(
        app.cfg.engine,
        {"question_text": "처리 조건을 알려 주세요.", "thread_key": "synthetic"},
        [],
        verification_repair=original,
        repair_receipts=receipts,
        required_evidence={"knowledge"},
        correction="invalid_verification",
        correction_detail=diagnostics,
    )
    data = payload(text)
    assert data["original_answer"] == original == before
    assert data["successful_read_receipts"] == receipts
    assert data["required_evidence_from_previous_attempt"] == ["knowledge"]
    assert data["validation_correction_detail"] == diagnostics
    assert data["validation_correction"] == "invalid_verification"
    assert data["question"] == "처리 조건을 알려 주세요."
    assert data["thread_key"] == "synthetic"
    instructions = text.split("DATA_JSON_START", 1)[0]
    for constraint in (
        "immediately preceding attempt of THIS inquiry",
        "via successful Skill calls during THIS call",
        "original verification has NOT passed validation",
        "Change ONLY verification",
        "Keep status, body, sources and lookup EXACTLY unchanged",
        "keep the original question_kind EXACTLY unchanged",
        "copying receipt IDs exactly",
        "using only their\nlisted kinds",
        "Live evidence cannot be reused",
        "Do not read new sources",
        "against those ORIGINAL read objects",
        "with the SAME required floor",
        "return the original answer unchanged",
    ):
        assert constraint in instructions
    assert "RESEARCH CONTRACT" not in instructions


@pytest.mark.parametrize(
    "options",
    [
        {"restored": True},
        {"required_evidence": {"knowledge", "live"}},
        {"presentation_repair": {}},
    ],
)
def test_prompt_rejects_incompatible_repair_modes(app, options):
    with pytest.raises(BotError, match="^invalid_verification_repair$"):
        prompt(
            app.cfg.engine,
            {"question_text": "질문입니다.", "thread_key": "synthetic"},
            [],
            verification_repair=answer(),
            **options,
        )


@pytest.mark.parametrize("bad", [None, "SECRET", {}, [None], ["SECRET"]])
def test_safe_detail_filter_accepts_only_structured_entries(bad):
    assert safe_verification_diagnostics(bad) == []


def test_detail_filter_allowlists_fields_reasons_and_indices_without_echoing_values():
    good = {"field": "verification.checks[29].source_index", "reason": "out_of_range"}
    bad = [
        {"field": "verification.checks[30].source_index", "reason": "out_of_range"},
        {"field": "verification.checks[00].source_index", "reason": "out_of_range"},
        {"field": "verification.required[4]", "reason": "invalid_enum"},
        {"field": "verification.SECRET", "reason": "invalid_type"},
        {"field": "verification", "reason": "SECRET"},
        {"field": "verification", "reason": "invalid_type", "value": "SECRET"},
        {"field": "verification", "reason": []},
        {"field": ["SECRET"], "reason": "invalid_type"},
    ]
    assert safe_verification_diagnostics([good, *bad]) == [good]
    filtered = safe_verification_diagnostics([good] * 30)
    assert len(filtered) == 12
    assert filtered[0] is not good


@pytest.mark.parametrize("repair", [False, True])
def test_both_prompt_paths_filter_correction_details(app, repair):
    good = {"field": "verification.required", "reason": "duplicate_items"}
    text = prompt(
        app.cfg.engine,
        {"question_text": "질문입니다.", "thread_key": "synthetic"},
        [],
        correction_detail=[good, {"field": "SECRET", "reason": "SECRET"}],
        verification_repair=answer() if repair else None,
    )
    assert "SECRET" not in text
    assert payload(text)["validation_correction_detail"] == [good]


def test_filter_preserves_generated_field_diagnostics():
    value = answer()
    value["verification"].update(required=["unknown"], missing=["other"], question_kind=None)
    value["verification"]["checks"] = [{"kind": "knowledge", "receipt": "", "source_index": True}]
    diagnostics = verification_diagnostics(value)
    assert safe_verification_diagnostics(diagnostics) == diagnostics
