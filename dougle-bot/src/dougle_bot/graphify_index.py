"""Optional Graphify navigation for registered remote code at one exact commit.

The graph is a private, derived locator. It never satisfies original-code evidence.
Building is explicit; inquiry-time queries neither clone nor update a repository.
"""

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

from .config import private_dir

MAX_GRAPH_BYTES = 128 * 1024 * 1024
MAX_QUERY_BYTES = 16 * 1024


class GraphError(Exception):
    """Fixed, content-free graph error code."""


def _digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def _key(repository, ref, commit):
    return hashlib.sha256(f"{repository}\n{ref}\n{commit}".encode()).hexdigest()


def graph_inventory(cache_root):
    """Small metadata fingerprint; actual graph bytes are checked when queried."""
    root = Path(cache_root)
    if not root.is_dir() or root.is_symlink():
        return []
    try:
        entries = sorted(root.iterdir())
        if len(entries) > 256:
            return []
        result = []
        for entry in entries:
            manifest_path = entry / "manifest.json"
            if (
                entry.is_symlink()
                or not entry.is_dir()
                or manifest_path.is_symlink()
                or not manifest_path.is_file()
                or manifest_path.stat().st_size > 4096
            ):
                continue
            data = json.loads(manifest_path.read_text())
            if isinstance(data, dict):
                result.append(
                    [data.get("repository"), data.get("ref"), data.get("commit"), data.get("graph_sha256")]
                )
        return result
    except (OSError, ValueError, TypeError, RecursionError):
        return []


def _run(runner, command, timeout, *, cwd=None, env=None):
    try:
        result = runner(
            command,
            cwd=cwd,
            env=env,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            shell=False,
        )
    except (OSError, subprocess.SubprocessError):
        raise GraphError("graphify_unavailable") from None
    if result.returncode:
        raise GraphError("graphify_operation_failed")
    return result.stdout


def _graph_env(home):
    # Code extraction is local-only; do not forward provider keys into Graphify.
    return {
        "HOME": str(home),
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        "LANG": "C.UTF-8",
        "PYTHONNOUSERSITE": "1",
    }


def _git_env():
    return {
        **os.environ,
        "GIT_CONFIG_GLOBAL": os.devnull,
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_LFS_SKIP_SMUDGE": "1",
        "GIT_TERMINAL_PROMPT": "0",
    }


def _binary(value):
    found = shutil.which(value)
    if not found:
        raise GraphError("graphify_not_installed")
    path = Path(found).resolve()
    if not path.is_file() or not os.access(path, os.X_OK):
        raise GraphError("graphify_not_installed")
    return path


def _load(cache_root, repository, ref, commit):
    target = cache_root / _key(repository, ref, commit)
    manifest_path = target / "manifest.json"
    graph = target / "graph.json"
    if not target.is_dir() or target.is_symlink() or not manifest_path.is_file():
        raise GraphError("graph_unavailable")
    if manifest_path.is_symlink() or graph.is_symlink() or not graph.is_file():
        raise GraphError("graph_unavailable")
    try:
        if manifest_path.stat().st_size > 4096:
            raise ValueError
        manifest = json.loads(manifest_path.read_text())
        if (
            not isinstance(manifest, dict)
            or manifest.get("repository") != repository
            or manifest.get("ref") != ref
            or manifest.get("commit") != commit
            or not isinstance(manifest.get("graph_sha256"), str)
            or not isinstance(manifest.get("graphify_bin"), str)
            or graph.stat().st_size > MAX_GRAPH_BYTES
            or _digest(graph) != manifest["graph_sha256"]
        ):
            raise ValueError
    except (OSError, ValueError, TypeError, RecursionError):
        raise GraphError("graph_unavailable") from None
    return graph, manifest


