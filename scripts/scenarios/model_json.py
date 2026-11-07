"""Isolated model computation runner. No tools or company context; authorization recorded."""

import hashlib
import json
import subprocess
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / ".tools/scenario-run-20261107"


def invoke(label, prompt, schema, timeout=900, images=()):
    folder = OUT / "model" / label
    folder.mkdir(parents=True, exist_ok=False)
    (folder / "prompt.txt").write_text(prompt)
    (folder / "schema.json").write_text(json.dumps(schema))
    record = {
        "label": label,
        "kind": "isolated_model_computation",
        "started_at": time.time(),
        "prompt_sha256": hashlib.sha256(prompt.encode()).hexdigest(),
    }
    with (OUT / "model-calls.jsonl").open("a") as f:
        f.write(json.dumps(record) + "\n")
    with tempfile.TemporaryDirectory(prefix="dougle-isolated-model-") as cwd:
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
        for image_path in images:
            cmd += ["--image", str(image_path)]
        cmd += [
            "--enable",
            "skip_host_skill_discovery",
            "--json",
            "--output-schema",
            str(folder / "schema.json"),
            "--output-last-message",
            str(folder / "answer.json"),
            "-",
        ]
        with (
            (folder / "events.jsonl").open("w") as stdout,
            (folder / "stderr.txt").open("w") as stderr,
        ):
            try:
                p = subprocess.run(
                    cmd,
                    input=prompt,
                    text=True,
                    stdout=stdout,
                    stderr=stderr,
                    timeout=timeout,
                    check=False,
                )
                record["exit"] = p.returncode
            except subprocess.TimeoutExpired:
                record["exit"] = "TIMEOUT"
    record["duration_seconds"] = time.time() - record["started_at"]
    usage = []
    for line in (folder / "events.jsonl").read_text().splitlines():
        try:
            event = json.loads(line)
            if event.get("type") == "turn.completed":
                usage.append(event.get("usage", {}))
        except ValueError:
            pass
    record["usage"] = usage
    (folder / "invocation.json").write_text(json.dumps(record, indent=2))
    if record["exit"] != 0 or not (folder / "answer.json").exists():
        raise RuntimeError("model_execution_failed:" + label)
    return json.loads((folder / "answer.json").read_text())
