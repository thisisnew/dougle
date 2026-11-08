"""Acceptance cases for exact remote filename discovery; no real gh or model calls."""

import copy
import io
import json
from types import SimpleNamespace

import pytest
from test_answer_style import answer
from test_github_reader import COMMIT, REF, REPO, ROOT, FakeRunner, fixture_reader

from dougle_bot.config import EngineConfig
from dougle_bot.engine import ClaudeCliEngine
from dougle_bot.evidence import read_kinds, read_receipts, validate_verification
from dougle_bot.github_reader import GitHubReader, ReaderError, dispatch, serve
from dougle_bot.receipt_hook import context
from dougle_bot.safety import BotError

RESOLUTION = {"sha": COMMIT, "commit": {"tree": {"sha": ROOT}}}
TREE_ENDPOINT = f"repos/{REPO}/git/trees/{ROOT}?recursive=1"


def entry(path, mode="100644", kind="blob"):
    return {"path": path, "mode": mode, "type": kind, "sha": "d" * 40}


def finder(entries=()):
    runner = FakeRunner([RESOLUTION, {"truncated": False, "tree": list(entries)}])
    return GitHubReader({(REPO, REF)}, runner), runner


def decode(result):
    return json.loads(result["content"][0]["text"])


def test_filename_only_discovery_then_actual_read_at_the_discovered_commit():
    reader, runner, _ = fixture_reader()
    read_replies = copy.deepcopy(runner.replies)
    discovered = copy.deepcopy(read_replies[2]["tree"][0])
    discovered["path"] = "src/code.py"
    runner.replies = [RESOLUTION, {"truncated": False, "tree": [discovered]}, *read_replies]
    paths = reader.find_files(REPO, REF, "code.py")
    assert paths["kind"] == "file_paths"
    assert paths["paths"] == ["src/code.py"] and paths["complete"] is True
    assert "content" not in paths
    result = reader.read_file(REPO, REF, paths["paths"][0], expected_commit=paths["commit"])
    assert result["content"] == "remote code\n"
    assert result["commit"] == paths["commit"] == COMMIT
    assert result["canonical_url"] == f"https://github.com/{REPO}/blob/{COMMIT}/src/code.py"
    assert runner.calls[1][0][-1] == TREE_ENDPOINT
    assert sum("/commits/" in call[0][-1] for call in runner.calls) == 2


def test_duplicate_basenames_return_all_regular_candidates_without_selecting_one():
    reader, _ = finder(
        [
            entry("module-b/Order.java"),
            entry("module-a/Order.java", "100755"),
            entry("module-a/Order.java"),
            entry("links/Order.java", "120000"),
            entry("Order.java", "040000", "tree"),
            entry("prefixOrder.java"),
            entry("order.java"),
        ]
    )
    result = reader.find_files(REPO, REF, "Order.java")
    assert result["paths"] == ["module-a/Order.java", "module-b/Order.java"]
    assert "path" not in result and "content" not in result


def test_complete_empty_search_is_not_access_failure():
    reader, _ = finder([entry("src/Other.java")])
    result = dispatch(
        reader,
        "tools/call",
        {
            "name": "find_files",
            "arguments": {"repository": REPO, "ref": REF, "filename": "Order.java"},
        },
    )
    assert not result.get("isError")
    assert decode(result)["paths"] == [] and decode(result)["complete"] is True


@pytest.mark.parametrize("truncated", [True, None, "false"])
def test_incomplete_tree_is_error_never_absence_or_cached(truncated):
    reader, runner = finder()
    runner.replies[1]["truncated"] = truncated
    result = dispatch(
        reader,
        "tools/call",
        {
            "name": "find_files",
            "arguments": {"repository": REPO, "ref": REF, "filename": "Order.java"},
        },
    )
    assert result["isError"] is True
    assert decode(result) == {"error": "incomplete_github_tree"}
    assert TREE_ENDPOINT not in reader._cache


def test_response_limit_is_error_not_empty_search():
    reader, runner = finder()
    runner.replies[1] = SimpleNamespace(
        returncode=0, stdout=b"x" * (8 * 1024 * 1024 + 1), stderr=b""
    )
    with pytest.raises(ReaderError, match="^github_response_too_large$"):
        reader.find_files(REPO, REF, "Order.java")
    assert TREE_ENDPOINT not in reader._cache


def test_excess_candidates_fail_without_silently_cutting_results():
    reader, _ = finder(entry(f"module-{index}/Order.java") for index in range(101))
    with pytest.raises(ReaderError, match="^too_many_file_matches$"):
        reader.find_files(REPO, REF, "Order.java")
    assert TREE_ENDPOINT not in reader._cache


@pytest.mark.parametrize(
    "repository,ref", [("other/repo", REF), (REPO, "other"), (REPO, "../main")]
)
def test_unregistered_discovery_never_queries_github(repository, ref):
    reader, runner = finder()
    with pytest.raises(ReaderError, match="^source_not_allowed$"):
        reader.find_files(repository, ref, "Order.java")
    assert runner.calls == []


@pytest.mark.parametrize(
    "filename",
    ["", ".", "..", "src/Order.java", "src\\Order.java", "Order.java\n", "x" * 256, None],
)
def test_invalid_filename_fails_before_remote_query(filename):
    reader, runner = finder()
    with pytest.raises(ReaderError, match="^invalid_filename$"):
        reader.find_files(REPO, REF, filename)
    assert runner.calls == []


