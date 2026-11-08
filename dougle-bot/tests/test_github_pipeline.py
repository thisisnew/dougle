"""Registered GitHub MCP -> hook -> CLI stream -> worker, with no real gh or model.

The MCP server runs unchanged in a subprocess. A bootstrap replaces its gh GET
runner before module import with hash-consistent synthetic repository responses.
"""

import base64
import hashlib
import json
import subprocess
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace

import pytest
from test_answer_style import SESSION, answer
from test_runtime import event

from dougle_bot.engine import ClaudeCliEngine
from dougle_bot.handoff_notice import NOTICE
from dougle_bot.intake import receive

REPOSITORY = "synthetic/orders"
COMMIT = "a" * 40
TREE = "b" * 40
CONTENT = "class Order {\n    boolean allowed = true;\n}\n"
CANONICAL = f"https://github.com/{REPOSITORY}/blob/{COMMIT}/Order.java"
TOOLS = {f"mcp__dougle_source__{name}" for name in ("read_file", "resolve_ref", "list_sources", "find_files")}


class RecordingEngine(ClaudeCliEngine):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.results = []

    def run(self, *args, **kwargs):
        result = super().run(*args, **kwargs)
        self.results.append(result)
        return result


@pytest.fixture
def pipeline(app, tmp_path, monkeypatch):
    user_home = tmp_path / "synthetic-user"
    sources = user_home / ".dougle/sources"
    sources.mkdir(parents=True)
    (sources / "orders.md").write_text(
        f"# Orders\nLocation: https://github.com/{REPOSITORY}/tree/main\nState: connected\n"
    )
    monkeypatch.setattr(Path, "home", classmethod(lambda cls: user_home))
    monkeypatch.setenv("PYTHONPATH", str(Path(__file__).resolve().parents[1] / "src"))
    raw = CONTENT.encode()
    blob = hashlib.sha1(f"blob {len(raw)}\0".encode() + raw).hexdigest()
    fixture = tmp_path / "github-fixture.json"
    fixture.write_text(
        json.dumps(
            {
                f"repos/{REPOSITORY}/commits/main": {
                    "sha": COMMIT,
                    "commit": {"tree": {"sha": TREE}},
                },
                f"repos/{REPOSITORY}/git/trees/{TREE}": {
                    "truncated": False,
                    "tree": [
                        {
                            "path": "Order.java",
                            "type": "blob",
                            "mode": "100644",
                            "sha": blob,
                            "size": len(raw),
                        }
                    ],
                },
                f"repos/{REPOSITORY}/git/blobs/{blob}": {
                    "sha": blob,
                    "size": len(raw),
                    "encoding": "base64",
                    "content": base64.b64encode(raw).decode(),
                },
            }
        )
    )
    observations = tmp_path / "observations"
    observations.mkdir()
    bootstrap = tmp_path / "synthetic-reader.py"
    bootstrap.write_text(f"""import json,runpy,subprocess,sys
from pathlib import Path
from types import SimpleNamespace
responses=json.loads(Path({str(fixture)!r}).read_text())
def synthetic_get(argv,**kwargs):
    assert argv[:6]==['gh','api','--hostname','github.com','--method','GET']
    assert kwargs['shell'] is False
    assert argv[6] in responses
    if responses.get('fail'):
        return SimpleNamespace(returncode=1,stdout=b'',stderr=b'SYNTHETIC_PRIVATE_ERROR')
    return SimpleNamespace(returncode=0,stdout=json.dumps(responses[argv[6]]).encode(),stderr=b'')
subprocess.run=synthetic_get
assert sys.argv[1:3]==['-m','dougle_bot.github_reader']
sys.argv=['dougle_bot.github_reader',*sys.argv[3:]]
runpy.run_module('dougle_bot.github_reader',run_name='__main__')
""")
    captures = []
    barrier = [None]

    def launch(args, **kwargs):
        settings_path = Path(args[args.index("--settings") + 1])
        mcp_path = Path(args[args.index("--mcp-config") + 1])
        policy_path = settings_path.parent / "tools.json"
        config = json.loads(mcp_path.read_text())
        server = config["mcpServers"]["dougle_source"]
        registry_path = Path(server["args"][server["args"].index("--sources") + 1])
        assert server["command"] == sys.executable
        assert server["args"][:2] == ["-m", "dougle_bot.github_reader"]
        assert registry_path.name == "github_sources.json"
        assert mcp_path.name == "github_mcp.json"
        for path in (settings_path, mcp_path, policy_path, registry_path):
            assert path.parent == settings_path.parent
            assert path.stat().st_mode & 0o777 == 0o600
        assert settings_path.parent.stat().st_mode & 0o777 == 0o700
        registered = json.loads(registry_path.read_text())
        assert registered == {"sources": [{"repository": REPOSITORY, "ref": "main"}]}
        policy = json.loads(policy_path.read_text())
        assert TOOLS <= set(policy["allowed"])
        assert TOOLS <= set(args[args.index("--allowedTools") + 1].split(","))
        assert {"Agent", "Task", "Bash"} <= set(
            args[args.index("--disallowedTools") + 1].split(",")
        )
        capture = {
            "settings": settings_path,
            "mcp": mcp_path,
            "sources": registry_path,
            "cwd": kwargs["cwd"],
        }
        captures.append(capture)
        if barrier[0] is not None:
            barrier[0].wait(timeout=10)
        return subprocess.Popen(args, **kwargs)

    def engine(mode="remote"):
        if mode.startswith("registered_local"):
            (sources / "local.md").write_text(
                "# Local original\nLocation: /synthetic/registered\nState: connected\n"
            )
        value = answer("등록 조건을 충족한 경우 처리됩니다.")
        value["sources"] = [{"title": "합성 원격 코드", "location": CANONICAL}]
        value["verification"].update(question_kind="implementation", required=["code"], checks=[])
        script = tmp_path / f"synthetic-cli-{mode}"
        script.write_text(
            f"#!{sys.executable}\n"
            + f"""import json,shlex,subprocess,sys,uuid
from pathlib import Path
prompt=sys.stdin.read()
assert {REPOSITORY!r} in prompt
settings=json.loads(Path(sys.argv[sys.argv.index('--settings')+1]).read_text())
server=json.loads(Path(sys.argv[sys.argv.index('--mcp-config')+1]).read_text())['mcpServers']['dougle_source']
mode={mode!r}
ident='toolu_'+uuid.uuid4().hex
args={{'repository':{REPOSITORY!r},'ref':'release' if mode=='unregistered' else 'main','path':'Order.java'}}
name='mcp__dougle_source__read_file'
if mode in ('local','local_citation','registered_local','registered_local_remote_citation'):
    name='Read'
    args={{'file_path':'/synthetic/registered/Order.java' if mode.startswith('registered_local') else '/synthetic/unregistered/Order.java'}}
    body={CONTENT!r}
else:
    requests=[
        {{'jsonrpc':'2.0','id':1,'method':'initialize','params':{{'protocolVersion':'2025-03-26'}}}},
        {{'jsonrpc':'2.0','id':2,'method':'tools/list'}},
        {{'jsonrpc':'2.0','id':3,'method':'tools/call','params':{{'name':'read_file','arguments':args}}}},
    ]
    command=[server['command'],{str(bootstrap)!r},*server['args']]
    completed=subprocess.run(command,input=''.join(json.dumps(r)+'\\n' for r in requests),text=True,capture_output=True,check=True,timeout=15)
    replies=[json.loads(line) for line in completed.stdout.splitlines()]
    assert replies[0]['result']['serverInfo']['name']=='dougle_source'
    assert {{t['name'] for t in replies[1]['result']['tools']}}=={{'list_sources','resolve_ref','read_file','find_files'}}
    body=replies[2]['result']
hook=settings['hooks']['PostToolUse'][0]['hooks'][0]['command']
hook_event={{'hook_event_name':'PostToolUse','tool_name':name,'tool_input':args,'tool_response':body,'tool_use_id':ident}}
hook_result=subprocess.run(shlex.split(hook),input=json.dumps(hook_event),text=True,capture_output=True,check=True,timeout=15)
reminder=json.loads(hook_result.stdout)
value={value!r}
if reminder:
    text=reminder['hookSpecificOutput']['additionalContext']
    proof=json.JSONDecoder().raw_decode(text.split('read receipt: ',1)[1])[0]
    assert proof['receipt']==ident and 'code' in proof['kinds']
    receipt=proof['receipt']
else:
    receipt=ident  # Adversarial synthetic claim: an errored reader must not verify it.
value['verification']['checks']=[{{'kind':'code','receipt':receipt,'source_index':0}}]
if mode=='branch_citation':
    value['sources'][0]['location']='https://github.com/{REPOSITORY}/blob/main/Order.java'
if mode in ('local_citation','registered_local'):
    value['sources'][0]['location']=args['file_path']
Path({str(observations)!r},ident+'.json').write_text(json.dumps({{'reminder':reminder,'body':body}}))
events=[
 {{'type':'assistant','message':{{'content':[{{'type':'tool_use','id':ident,'name':name,'input':args}}]}}}},
 {{'type':'user','message':{{'content':[{{'type':'tool_result','tool_use_id':ident,'content':body}}]}}}},
]
for i,skill in enumerate({list(app.cfg.engine.required_skills)!r}):
 events.extend([
  {{'type':'assistant','message':{{'content':[{{'type':'tool_use','id':'skill'+str(i),'name':'Skill','input':{{'skill':skill}}}}]}}}},
  {{'type':'user','message':{{'content':[{{'type':'tool_result','tool_use_id':'skill'+str(i),'content':'Launching skill: '+skill}}]}}}},
 ])
events.append({{'type':'result','session_id':{SESSION!r},'structured_output':value,'total_cost_usd':0.01}})
for item in events:
 print(json.dumps(item))
"""
        )
        script.chmod(0o700)
        config = replace(
            app.cfg,
            home=tmp_path / "runtime",
            engine=replace(app.cfg.engine, claude_bin=str(script)),
            slack=replace(app.cfg.slack, require_approval=False),
        )
        app.cfg = app.worker.config = app.actions.config = config
        result = RecordingEngine(config.engine, config.home, popen=launch)
        app.worker.engine = result
        return result

    def run(mode="remote"):
        active = engine(mode)
        ident = receive(
            app.store, app.cfg, event(text="<@UBOT> 실제 코드를 확인해 주세요."), "UBOT"
        )
        app.worker.run_once()
        return active, app.store.inquiry(ident)

    return SimpleNamespace(
        engine=engine,
        run=run,
        app=app,
        captures=captures,
        observations=observations,
        fixture=fixture,
        barrier=barrier,
    )


