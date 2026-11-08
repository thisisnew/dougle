"""Discover registered GitHub sources without scanning knowledge or following links.

Supported Markdown: heading-separated records with Location/Source/Repository/
Canonical URL/Canonical root/Origin/URL fields, optional Branch/Ref and State/Status;
or pipe tables with those column labels. Labels are case insensitive, may use
underscores/hyphens, and may be bold. HTTPS github.com owner/repo[.git] and
owner/repo/tree/ref locators only. An explicit ref must agree with a tree URL.
The complete tree suffix is a ref (including slashes), never a guessed subtree.
No SSH URLs, blob/issue links, prose links, YAML nesting, or arbitrary pointers.
Missing state is accepted for legacy records; explicitly inactive states exclude
the repository, including stale index duplicates. Source files override index
refs for the same repository. Missing refs mean remote HEAD, never local HEAD.

Limits: 256 files, 64 KiB/file, 8 MiB total, 4096 directory entries examined.
Symlinks and nonregular files are skipped. Missing directories are empty;
unreadable files, invalid UTF-8 and exceeded limits raise SourceRegistryError.
Only caller-supplied memory roots are inspected; no environment/home fallback.

Local discovery accepts absolute POSIX paths only in Location, Canonical location,
or Canonical root fields (including tables). It normalizes paths lexically, never
stats/resolves the original. Relative paths, traversal, filesystem root, memory
store paths, and generic Workspace/Root/Path pointers do not authorize originals.
The caller must enforce real path containment when using these registrations.
"""

import errno
import os
import re
import stat
from pathlib import Path
from urllib.parse import unquote, urlsplit

from dougle_bot.github_reader import valid_source

MAX_FILES = 256
MAX_FILE_BYTES = 64 * 1024
MAX_TOTAL_BYTES = 8 * 1024 * 1024
MAX_ENTRIES = 4096
LOCATORS = {
    "location",
    "source",
    "repository",
    "repo",
    "canonical",
    "canonical url",
    "canonical root",
    "canonical location",
    "origin",
    "url",
    "source url",
}
REFS = {"branch", "ref", "registered ref", "requested ref", "default branch"}
STATES = {"state", "status"}
LOCAL_LOCATORS = {"location", "canonical location", "canonical root"}
ACTIVE = {"read", "partial", "connected", "active", "registered"}
URL = re.compile(r"https://github\.com/[^\s<>`\"\]\)]+")


class SourceRegistryError(Exception):
    """Discovery was incomplete; do not treat it as an empty registry."""


def _label(value):
    return re.sub(r"[\s_-]+", " ", value.strip().strip("*` ")).casefold()


def _locator(value):
    matches = URL.findall(value)
    if len(matches) != 1:
        return None
    parsed = urlsplit(matches[0])
    if parsed.netloc != "github.com" or parsed.query or parsed.fragment:
        return None
    parts = parsed.path.strip("/").split("/")
    if len(parts) < 2:
        return None
    repo = "/".join(parts[:2]).removesuffix(".git")
    ref = None
    if len(parts) > 2:
        if len(parts) < 4 or parts[2] != "tree":
            return None
        ref = unquote("/".join(parts[3:]))
    if not valid_source(repo, ref or "HEAD"):
        return None
    return repo, ref


def _record(fields):
    locators = {_locator(value) for key, value in fields if key in LOCATORS}
    locators.discard(None)
    repositories = {repo for repo, _ in locators}
    states = {value.strip("`* \"'").casefold() for key, value in fields if key in STATES}
    if states and not states <= ACTIVE:
        return set(), repositories
    if not locators:
        return set(), set()
    url_refs = {ref for _, ref in locators if ref is not None}
    if len(repositories) != 1 or len(url_refs) > 1:
        return set(), repositories
    repo = next(iter(repositories))
    url_ref = next(iter(url_refs), None)
    refs = {value.strip("`* \"'") for key, value in fields if key in REFS}
    refs.discard("")
    if len(refs) > 1:
        return set(), {repo}
    ref = next(iter(refs), url_ref or "HEAD")
    if not valid_source(repo, ref) or (url_ref and ref != url_ref):
        return set(), {repo}
    return {(repo, ref)}, set()


def _local_record(fields, memoryroot):
    paths = set()
    for key, value in fields:
        if key not in LOCAL_LOCATORS:
            continue
        path = value.strip().strip("`\"'")
        if (
            not path.startswith("/")
            or path.startswith("//")
            or ".." in path.split("/")
            or any(ord(char) < 32 for char in path)
        ):
            continue
        path = os.path.normpath(path)
        if (
            path == "/"
            or path == memoryroot
            or path.startswith(memoryroot.rstrip("/") + "/")
            or ".dougle" in path.split("/")
        ):
            continue
        paths.add(path)
    states = {value.strip("`* \"'").casefold() for key, value in fields if key in STATES}
    if states and not states <= ACTIVE:
        return set(), paths
    if len(paths) != 1:
        return set(), paths
    return paths, set()


