import os

import pytest

from dougle_bot import source_registry as registry
from dougle_bot.source_registry import (
    SourceRegistryError,
    discover_github_sources,
    discover_local_sources,
)


def put(root, path, content):
    target = root / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")
    return target


def test_registered_files_and_legacy_index_only(tmp_path):
    put(
        tmp_path,
        "sources/service.md",
        "# Service\nLocation: https://github.com/org/service\nBranch: develop\nState: read",
    )
    put(tmp_path, "index.md", "Repository: https://github.com/org/indexed.git")
    put(
        tmp_path,
        "workspaces/old/workspace.md",
        "Canonical_root: https://github.com/org/legacy/tree/release/v2",
    )
    put(tmp_path, "knowledge/topic.md", "Repository: https://github.com/evil/knowledge")
    put(tmp_path, "workspaces/old/knowledge/topic.md", "Repository: https://github.com/evil/legacy")
    put(
        tmp_path,
        "workspaces/old/sources/unregistered.md",
        "Repository: https://github.com/evil/nested",
    )
    put(tmp_path, "sources/nested/source.md", "Repository: https://github.com/evil/nested")
    assert discover_github_sources(tmp_path) == {
        ("org/service", "develop"),
        ("org/indexed", "HEAD"),
        ("org/legacy", "release/v2"),
    }


@pytest.mark.parametrize(
    "label", ["Location", "Source", "Repository", "Canonical URL", "canonical_root", "Origin"]
)
def test_field_spellings_and_markdown(tmp_path, label):
    put(
        tmp_path,
        "sources/a.md",
        f"- **{label}:** [source](https://github.com/org/repo)\n- **Ref:** `refs/heads/release/v2`",
    )
    assert discover_github_sources(tmp_path) == {("org/repo", "refs/heads/release/v2")}


def test_tables_scope_ref_per_row(tmp_path):
    put(
        tmp_path,
        "index.md",
        """# Sources
| ID | Location | Branch | State |
| --- | --- | --- | --- |
| S1 | https://github.com/org/one | develop | read |
| S2 | https://github.com/org/two | release/v1 | partial |
""",
    )
    assert discover_github_sources(tmp_path) == {("org/one", "develop"), ("org/two", "release/v1")}


def test_record_headings_do_not_leak_ref(tmp_path):
    put(
        tmp_path,
        "index.md",
        "# One\nLocation: https://github.com/org/one\nRef: main\n# Two\nLocation: https://github.com/org/two",
    )
    assert discover_github_sources(tmp_path) == {("org/one", "main"), ("org/two", "HEAD")}


def test_empty_table_ref_means_remote_head(tmp_path):
    put(
        tmp_path, "index.md", "| Location | Ref |\n| --- | --- |\n| https://github.com/org/repo | |"
    )
    assert discover_github_sources(tmp_path) == {("org/repo", "HEAD")}


def test_authoritative_source_overrides_stale_index_default(tmp_path):
    put(tmp_path, "index.md", "Location: https://github.com/org/repo")
    put(tmp_path, "sources/a.md", "Location: https://github.com/org/repo\nRef: release/v1")
    assert discover_github_sources(tmp_path) == {("org/repo", "release/v1")}


@pytest.mark.parametrize(
    "state", ["disconnected", "pending_access", "unavailable", "deleted", "unknown"]
)
def test_inactive_source_not_resurrected_by_index(tmp_path, state):
    put(tmp_path, "index.md", "Location: https://github.com/org/repo")
    put(tmp_path, "sources/a.md", f"Location: https://github.com/org/repo\nState: {state}")
    assert discover_github_sources(tmp_path) == set()


@pytest.mark.parametrize(
    "location",
    [
        "https://github.com/org/repo/blob/main/code.py",
        "https://github.com/org/repo/issues/1",
        "https://github.com/org/repo?token=secret",
        "https://github.com/org/repo#section",
        "https://github.com.evil/org/repo",
        "https://github.com@evil/org/repo",
        "git@github.com:org/repo.git",
        "https://github.com/org/repo/tree/../main",
    ],
)
def test_noncanonical_locators_do_not_authorize(tmp_path, location):
    put(tmp_path, "sources/a.md", f"Location: {location}")
    assert discover_github_sources(tmp_path) == set()


def test_prose_code_block_quotes_and_pointers_never_authorize(tmp_path):
    put(
        tmp_path,
        "sources/a.md",
        """See https://github.com/evil/prose
```markdown
Location: https://github.com/evil/code
```
> Location: https://github.com/evil/quote
Evidence: https://github.com/evil/evidence
Location: ../knowledge/linked.md
""",
    )
    put(tmp_path, "knowledge/linked.md", "Location: https://github.com/evil/linked")
    assert discover_github_sources(tmp_path) == set()