@pytest.mark.parametrize("operation", ["find_files", "read_file"])
def test_moved_ref_blocks_read_or_repeated_search_before_cached_tree_use(operation):
    reader, runner = finder([entry("src/Order.java")])
    discovered = reader.find_files(REPO, REF, "Order.java")
    runner.replies.append({"sha": "e" * 40, "commit": {"tree": {"sha": ROOT}}})
    target = "Order.java" if operation == "find_files" else discovered["paths"][0]
    with pytest.raises(ReaderError, match="^github_ref_changed$"):
        getattr(reader, operation)(REPO, REF, target, expected_commit=discovered["commit"])
    assert len(runner.calls) == 3
    assert "/commits/" in runner.calls[-1][0][-1]


def test_repeated_filenames_recheck_ref_but_reuse_immutable_recursive_tree():
    reader, runner = finder([entry("src/Order.java"), entry("src/Other.java")])
    runner.replies.extend([RESOLUTION, RESOLUTION])
    first = reader.find_files(REPO, REF, "Order.java")
    first["paths"].append("forged/Other.java")
    second = reader.find_files(REPO, REF, "Other.java", expected_commit=COMMIT)
    third = reader.find_files(REPO, REF, "Order.java")
    assert second["paths"] == ["src/Other.java"]
    assert third["paths"] == ["src/Order.java"]
    endpoints = [call[0][-1] for call in runner.calls]
    assert endpoints.count(TREE_ENDPOINT) == 1
    assert sum("/commits/" in endpoint for endpoint in endpoints) == 3


def test_changed_tree_is_fetched_and_search_cache_does_not_cross_reader_instances():
    reader, runner = finder([entry("old/Order.java")])
    reader.find_files(REPO, REF, "Order.java")
    new_tree = "f" * 40
    runner.replies.extend(
        [
            {"sha": "e" * 40, "commit": {"tree": {"sha": new_tree}}},
            {"truncated": False, "tree": [entry("new/Order.java")]},
        ]
    )
    assert reader.find_files(REPO, REF, "Order.java")["paths"] == ["new/Order.java"]
    assert runner.calls[-1][0][-1] == f"repos/{REPO}/git/trees/{new_tree}?recursive=1"
    other, other_runner = finder([entry("old/Order.java")])
    other.find_files(REPO, REF, "Order.java")
    assert len(other_runner.calls) == 2


def test_discovered_paths_issue_no_code_receipt_or_code_proof():
    reader, _ = finder([entry("src/Order.java")])
    args = {"repository": REPO, "ref": REF, "filename": "Order.java"}
    body = dispatch(reader, "tools/call", {"name": "find_files", "arguments": args})
    name = "mcp__dougle_source__find_files"
    assert "code" not in read_kinds(name, args)
    assert (
        context(
            {
                "hook_event_name": "PostToolUse",
                "tool_name": name,
                "tool_input": args,
                "tool_response": body,
                "tool_use_id": "find1",
            }
        )
        == {}
    )
    reads = read_receipts(
        "\n".join(
            map(
                json.dumps,
                [
                    {
                        "type": "assistant",
                        "message": {
                            "content": [
                                {"type": "tool_use", "name": name, "id": "find1", "input": args}
                            ]
                        },
                    },
                    {
                        "type": "user",
                        "message": {
                            "content": [
                                {"type": "tool_result", "tool_use_id": "find1", "content": body}
                            ]
                        },
                    },
                ],
            )
        )
    )
    assert reads == {}
    value = answer()
    value["verification"].update(
        question_kind="implementation",
        required=["code"],
        checks=[
            {"kind": "code", "receipt": "find1", "source_index": 0},
        ],
    )
    with pytest.raises(BotError, match="^required_read_not_verified$"):
        validate_verification(value, reads)


@pytest.mark.parametrize(
    "args",
    [
        {"repository": REPO, "ref": REF},
        {"repository": REPO, "ref": REF, "filename": 1},
        {"repository": REPO, "ref": REF, "filename": "Order.java", "command": "SECRET"},
    ],
)
def test_dispatch_rejects_invalid_discovery_arguments_without_query(args):
    reader, runner = finder()
    result = dispatch(reader, "tools/call", {"name": "find_files", "arguments": args})
    assert result["isError"] and decode(result) == {"error": "invalid_arguments"}
    assert runner.calls == []


def test_stdio_dispatch_advertises_and_executes_filename_discovery():
    reader, runner = finder([entry("src/Order.java")])
    requests = [
        {"jsonrpc": "2.0", "id": 1, "method": "tools/list"},
        {
            "jsonrpc": "2.0",
            "id": 2,
            "method": "tools/call",
            "params": {
                "name": "find_files",
                "arguments": {
                    "repository": REPO,
                    "ref": REF,
                    "filename": "Order.java",
                    "expected_commit": COMMIT,
                },
            },
        },
    ]
    stream = io.StringIO()
    serve(reader, io.StringIO("\n".join(map(json.dumps, requests)) + "\n"), stream)
    replies = list(map(json.loads, stream.getvalue().splitlines()))
    tool = next(tool for tool in replies[0]["result"]["tools"] if tool["name"] == "find_files")
    assert tool["inputSchema"]["required"] == ["repository", "ref", "filename"]
    assert tool["annotations"]["readOnlyHint"] is True
    assert decode(replies[1]["result"])["paths"] == ["src/Order.java"]
    assert len(runner.calls) == 2


def test_engine_allows_discovery_but_keeps_nested_agents_denied(tmp_path):
    command = ClaudeCliEngine(EngineConfig(), tmp_path).command(None, 1, tmp_path / "settings.json")
    assert "mcp__dougle_source__find_files" in command[command.index("--allowedTools") + 1].split(
        ","
    )
    assert {"Agent", "Task"} <= set(command[command.index("--disallowedTools") + 1].split(","))
