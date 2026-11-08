import base64
import hashlib
import io
import json
import subprocess
from types import SimpleNamespace

import pytest

from dougle_bot.github_reader import (
    MAX_FILE,
    GitHubReader,
    ReaderError,
    dispatch,
    load_sources,
    serve,
)

COMMIT = "a" * 40
ROOT = "b" * 40
SUBTREE = "c" * 40
REPO = "example/project"
REF = "feature/registered"


class FakeRunner:
    def __init__(self, replies):
        self.replies = list(replies)
        self.calls = []

    def __call__(self, argv, **kwargs):
        self.calls.append((argv, kwargs))
        assert argv[:7] == ["gh", "api", "--hostname", "github.com", "--method", "GET", argv[6]]
        assert len(argv) == 7
        assert kwargs == {
            "stdin": subprocess.DEVNULL,
            "stdout": subprocess.PIPE,
            "stderr": subprocess.PIPE,
            "timeout": 20,
            "shell": False,
        }
        reply = self.replies.pop(0)
        if isinstance(reply, Exception):
            raise reply
        if isinstance(reply, SimpleNamespace):
            return reply
        return SimpleNamespace(returncode=0, stdout=json.dumps(reply).encode(), stderr=b"")


def fixture_reader(raw=b"remote code\n", *, mode="100644", kind="blob", size=None):
    sha = hashlib.sha1(f"blob {len(raw)}\0".encode() + raw).hexdigest()
    replies = [
        {"sha": COMMIT, "commit": {"tree": {"sha": ROOT}}},
        {
            "truncated": False,
            "tree": [{"path": "src", "type": "tree", "mode": "040000", "sha": SUBTREE}],
        },
        {
            "truncated": False,
            "tree": [
                {
                    "path": "code.py",
                    "type": kind,
                    "mode": mode,
                    "sha": sha,
                    "size": len(raw) if size is None else size,
                }
            ],
        },
        {
            "sha": sha,
            "size": len(raw),
            "encoding": "base64",
            "content": base64.b64encode(raw).decode() + "\n",
        },
    ]
    runner = FakeRunner(replies)
    return GitHubReader({(REPO, REF)}, runner), runner, sha


def test_remote_read_pins_every_request_and_preserves_registered_ref():
    reader, runner, sha = fixture_reader()
    result = reader.read_file(REPO, REF, "src/code.py", expected_commit=COMMIT)
    assert result == {
        "kind": "file",
        "repository": REPO,
        "requested_ref": REF,
        "commit": COMMIT,
        "checked_at": result["checked_at"],
        "canonical_url": f"https://github.com/{REPO}/blob/{COMMIT}/src/code.py",
        "requested_ref_url": f"https://github.com/{REPO}/blob/{REF}/src/code.py",
        "path": "src/code.py",
        "blob_sha": sha,
        "size": 12,
        "content": "remote code\n",
    }
    assert result["checked_at"].endswith("+00:00")
    assert [call[0][-1] for call in runner.calls] == [
        f"repos/{REPO}/commits/feature%2Fregistered",
        f"repos/{REPO}/git/trees/{ROOT}",
        f"repos/{REPO}/git/trees/{SUBTREE}",
        f"repos/{REPO}/git/blobs/{sha}",
    ]


def test_resolve_is_metadata_not_file_evidence():
    reader, runner, _ = fixture_reader()
    result = reader.resolve_ref(REPO, REF)
    assert result["kind"] == "ref"
    assert "content" not in result and "path" not in result
    assert result["canonical_url"] == f"https://github.com/{REPO}/tree/{COMMIT}"
    assert len(runner.calls) == 1


def test_changed_ref_fails_before_reading_file():
    reader, runner, _ = fixture_reader()
    with pytest.raises(ReaderError, match="github_ref_changed"):
        reader.read_file(REPO, REF, "src/code.py", expected_commit="d" * 40)
    assert len(runner.calls) == 1


@pytest.mark.parametrize(
    "repo,ref",
    [
        (REPO, "develop"),
        ("other/project", REF),
        (REPO, "../main"),
        (REPO, "main?x=1"),
        ("--hostname/evil", "main"),
        (REPO, ["main"]),
    ],
)
def test_unregistered_sources_never_execute(repo, ref):
    reader, runner, _ = fixture_reader()
    with pytest.raises(ReaderError, match="source_not_allowed"):
        reader.resolve_ref(repo, ref)
    assert not runner.calls


