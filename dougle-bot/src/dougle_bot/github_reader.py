"""Allowlisted, remote-only GitHub reads over newline-delimited stdio MCP.

Launch with --sources pointing at trusted runtime JSON:
{"sources": [{"repository": "owner/repo", "ref": "develop"}]}.
No credentials are read here: the fixed GET commands use gh's existing auth.
"""

import argparse
import base64
import hashlib
import json
import re
import subprocess
import sys
from collections import OrderedDict
from datetime import UTC, datetime
from pathlib import Path
from urllib.parse import quote

MAX_FILE = 1024 * 1024
MAX_MESSAGE = 65536
MAX_CACHE_ENTRIES = 32
MAX_CACHE_BYTES = 8 * 1024 * 1024
SHA = re.compile(r"[0-9a-f]{40}\Z")
VERSIONS = ("2024-11-05", "2025-03-26", "2025-06-18")


def _http_failure(stderr):
    """Classify only gh's observed HTTP status; never expose diagnostics."""
    if isinstance(stderr, bytes):
        stderr = stderr.decode("utf-8", errors="replace")
    if not isinstance(stderr, str):
        return "github_read_failed"
    codes = set(re.findall(r"\(HTTP (\d{3})\)", stderr))
    if len(codes) != 1:
        return "github_read_failed"
    code = codes.pop()
    if code == "403" and re.search(
        r"\b(?:api rate limit exceeded|rate limit exceeded|secondary rate limit)\b",
        stderr,
        re.IGNORECASE,
    ):
        return "github_rate_limited"
    return {
        "401": "github_auth_required",
        "403": "github_access_denied",
        "404": "github_not_found",
        "429": "github_rate_limited",
    }.get(code, "github_read_failed")


class ReaderError(Exception):
    """A content-free error safe to return to the caller."""


def valid_source(repository, ref):
    return (
        isinstance(repository, str)
        and re.fullmatch(r"[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+", repository) is not None
        and repository.split("/")[1] not in (".", "..")
        and isinstance(ref, str)
        and 0 < len(ref) <= 256
        and not any(ord(c) < 33 or c in "~^:?*[\\" for c in ref)
        and ".." not in ref
        and "@{" not in ref
        and all(p and not p.startswith(".") and not p.endswith(".lock") for p in ref.split("/"))
        and not ref.endswith(".")
    )


def load_sources(path):
    try:
        with Path(path).open("rb") as stream:
            raw = stream.read(262145)
        if len(raw) > 262144:
            raise ValueError
        value = json.loads(raw)
        if not isinstance(value, dict) or set(value) != {"sources"}:
            raise ValueError
        rows = value["sources"]
        if not isinstance(rows, list) or len(rows) > 1000:
            raise ValueError
        pairs = set()
        for row in rows:
            if not isinstance(row, dict) or set(row) != {"repository", "ref"}:
                raise ValueError
            if not valid_source(row["repository"], row["ref"]):
                raise ValueError
            pairs.add((row["repository"], row["ref"]))
        return pairs
    except (OSError, ValueError, TypeError, RecursionError):
        raise ReaderError("invalid_sources_config") from None