@pytest.mark.parametrize("extra", ["Ref: main", "Ref: develop\nBranch: main"])
def test_conflicting_tree_and_explicit_ref_excluded(tmp_path, extra):
    put(tmp_path, "sources/a.md", f"Location: https://github.com/org/repo/tree/develop\n{extra}")
    assert discover_github_sources(tmp_path) == set()


def test_encoded_slash_ref_preserved(tmp_path):
    put(
        tmp_path,
        "sources/a.md",
        "Location: https://github.com/org/repo/tree/release%2Fv1\nRef: release/v1",
    )
    assert discover_github_sources(tmp_path) == {("org/repo", "release/v1")}


def test_symlink_files_directories_and_root_never_followed(tmp_path):
    outside = tmp_path / "outside"
    put(outside, "sources/a.md", "Location: https://github.com/evil/outside")
    root = tmp_path / "memory"
    root.mkdir()
    (root / "index.md").symlink_to(outside / "sources/a.md")
    (root / "sources").symlink_to(outside / "sources", target_is_directory=True)
    (root / "workspaces").mkdir()
    (root / "workspaces/old").symlink_to(outside, target_is_directory=True)
    assert discover_github_sources(root) == set()
    linked_root = tmp_path / "linked-root"
    linked_root.symlink_to(outside, target_is_directory=True)
    assert discover_github_sources(linked_root) == set()


def test_symlink_source_file_and_nonregular_file_skipped(tmp_path):
    target = put(tmp_path, "outside.md", "Location: https://github.com/evil/repo")
    (tmp_path / "sources").mkdir()
    (tmp_path / "sources/symlink.md").symlink_to(target)
    (tmp_path / "sources/directory.md").mkdir()
    os.mkfifo(tmp_path / "sources/pipe.md")
    assert discover_github_sources(tmp_path) == set()


def test_missing_root_returns_empty_without_creation(tmp_path):
    missing = tmp_path / "missing"
    assert discover_github_sources(missing) == set()
    assert not missing.exists()


@pytest.mark.parametrize(
    "limit,value,code",
    [
        ("MAX_FILES", 0, "registry_file_limit"),
        ("MAX_FILE_BYTES", 10, "registry_file_limit"),
        ("MAX_TOTAL_BYTES", 10, "registry_byte_limit"),
        ("MAX_ENTRIES", 0, "registry_entry_limit"),
    ],
)
def test_limits_fail_without_partial_results(tmp_path, monkeypatch, limit, value, code):
    put(tmp_path, "sources/a.md", "Location: https://github.com/org/repo")
    monkeypatch.setattr(registry, limit, value)
    with pytest.raises(SourceRegistryError, match=code):
        discover_github_sources(tmp_path)


def test_invalid_utf8_is_incomplete_discovery(tmp_path):
    (tmp_path / "index.md").write_bytes(b"\xff")
    with pytest.raises(SourceRegistryError, match="registry_unreadable"):
        discover_github_sources(tmp_path)


def test_relative_root_uses_only_explicit_argument(tmp_path, monkeypatch):
    root = tmp_path / "memory"
    put(root, "sources/a.md", "Location: https://github.com/org/repo")
    monkeypatch.chdir(tmp_path)
    assert discover_github_sources("memory") == {("org/repo", "HEAD")}


def test_no_reads_of_knowledge_or_arbitrary_pointer(tmp_path, monkeypatch):
    put(tmp_path, "index.md", "Location: knowledge/a.md\nSource: /outside/source.md")
    put(tmp_path, "knowledge/a.md", "Location: https://github.com/evil/repo")
    original = os.open
    calls = []

    def tracking(path, *args, **kwargs):
        calls.append(str(path))
        return original(path, *args, **kwargs)

    monkeypatch.setattr(registry.os, "open", tracking)
    assert discover_github_sources(tmp_path) == set()
    assert "knowledge" not in calls and "a.md" not in calls and "/outside/source.md" not in calls


def test_local_originals_discovered_across_allowed_layouts(tmp_path):
    put(tmp_path, "sources/a.md", "Location: `/original/service/`\nState: read")
    put(
        tmp_path,
        "index.md",
        "| Canonical location | State |\n| --- | --- |\n| /original/indexed | partial |",
    )
    put(tmp_path, "workspaces/old/workspace.md", "Canonical_root: /original/legacy\nState: read")
    put(tmp_path, "knowledge/topic.md", "Location: /evil/knowledge")
    assert discover_local_sources(tmp_path) == {
        "/original/service",
        "/original/indexed",
        "/original/legacy",
    }


