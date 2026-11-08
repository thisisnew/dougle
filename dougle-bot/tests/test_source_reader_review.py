"""Independent GitHub reader/registry review; synthetic responses and temp registries only."""

import json

import pytest
from test_evidence_binding import observed, output
from test_github_reader import COMMIT, REF, REPO, fixture_reader

from dougle_bot.evidence import validate_verification
from dougle_bot.github_reader import GitHubReader, ReaderError
from dougle_bot.prompts import validate_output
from dougle_bot.receipt_hook import context
from dougle_bot.safety import BotError
from dougle_bot.source_registry import discover_github_sources


def github_receipt():
    reader, _, _ = fixture_reader(raw=b"allowed = True\n")
    args = {"repository": REPO, "ref": REF, "path": "src/code.py", "expected_commit": COMMIT}
    value = reader.read_file(**args)
    body = {"content": [{"type": "text", "text": json.dumps(value)}]}
    reads = observed("mcp__dougle_source__read_file", args, body)
    assert reads["read1"] == {"knowledge", "code"}
    return value, reads, args, body


@pytest.mark.parametrize("state", ["disconnected", "pending_access"])
def test_authoritative_inactive_source_with_root_and_ref_cannot_fall_back_to_index(tmp_path, state):
    (tmp_path / "index.md").write_text(f"Location: https://github.com/{REPO}\n")
    (tmp_path / "sources").mkdir()
    (tmp_path / "sources/service.md").write_text(
        f"Location: https://github.com/{REPO}\n"
        f"Canonical root: https://github.com/{REPO}/tree/main\n"
        f"State: {state}\n"
    )
    sources = discover_github_sources(tmp_path)
    assert not sources


@pytest.mark.parametrize("citation", ["OrderService", "GitHub source"])
def test_owned_github_code_receipt_requires_comparable_canonical_source(citation):
    _, reads, _, _ = github_receipt()
    answer = validate_output(output(location=citation))
    with pytest.raises(BotError, match="required_read_not_verified"):
        validate_verification(answer, reads)


def test_owned_github_full_file_receipt_rejects_lines_beyond_returned_content():
    value, reads, _, _ = github_receipt()
    validate_verification(output(location=value["canonical_url"] + "#L1"), reads)
    answer = validate_output(output(location=value["canonical_url"] + "#L999-L1000"))
    with pytest.raises(BotError, match="required_read_not_verified"):
        validate_verification(answer, reads)


def test_owned_github_reminder_and_verifier_use_same_pinned_read():
    value, reads, args, body = github_receipt()
    reminder = context({
        "hook_event_name": "PostToolUse", "tool_name": "mcp__dougle_source__read_file",
        "tool_input": args, "tool_response": body, "tool_use_id": "read1",
    })
    assert reminder
    assert value["content"] not in json.dumps(reminder)
    validate_verification(output(location=value["canonical_url"]), reads)
    with pytest.raises(BotError, match="required_read_not_verified"):
        validate_verification(output(location=value["requested_ref_url"]), reads)


def test_unregistered_ref_fails_before_any_runner_call():
    def forbidden(*args, **kwargs):
        raise AssertionError("an unregistered ref must not execute gh")

    reader = GitHubReader({(REPO, REF)}, runner=forbidden)
    with pytest.raises(ReaderError, match="source_not_allowed"):
        reader.read_file(REPO, "unregistered", "src/code.py")