class GitHubReader:
    def __init__(self, sources, runner=subprocess.run, graph_cache=None):
        self.sources = frozenset(sources)
        if any(not valid_source(*pair) for pair in self.sources):
            raise ReaderError("invalid_sources_config")
        self.runner = runner
        self.graph_cache = Path(graph_cache) if graph_cache is not None else None
        # Serialized values bound retained payload and avoid shared mutable trees.
        # Endpoint includes repository, object kind and immutable SHA; never refs.
        self._cache = OrderedDict()
        self._cache_bytes = 0

    def _immutable_get(self, endpoint):
        cached = self._cache.get(endpoint)
        if cached is not None:
            self._cache.move_to_end(endpoint)
            return json.loads(cached)
        return self._get(endpoint)

    def _remember(self, endpoint, value):
        if endpoint in self._cache:
            self._cache.move_to_end(endpoint)
            return
        encoded = json.dumps(value, separators=(",", ":")).encode("utf-8")
        size = len(encoded) + len(endpoint.encode("utf-8"))
        if MAX_CACHE_ENTRIES <= 0 or size > MAX_CACHE_BYTES:
            return
        while self._cache and (
            len(self._cache) >= MAX_CACHE_ENTRIES or self._cache_bytes + size > MAX_CACHE_BYTES
        ):
            old_key, old_value = self._cache.popitem(last=False)
            self._cache_bytes -= len(old_key.encode("utf-8")) + len(old_value)
        self._cache[endpoint] = encoded
        self._cache_bytes += size

    def _get(self, endpoint):
        try:
            result = self.runner(
                ["gh", "api", "--hostname", "github.com", "--method", "GET", endpoint],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=20,
                shell=False,
            )
            if result.returncode:
                raise ReaderError(_http_failure(result.stderr))
            if len(result.stdout) > 8 * 1024 * 1024:
                raise ReaderError("github_response_too_large")
            value = json.loads(result.stdout)
            if not isinstance(value, dict):
                raise ValueError
            return value
        except (OSError, subprocess.SubprocessError):
            raise ReaderError("github_unavailable") from None
        except (ValueError, TypeError, RecursionError):
            raise ReaderError("invalid_github_response") from None

    def _resolve(self, repository, ref):
        if not valid_source(repository, ref) or (repository, ref) not in self.sources:
            raise ReaderError("source_not_allowed")
        data = self._get(f"repos/{repository}/commits/{quote(ref, safe='')}")
        commit = data.get("sha")
        tree = data.get("commit", {})
        tree = tree.get("tree", {}) if isinstance(tree, dict) else {}
        tree = tree.get("sha") if isinstance(tree, dict) else None
        if not isinstance(commit, str) or not SHA.fullmatch(commit):
            raise ReaderError("invalid_github_response")
        if not isinstance(tree, str) or not SHA.fullmatch(tree):
            raise ReaderError("invalid_github_response")
        return commit, tree

    @staticmethod
    def _metadata(repository, ref, commit, kind, path=None):
        suffix = f"/{quote(path, safe='/')}" if path is not None else ""
        route = "blob" if path is not None else "tree"
        root = f"https://github.com/{repository}/{route}/"
        return {
            "kind": kind,
            "repository": repository,
            "requested_ref": ref,
            "commit": commit,
            "checked_at": datetime.now(UTC).isoformat(),
            "canonical_url": root + commit + suffix,
            "requested_ref_url": root + quote(ref, safe="/") + suffix,
        }

    def resolve_ref(self, repository, ref):
        commit, _ = self._resolve(repository, ref)
        return self._metadata(repository, ref, commit, "ref")

    def list_sources(self):
        """Expose the runtime snapshot only, without claiming a remote read."""
        return {
            "kind": "registry",
            "sources": [
                {"repository": repository, "ref": ref} for repository, ref in sorted(self.sources)
            ],
        }

    def graph_query(self, repository, ref, query, expected_commit):
        """Use a commit-bound Graphify index only to locate candidate files."""
        from .graphify_index import GraphError, query_graph

        if self.graph_cache is None:
            raise ReaderError("graph_unavailable")
        if not isinstance(expected_commit, str) or not SHA.fullmatch(expected_commit):
            raise ReaderError("invalid_expected_commit")
        commit, _ = self._resolve(repository, ref)
        if commit != expected_commit:
            raise ReaderError("github_ref_changed")
        try:
            return query_graph(self.graph_cache, repository, ref, commit, query)
        except GraphError as exc:
            raise ReaderError(str(exc)) from None

    def find_files(self, repository, ref, filename, expected_commit=None):
        """Find exact basenames remotely; paths are discovery, never file evidence."""
        if (
            not isinstance(filename, str)
            or not 0 < len(filename) <= 255
            or filename in (".", "..")
            or any(ord(c) < 32 or c in "/\\" for c in filename)
        ):
            raise ReaderError("invalid_filename")
        if expected_commit is not None and (
            not isinstance(expected_commit, str) or not SHA.fullmatch(expected_commit)
        ):
            raise ReaderError("invalid_expected_commit")
        commit, tree = self._resolve(repository, ref)
        if expected_commit is not None and expected_commit != commit:
            raise ReaderError("github_ref_changed")
        endpoint = f"repos/{repository}/git/trees/{tree}?recursive=1"
        data = self._immutable_get(endpoint)
        entries = data.get("tree")
        if data.get("truncated") is not False or not isinstance(entries, list):
            raise ReaderError("incomplete_github_tree")
        paths = set()
        for entry in entries:
            if not isinstance(entry, dict) or not isinstance(entry.get("path"), str):
                raise ReaderError("invalid_github_response")
            path = entry["path"]
            if entry.get("type") == "blob" and entry.get("mode") in ("100644", "100755"):
                if path.rsplit("/", 1)[-1] == filename:
                    if any(p in ("", ".", "..") for p in path.split("/")):
                        raise ReaderError("invalid_github_response")
                    paths.add(path)
        if len(paths) > 100:
            raise ReaderError("too_many_file_matches")
        self._remember(endpoint, data)
        return {
            **self._metadata(repository, ref, commit, "file_paths"),
            "filename": filename,
            "paths": sorted(paths),
            "complete": True,
        }

    def read_file(self, repository, ref, path, expected_commit=None):
        if (
            not isinstance(path, str)
            or not 0 < len(path) <= 4096
            or len(path.split("/")) > 32
            or any(p in ("", ".", "..") for p in path.split("/"))
            or any(ord(c) < 32 or c == "\\" for c in path)
        ):
            raise ReaderError("invalid_path")
        if expected_commit is not None and (
            not isinstance(expected_commit, str) or not SHA.fullmatch(expected_commit)
        ):
            raise ReaderError("invalid_expected_commit")
        commit, tree = self._resolve(repository, ref)
        if expected_commit is not None and expected_commit != commit:
            raise ReaderError("github_ref_changed")
        parts = path.split("/")
        entry = None
        for index, part in enumerate(parts):
            endpoint = f"repos/{repository}/git/trees/{tree}"
            data = self._immutable_get(endpoint)
            entries = data.get("tree")
            if data.get("truncated") is not False or not isinstance(entries, list):
                raise ReaderError("incomplete_github_tree")
            self._remember(endpoint, data)
            matches = [
                item for item in entries if isinstance(item, dict) and item.get("path") == part
            ]
            if len(matches) != 1:
                raise ReaderError("file_not_found")
            entry = matches[0]
            tree = entry.get("sha")
            if not isinstance(tree, str) or not SHA.fullmatch(tree):
                raise ReaderError("invalid_github_response")
            if index < len(parts) - 1:
                if entry.get("type") != "tree" or entry.get("mode") != "040000":
                    raise ReaderError("not_regular_file")
            elif entry.get("type") != "blob" or entry.get("mode") not in ("100644", "100755"):
                raise ReaderError("not_regular_file")
        size = entry.get("size")
        if type(size) is not int or not 0 <= size <= MAX_FILE:
            raise ReaderError("file_too_large")
        endpoint = f"repos/{repository}/git/blobs/{tree}"
        blob = self._immutable_get(endpoint)
        if blob.get("sha") != tree or blob.get("size") != size or blob.get("encoding") != "base64":
            raise ReaderError("invalid_github_blob")
        try:
            encoded = blob["content"]
            if not isinstance(encoded, str):
                raise ValueError
            raw = base64.b64decode(encoded.replace("\n", ""), validate=True)
            digest = hashlib.sha1(f"blob {len(raw)}\0".encode() + raw).hexdigest()
            if len(raw) != size or digest != tree:
                raise ValueError
            content = raw.decode("utf-8")
            if "\0" in content:
                raise UnicodeError
        except UnicodeError:
            raise ReaderError("file_not_utf8_text") from None
        except (KeyError, ValueError, TypeError):
            raise ReaderError("invalid_github_blob") from None
        self._remember(endpoint, blob)
        return {
            **self._metadata(repository, ref, commit, "file", path),
            "path": path,
            "blob_sha": tree,
            "size": size,
            "content": content,
        }