@pytest.mark.parametrize("field", ["Root", "Path", "Workspace", "Source", "Repository", "URL"])
def test_generic_local_pointers_do_not_register_originals(tmp_path, field):
    put(tmp_path, "workspaces/old/workspace.md", f"{field}: /original/project")
    assert discover_local_sources(tmp_path) == set()


@pytest.mark.parametrize(
    "path",
    [
        "/",
        "//server/share",
        "../project",
        "~/project",
        "/original/../project",
        "/original\x00/project",
        "/other/.dougle/workspaces/old",
    ],
)
def test_ambiguous_or_memory_paths_not_local_originals(tmp_path, path):
    put(tmp_path, "sources/a.md", f"Location: {path}")
    assert discover_local_sources(tmp_path) == set()


def test_memory_root_and_legacy_workspace_pointers_excluded(tmp_path):
    put(tmp_path, "sources/a.md", f"Location: {tmp_path}")
    put(tmp_path, "workspaces/old/workspace.md", f"Canonical_root: {tmp_path}/workspaces/old")
    put(tmp_path, "index.md", f"Location: {tmp_path}/workspaces/old/workspace.md")
    assert discover_local_sources(tmp_path) == set()


def test_local_state_exclusion_and_remote_local_coexistence(tmp_path):
    put(tmp_path, "index.md", "Location: /original/disconnected")
    put(tmp_path, "sources/a.md", "Location: /original/disconnected\nState: disconnected")
    put(
        tmp_path,
        "sources/b.md",
        "Location: /original/service\nOrigin: https://github.com/org/repo\nRef: main",
    )
    assert discover_local_sources(tmp_path) == {"/original/service"}
    assert discover_github_sources(tmp_path) == {("org/repo", "main")}


def test_local_discovery_never_inspects_original_filesystem(tmp_path, monkeypatch):
    put(tmp_path, "sources/a.md", "Location: /nonexistent original/service")
    original_open = os.open

    def guarded_open(path, *args, **kwargs):
        assert not str(path).startswith("/nonexistent")
        return original_open(path, *args, **kwargs)

    def no_path_inspection(*args, **kwargs):
        raise AssertionError("Original stat/realpath forbidden")

    monkeypatch.setattr(registry.os, "open", guarded_open)
    monkeypatch.setattr(registry.os, "stat", no_path_inspection)
    monkeypatch.setattr(registry.os, "lstat", no_path_inspection)
    monkeypatch.setattr(registry.os.path, "realpath", no_path_inspection)
    assert discover_local_sources(tmp_path) == {"/nonexistent original/service"}


def test_local_discovery_shares_bounds_and_symlink_exclusion(tmp_path, monkeypatch):
    source = put(tmp_path, "outside.md", "Location: /original/outside")
    (tmp_path / "sources").mkdir()
    (tmp_path / "sources/link.md").symlink_to(source)
    assert discover_local_sources(tmp_path) == set()
    put(tmp_path, "sources/real.md", "Location: /original/real")
    monkeypatch.setattr(registry, "MAX_FILES", 0)
    with pytest.raises(SourceRegistryError, match="registry_file_limit"):
        discover_local_sources(tmp_path)


@pytest.mark.parametrize("state", ["disconnected", "pending_access", "read"])
def test_ambiguous_authoritative_refs_never_restore_index_head(tmp_path, state):
    put(tmp_path, "index.md", "Location: https://github.com/org/repo")
    put(
        tmp_path,
        "sources/a.md",
        f"Location: https://github.com/org/repo/tree/main\n"
        f"Canonical root: https://github.com/org/repo/tree/develop\nState: {state}",
    )
    assert discover_github_sources(tmp_path) == set()


def test_root_and_single_tree_registration_preserves_ref(tmp_path):
    put(tmp_path, "index.md", "Location: https://github.com/org/repo")
    put(
        tmp_path,
        "sources/a.md",
        "Location: https://github.com/org/repo\n"
        "Canonical root: https://github.com/org/repo/tree/develop\nState: read",
    )
    assert discover_github_sources(tmp_path) == {("org/repo", "develop")}


def test_inactive_ambiguous_local_record_blocks_stale_index(tmp_path):
    put(tmp_path, "index.md", "Location: /original/one")
    put(
        tmp_path,
        "sources/a.md",
        "Location: /original/one\nCanonical root: /original/two\nState: disconnected",
    )
    assert discover_local_sources(tmp_path) == set()