def build_graph(home, memory_root, repository, ref, graphify="graphify", *, reader=None, runner=None):
    """Build a code-only graph for one registered remote source without editing it."""
    from .github_reader import GitHubReader, ReaderError
    from .source_registry import SourceRegistryError, discover_github_sources

    try:
        sources = discover_github_sources(memory_root)
    except SourceRegistryError:
        raise GraphError("source_registry_incomplete") from None
    if (repository, ref) not in sources:
        raise GraphError("source_not_allowed")
    reader = reader or GitHubReader(sources)
    runner = runner or subprocess.run
    binary = _binary(graphify)
    try:
        resolved = reader.resolve_ref(repository, ref)
    except ReaderError as exc:
        raise GraphError(str(exc)) from None
    commit = resolved["commit"]
    cache_root = private_dir(Path(home) / "graphify")
    target = cache_root / _key(repository, ref, commit)
    if target.exists():
        _, manifest = _load(cache_root, repository, ref, commit)
        return {"status": "ready", "reused": True, **manifest}
    stage = Path(tempfile.mkdtemp(prefix=".build-", dir=cache_root))
    stage.chmod(0o700)
    try:
        source = stage / "source"
        output = stage / "output"
        github_url = f"https://github.com/{repository}.git"
        _run(
            runner,
            ["gh", "repo", "clone", github_url, str(source), "--", "--filter=blob:none", "--no-checkout"],
            180,
            env=_git_env(),
        )
        _run(runner, ["git", "-C", str(source), "-c", "core.hooksPath=/dev/null", "fetch", "--no-tags", "origin", commit], 180, env=_git_env())
        _run(runner, ["git", "-C", str(source), "-c", "core.hooksPath=/dev/null", "checkout", "--detach", commit], 120, env=_git_env())
        head = _run(runner, ["git", "-C", str(source), "rev-parse", "HEAD"], 20, env=_git_env()).decode().strip()
        if head != commit:
            raise GraphError("graph_source_mismatch")
        if any(path.is_symlink() for path in source.rglob("*")):
            raise GraphError("graph_source_symlink")
        version = _run(runner, [str(binary), "--version"], 20, env=_graph_env(home)).decode().strip()
        _run(
            runner,
            [str(binary), "extract", str(source), "--code-only", "--no-cluster", "--out", str(output)],
            600,
            env=_graph_env(home),
        )
        generated = output / "graphify-out" / "graph.json"
        if generated.is_symlink() or not generated.is_file() or generated.stat().st_size > MAX_GRAPH_BYTES:
            raise GraphError("graph_build_invalid")
        with generated.open("rb") as stream:
            shape = json.load(stream)
        if not isinstance(shape, dict) or not isinstance(shape.get("nodes"), list) or not isinstance(shape.get("edges"), list):
            raise GraphError("graph_build_invalid")
        graph = stage / "graph.json"
        generated.replace(graph)
        graph.chmod(0o600)
        manifest = {
            "repository": repository,
            "ref": ref,
            "commit": commit,
            "graph_sha256": _digest(graph),
            "graphify_version": version,
            "graphify_bin": str(binary),
            "nodes": len(shape["nodes"]),
            "edges": len(shape["edges"]),
        }
        (stage / "manifest.json").write_text(json.dumps(manifest))
        (stage / "manifest.json").chmod(0o600)
        shutil.rmtree(source)
        shutil.rmtree(output)
        if target.exists():
            _, manifest = _load(cache_root, repository, ref, commit)
            return {"status": "ready", "reused": True, **manifest}
        stage.rename(target)
        return {"status": "ready", "reused": False, **manifest}
    finally:
        if stage.exists():
            shutil.rmtree(stage)


def query_graph(cache_root, repository, ref, commit, question, *, runner=None):
    """Return bounded navigation text from a graph bound to the remote commit."""
    if not isinstance(question, str) or not 0 < len(question) <= 500 or any(ord(c) < 32 for c in question):
        raise GraphError("invalid_graph_query")
    graph, manifest = _load(Path(cache_root), repository, ref, commit)
    binary = _binary(manifest.get("graphify_bin", "graphify"))
    runner = runner or subprocess.run
    try:
        result = runner(
            [str(binary), "query", question, "--graph", str(graph), "--budget", "500", "--depth", "2"],
            cwd=graph.parent,
            env=_graph_env(graph.parent),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=20,
            shell=False,
        )
    except (OSError, subprocess.SubprocessError):
        raise GraphError("graphify_unavailable") from None
    if result.returncode == 4:
        raise GraphError("graph_no_match")
    if result.returncode != 0:
        raise GraphError("graph_query_failed")
    if len(result.stdout) > MAX_QUERY_BYTES:
        raise GraphError("graph_result_too_large")
    try:
        content = result.stdout.decode("utf-8")
    except UnicodeError:
        raise GraphError("graph_query_failed") from None
    return {
        "kind": "graph_navigation",
        "repository": repository,
        "ref": ref,
        "commit": commit,
        "graphify_version": manifest["graphify_version"],
        "content": content,
        "code_evidence": False,
    }