def _parse(text, record_parser=_record):
    found, blocked = set(), set()
    fields = []
    headers = None
    fenced = False

    def flush(values):
        pairs, excluded = record_parser(values)
        found.update(pairs)
        blocked.update(excluded)

    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith(("```", "~~~")):
            fenced = not fenced
            continue
        if fenced or stripped.startswith(">"):
            continue
        if stripped.startswith("#"):
            flush(fields)
            fields = []
            headers = None
            continue
        if stripped.startswith("|") and stripped.endswith("|"):
            cells = [cell.strip() for cell in stripped.strip("|").split("|")]
            labels = [_label(cell) for cell in cells]
            if set(labels) & LOCATORS:
                flush(fields)
                fields = []
                headers = labels
            elif headers and len(cells) == len(headers):
                if not all(re.fullmatch(r":?-+:?", cell) for cell in cells):
                    flush(list(zip(headers, cells, strict=True)))
            continue
        headers = None
        match = re.match(r"^(?:[-*+]\s+)?(.+?)\s*:\s*(.*?)\s*$", stripped)
        if match:
            key = _label(match[1])
            value = match[2].removeprefix("**").strip()
            if key in LOCATORS | REFS | STATES:
                fields.append((key, value))
    flush(fields)
    return found, blocked


def _open_directory(parent, name):
    try:
        return os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
    except OSError as exc:
        if exc.errno in (errno.ENOENT, errno.ENOTDIR, errno.ELOOP):
            return None
        raise


def _discover(memoryroot, *, local=False):
    root = Path(memoryroot).absolute()
    if ".." in root.parts:
        raise SourceRegistryError("invalid_memory_root")
    counters = {"entries": 0, "files": 0, "bytes": 0}
    primary, indexes, blocked = set(), set(), set()

    def names(fd):
        result = []
        with os.scandir(fd) as entries:
            for entry in entries:
                counters["entries"] += 1
                if counters["entries"] > MAX_ENTRIES:
                    raise SourceRegistryError("registry_entry_limit")
                result.append(entry.name)
        return sorted(result)

    def read(fd, name, destination):
        try:
            file_fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
        except OSError as exc:
            if exc.errno in (errno.ENOENT, errno.ELOOP):
                return
            raise
        info = os.fstat(file_fd)
        if not stat.S_ISREG(info.st_mode):
            os.close(file_fd)
            return
        with os.fdopen(file_fd, "rb") as stream:
            counters["files"] += 1
            if counters["files"] > MAX_FILES or info.st_size > MAX_FILE_BYTES:
                raise SourceRegistryError("registry_file_limit")
            data = stream.read(MAX_FILE_BYTES + 1)
            counters["bytes"] += len(data)
            if len(data) > MAX_FILE_BYTES or counters["bytes"] > MAX_TOTAL_BYTES:
                raise SourceRegistryError("registry_byte_limit")
            parser = (lambda fields: _local_record(fields, str(root))) if local else _record
            pairs, excluded = _parse(data.decode("utf-8"), parser)
            destination.update(pairs)
            blocked.update(excluded)

    fd = None
    try:
        fd = os.open(root.anchor, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        for part in root.parts[1:]:
            child = _open_directory(fd, part)
            os.close(fd)
            fd = child
            if fd is None:
                return set()
        read(fd, "index.md", indexes)
        sources = _open_directory(fd, "sources")
        if sources is not None:
            try:
                for name in names(sources):
                    if name.endswith(".md"):
                        read(sources, name, primary)
            finally:
                os.close(sources)
        workspaces = _open_directory(fd, "workspaces")
        if workspaces is not None:
            try:
                for name in names(workspaces):
                    workspace = _open_directory(workspaces, name)
                    if workspace is not None:
                        try:
                            read(workspace, "workspace.md", indexes)
                        finally:
                            os.close(workspace)
            finally:
                os.close(workspaces)
    except (OSError, UnicodeError, ValueError):
        raise SourceRegistryError("registry_unreadable") from None
    finally:
        if fd is not None:
            os.close(fd)
    if local:
        return (primary | indexes) - blocked
    primary_repos = {repo for repo, _ in primary}
    return {
        (repo, ref)
        for repo, ref in primary | indexes
        if repo not in blocked and ((repo, ref) in primary or repo not in primary_repos)
    }


def discover_github_sources(memoryroot) -> set[tuple[str, str]]:
    """Return exact (repository, ref) pairs; never fetch or modify a source."""
    return _discover(memoryroot)


def discover_local_sources(memoryroot) -> set[str]:
    """Return registered absolute original paths without touching those paths."""
    return _discover(memoryroot, local=True)
