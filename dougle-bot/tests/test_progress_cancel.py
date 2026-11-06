"""Synthetic Slack, actual SQLite and fake owned CLI processes; no company access."""

import json
import subprocess
import threading
import time
from dataclasses import replace

import pytest
from test_boundaries import bolt, fake_cli
from test_runtime import event, ready
from test_simple_approval import action_ids, button_request

from dougle_bot import cards
from dougle_bot.engine import ClaudeCliEngine
from dougle_bot.intake import receive
from dougle_bot.progress import Progress, StreamProgress, progress_text
from dougle_bot.safety import BotError


def pending(app, **kwargs):
    ident = receive(app.store, app.cfg, event(**kwargs), "UBOT")
    app.worker.notify(ident)
    row = app.store.inquiry(ident)
    card = app.slack.cards[(row["card_channel_id"], row["card_ts"])]
    value = next(
        e["value"]
        for b in card["blocks"]
        if b["type"] == "actions"
        for e in b["elements"]
        if e["action_id"] == "cancel"
    )
    return ident, value, row["card_channel_id"], row["card_ts"]


def cancel(app, ref, user="UAPPROVER"):
    return app.actions.cancel(user, *ref[1:])


def test_queued_cancel_is_durable_and_does_not_stop_other_inquiry(app):
    ref = pending(app)
    cancel(app, ref)
    cancel(app, ref)  # double click is idempotent
    assert not app.worker.run_once()
    assert not app.engine.calls
    app.store.recover()
    assert app.store.inquiry(ref[0])["status"] == "cancelled"
    assert app.store.all("SELECT status FROM jobs") == [{"status": "cancelled"}]
    assert len(app.store.all("SELECT * FROM audit WHERE action='inquiry_cancelled'")) == 1
    other = ready(app, ts="1002.000001")
    assert app.store.inquiry(other[0])["status"] == "awaiting_approval"
    assert app.store.control()["desired"] == "running"


@pytest.mark.parametrize("wrong", ["user", "channel", "ts", "job", "inquiry", "bool", "missing"])
def test_cancel_rejects_untrusted_or_stale_context(app, wrong):
    ref = list(pending(app))
    user = "UAPPROVER"
    if wrong == "user":
        user = "UOTHER"
    elif wrong == "channel":
        ref[2] = "DOTHER"
    elif wrong == "ts":
        ref[3] = "other"
    else:
        data = json.loads(ref[1])
        if wrong == "missing":
            data.pop("job_id")
        elif wrong == "bool":
            data["job_id"] = True
        else:
            data["job_id" if wrong == "job" else "inquiry_id"] = 999
        ref[1] = json.dumps(data)
    with pytest.raises(BotError):
        cancel(app, ref, user)
    assert app.store.inquiry(ref[0])["status"] == "queued"


def test_cancel_bolt_checks_workspace_and_commits_before_ack(app):
    ref = pending(app)
    built, executor = bolt(app)
    assert built.dispatch(button_request(ref, "cancel", team={"id": "TOTHER"})).status == 200
    assert app.store.inquiry(ref[0])["status"] == "queued"
    assert built.dispatch(button_request(ref, "cancel")).status == 200
    assert app.store.inquiry(ref[0])["status"] == "cancelled"
    fn, *args = executor.calls[-1]
    fn(*args)
    assert action_ids(app.slack.cards[(ref[2], ref[3])]) == []
    assert not app.engine.calls


def test_stale_cancel_cannot_cancel_ready_or_new_job(app):
    ref = pending(app)
    app.worker.run_once()
    with pytest.raises(BotError, match="stale_job"):
        cancel(app, ref)
    row = app.store.inquiry(ref[0])
    app.store.enqueue(ref[0], row["current_draft_id"], "regenerated", "{}")
    with pytest.raises(BotError, match="stale_job"):
        cancel(app, ref)
    assert app.store.inquiry(ref[0])["status"] == "queued"


def test_late_engine_result_cannot_resurrect_cancelled_inquiry_or_session(app):
    ref = pending(app)
    original = app.engine.run

    def late(*args, **kwargs):
        cancel(app, ref)
        assert kwargs["cancel_requested"]()
        return original(*args, **kwargs)

    app.engine.run = late
    app.worker.run_once()
    row = app.store.inquiry(ref[0])
    assert row["status"] == "cancelled" and row["engine_session_id"] is None
    assert app.store.current(row) is None
    assert app.store.all("SELECT status FROM jobs") == [{"status": "cancelled"}]
    assert not any(c[0] == "post" for c in app.slack.calls)
    assert action_ids(app.slack.cards[(ref[2], ref[3])]) == []


def test_cancel_during_lookup_never_launches_engine(app):
    ref = pending(app)
    original = app.slack.history

    def history(*args):
        cancel(app, ref)
        return original(*args)

    app.slack.history = history
    app.worker.run_once()
    assert not app.engine.calls
    assert app.store.inquiry(ref[0])["status"] == "cancelled"


def test_owned_cli_is_terminated_on_cancel_while_bot_remains_enabled(app, tmp_path):
    marker = tmp_path / "started"
    script = fake_cli(
        tmp_path,
        f'import time\nfrom pathlib import Path\nPath({str(marker)!r}).write_text("ready")\ntime.sleep(20)\n',
    )
    procs = []

    def launch(*args, **kwargs):
        p = subprocess.Popen(*args, **kwargs)
        procs.append(p)
        return p

    cfg = replace(app.cfg.engine, claude_bin=script)
    engine = ClaudeCliEngine(cfg, app.cfg.home, popen=launch)
    start = time.monotonic()
    with pytest.raises(BotError, match="inquiry_cancelled"):
        engine.run("synthetic", None, 1, cancel_requested=marker.exists)
    assert time.monotonic() - start < 5
    assert procs and procs[0].poll() is not None
    assert app.store.control()["desired"] == "running"