def test_registered_remote_mcp_read_reminder_and_stream_reach_worker_post(pipeline):
    engine, row = pipeline.run()
    assert row["status"] == "posted"
    assert len(engine.results) == 1
    result = engine.results[0]
    check = result.output["verification"]["checks"][0]
    receipt = result.reads[check["receipt"]]
    assert receipt == {"knowledge", "code"}
    assert receipt.metadata["commit"] == COMMIT
    assert receipt.metadata["requested_ref"] == "main"
    policy = receipt.metadata["source_policy"]
    assert policy["complete"] is True
    assert {tuple(pair) for pair in policy["github"]} == {(REPOSITORY, "main")}
    assert receipt.metadata["locator"]["target"] == CANONICAL.removeprefix("https://")
    assert result.output["sources"][0]["location"] == CANONICAL
    assert CONTENT not in repr(receipt.metadata)
    observed = json.loads(next(pipeline.observations.iterdir()).read_text())
    reminder = json.dumps(observed["reminder"])
    assert check["receipt"] in reminder
    assert CONTENT not in reminder and CANONICAL not in reminder
    assert (
        len([call for call in pipeline.app.slack.calls if call[:2] == ("post", row["channel_id"])])
        == 1
    )
    assert not pipeline.app.slack.cards
    assert all(not item["settings"].parent.exists() for item in pipeline.captures)