@pytest.mark.parametrize(
    "path",
    [
        "",
        "/src/code.py",
        "../code.py",
        "src/../code.py",
        "src//code.py",
        "src\\code.py",
        "src/",
        "x\n",
        "a/" * 33,
    ],
)
def test_invalid_paths_never_execute(path):
    reader, runner, _ = fixture_reader()
    with pytest.raises(ReaderError, match="invalid_path"):
        reader.read_file(REPO, REF, path)
    assert not runner.calls


@pytest.mark.parametrize(
    "mode,kind",
    [
        ("040000", "tree"),
        ("120000", "blob"),
        ("160000", "commit"),
    ],
)
def test_directory_symlink_and_submodule_rejected(mode, kind):
    reader, runner, _ = fixture_reader(mode=mode, kind=kind)
    with pytest.raises(ReaderError, match="not_regular_file"):
        reader.read_file(REPO, REF, "src/code.py")
    assert len(runner.calls) == 3


def test_ancestor_symlink_is_not_followed():
    reader, runner, _ = fixture_reader()
    runner.replies[1]["tree"][0].update(mode="120000", type="blob")
    with pytest.raises(ReaderError, match="not_regular_file"):
        reader.read_file(REPO, REF, "src/code.py")
    assert len(runner.calls) == 2


@pytest.mark.parametrize("raw", [b"\xff", b"hello\0world"])
def test_binary_files_rejected(raw):
    reader, _, _ = fixture_reader(raw)
    with pytest.raises(ReaderError, match="file_not_utf8_text"):
        reader.read_file(REPO, REF, "src/code.py")


def test_size_limit_before_blob_download():
    reader, runner, _ = fixture_reader(size=MAX_FILE + 1)
    with pytest.raises(ReaderError, match="file_too_large"):
        reader.read_file(REPO, REF, "src/code.py")
    assert len(runner.calls) == 3


@pytest.mark.parametrize(
    "field,value",
    [
        ("content", "aW52YWxpZA=="),
        ("content", "@@@"),
        ("encoding", "utf-8"),
        ("sha", "f" * 40),
        ("size", 123),
        ("content", None),
    ],
)
def test_blob_integrity_required(field, value):
    reader, runner, _ = fixture_reader()
    runner.replies[-1][field] = value
    with pytest.raises(ReaderError, match="invalid_github_blob"):
        reader.read_file(REPO, REF, "src/code.py")


def test_truncated_tree_does_not_establish_absence():
    reader, runner, _ = fixture_reader()
    runner.replies[1]["truncated"] = True
    with pytest.raises(ReaderError, match="incomplete_github_tree"):
        reader.read_file(REPO, REF, "src/code.py")


@pytest.mark.parametrize(
    "failure,code",
    [
        (FileNotFoundError("secret"), "github_unavailable"),
        (subprocess.TimeoutExpired("secret", 20), "github_unavailable"),
        (
            SimpleNamespace(returncode=1, stdout=b"secret", stderr=b"credential"),
            "github_read_failed",
        ),
        (
            SimpleNamespace(returncode=0, stdout=b"secret", stderr=b"credential"),
            "invalid_github_response",
        ),
    ],
)
def test_failures_never_expose_process_output(failure, code):
    runner = FakeRunner([failure])
    result = dispatch(
        GitHubReader({(REPO, REF)}, runner),
        "tools/call",
        {"name": "resolve_ref", "arguments": {"repository": REPO, "ref": REF}},
    )
    assert result == {
        "isError": True,
        "content": [{"type": "text", "text": json.dumps({"error": code})}],
    }


@pytest.mark.parametrize(
    "args",
    [
        {"repository": REPO, "ref": REF, "command": "delete"},
        {"repository": REPO},
        {"repository": REPO, "ref": 1},
        [],
    ],
)
def test_tool_arguments_fail_closed(args):
    reader, runner, _ = fixture_reader()
    result = dispatch(reader, "tools/call", {"name": "resolve_ref", "arguments": args})
    assert result["isError"] is True
    assert not runner.calls


def test_stdio_handshake_tools_and_read():
    reader, runner, _ = fixture_reader()
    messages = [
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {"protocolVersion": "2025-06-18"},
        },
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
        {
            "jsonrpc": "2.0",
            "id": 3,
            "method": "tools/call",
            "params": {
                "name": "read_file",
                "arguments": {"repository": REPO, "ref": REF, "path": "src/code.py"},
            },
        },
    ]
    output = io.StringIO()
    serve(reader, io.StringIO("\n".join(map(json.dumps, messages)) + "\n"), output)
    replies = list(map(json.loads, output.getvalue().splitlines()))
    assert [reply["id"] for reply in replies] == [1, 2, 3]
    assert replies[0]["result"]["protocolVersion"] == "2025-06-18"
    assert {tool["name"] for tool in replies[1]["result"]["tools"]} == {
        "read_file",
        "resolve_ref",
        "list_sources",
    }
    assert json.loads(replies[2]["result"]["content"][0]["text"])["commit"] == COMMIT
    assert len(runner.calls) == 4


