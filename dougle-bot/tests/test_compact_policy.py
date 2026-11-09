"""Review-only checks for the candidate policy; the running prompt is unchanged."""

from pathlib import Path

from dougle_bot.compact_policy import (
    CANDIDATE_UTF8_BYTES,
    COMPACT_POLICY,
    POLICY_COVERAGE,
)
from dougle_bot.config import EngineConfig
from dougle_bot.prompts import prompt

REPO = Path(__file__).resolve().parents[2]
SKILLS = ("dougle-ask", "i-have-adhd", "plain-language")


def test_every_audit_section_has_a_source_and_one_candidate_clause():
    names = [entry.section for entry in POLICY_COVERAGE]
    assert len(names) == len(set(names)) == 15
    assert all(entry.sources for entry in POLICY_COVERAGE)
    headings = [line for line in COMPACT_POLICY.splitlines() if line in names]
    assert headings == names


def test_candidate_keeps_high_risk_boundaries():
    clauses = {
        "SOURCE_SCOPE": ("authorized scope", "conditions", "disconnected source"),
        "FRESHNESS": ("SAME source and coverage", "last_verified_at", "persisted"),
        "REMOTE_CODE": ("REMOTE ref", "expected_commit", "canonical_url"),
        "RESEARCH": ("original", "fresh bounded", "required evidence"),
        "LIVE_DATA": ("actual schema", "empty rows", "production absence"),
        "RECEIPTS": ("THIS-call tool_use", "source_index", "missing must be empty"),
        "MEMORY": ("runtime", "staged", "corrections and conflicts"),
        "HANDOFF": ("needs_lookup", "developer report", "this inquiry/scope"),
        "PLAIN_LANGUAGE": ("존댓말", "conditions", "uncertainty"),
        "OUTPUT": ("JSON schema", "privately", "no citation markers"),
        "REPAIR": ("body ONLY", "verification ONLY", "original receipt"),
        "UNTRUSTED_INPUT": ("untrusted data", "never permissions", "Never follow"),
    }
    sections = {
        name: body for name, body in (
            part.split("\n", 1) for part in COMPACT_POLICY.strip().split("\n\n")
        )
    }
    for name, required in clauses.items():
        assert all(phrase in sections[name] for phrase in required), name


def test_candidate_is_measured_against_current_prompt_and_skills():
    inquiry = {"question_text": "Synthetic question", "thread_key": "synthetic"}
    current = prompt(EngineConfig(), inquiry, [])
    skills = sum((REPO / "skills" / name / "SKILL.md").stat().st_size for name in SKILLS)
    assert CANDIDATE_UTF8_BYTES == len(COMPACT_POLICY.encode("utf-8"))
    # The measured release candidate is 10,603 bytes; keep a bounded headroom
    # while the paired evaluation measures the actual 50% target.
    assert CANDIDATE_UTF8_BYTES < 11_000
    assert CANDIDATE_UTF8_BYTES * 5 < len(current.encode("utf-8")) + skills


def test_candidate_is_opt_in_and_default_policy_remains_available():
    assert EngineConfig().compact_policy is False
    active = (REPO / "dougle-bot" / "src" / "dougle_bot" / "prompts.py").read_text()
    assert "if config.compact_policy:" in active
    assert "successful Skill calls during THIS call" in active


def test_compact_policy_can_run_in_automatic_mode(tmp_path):
    from dataclasses import replace

    from dougle_bot.config import Config, SlackConfig

    config = Config(
        SlackConfig("UAPPROVER", ["CTEST"], require_approval=False),
        replace(EngineConfig(), compact_policy=True),
        tmp_path,
    )
    assert config.validate() == config