def test_progress_stream_only_exposes_safe_stage_enums_with_split_utf8():
    events = []
    parser = StreamProgress(events.append)
    data = (
        json.dumps(
            {
                "type": "assistant",
                "message": {
                    "content": [
                        {
                            "type": "tool_use",
                            "name": "Read",
                            "input": {"file_path": "비밀경로", "token": "secret"},
                        }
                    ]
                },
            },
            ensure_ascii=False,
        )
        + "\n"
    ).encode()
    for byte in data:
        parser.feed(bytes([byte]))
    parser.feed(
        b'not json\n[]\n{"type":"assistant","message":{"content":[{"type":"tool_use","name":[]}]}}\n'
    )
    parser.feed(
        b'{"type":"user","message":{"content":[{"type":"tool_result","is_error":true,"content":"secret"}]}}\n'
    )
    assert events == ["activity", "lookup", "activity", "activity", "tool_result"]
    assert "secret" not in repr(events)


def test_progress_counts_responses_not_success_and_flags_silence():
    now = [0]
    p = Progress(clock=lambda: now[0])
    p.phase("lookup", attempt=2)
    p.activity("tool_result")
    now[0] = 125
    snap = p.snapshot()
    text = progress_text(snap)
    assert snap["elapsed_seconds"] == 125 and snap["quiet_seconds"] == 125
    assert "도구 응답 1건" in text and "새 진행 신호" in text
    assert "완료했습니다" not in text and "%" not in text
    assert p.remaining(900) == 775


@pytest.mark.parametrize(
    "value",
    [None, {}, {"stage": "lookup", "elapsed_seconds": True}, {"stage": "secret"}, {"stage": []}],
)
def test_corrupt_progress_cannot_render_arbitrary_text(value):
    assert progress_text(value) == "진행 정보를 확인하고 있습니다."


def test_periodic_progress_updates_same_dm_and_never_overwrites_final(app):
    ref = pending(app)
    original = app.engine.run
    updated = threading.Event()
    app.worker.progress_interval = 0.01
    card = app.slack.card

    def observe(*args, **kwargs):
        result = card(*args, **kwargs)
        if len(app.store.all("SELECT * FROM audit WHERE action='answer_progress'")) >= 2:
            updated.set()
        return result

    app.slack.card = observe

    def slow(*args, **kwargs):
        kwargs["on_progress"]("lookup")
        assert updated.wait(2)
        return original(*args, **kwargs)

    app.engine.run = slow
    app.worker.run_once()
    assert updated.is_set()
    assert len(app.slack.cards) == 1
    assert app.store.inquiry(ref[0])["status"] == "awaiting_approval"
    before = len(app.slack.calls)
    app.worker.report_progress(ref[0], Progress())
    assert len(app.slack.calls) == before
    assert not any(c[0] == "post" for c in app.slack.calls)


def test_retries_share_one_deadline(app, monkeypatch):
    import dougle_bot.worker as worker

    now = [0]
    monkeypatch.setattr(worker, "Progress", lambda: Progress(clock=lambda: now[0]))
    app.engine.results[0] = BotError("session_not_found")
    # A style-invalid result requires one correction, without resetting the budget.
    app.engine.results.pop(0)
    app.engine.results[0] = replace(app.engine.results[0], activated=set())
    original = app.engine.run

    def timed(*args, **kwargs):
        now[0] += 100
        return original(*args, **kwargs)

    app.engine.run = timed
    ready(app)
    assert app.engine.timeouts == [900, 800]


def test_expired_total_budget_prevents_retry(app, monkeypatch):
    import dougle_bot.worker as worker

    now = [0]
    monkeypatch.setattr(worker, "Progress", lambda: Progress(clock=lambda: now[0]))
    original = app.engine.run

    def overdue(*args, **kwargs):
        now[0] = 901
        return original(*args, **kwargs)

    app.engine.run = overdue
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.run_once()
    assert app.store.inquiry(ident)["error"] == "engine_timeout"
    assert len(app.engine.calls) == 1 and not app.store.current(app.store.inquiry(ident))


def test_card_order_labels_and_public_payload_are_distinct(app):
    ref = ready(app)
    row = app.store.inquiry(ref[0])
    draft = app.store.current(row)
    card = app.slack.cards[(ref[2], ref[3])]
    blocks = card["blocks"]
    labels = [b.get("text", {}).get("text") for b in blocks]
    q = labels.index("*질문*")
    a = labels.index("*답변*")
    action = next(i for i, b in enumerate(blocks) if b["type"] == "actions")
    source = next(i for i, b in enumerate(blocks) if b["type"] == "context" and "승인자" in str(b))
    assert q < a < action < source
    assert any(b["type"] == "divider" for b in blocks[q:a])
    assert "초안" not in json.dumps(card, ensure_ascii=False)
    assert blocks[q + 1]["text"]["type"] == "plain_text"
    assert cards.final_text(draft) == blocks[a + 1]["text"]["text"]
    assert blocks[action]["elements"][0]["style"] == "primary"


def test_dm_notification_question_cannot_trigger_mentions(app):
    ref = ready(app)
    row = app.store.inquiry(ref[0])
    row["question_text"] = "<!channel> <@UOTHER> 관련 질문입니다."
    text, blocks = cards.render(row, app.store.current(row))
    assert "<!channel>" not in text and "<@UOTHER>" not in text
    assert "&lt;!channel&gt;" in text
    question = next(b for b in blocks if b.get("text", {}).get("text") == row["question_text"])
    assert question["text"]["type"] == "plain_text"
