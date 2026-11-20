"""List the knowledge packages a shared GitHub repository offers (read-only).

Uses the user's existing `gh` login. Nothing is cloned or written; the result
only helps the user pick what to fetch. The fetch skill still validates the
package itself.
"""

from __future__ import annotations

import base64
import json
import re
import shutil
import subprocess

PACKAGE_ROOT = "knowledge"
SHARING_BRANCH = "dougle-knowledge"
GH_TIMEOUT = 20
MAX_PACKAGES = 50
_REPO = re.compile(r"^(?:https?://)?github\.com/([A-Za-z0-9-]{1,39})/([A-Za-z0-9._-]{1,100}?)(?:\.git)?/?$")
_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")


class RemoteError(Exception):
    def __init__(self, code: str, status: int = 400, detail: str = ""):
        super().__init__(code)
        self.code = code
        self.status = status
        self.detail = detail


def parse_repo(value: str) -> tuple[str, str]:
    match = _REPO.match((value or "").strip())
    if not match:
        raise RemoteError("unsupported_repository")
    return match.group(1), match.group(2)


def _field(text: str, name: str) -> str:
    match = re.search(rf"^{re.escape(name)}:\s*(.+)$", text, re.MULTILINE)
    return match.group(1).strip() if match else ""


def summarize_manifest(text: str) -> dict:
    scope = ""
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if line.strip() in ("## 포함 범위", "## Scope", "## Included scope"):
            for following in lines[index + 1:]:
                if following.strip():
                    scope = following.strip()
                    break
            break
    return {
        "format": _field(text, "Format"),
        "project": _field(text, "Project-ID"),
        "exported": _field(text, "Exported"),
        "title": lines[0].lstrip("#").strip() if lines and lines[0].startswith("#") else "",
        "scope": scope if len(scope) <= 400 else scope[:400].rstrip() + "…",
        "topics": len(set(re.findall(r"\]\(topics/([^)]+\.md)\)", text))),
    }


class Remote:
    def __init__(self, gh_bin: str = "gh"):
        self.gh_bin = gh_bin

    def _gh(self, path: str):
        binary = shutil.which(self.gh_bin) if "/" not in self.gh_bin else self.gh_bin
        if not binary:
            raise RemoteError("gh_not_found", 503)
        try:
            done = subprocess.run(
                [binary, "api", "-H", "Accept: application/vnd.github+json", path],
                stdin=subprocess.DEVNULL,
                capture_output=True,
                text=True,
                timeout=GH_TIMEOUT,
            )
        except subprocess.TimeoutExpired as exc:
            raise RemoteError("gh_timeout", 504) from exc
        if done.returncode != 0:
            message = (done.stdout + done.stderr).strip()
            if "404" in message or "Not Found" in message:
                return None
            if "401" in message or "auth login" in message:
                raise RemoteError("gh_not_logged_in", 401)
            raise RemoteError("gh_failed", 502, message[-300:])
        try:
            return json.loads(done.stdout)
        except json.JSONDecodeError as exc:
            raise RemoteError("gh_failed", 502) from exc

    def packages(self, repository: str) -> dict:
        owner, repo = parse_repo(repository)
        info = self._gh(f"repos/{owner}/{repo}")
        if info is None:
            # GitHub answers 404 for both missing and inaccessible private repositories.
            raise RemoteError("repository_not_accessible", 404)
        default = str(info.get("default_branch") or "main")
        branches = [default]
        if default != SHARING_BRANCH and self._gh(f"repos/{owner}/{repo}/branches/{SHARING_BRANCH}") is not None:
            branches.append(SHARING_BRANCH)
        found = []
        for branch in branches:
            if not _NAME.fullmatch(branch):
                continue
            listing = self._gh(f"repos/{owner}/{repo}/contents/{PACKAGE_ROOT}?ref={branch}")
            if not isinstance(listing, list):
                continue
            for entry in listing:
                name = str(entry.get("name", ""))
                if entry.get("type") != "dir" or not _NAME.fullmatch(name):
                    continue
                manifest = self._gh(f"repos/{owner}/{repo}/contents/{PACKAGE_ROOT}/{name}/manifest.md?ref={branch}")
                if not isinstance(manifest, dict) or manifest.get("encoding") != "base64":
                    continue
                try:
                    text = base64.b64decode(manifest.get("content", "")).decode("utf-8", errors="replace")
                except ValueError:
                    continue
                summary = summarize_manifest(text)
                summary.update(branch=branch, path=f"{PACKAGE_ROOT}/{name}/", folder=name)
                summary["project"] = summary["project"] or name
                found.append(summary)
                if len(found) >= MAX_PACKAGES:
                    break
        return {
            "repository": f"github.com/{owner}/{repo}",
            "private": bool(info.get("private")),
            "default_branch": default,
            "packages": found,
        }
