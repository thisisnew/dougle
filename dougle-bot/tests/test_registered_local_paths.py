"""Canonical local-source authorization; temporary paths, no service/model calls."""

from pathlib import Path

import pytest

from dougle_bot.guard import decision, permitted, registered_local_target


def policy(tmp_path, roots=(), *, github=True, complete=True):
    return {
        "allowed": ["Read", "Grep", "Glob"], "skills": [],
        "memory": str(tmp_path / "memory"), "runtime_home": str(tmp_path / "runtime"),
        "source_policy": {
            "github": [["synthetic/repo", "main"]] if github else [],
            "local": [str(root) for root in roots], "complete": complete,
        },
    }


def allowed(config, name, path):
    args = {"file_path" if name == "Read" else "path": str(path)}
    if name == "Grep":
        args.update(pattern=".", output_mode="content")
    expected = permitted(config, name, args)
    actual = decision(config, {"tool_name": name, "tool_input": args})["hookSpecificOutput"]
    assert (actual["permissionDecision"] == "allow") == expected
    return expected


@pytest.mark.parametrize("name", ["Read", "Grep"])
@pytest.mark.parametrize("github", [False, True])
@pytest.mark.parametrize("link_kind", ["directory", "file"])
def test_registered_prefix_cannot_authorize_symlink_escape(tmp_path, name, github, link_kind):
    root, outside = tmp_path / "registered", tmp_path / "unregistered"
    root.mkdir()
    outside.mkdir()
    target = outside / "File.java"
    target.write_text("class Outside {}")
    if link_kind == "directory":
        (root / "link").symlink_to(outside, target_is_directory=True)
        requested = root / "link/File.java"
    else:
        requested = root / "File.java"
        requested.symlink_to(target)
    config = policy(tmp_path, [root], github=github)
    assert registered_local_target(requested, [root]) is None
    assert not allowed(config, name, requested)


@pytest.mark.parametrize("name", ["Read", "Grep"])
def test_internal_link_and_alias_registered_root_remain_usable(tmp_path, name):
    root = tmp_path / "canonical"
    root.mkdir()
    target = root / "File.java"
    target.write_text("class Inside {}")
    alias = tmp_path / "alias"
    alias.symlink_to(root, target_is_directory=True)
    (root / "link.java").symlink_to(target)
    config = policy(tmp_path, [alias])
    for path in (target, alias / "File.java", root / "link.java"):
        assert registered_local_target(path, [alias]) == str(target.resolve())
        assert allowed(config, name, path)


def test_macos_tmp_alias_is_compared_canonically_without_file_access(tmp_path):
    canonical_tmp = Path("/tmp").resolve()
    requested = canonical_tmp / "synthetic-unopened-source.java"
    assert registered_local_target(requested, ["/tmp"]) == str(requested)
    assert allowed(policy(tmp_path, ["/tmp"]), "Read", requested)


@pytest.mark.parametrize("name", ["Read", "Grep"])
def test_remote_only_or_incomplete_registry_does_not_allow_local_code(tmp_path, name):
    target = tmp_path / "outside.java"
    for config in (policy(tmp_path), policy(tmp_path, github=False, complete=False)):
        assert not allowed(config, name, target)


@pytest.mark.parametrize("name", ["Read", "Grep"])
def test_registered_local_file_does_not_authorize_sibling_or_prefix_neighbor(tmp_path, name):
    root = tmp_path / "registered"
    root.mkdir()
    first, sibling = root / "Allowed.java", root / "Other.java"
    first.write_text("class Allowed {}")
    sibling.write_text("class Other {}")
    assert allowed(policy(tmp_path, [first]), name, first)
    assert not allowed(policy(tmp_path, [first]), name, sibling)
    assert not allowed(policy(tmp_path, [root]), name, tmp_path / "registered-extra/Other.java")


def test_memory_code_suffix_and_noncode_reads_are_not_original_code(tmp_path):
    config = policy(tmp_path)
    memory = Path(config["memory"])
    memory.mkdir()
    note = memory / "note.py"
    note.write_text("curated memory")
    assert allowed(config, "Read", note)
    assert allowed(config, "Read", tmp_path / "instructions.md")
    assert allowed(config, "Glob", tmp_path / "originals")


def test_no_registry_preserves_existing_read_contract(tmp_path):
    config = policy(tmp_path, github=False)
    target = tmp_path / "legacy.java"
    assert allowed(config, "Read", target)
    config.pop("source_policy")
    assert allowed(config, "Read", target)


def test_alias_hiding_code_extension_does_not_bypass_registered_bounds(tmp_path):
    root, outside = tmp_path / "registered", tmp_path / "outside"
    root.mkdir()
    outside.mkdir()
    target = outside / "File.java"
    target.write_text("class Outside {}")
    alias = root / "extensionless"
    alias.symlink_to(target)
    assert not allowed(policy(tmp_path, [root]), "Read", alias)


def test_relative_traversal_and_symlink_loop_do_not_authorize(tmp_path):
    root = tmp_path / "registered"
    root.mkdir()
    loop = root / "loop.java"
    loop.symlink_to(loop)
    for path in ("relative.java", root / "../outside.java", loop):
        assert registered_local_target(path, [root]) is None
    assert not allowed(policy(tmp_path, [root]), "Read", loop)