@pytest.mark.parametrize(
    "mode,reason",
    [
        ("local", "registered_source_not_read"),
        ("local_citation", "registered_source_not_read"),
        ("registered_local_remote_citation", "source_identity_unverified"),
        ("branch_citation", "source_mismatch"),
    ],
)
def test_unverified_local_or_branch_citation_never_posts(pipeline, mode, reason):
    engine, row = pipeline.run(mode)
    assert row["status"] == "failed"
    assert len(engine.results) == 2
    assert pipeline.app.store.current(row) is None
    # The confirmed developer DM may trigger one public wait notice, never this answer.
    public = [call for call in pipeline.app.slack.calls if call[:2] == ("post", row["channel_id"])]
    assert len(public) <= 1 and all(call[3] == NOTICE for call in public)
    audits = pipeline.app.store.all(
        "SELECT detail FROM audit WHERE action='read_verification_failed'"
    )
    assert len(audits) == 2
    assert all(json.loads(audit["detail"])["issues"][0]["reason"] == reason for audit in audits)


def test_registered_local_original_remains_usable_with_registered_github(pipeline):
    engine, row = pipeline.run("registered_local")
    assert row["status"] == "posted"
    assert len(engine.results) == 1
    result = engine.results[0]
    receipt = next(iter(result.reads.values()))
    assert receipt.metadata["source_policy"]["local"] == ["/synthetic/registered"]
    assert result.output["sources"][0]["location"] == "/synthetic/registered/Order.java"


@pytest.mark.parametrize("mode", ["read_error", "unregistered"])
def test_reader_errors_create_neither_receipt_reminder_nor_code_evidence(pipeline, mode):
    if mode == "read_error":
        fixture = json.loads(pipeline.fixture.read_text())
        fixture["fail"] = True
        pipeline.fixture.write_text(json.dumps(fixture))
    engine, row = pipeline.run(mode)
    assert row["status"] == "failed"
    assert len(engine.results) == 2
    assert all(result.reads == {} for result in engine.results)
    for path in pipeline.observations.iterdir():
        observed = json.loads(path.read_text())
        assert observed["body"]["isError"] is True
        assert observed["reminder"] == {}
        assert "SYNTHETIC_PRIVATE_ERROR" not in json.dumps(observed)
    public = [call for call in pipeline.app.slack.calls if call[:2] == ("post", row["channel_id"])]
    assert len(public) <= 1 and all(call[3] == NOTICE for call in public)


def test_parallel_calls_get_private_isolated_mcp_and_registry_settings(pipeline):
    engine = pipeline.engine()
    pipeline.barrier[0] = threading.Barrier(2)
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(engine.run, f"synthetic {i}", None, 1) for i in range(2)]
        results = [future.result(timeout=20) for future in futures]
    assert all(len(result.reads) == 1 for result in results)
    assert len(pipeline.captures) == 2
    assert len({capture["mcp"] for capture in pipeline.captures}) == 2
    assert len({capture["sources"] for capture in pipeline.captures}) == 2
    assert len({capture["cwd"] for capture in pipeline.captures}) == 1
    assert all(not capture["settings"].parent.exists() for capture in pipeline.captures)
