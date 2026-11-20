import os
import stat

import pytest

from dougle_gui.token import TokenError, load_or_create


def mode(path):
    return stat.S_IMODE(path.stat().st_mode)


def test_token_is_created_once_and_reused(tmp_path):
    home = tmp_path / "gui"
    first = load_or_create(home)
    assert len(first) >= 32
    assert load_or_create(home) == first
    assert mode(home) == 0o700
    assert mode(home / "token") == 0o600


def test_rotate_replaces_token(tmp_path):
    first = load_or_create(tmp_path / "gui")
    second = load_or_create(tmp_path / "gui", rotate=True)
    assert second != first
    assert load_or_create(tmp_path / "gui") == second


def test_invalid_saved_token_is_regenerated(tmp_path):
    home = tmp_path / "gui"
    home.mkdir(mode=0o700)
    (home / "token").write_text("short\n", encoding="utf-8")
    os.chmod(home / "token", 0o644)
    value = load_or_create(home)
    assert value != "short" and len(value) >= 32
    assert mode(home / "token") == 0o600


def test_loose_permissions_are_tightened(tmp_path):
    home = tmp_path / "gui"
    value = load_or_create(home)
    os.chmod(home, 0o755)
    os.chmod(home / "token", 0o644)
    assert load_or_create(home) == value
    assert mode(home) == 0o700 and mode(home / "token") == 0o600


def test_symlinked_token_file_is_refused(tmp_path):
    home = tmp_path / "gui"
    home.mkdir(mode=0o700)
    target = tmp_path / "elsewhere"
    target.write_text("x" * 40, encoding="utf-8")
    (home / "token").symlink_to(target)
    with pytest.raises(TokenError):
        load_or_create(home)


def test_symlinked_home_is_refused(tmp_path):
    real = tmp_path / "real"
    real.mkdir()
    (tmp_path / "gui").symlink_to(real)
    with pytest.raises(TokenError):
        load_or_create(tmp_path / "gui")
