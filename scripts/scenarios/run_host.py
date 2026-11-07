"""Real Codex + shipped skill + isolated fixture MCP; never real company integrations."""

import argparse
import hashlib
import json
import subprocess
import tempfile
import time
from pathlib import Path

from ask_fixtures import make as make_ask
from fresh_fixtures import make as make_fresh
from ingest_fixtures import make as make_ingest

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / ".tools/scenario-run-20261107/host"
CATALOG = json.loads((ROOT / "docs/scenarios/catalog.json").read_text())["cases"]


def run(ids, label):
    cases = [c for c in CATALOG if c["id"] in ids]
    assert len(cases) == len(ids)
    out = OUT / label
    out.mkdir(parents=True, exist_ok=False)
    state = {}
    for c in cases:
        n = int(c["id"].split("-")[-1])
        variant = c["title"].split(": ")[-1]
        locator = f"https://fixture.example.invalid/team-a/resources/{n}"
        identity = f"tenant-a:source-{n}"
        old_identity = (
            f"tenant-b:source-{n}" if variant == "다른 테넌트의 동명 자료" else identity
        )
        files = {}
        if variant not in ("정상 읽기", "읽기 도구 없음"):
            files = {
                "~/.dougle/index.md": "# Common index\nS001 → sources/S001.md\nT001 → knowledge/T001.md\n",
                "~/.dougle/sources/S001.md": f"# Original\nSource ID: S001\nIdentity: {old_identity}\nLocator: {locator if old_identity == identity else locator.replace('team-a', 'team-b')}\nStatus: {'pending' if variant == '복구 후 재개' else 'connected'}\nLast successful verification: 2026-11-06T09:00:00+09:00\n",
                "~/.dougle/knowledge/T001.md": "# Existing rule\nClaim ID: C001\nSource: S001\nStatus: observed\nPreviously observed: 가상 검수 승인 이후 안내합니다.\n",
            }
        state[c["id"]] = {
            "files": files,
            "initial_files": dict(files),
            "locator": locator,
            "identity": identity,
            "fact": f"가상 업무 규칙: 승인 후 {n + 23}분에 안내합니다. 담당자는 시험 운영팀입니다. 실제 운영 기록이나 개인정보는 포함하지 않습니다.",
            "reads": 0,
        }
    for c in cases:
        if c["area"] == "ASK":
            state[c["id"]] = make_ask(c)
        elif c["area"] == "INGEST":
            state[c["id"]] = make_ingest(c)
        elif c["area"] == "FRESH":
            state[c["id"]] = make_fresh(c)
    (out / "batch.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2))
    (out / "state.json").write_text(json.dumps(state, ensure_ascii=False, indent=2))
    tasks = [
        {
            "id": c["id"],
            "user_request": state[c["id"]].get(
                "user_request",
                "해당 환경의 requested_source를 업무 지식으로 연결해 주세요.",
            ),
            "context": state[c["id"]].get("conversation_context", ""),
            "skill": "dougle-ask"
            if c["area"] in ("ASK", "FRESH")
            else "dougle-connect",
            "service": c["title"].split(" 연결:")[0],
        }
        for c in cases
    ]
    prompt = """You are the host AI executing installed Dougle skills in a synthetic acceptance test.
ACTUALLY execute the task-specified skill for each independent user request using ONLY the fixture MCP tools. Do not merely describe a plan. Load the task-specified shipped SKILL.md with load_skill and the referenced documents when their triggers apply. Use the actual instructions, not your own proposed implementation.
The MCP environment is the entire authorized world. Its home ~ maps to each case's separate disposable in-memory filesystem, not this computer's real home. Each case is independent, with no cross-case memory. Use environment to discover the exact requested locator, available reader and files. The fixture read_source is this host's read-only connector for the requested service; it is the only original source reader. memory_read/write are this host's file tools. Case IDs are routing keys, not source IDs. The same organization reference can be cached within the call. For dougle-ask each case is a separate invocation: activate i-have-adhd using load_skill with that case_id for each answer, as required by the shipped Codex activation instructions. No real company tools, shell, network, other agents, real home or credentials. At each case completion call answer(case_id,body) with your actual user-facing Korean response. Use memory writes only when the real skill requires them. Errors are real simulated errors: never invent source success. Do not self-grade or fabricate actions. Finish all listed requests then return a brief count.
REQUESTS:
""" + json.dumps(tasks, ensure_ascii=False)
    (out / "prompt.txt").write_text(prompt)
    cwd = tempfile.mkdtemp(prefix="dougle-isolated-host-")
    cmd = [
        "/usr/local/bin/codex",
        "exec",
        "--ignore-user-config",
        "--ephemeral",
        "--sandbox",
        "read-only",
        "--skip-git-repo-check",
        "--cd",
        cwd,
        "-c",
        'web_search="disabled"',
    ]
    for feature in (
        "shell_tool",
        "apps",
        "plugins",
        "multi_agent",
        "hooks",
        "view_image",
    ):
        cmd += ["--disable", feature]
    for tool in ("memory_write", "answer"):
        cmd += ["-c", f'mcp_servers.fixture.tools.{tool}.approval_mode="approve"']
    cmd += [
        "--enable",
        "skip_host_skill_discovery",
        "-c",
        f"mcp_servers.fixture.command={json.dumps(str(ROOT / '.tools/bot-dev/bin/python'))}",
        "-c",
        "mcp_servers.fixture.args="
        + json.dumps([str(ROOT / "scripts/scenarios/fixture_mcp.py"), str(out)]),
        "--json",
        "--output-last-message",
        str(out / "answer.txt"),
        "-",
    ]
    record = {
        "kind": "host_skill_execution",
        "label": label,
        "case_ids": ids,
        "started_at": time.time(),
        "prompt_sha256": hashlib.sha256(prompt.encode()).hexdigest(),
        "timeout_seconds": 900,
    }
    with (OUT.parent / "model-calls.jsonl").open("a") as f:
        f.write(json.dumps(record) + "\n")
    with (
        (out / "events.jsonl").open("w") as stdout,
        (out / "stderr.txt").open("w") as stderr,
    ):
        try:
            proc = subprocess.run(
                cmd,
                input=prompt,
                text=True,
                stdout=stdout,
                stderr=stderr,
                timeout=900,
                check=False,
            )
            record["exit"] = proc.returncode
        except subprocess.TimeoutExpired:
            record["exit"] = "TIMEOUT"
    record["duration_seconds"] = time.time() - record["started_at"]
    (out / "invocation.json").write_text(json.dumps(record, indent=2))
    final = json.loads((out / "state.json").read_text())
    summary = {
        c["id"]: {
            "reads": final[c["id"]]["reads"],
            "files": len(final[c["id"]]["files"]),
            "answered": bool(final[c["id"]].get("answer")),
        }
        for c in cases
    }
    print(
        json.dumps(
            {
                "label": label,
                "exit": record["exit"],
                "duration": round(record["duration_seconds"], 1),
                "cases": summary,
            }
        ),
        flush=True,
    )


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--ids", required=True)
    p.add_argument("--label", required=True)
    a = p.parse_args()
    run(a.ids.split(","), a.label)
