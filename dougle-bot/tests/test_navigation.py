"""The compact runtime may supply an untrusted index, never source evidence."""

from dougle_bot.navigation import MAX_INDEX_BYTES, read_navigation_index


def test_reads_only_a_complete_small_utf8_regular_index(tmp_path):
    index = tmp_path / "index.md"
    index.write_text("# 색인\nAlpha → knowledge/T001.md\n")
    assert read_navigation_index(tmp_path) == index.read_text()

    index.write_bytes(b"x" * (MAX_INDEX_BYTES + 1))
    assert read_navigation_index(tmp_path) is None

    index.write_bytes(b"\xff")
    assert read_navigation_index(tmp_path) is None


def test_symlink_index_is_not_followed(tmp_path):
    outside = tmp_path / "outside.md"
    outside.write_text("do not read")
    (tmp_path / "index.md").symlink_to(outside)
    assert read_navigation_index(tmp_path) is None


def test_symlink_parent_is_not_followed(tmp_path):
    actual = tmp_path / "actual"
    actual.mkdir()
    (actual / "index.md").write_text("private navigation")
    alias = tmp_path / "alias"
    alias.symlink_to(actual, target_is_directory=True)
    assert read_navigation_index(alias) is None
