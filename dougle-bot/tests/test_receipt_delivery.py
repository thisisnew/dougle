"""Exercise generated settings -> hook subprocess -> model-visible receipt -> stream verifier.

The simulated model copies ONLY hook-visible IDs; no oracle ID is prefilled in its output.
This is protocol regression coverage, not a claim of real Claude execution.
"""

import json
from dataclasses import replace
from pathlib import Path

import pytest
from test_answer_style import SESSION, answer
from test_boundaries import fake_cli
from test_runtime import event

from dougle_bot.engine import ClaudeCliEngine
from dougle_bot.evidence import failure_summary, validate_verification
from dougle_bot.intake import receive
from dougle_bot.receipt_hook import context
from dougle_bot.safety import BotError


@pytest.mark.parametrize(
    "kind,path",
    [
        ("knowledge", "/synthetic/.dougle/topics/email.md"),
        ("code", "/synthetic/project/EmailRule.java"),
    ],
)
def test_actual_hook_subprocess_supplies_unpredictable_id_and_worker_posts(
    app, tmp_path, kind, path, monkeypatch
):
    monkeypatch.setenv("PYTHONPATH", str(Path(__file__).resolve().parents[1] / "src"))
    value = answer("이메일은 처리 조건을 충족한 뒤 발송됩니다.")
    value["verification"].update(
        question_kind="domain" if kind == "knowledge" else "implementation", required=[kind]
    )
    value["verification"]["checks"] = []
    value["sources"][0]["location"] = path
    # A fresh random ID is generated inside the subprocess. Model output starts without any ID.
    code = f"""
import sys,json,subprocess,shlex,uuid
sys.stdin.read()
settings=json.load(open(sys.argv[sys.argv.index('--settings')+1]))
hook=settings['hooks']['PostToolUse'][0]['hooks'][0]['command']
ident='toolu_'+uuid.uuid4().hex
args={{'file_path':{path!r}}}
event={{'hook_event_name':'PostToolUse','tool_name':'Read','tool_input':args,'tool_response':{{'content':'Synthetic successful source content'}},'tool_use_id':ident}}
p=subprocess.run(shlex.split(hook),input=json.dumps(event),capture_output=True,text=True,check=True)
reminder=json.loads(p.stdout)['hookSpecificOutput']['additionalContext']
proof=json.JSONDecoder().raw_decode(reminder.split('read receipt: ',1)[1])[0]
value={value!r}
value['verification']['checks']=[{{'kind':{kind!r},'receipt':proof['receipt'],'source_index':0}}]
print(json.dumps({{'type':'assistant','message':{{'content':[{{'type':'tool_use','name':'Read','input':args,'id':ident}}]}}}}))
print(json.dumps({{'type':'user','message':{{'content':[{{'type':'tool_result','tool_use_id':ident,'content':'Synthetic successful source content'}}]}}}}))
for i,skill in enumerate({list(app.cfg.engine.required_skills)!r}):
 print(json.dumps({{'type':'assistant','message':{{'content':[{{'type':'tool_use','name':'Skill','input':{{'skill':skill}},'id':'s'+str(i)}}]}}}}))
 print(json.dumps({{'type':'user','message':{{'content':[{{'type':'tool_result','tool_use_id':'s'+str(i),'content':'Launching skill: '+skill}}]}}}}))
print(json.dumps({{'type':'result','session_id':{SESSION!r},'structured_output':value,'total_cost_usd':0.01}}))
"""
    cfg = replace(
        app.cfg,
        engine=replace(app.cfg.engine, claude_bin=fake_cli(tmp_path, code)),
        slack=replace(app.cfg.slack, require_approval=False),
    )
    app.cfg = app.worker.config = app.actions.config = cfg
    app.worker.engine = ClaudeCliEngine(cfg.engine, cfg.home)
    ident = receive(app.store, cfg, event(), "UBOT")
    app.worker.run_once()
    assert app.store.inquiry(ident)["status"] == "posted"
    assert not app.slack.cards
    assert len([x for x in app.slack.calls if x[0] == "post"]) == 1


@pytest.mark.parametrize(
    "body", [None, "", {"isError": True}, "Permission denied", {"error": "rejected"}]
)
def test_failed_read_never_issues_receipt(body):
    assert (
        context(
            dict(
                hook_event_name="PostToolUse",
                tool_name="Read",
                tool_input={"file_path": "/project/Code.java"},
                tool_response=body,
                tool_use_id="toolu_actual",
            )
        )
        == {}
    )


@pytest.mark.parametrize(
    "name,args",
    [
        ("Glob", {"pattern": "*.java"}),
        ("Skill", {"skill": "dougle:dougle-ask"}),
        ("Write", {"file_path": "/project/Code.java"}),
        ("Grep", {"path": "/project", "output_mode": "files_with_matches"}),
    ],
)
def test_navigation_or_mutation_is_not_read_receipt(name, args):
    assert (
        context(
            dict(
                hook_event_name="PostToolUse",
                tool_name=name,
                tool_input=args,
                tool_response="ok",
                tool_use_id="toolu_actual",
            )
        )
        == {}
    )


def test_hook_context_is_not_proof_without_current_stream_read():
    value = answer("확인한 내용입니다.")
    value["verification"]["checks"][0]["receipt"] = "toolu_forged"
    with pytest.raises(BotError, match="required_read_not_verified"):
        validate_verification(value, {})


def test_diagnostics_distinguish_missing_id_from_kind_without_leaking():
    value = answer("민감한 답변 원문")
    value["verification"]["checks"] = [
        {"kind": "knowledge", "receipt": "secret-path", "source_index": 0},
        {"kind": "code", "receipt": "actual", "source_index": 0},
    ]
    assert failure_summary(value, {"actual": {"knowledge"}}) == dict(
        successful_reads=1, available_kinds=["knowledge"], unknown_receipts=1, unsupported_kinds=1
    )


def test_status_diagnostics_include_counts_only(app):
    from dougle_bot.config import save
    from dougle_bot.main import status

    path = app.cfg.home / "config.toml"
    save(app.cfg, path)
    good = dict(
        successful_reads=2, available_kinds=["knowledge"], unknown_receipts=1, unsupported_kinds=0
    )
    app.store.audit(None, "system", "read_verification_failed", json.dumps(good))
    app.store.audit(
        None, "system", "read_verification_failed", json.dumps({"secret": "must-not-escape"})
    )
    result = status(app.cfg.home, path)["recent_read_failures"]
    assert result == [{"inquiry_id": None, **good}]


def test_receipt_reminder_does_not_copy_source_content_or_path():
    result = context(
        dict(
            hook_event_name="PostToolUse",
            tool_name="Read",
            tool_input={"file_path": "/secret/project/Code.java"},
            tool_response={"content": "private sentinel"},
            tool_use_id="toolu_actual",
        )
    )
    text = json.dumps(result)
    assert "toolu_actual" in text and "private sentinel" not in text and "/secret/" not in text


@pytest.mark.parametrize("event_name", ["PreToolUse", "PostToolUseFailure", "fake"])
def test_only_success_hook_announces_receipt(event_name):
    assert (
        context(
            dict(
                hook_event_name=event_name,
                tool_name="Read",
                tool_input={"file_path": "/project/Code.java"},
                tool_response="ok",
                tool_use_id="toolu_actual",
            )
        )
        == {}
    )