def tool_definitions(graph_enabled=False):
    properties = {name: {"type": "string"} for name in ("repository", "ref")}
    definitions = [
        {
            "name": name,
            "description": description,
            "inputSchema": {
                "type": "object",
                "properties": props,
                "required": required,
                "additionalProperties": False,
            },
            "annotations": {"readOnlyHint": True, "destructiveHint": False, "openWorldHint": True},
        }
        for name, description, props, required in (
            (
                "list_sources",
                "List registered repository/ref pairs; no remote call or file evidence.",
                {},
                [],
            ),
            (
                "resolve_ref",
                "Resolve an allowlisted GitHub ref remotely; not file evidence.",
                properties,
                ["repository", "ref"],
            ),
            (
                "find_files",
                "Find exact filenames in the registered remote ref. Returns all matching paths "
                "and commit, not file contents or code evidence. Read candidates with read_file.",
                {**properties, "filename": {"type": "string"}, "expected_commit": {"type": "string"}},
                ["repository", "ref", "filename"],
            ),
            (
                "read_file",
                "Read a regular UTF-8 file at the remote registered ref, pinned to a commit.",
                {**properties, "path": {"type": "string"}, "expected_commit": {"type": "string"}},
                ["repository", "ref", "path"],
            ),
        )
    ]
    if graph_enabled:
        definitions.append(
            {
                "name": "graph_query",
                "description": "Query a prepared Graphify code graph for candidate paths/callers at a "
                "registered remote commit. Navigation only; read_file is required for code evidence. "
                "A missing/stale graph must fall back to remote source discovery.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        **properties,
                        "query": {"type": "string"},
                        "expected_commit": {"type": "string"},
                    },
                    "required": ["repository", "ref", "query", "expected_commit"],
                    "additionalProperties": False,
                },
                "annotations": {"readOnlyHint": True, "destructiveHint": False, "openWorldHint": False},
            }
        )
    return definitions


