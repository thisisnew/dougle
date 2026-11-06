"""The published runtime must contain the source exercised by the tests."""

import hashlib
import zipfile
from pathlib import Path


def test_bundled_wheel_matches_tested_source_and_declared_hashes():
    project = Path(__file__).resolve().parents[1]
    assets = project.parent / "skills/dougle-bot/assets"
    with zipfile.ZipFile(assets / "dougle_bot-0.1.2-py3-none-any.whl") as wheel:
        for source in (project / "src/dougle_bot").glob("*.py"):
            assert wheel.read("dougle_bot/" + source.name) == source.read_bytes(), source.name
    for line in (assets / "runtime.sha256").read_text().splitlines():
        expected, name = line.split()
        assert Path(name).name == name
        assert hashlib.sha256((assets / name).read_bytes()).hexdigest() == expected
