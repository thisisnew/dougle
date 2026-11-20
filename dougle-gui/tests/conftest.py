import json
import stat
import sys
import textwrap
import threading
from pathlib import Path

import pytest

from dougle_gui.runs import Runner
from dougle_gui.server import App, serve
from dougle_gui.store import Store

from .helpers import TOKEN

TOPIC = """\
# 입하 확정
Imported-From: example/knowledge@abc1234 knowledge/receiving/topics/receiving.md
Local-Freshness: all claims needs_recheck (imported 2026-01-01T00:00:00Z)
About: 입하 확정의 조건과 기록 흐름

## Claims
### C01 — 저장 위치 (observed)
Statement: 확정 결과는 receipt 테이블에 저장됩니다.
Evidence: [S001](../sources/S001.md), src/ReceiptService.java
Freshness: current_at_observation

### C02 — 취소 조건 (reported: S002)
확정 뒤에는 취소할 수 없다는 보고와 코드가 충돌합니다 (unresolved).
"""

LOCAL_TOPIC = """\
# 회송 메일
Sources: [S002](../sources/S002.md). Observed: 2026-01-02. Freshness: current_at_observation.

## Claims
## C01 — 발송 시각
Provenance: observed
승인 다음 날 09:00 에 회송 안내 메일을 예약합니다.
"""

SOURCE = """\
# S001 — Receiving service
Location: /work/example-receiving
State: partial
Observed: 2026-01-01T00:00:00Z
Related topics: [receiving](../knowledge/receiving.md)
"""


@pytest.fixture
def store_root(tmp_path: Path) -> Path:
    root = tmp_path / "dougle"
    (root / "knowledge").mkdir(parents=True)
    (root / "sources").mkdir()
    (root / "index.md").write_text("# Dougle index\n", encoding="utf-8")
    (root / "knowledge" / "receiving.md").write_text(TOPIC, encoding="utf-8")
    (root / "knowledge" / "rts-mail.md").write_text(LOCAL_TOPIC, encoding="utf-8")
    (root / "sources" / "S001.md").write_text(SOURCE, encoding="utf-8")
    (root / "sources" / "S002.md").write_text("# S002 — Slack\nState: read\n", encoding="utf-8")
    sharing = root / "sharing" / "example"
    sharing.mkdir(parents=True)
    (sharing / "binding.md").write_text(
        "# binding\nRepository: github.com/example/knowledge (private; note)\nBranch: main\n"
        "Package path: knowledge/receiving/\nProject-ID: receiving\n"
        "Last fully merged commit: abc1234def\nLast published commit: none (never published)\n"
        "Pending operation: none\n",
        encoding="utf-8",
    )
    (sharing / "operation.md").write_text("# Operation — fetch example\nStatus: completed 2026-01-01T00:00:00Z\n", encoding="utf-8")
    legacy = root / "workspaces" / "old" / "topics"
    legacy.mkdir(parents=True)
    (legacy / "receiving.md").write_text("# 옛 입하 노트\n## C01 — 옛 규칙\n내용\n", encoding="utf-8")
    schedule = root / "schedules" / "m1"
    (schedule / "inquiries").mkdir(parents=True)
    (schedule / "monitor.md").write_text("Channel: #qna\nStatus: active\n", encoding="utf-8")
    (schedule / "inquiries" / "a.md").write_text("State: awaiting_approval\n", encoding="utf-8")
    (schedule / "inquiries" / "b.md").write_text("State: sent\n", encoding="utf-8")
    return root


FAKE = """\
#!{python}
import json, os, sys, time
args = sys.argv[1:]
with open({log!r}, "a", encoding="utf-8") as fh:
    fh.write(json.dumps(args) + "\\n")
prompt = args[args.index("-p") + 1]
def out(obj):
    print(json.dumps(obj, ensure_ascii=False), flush=True)
out({{"type": "system", "subtype": "init", "session_id": "11111111-2222-3333-4444-555555555555"}})
if "SLOW" in prompt:
    time.sleep(30)
out({{"type": "assistant", "message": {{"content": [
    {{"type": "tool_use", "name": "Read", "input": {{"file_path": "/home/x/.dougle/index.md"}}}}]}}}})
out({{"type": "user", "message": {{"content": [
    {{"type": "tool_result", "is_error": True, "content": "denied"}}]}}}})
print("warning line", file=sys.stderr, flush=True)
out({{"type": "assistant", "message": {{"content": [{{"type": "text", "text": "답변 초안"}}]}}}})
if "FAIL" in prompt:
    out({{"type": "result", "subtype": "error_during_execution", "is_error": True, "result": "",
         "session_id": "11111111-2222-3333-4444-555555555555"}})
    sys.exit(1)
out({{"type": "result", "subtype": "success", "is_error": False, "result": "최종 답변입니다.",
     "session_id": "11111111-2222-3333-4444-555555555555", "total_cost_usd": 0.0123, "num_turns": 3,
     "permission_denials": [{{"tool_name": "Bash", "tool_input": {{"command": "aws s3 ls"}}}}]}})
"""


@pytest.fixture
def fake_claude(tmp_path: Path):
    log = tmp_path / "claude-calls.jsonl"
    path = tmp_path / "fake-claude"
    path.write_text(textwrap.dedent(FAKE.format(python=sys.executable, log=str(log))), encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IXUSR)

    def calls():
        if not log.exists():
            return []
        return [json.loads(line) for line in log.read_text(encoding="utf-8").splitlines()]

    return path, calls


@pytest.fixture
def server(store_root, fake_claude, tmp_path):
    binary, calls = fake_claude
    app = App(Store(store_root), Runner(claude_bin=str(binary), workdir=tmp_path),
              bot_bin=str(tmp_path / "no-bot"), token=TOKEN)
    httpd = serve(app)
    thread = threading.Thread(target=httpd.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
    thread.start()
    yield app, calls
    app.runner.shutdown()
    httpd.shutdown()
    httpd.server_close()


@pytest.fixture
def open_server(store_root, fake_claude, tmp_path):
    binary, calls = fake_claude
    app = App(Store(store_root), Runner(claude_bin=str(binary), workdir=tmp_path), bot_bin=str(tmp_path / "no-bot"))
    httpd = serve(app)
    threading.Thread(target=httpd.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()
    yield app, calls
    app.runner.shutdown()
    httpd.shutdown()
    httpd.server_close()