def test_empty_config_startup_and_listing_require_no_github(tmp_path):
    path = tmp_path / "sources.json"
    path.write_text('{"sources": []}')
    runner = FakeRunner([])
    reader = GitHubReader(load_sources(path), runner)
    assert dispatch(reader, "initialize", {})["capabilities"] == {"tools": {}}
    assert len(dispatch(reader, "tools/list", {})["tools"]) == 3
    with pytest.raises(ReaderError, match="source_not_allowed"):
        reader.resolve_ref(REPO, REF)
    assert not runner.calls


@pytest.mark.parametrize(
    "value",
    [
        {},
        {"sources": [{}]},
        {"sources": "bad"},
        {"sources": [{"repository": REPO, "ref": "main", "token": "secret"}]},
        {"sources": [{"repository": REPO, "ref": "../main"}]},
    ],
)
def test_invalid_config_fails_closed(tmp_path, value):
    path = tmp_path / "sources.json"
    path.write_text(json.dumps(value))
    with pytest.raises(ReaderError, match="invalid_sources_config"):
        load_sources(path)


def test_valid_config_preserves_ref(tmp_path):
    path = tmp_path / "sources.json"
    path.write_text(json.dumps({"sources": [{"repository": REPO, "ref": REF}]}))
    assert load_sources(path) == {(REPO, REF)}


@pytest.mark.parametrize("sources", [set(), {(REPO, REF), (REPO, "develop"), ("a/repo", "HEAD")}])
def test_list_sources_is_sorted_snapshot_only(sources):
    runner = FakeRunner([])
    reader = GitHubReader(sources, runner)
    result = dispatch(reader, "tools/call", {"name": "list_sources", "arguments": {}})
    assert json.loads(result["content"][0]["text"]) == {
        "kind": "registry",
        "sources": [{"repository": repo, "ref": ref} for repo, ref in sorted(sources)],
    }
    assert not runner.calls
    schema = next(
        tool
        for tool in dispatch(reader, "tools/list", {})["tools"]
        if tool["name"] == "list_sources"
    )["inputSchema"]
    assert schema == {
        "type": "object",
        "properties": {},
        "required": [],
        "additionalProperties": False,
    }


def test_list_sources_rejects_arguments_without_github_call():
    runner = FakeRunner([])
    reader = GitHubReader(set(), runner)
    result = dispatch(
        reader, "tools/call", {"name": "list_sources", "arguments": {"repository": "evil/repo"}}
    )
    assert result["isError"] is True
    assert not runner.calls


def test_discovery_snapshot_lists_registered_sources(tmp_path):
    from dougle_bot.source_registry import discover_github_sources

    (tmp_path / "sources").mkdir()
    (tmp_path / "sources/repo.md").write_text(
        "Location: https://github.com/example/project\nBranch: release/v1\nState: read\n"
    )
    sources = discover_github_sources(tmp_path)
    snapshot = tmp_path / "runtime-sources.json"
    snapshot.write_text(
        json.dumps({"sources": [{"repository": repo, "ref": ref} for repo, ref in sorted(sources)]})
    )
    runner = FakeRunner([])
    reader = GitHubReader(load_sources(snapshot), runner)
    assert reader.list_sources() == {
        "kind": "registry",
        "sources": [{"repository": "example/project", "ref": "release/v1"}],
    }
    assert not runner.calls


def test_malformed_requests_do_not_execute():
    reader, runner, _ = fixture_reader()
    output = io.StringIO()
    serve(reader, io.StringIO('[]\n{"jsonrpc":"2.0","id":4,"method":"missing"}\n'), output)
    assert [json.loads(line)["error"]["code"] for line in output.getvalue().splitlines()] == [
        -32600,
        -32601,
    ]
    assert not runner.calls


def test_related_files_reuse_trees_and_repeated_file_reuses_blob():
    reader, runner, _ = fixture_reader()
    raw = b"second file\n"
    sha = hashlib.sha1(f"blob {len(raw)}\0".encode() + raw).hexdigest()
    runner.replies[2]["tree"].append(
        {"path": "second.py", "type": "blob", "mode": "100644", "sha": sha, "size": len(raw)}
    )
    resolution = runner.replies[0]
    runner.replies.extend(
        [
            resolution,
            {
                "sha": sha,
                "size": len(raw),
                "encoding": "base64",
                "content": base64.b64encode(raw).decode(),
            },
            resolution,
        ]
    )
    reader.read_file(REPO, REF, "src/code.py")
    assert len(runner.calls) == 4
    assert reader.read_file(REPO, REF, "src/second.py")["content"] == "second file\n"
    assert len(runner.calls) == 6
    assert reader.read_file(REPO, REF, "src/code.py")["content"] == "remote code\n"
    assert len(runner.calls) == 7
    assert sum("/commits/" in call[0][-1] for call in runner.calls) == 3


