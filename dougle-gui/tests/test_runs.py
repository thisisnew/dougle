import time

import pytest

from dougle_gui.runs import (
    DEFAULT_ALLOWED_TOOLS,
    PUBLISH_PREPARE_BLOCKED,
    RunError,
    Runner,
    build_prompt,
    translate,
)


def wait(run, timeout=10):
    deadline = time.time() + timeout
    while run.status == "running" and time.time() < deadline:
        time.sleep(0.05)
    with run.cond:
        while not run.events or run.events[-1]["kind"] != "exit":
            if not run.cond.wait(max(0.0, deadline - time.time())):
                break
    return run


def test_default_tools_allow_read_helpers_used_in_compound_commands():
    for rule in ("Bash(cd:*)", "Bash(cat:*)", "Bash(diff:*)", "Bash(git:*)", "Write(~/.dougle/**)"):
        assert rule in DEFAULT_ALLOWED_TOOLS
    assert not any(rule.startswith(("Bash(rm", "Bash(sed", "Bash(curl")) for rule in DEFAULT_ALLOWED_TOOLS)


def test_prompts_invoke_dougle_skills():
    assert build_prompt("ask", "회송  흐름\n알려 줘") == "/dougle:dougle-ask 회송 흐름 알려 줘"
    assert build_prompt("connect", "~/repo").startswith("/dougle:dougle-connect ~/repo")
    assert build_prompt("fetch", "https://x").startswith("/dougle:dougle-fetch https://x")
    assert build_prompt("publish", "https://x").startswith("/dougle:dougle-publish https://x")
    with pytest.raises(RunError):
        build_prompt("delete", "x")


def test_translate_maps_stream_json_events():
    assert translate('{"type":"system","subtype":"init","session_id":"s1"}') == [{"kind": "session", "session": "s1"}]
    events = translate(
        '{"type":"assistant","message":{"content":[{"type":"text","text":"hi"},'
        '{"type":"tool_use","name":"Bash","input":{"command":"git   status"}}]}}'
    )
    assert events == [{"kind": "text", "text": "hi"}, {"kind": "tool", "name": "Bash", "detail": "git status"}]
    assert translate("not json") == [{"kind": "log", "text": "not json"}]
    assert translate("") == []


def test_ask_run_streams_progress_and_result(fake_claude, tmp_path):
    binary, calls = fake_claude
    runner = Runner(claude_bin=str(binary), workdir=tmp_path)
    run = wait(runner.start("ask", "회송 흐름"))
    assert run.status == "done"
    assert run.session == "11111111-2222-3333-4444-555555555555"
    assert run.result == "최종 답변입니다."
    assert run.cost == 0.0123 and run.turns == 3
    assert run.denials == [{"tool": "Bash", "detail": "aws s3 ls"}]
    kinds = [e["kind"] for e in run.events]
    for expected in ("session", "tool", "tool_error", "text", "result", "exit"):
        assert expected in kinds
    assert any(e["kind"] == "stderr" for e in run.events)
    argv = calls()[0]
    assert argv[argv.index("-p") + 1] == "/dougle:dougle-ask 회송 흐름"
    assert argv[argv.index("--allowedTools") + 1] == ",".join(DEFAULT_ALLOWED_TOOLS)
    assert "--disallowedTools" not in argv and "--resume" not in argv
    assert ["--output-format", "stream-json"] == argv[argv.index("--output-format"):argv.index("--output-format") + 2]


def test_follow_up_ask_resumes_session(fake_claude, tmp_path):
    binary, calls = fake_claude
    runner = Runner(claude_bin=str(binary), workdir=tmp_path)
    first = wait(runner.start("ask", "첫 질문"))
    wait(runner.start("ask", "이어서", session=first.session))
    argv = calls()[1]
    assert argv[argv.index("--resume") + 1] == first.session
    with pytest.raises(RunError) as err:
        runner.start("connect", "~/repo", session=first.session)
    assert err.value.code == "resume_not_allowed"


def test_failed_result_marks_run_failed(fake_claude, tmp_path):
    binary, _ = fake_claude
    run = wait(Runner(claude_bin=str(binary), workdir=tmp_path).start("ask", "FAIL"))
    assert run.status == "failed"
    assert {k: run.events[-1][k] for k in ("kind", "code", "status")} == {"kind": "exit", "code": 1, "status": "failed"}
    assert run.finished is not None and run.summary()["finished"] == run.finished


def test_only_one_run_at_a_time_and_cancel(fake_claude, tmp_path):
    binary, _ = fake_claude
    runner = Runner(claude_bin=str(binary), workdir=tmp_path)
    slow = runner.start("connect", "SLOW")
    with pytest.raises(RunError) as err:
        runner.start("ask", "다른 질문")
    assert err.value.code == "another_run_active" and err.value.status == 409
    runner.cancel(slow.id)
    wait(slow)
    assert slow.status == "cancelled"
    assert wait(runner.start("ask", "이제 가능")).status == "done"


def test_publish_prepare_blocks_remote_writes_then_confirm_resumes(fake_claude, tmp_path):
    binary, calls = fake_claude
    runner = Runner(claude_bin=str(binary), workdir=tmp_path)
    prepare = wait(runner.start("publish", "https://github.com/org/knowledge"))
    assert prepare.phase == "prepare" and prepare.status == "done"
    argv = calls()[0]
    assert argv[argv.index("--disallowedTools") + 1].startswith(",".join(PUBLISH_PREPARE_BLOCKED))
    assert "원격 저장소에 쓰지 마세요" in argv[argv.index("-p") + 1]
    blocked = argv[argv.index("--disallowedTools") + 1].split(",")
    assert {"Bash(git push:*)", "Bash(gh repo create:*)"} <= set(blocked)
    assert "Bash(gh repo:*)" not in blocked  # read-only `gh repo view` stays allowed
    # Without an approval channel, calls that would be asked about are denied instead.
    assert "Bash(gh api:*)" in blocked and "--settings" not in argv
    confirm = wait(runner.confirm_publish(prepare.id))
    assert confirm.phase == "confirm" and confirm.parent == prepare.id
    argv = calls()[1]
    assert "--disallowedTools" not in argv
    assert argv[argv.index("--resume") + 1] == prepare.session
    with pytest.raises(RunError) as err:
        runner.confirm_publish(prepare.id)
    assert err.value.code == "already_confirmed"


def test_confirm_requires_finished_publish_preparation(fake_claude, tmp_path):
    binary, _ = fake_claude
    runner = Runner(claude_bin=str(binary), workdir=tmp_path)
    ask = wait(runner.start("ask", "질문"))
    with pytest.raises(RunError) as err:
        runner.confirm_publish(ask.id)
    assert err.value.code == "not_a_publish_preparation"
    failed = wait(runner.start("publish", "FAIL"))
    with pytest.raises(RunError) as err:
        runner.confirm_publish(failed.id)
    assert err.value.code == "preparation_not_finished"


@pytest.mark.parametrize("text,code", [("", "empty_input"), ("   ", "empty_input"), ("x" * 4001, "input_too_long")])
def test_input_validation(fake_claude, tmp_path, text, code):
    binary, calls = fake_claude
    with pytest.raises(RunError) as err:
        Runner(claude_bin=str(binary), workdir=tmp_path).start("ask", text)
    assert err.value.code == code
    assert calls() == []


def test_missing_claude_binary(tmp_path):
    runner = Runner(claude_bin=str(tmp_path / "missing"), workdir=tmp_path)
    assert runner.claude_path() is None
    with pytest.raises(RunError) as err:
        runner.start("ask", "질문")
    assert err.value.code == "claude_not_found"
