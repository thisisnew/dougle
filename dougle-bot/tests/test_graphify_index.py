"""Registered, commit-bound Graphify navigation with synthetic external tools."""

import json
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

from dougle_bot.config import EngineConfig
from dougle_bot.engine import ClaudeCliEngine
from dougle_bot.evidence import read_kinds
from dougle_bot.github_reader import GitHubReader, ReaderError, dispatch
from dougle_bot.graphify_index import GraphError, build_graph, query_graph

REPO = "example/orders"
REF = "main"
COMMIT = "a" * 40
TREE = "b" * 40


class Remote:
    def resolve_ref(self, repository, ref):
        assert (repository, ref) == (REPO, REF)
        return {"commit": COMMIT}


class FakeRunner:
    def __init__(self):
        self.calls = []

    def __call__(self, argv, **kwargs):
        self.calls.append(argv)
        assert kwargs["shell"] is False
        assert kwargs["stdin"] == subprocess.DEVNULL
        if argv[:3] == ["gh", "repo", "clone"]:
            Path(argv[4]).mkdir()
            return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")
        if argv[0] == "git":
            content = (COMMIT + "\n").encode() if argv[-2:] == ["rev-parse", "HEAD"] else b""
            return SimpleNamespace(returncode=0, stdout=content, stderr=b"")
        if argv[-1] == "--version":
            return SimpleNamespace(returncode=0, stdout=b"graphify 0.9.104\n", stderr=b"")
        if "extract" in argv:
            output = Path(argv[argv.index("--out") + 1]) / "graphify-out"
            output.mkdir(parents=True)
            (output / "graph.json").write_text(
                json.dumps({"nodes": [{"label": "processOrder", "source_file": "order.ts"}], "edges": []})
            )
            assert "--code-only" in argv and "--no-cluster" in argv
            assert "ANTHROPIC_API_KEY" not in kwargs["env"]
            return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")
        if "query" in argv:
            assert argv[argv.index("--budget") + 1] == "500"
            return SimpleNamespace(returncode=0, stdout=b"NODE processOrder [src=order.ts]\n", stderr=b"")
        raise AssertionError(argv)


def registered(tmp_path):
    root = tmp_path / "knowledge"
    sources = root / "sources"
    sources.mkdir(parents=True)
    (sources / "S1.md").write_text(
        f"# S1\nLocation: https://github.com/{REPO}/tree/{REF}\nState: read\n"
    )
    return root


def test_build_and_query_only_registered_exact_commit(tmp_path):
    root = registered(tmp_path)
    home = tmp_path / "bot"
    runner = FakeRunner()
    first = build_graph(home, root, REPO, REF, sys.executable, reader=Remote(), runner=runner)
    assert first["status"] == "ready" and first["reused"] is False
    assert first["commit"] == COMMIT and first["nodes"] == 1
    assert not list((home / "graphify").glob("*/source"))
    result = query_graph(home / "graphify", REPO, REF, COMMIT, "processOrder", runner=runner)
    assert result["kind"] == "graph_navigation" and result["code_evidence"] is False
    assert "order.ts" in result["content"]
    assert read_kinds("mcp__dougle_source__graph_query", {"query": "processOrder"}) == set()
    second = build_graph(home, root, REPO, REF, sys.executable, reader=Remote(), runner=runner)
    assert second["reused"] is True
    command = ClaudeCliEngine(EngineConfig(), home).command(None, 1, tmp_path / "settings.json")
    allowed = command[command.index("--allowedTools") + 1].split(",")
    assert "mcp__dougle_source__graph_query" in allowed
    startup = ClaudeCliEngine(EngineConfig(), home, startup_probe=True).command(None, 1, tmp_path / "settings.json")
    assert "mcp__dougle_source__graph_query" not in startup[startup.index("--allowedTools") + 1]
    with pytest.raises(GraphError, match="graph_unavailable"):
        query_graph(home / "graphify", REPO, REF, "c" * 40, "processOrder", runner=runner)
    with pytest.raises(GraphError, match="invalid_graph_query"):
        query_graph(home / "graphify", REPO, REF, COMMIT, "\n", runner=runner)


def test_modified_graph_is_rejected_and_not_rebuilt_implicitly(tmp_path):
    root = registered(tmp_path)
    home = tmp_path / "bot"
    runner = FakeRunner()
    build_graph(home, root, REPO, REF, sys.executable, reader=Remote(), runner=runner)
    graph = next((home / "graphify").glob("*/graph.json"))
    graph.write_text("{}")
    with pytest.raises(GraphError, match="graph_unavailable"):
        query_graph(home / "graphify", REPO, REF, COMMIT, "processOrder", runner=runner)
    before = len(runner.calls)
    with pytest.raises(GraphError, match="graph_unavailable"):
        build_graph(home, root, REPO, REF, sys.executable, reader=Remote(), runner=runner)
    assert len(runner.calls) == before


def test_unregistered_source_never_clones(tmp_path):
    runner = FakeRunner()
    with pytest.raises(GraphError, match="source_not_allowed"):
        build_graph(tmp_path / "bot", registered(tmp_path), "other/repo", REF, sys.executable, reader=Remote(), runner=runner)
    assert not runner.calls


def test_mcp_graph_query_requires_matching_remote_ref(tmp_path):
    def runner(argv, **kwargs):
        return SimpleNamespace(
            returncode=0,
            stdout=json.dumps({"sha": COMMIT, "commit": {"tree": {"sha": TREE}}}).encode(),
            stderr=b"",
        )
    reader = GitHubReader({(REPO, REF)}, runner, graph_cache=tmp_path)
    with pytest.raises(ReaderError, match="github_ref_changed"):
        reader.graph_query(REPO, REF, "processOrder", "c" * 40)
    with pytest.raises(ReaderError, match="source_not_allowed"):
        reader.graph_query("other/repo", REF, "processOrder", COMMIT)
    result = dispatch(
        reader,
        "tools/call",
        {"name": "graph_query", "arguments": {"repository": REPO, "ref": REF, "query": "processOrder", "expected_commit": COMMIT}},
    )
    assert result["isError"] is True and "graph_unavailable" in result["content"][0]["text"]
    assert "graph_query" in {tool["name"] for tool in dispatch(reader, "tools/list", {})["tools"]}