def test_cached_objects_do_not_bypass_changed_ref_or_cross_instances():
    reader, runner, _ = fixture_reader()
    runner.replies.append({"sha": "d" * 40, "commit": {"tree": {"sha": ROOT}}})
    reader.read_file(REPO, REF, "src/code.py")
    with pytest.raises(ReaderError, match="github_ref_changed"):
        reader.read_file(REPO, REF, "src/code.py", expected_commit=COMMIT)
    assert len(runner.calls) == 5
    other, other_runner, _ = fixture_reader()
    other.read_file(REPO, REF, "src/code.py")
    assert len(other_runner.calls) == 4


def test_cache_entry_limit_evicts_least_recently_used(monkeypatch):
    from dougle_bot import github_reader as module

    monkeypatch.setattr(module, "MAX_CACHE_ENTRIES", 2)
    reader, runner, sha = fixture_reader()
    reader.read_file(REPO, REF, "src/code.py")
    root_key = f"repos/{REPO}/git/trees/{ROOT}"
    subtree_key = f"repos/{REPO}/git/trees/{SUBTREE}"
    blob_key = f"repos/{REPO}/git/blobs/{sha}"
    assert list(reader._cache) == [subtree_key, blob_key]
    reader._immutable_get(subtree_key)
    reader._remember(root_key, {"tree": [], "truncated": False})
    assert list(reader._cache) == [subtree_key, root_key]
    assert len(runner.calls) == 4


def test_cache_byte_limit_and_oversized_entry_skip(monkeypatch):
    from dougle_bot import github_reader as module

    monkeypatch.setattr(module, "MAX_CACHE_BYTES", 100)
    reader = GitHubReader(set(), FakeRunner([]))
    value = {"payload": "a" * 40}
    reader._remember("first", value)
    reader._remember("second", value)
    assert list(reader._cache) == ["second"]
    assert reader._cache_bytes <= 100
    assert reader._cache_bytes == sum(
        len(key.encode()) + len(data) for key, data in reader._cache.items()
    )
    reader._remember("oversized", {"payload": "a" * 101})
    assert list(reader._cache) == ["second"]


def test_cache_is_repository_scoped_and_failed_blob_is_not_cached():
    reader, runner, sha = fixture_reader()
    runner.replies[-1]["content"] = "invalid"
    with pytest.raises(ReaderError, match="invalid_github_blob"):
        reader.read_file(REPO, REF, "src/code.py")
    assert f"repos/{REPO}/git/blobs/{sha}" not in reader._cache
    tree_value = {"tree": [], "truncated": False}
    runner.replies.append(tree_value)
    assert reader._immutable_get(f"repos/other/repo/git/trees/{ROOT}") == tree_value
    assert len(runner.calls) == 5


@pytest.mark.parametrize(
    "diagnostic,code",
    [
        ("gh: Bad credentials (HTTP 401)", "github_auth_required"),
        ("gh: Resource not accessible (HTTP 403)", "github_access_denied"),
        ("gh: Not Found (HTTP 404)", "github_not_found"),
        ("gh: Too many requests (HTTP 429)", "github_rate_limited"),
        ("gh: API rate limit exceeded (HTTP 403)", "github_rate_limited"),
        ("gh: You have exceeded a secondary rate limit (HTTP 403)", "github_rate_limited"),
        ("gh: API rate limit exceeded", "github_read_failed"),
        ("gh: Bad credentials; please login", "github_read_failed"),
        ("gh: status 401", "github_read_failed"),
        ("gh: failure (HTTP 500)", "github_read_failed"),
        ("gh: first (HTTP 401); then (HTTP 404)", "github_read_failed"),
    ],
)
def test_http_failures_report_only_observed_safe_constant(diagnostic, code):
    runner = FakeRunner(
        [
            SimpleNamespace(
                returncode=1,
                stdout=b"private response",
                stderr=(diagnostic + "\nsecret-token private-repository private-user").encode(),
            )
        ]
    )
    result = dispatch(
        GitHubReader({(REPO, REF)}, runner),
        "tools/call",
        {"name": "resolve_ref", "arguments": {"repository": REPO, "ref": REF}},
    )
    assert result == {
        "isError": True,
        "content": [{"type": "text", "text": json.dumps({"error": code})}],
    }
    assert len(runner.calls) == 1