def dispatch(reader, method, params):
    if method == "initialize":
        version = params.get("protocolVersion")
        return {
            "protocolVersion": version if version in VERSIONS else VERSIONS[-1],
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "dougle_source", "version": "1.0.0"},
        }
    if method == "ping":
        return {}
    if method == "tools/list":
        return {"tools": tool_definitions(reader.graph_cache is not None)}
    if method != "tools/call":
        raise ReaderError("method_not_found")
    try:
        name = params.get("name")
        definitions = {item["name"]: item for item in tool_definitions(reader.graph_cache is not None)}
        if not isinstance(name, str) or name not in definitions:
            raise ReaderError("unknown_tool")
        args = params.get("arguments", {})
        schema = definitions[name]["inputSchema"]
        if (
            not isinstance(args, dict)
            or not set(schema["required"]) <= set(args) <= set(schema["properties"])
            or any(not isinstance(value, str) for value in args.values())
        ):
            raise ReaderError("invalid_arguments")
        result = getattr(reader, name)(**args)
        return {"content": [{"type": "text", "text": json.dumps(result, ensure_ascii=False)}]}
    except ReaderError as exc:
        return {
            "isError": True,
            "content": [{"type": "text", "text": json.dumps({"error": str(exc)})}],
        }


def serve(reader, input_stream, output_stream):
    while True:
        line = input_stream.readline(MAX_MESSAGE + 1)
        if not line:
            return
        request_id = None
        try:
            if len(line) > MAX_MESSAGE:
                # Close rather than interpret the remainder as a separate request.
                return
            message = json.loads(line)
            if not isinstance(message, dict) or message.get("jsonrpc") != "2.0":
                raise ValueError
            request_id = message.get("id")
            if request_id is not None and type(request_id) not in (str, int):
                request_id = None
                raise ValueError
            method = message.get("method")
            params = message.get("params", {})
            if not isinstance(method, str) or not isinstance(params, dict):
                raise ValueError
            if "id" not in message:
                continue
            result = dispatch(reader, method, params)
            response = {"jsonrpc": "2.0", "id": request_id, "result": result}
        except (ValueError, RecursionError):
            response = {
                "jsonrpc": "2.0",
                "id": request_id,
                "error": {"code": -32600, "message": "Invalid request"},
            }
        except ReaderError:
            response = {
                "jsonrpc": "2.0",
                "id": request_id,
                "error": {"code": -32601, "message": "Method not found"},
            }
        output_stream.write(json.dumps(response, ensure_ascii=False) + "\n")
        output_stream.flush()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", required=True)
    parser.add_argument("--graph-cache")
    args = parser.parse_args()
    try:
        reader = GitHubReader(load_sources(args.sources), graph_cache=args.graph_cache)
    except ReaderError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    serve(reader, sys.stdin, sys.stdout)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
