"""Execute the distributed bootstrap with fake platform/uv, real hashes and isolated homes."""

import os
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]


@pytest.mark.parametrize(
    "variant",
    ["corrupt", "network", "partial", "repeat"],
    ids=["DG-INSTALL-017", "DG-INSTALL-022", "DG-INSTALL-018", "DG-INSTALL-023"],
)
def test_catalog_bootstrap_isolated(tmp_path, variant):
    skill = tmp_path / "한글 설치 경로"
    shutil.copytree(ROOT / "skills/dougle-bot", skill)
    home = tmp_path / "fake-bot-home"
    home.mkdir()
    (home / "config.toml").write_text("existing config must survive\n")
    (home / "bot.db").write_bytes(b"existing history")
    runtime = home / "runtime/0.1.25"
    if variant == "partial":
        runtime.mkdir(parents=True)
        (runtime / "partial-file").write_text("unfinished")
    if variant == "repeat":
        (runtime / "bin").mkdir(parents=True)
        exe = runtime / "bin/dougle-bot"
        exe.write_text("#!/bin/sh\nprintf '0.1.25\\n'\n")
        exe.chmod(0o755)
    if variant == "corrupt":
        wheel = next((skill / "assets").glob("*.whl"))
        wheel.write_bytes(wheel.read_bytes() + b"corrupt")
    tools = tmp_path / "bin"
    tools.mkdir()
    for name, body in {"uname": "printf 'Darwin\\n'", "sysctl": "printf '1\\n'"}.items():
        p = tools / name
        p.write_text("#!/bin/sh\n" + body + "\n")
        p.chmod(0o755)
    uv = tools / "uv"
    uv.write_text("""#!/bin/sh
printf 'called\\n' >> "$DOUGLE_FIXTURE_CALLS"
[ "$DOUGLE_FIXTURE_FAILURE" != network ] || exit 9
if [ "$1" = venv ]; then
 for arg do destination=$arg; done
 mkdir -p "$destination/bin"
 printf '#!/bin/sh\\nprintf "0.1.25\\\\n"\\n' > "$destination/bin/dougle-bot"
 chmod +x "$destination/bin/dougle-bot"
fi
exit 0
""")
    uv.chmod(0o755)
    marker = tmp_path / "calls"
    before = {p.name: p.read_bytes() for p in [home / "config.toml", home / "bot.db"]}
    result = subprocess.run(
        ["/bin/sh", str(skill / "scripts/bot.sh"), "prepare"],
        env={
            **os.environ,
            "PATH": str(tools) + ":" + os.environ["PATH"],
            "DOUGLE_BOT_HOME": str(home),
            "DOUGLE_FIXTURE_FAILURE": variant,
            "DOUGLE_FIXTURE_CALLS": str(marker),
        },
        text=True,
        capture_output=True,
        check=False,
        timeout=10,
    )
    assert before == {p.name: p.read_bytes() for p in [home / "config.toml", home / "bot.db"]}
    assert not list((home / "runtime").glob(".install.*"))
    if variant == "repeat":
        assert result.returncode == 0 and "0.1.25" in result.stdout
        assert not marker.exists()
    else:
        assert result.returncode != 0
        if variant == "corrupt":
            assert "설치 파일 검증에 실패" in result.stderr and not marker.exists()
        if variant == "partial":
            assert (runtime / "partial-file").read_text() == "unfinished"
        assert not (runtime / "bin/dougle-bot").exists()
