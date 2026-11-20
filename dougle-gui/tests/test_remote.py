import base64
import json
import stat
import sys
import textwrap

import pytest

from dougle_gui.remote import Remote, RemoteError, parse_repo, summarize_manifest

MANIFEST = """\
# receiving 지식 패키지
Format: dougle-knowledge/v1
Project-ID: receiving
Exported: 2026-01-02

## 포함 범위
입고 서비스 코드와 문서에서 정리한 개념.

## Topics
- [a](topics/a.md) — A
- [b](topics/b.md) — B, see also [a](topics/a.md)
"""


def make_gh(tmp_path, responses: dict, *, fail: str | None = None):
    """Fake gh: `gh api ... <path>` prints responses[path] or fails like a 404."""
    data = tmp_path / "gh-responses.json"
    data.write_text(json.dumps(responses), encoding="utf-8")
    log = tmp_path / "gh-calls.log"
    script = tmp_path / "fake-gh"
    script.write_text(textwrap.dedent(f"""\
        #!{sys.executable}
        import json, sys
        path = sys.argv[-1]
        open({str(log)!r}, "a").write(" ".join(sys.argv[1:]) + "\\n")
        if {fail!r}:
            print({fail!r}, file=sys.stderr); sys.exit(1)
        table = json.load(open({str(data)!r}))
        if path not in table:
            print("gh: Not Found (HTTP 404)", file=sys.stderr); sys.exit(1)
        print(json.dumps(table[path]))
    """), encoding="utf-8")
    script.chmod(script.stat().st_mode | stat.S_IXUSR)
    return Remote(gh_bin=str(script)), log


def manifest_entry(text):
    return {"encoding": "base64", "content": base64.b64encode(text.encode()).decode()}


def test_parse_repo_accepts_github_urls_only():
    assert parse_repo("https://github.com/org/knowledge") == ("org", "knowledge")
    assert parse_repo("github.com/org/knowledge.git/") == ("org", "knowledge")
    for bad in ("https://gitlab.com/org/x", "org/knowledge", "https://github.com/org", "https://github.com/a/b/../c",
                "https://github.com/org/x?ref=y", ""):
        with pytest.raises(RemoteError):
            parse_repo(bad)


def test_summarize_manifest():
    summary = summarize_manifest(MANIFEST)
    assert summary == {
        "format": "dougle-knowledge/v1",
        "project": "receiving",
        "exported": "2026-01-02",
        "title": "receiving 지식 패키지",
        "scope": "입고 서비스 코드와 문서에서 정리한 개념.",
        "topics": 2,
    }


def test_lists_packages_on_default_and_sharing_branch(tmp_path):
    remote, log = make_gh(tmp_path, {
        "repos/org/kb": {"default_branch": "main", "private": True},
        "repos/org/kb/branches/dougle-knowledge": {"name": "dougle-knowledge"},
        "repos/org/kb/contents/knowledge?ref=main": [
            {"name": "receiving", "type": "dir"},
            {"name": "README.md", "type": "file"},
            {"name": "../evil", "type": "dir"},
        ],
        "repos/org/kb/contents/knowledge/receiving/manifest.md?ref=main": manifest_entry(MANIFEST),
        "repos/org/kb/contents/knowledge?ref=dougle-knowledge": [{"name": "shipping", "type": "dir"}],
        "repos/org/kb/contents/knowledge/shipping/manifest.md?ref=dougle-knowledge":
            manifest_entry("# shipping\nFormat: dougle-knowledge/v1\n"),
    })
    result = remote.packages("https://github.com/org/kb")
    assert result["repository"] == "github.com/org/kb" and result["private"] is True
    assert [(p["project"], p["branch"], p["path"]) for p in result["packages"]] == [
        ("receiving", "main", "knowledge/receiving/"),
        ("shipping", "dougle-knowledge", "knowledge/shipping/"),
    ]
    assert "evil" not in log.read_text()
    assert all(line.startswith("api ") for line in log.read_text().splitlines())


def test_repository_without_packages(tmp_path):
    remote, _ = make_gh(tmp_path, {"repos/org/empty": {"default_branch": "main"}})
    assert remote.packages("github.com/org/empty")["packages"] == []


def test_inaccessible_repository(tmp_path):
    remote, _ = make_gh(tmp_path, {})
    with pytest.raises(RemoteError) as err:
        remote.packages("https://github.com/org/private")
    assert err.value.code == "repository_not_accessible" and err.value.status == 404


def test_gh_not_logged_in(tmp_path):
    remote, _ = make_gh(tmp_path, {}, fail="HTTP 401: Bad credentials. Try: gh auth login")
    with pytest.raises(RemoteError) as err:
        remote.packages("https://github.com/org/kb")
    assert err.value.code == "gh_not_logged_in"


def test_missing_gh(tmp_path):
    with pytest.raises(RemoteError) as err:
        Remote(gh_bin="definitely-not-installed-gh").packages("https://github.com/org/kb")
    assert err.value.code == "gh_not_found"
