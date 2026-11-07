"""Synthetic evidence only; test the actual published payload and durable approval flow."""

import json
import os
import subprocess
from dataclasses import replace
from pathlib import Path

import pytest
from test_answer_style import READS, SESSION, SKILLS, answer
from test_boundaries import bolt, request
from test_runtime import event, post, ready

from dougle_bot import cards
from dougle_bot.engine import EngineResult
from dougle_bot.intake import receive
from dougle_bot.main import status
from dougle_bot.presentation import chat_text, reaction_names
from dougle_bot.prompts import validate_output
from dougle_bot.safety import BotError
from dougle_bot.slack_gateway import BoltSlackGateway, SlackFailure
from dougle_bot.worker import Worker


def visible(card):
    return "\n".join(b.get("text", {}).get("text", "") for b in card["blocks"])


def test_business_answer_public_preview_matches_post_without_evidence_or_signature(app):
    body = "**매일 오전 9시에 발송합니다.** [1]\n\n- 승인 다음 날부터 발송합니다.\n- 담당자가 없으면 발송하지 않습니다.\n\n현재 운영 설정은 확인하지 못했습니다.\n\n✦ dougle"
    output = answer(body)
    output["sources"][0]["title"] = "[1] [1] 가상 설정 · ExampleConfig.java · draft abc123"
    app.engine.results = [EngineResult(SESSION, output, 0.02, SKILLS, READS)]
    ref = ready(app)
    inquiry = app.store.inquiry(ref[0])
    draft = app.store.current(inquiry)
    preview = cards.final_text(draft)
    card = app.slack.cards[(ref[2], ref[3])]
    assert preview in visible(card)
    assert "승인자에게만 표시" in json.dumps(card, ensure_ascii=False)
    assert "ExampleConfig.java" in visible(card)
    assert "[1] [1]" not in visible(card)
    post(app, ref)
    payload = [c[3] for c in app.slack.calls if c[0] == "post"][0]
    assert payload == preview
    for hidden in [
        "출처",
        "[1]",
        "https://example.test",
        "ExampleConfig",
        "abc123",
        "✦ dougle",
        "**",
    ]:
        assert hidden not in payload
    for fact in [
        "오전 9시",
        "승인 다음 날",
        "담당자가 없으면",
        "현재 운영 설정은 확인하지 못했습니다.",
    ]:
        assert fact in payload
    assert "• 승인" in payload
    assert json.loads(draft["sources_json"]) == output["sources"]


@pytest.mark.parametrize(
    "body,expected",
    [
        ("설명입니다. [1][2]", "설명입니다."),
        ("설명입니다. [R1] [R2]", "설명입니다."),
        (
            "# 조건\n**승인 후** 발송합니다.\n- 예외입니다.",
            "*조건*\n*승인 후* 발송합니다.\n• 예외입니다.",
        ),
        ("`items[1]`입니다.", "`items[1]`입니다."),
        ("items[1] 값입니다.", "items[1] 값입니다."),
        ("```java\nitems[1];\n\n\n**literal**;\n```", "```java\nitems[1];\n\n\n**literal**;\n```"),
        ("“원문 [1]”입니다.", "“원문 [1]”입니다."),
        ("> 원문 [1]\n설명입니다.", "&gt; 원문 [1]\n설명입니다."),
        ("<@UABC> <!channel> & 설명입니다.", "&lt;@UABC&gt; &lt;!channel&gt; &amp; 설명입니다."),
        ("설명입니다.\n\n**✦ dougle**", "설명입니다."),
    ],
)
def test_renderer_preserves_meaning_and_protected_text(body, expected):
    assert chat_text(body, 2) == expected


@pytest.mark.parametrize("heading", ["출처", "**출처**", "## 참고 자료", "Sources:"])
def test_public_bibliography_is_not_silently_deleted(heading):
    with pytest.raises(BotError, match="sources_belong_in_review_field"):
        validate_output(answer("설명입니다.\n\n" + heading + "\n보존해야 하는 자료입니다."))


def test_bibliography_causes_one_retry_then_no_approvable_draft(app):
    app.engine.results = [
        EngineResult(SESSION, answer("설명입니다.\n출처\n가상 자료입니다."), 0.02, SKILLS, READS)
    ] * 2
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.run_once()
    assert app.store.inquiry(ident)["status"] == "failed"
    assert not app.store.current(app.store.inquiry(ident))
    assert len(app.engine.calls) == 2
    assert not any(c[0] == "post" for c in app.slack.calls)


def test_initial_dm_and_reaction_exist_before_engine_and_same_card_becomes_approval(app):
    built, executor = bolt(app)
    assert built.dispatch(request()).status == 200
    assert not app.slack.cards
    fn, *args = executor.calls[0]
    fn(*args)
    assert len(app.slack.cards) == 1
    key = next(iter(app.slack.cards))
    assert "처리 대기" in visible(app.slack.cards[key])
    assert ("CTEST", "1001.000001", "dougle") in app.slack.reactions
    assert not app.engine.calls
    original = app.engine.run

    def observed(*args, **kwargs):
        assert next(iter(app.slack.cards)) == key
        assert "답변 작성 중" in visible(app.slack.cards[key])
        assert [
            e["action_id"]
            for b in app.slack.cards[key]["blocks"]
            if b["type"] == "actions"
            for e in b["elements"]
        ] == ["cancel"]
        return original(*args, **kwargs)

    app.engine.run = observed
    app.worker.run_once()
    assert len(app.slack.cards) == 1
    assert "게시 승인 대기" in visible(app.slack.cards[key])
    assert app.slack.reactions == {
        ("CTEST", "1001.000001", "dougle"),
        ("CTEST", "1001.000001", "hourglass_flowing_sand"),
    }
    assert not any(c[0] == "post" for c in app.slack.calls)


def test_post_changes_reaction_to_done_and_never_reacts_to_answer(app):
    ref = ready(app)
    post(app, ref)
    assert app.slack.reactions == {
        ("CTEST", "1001.000001", "dougle"),
        ("CTEST", "1001.000001", "white_check_mark"),
    }


@pytest.mark.parametrize("fault", ["reaction", "remove_reaction", "card_before"])
def test_progress_transport_failure_does_not_block_answer(app, fault):
    app.slack.failures[fault] = SlackFailure()
    ref = ready(app)
    assert app.store.inquiry(ref[0])["status"] == "awaiting_approval"
    assert len(app.engine.calls) == 1
    assert len(app.slack.cards) == 1


def test_uncertain_first_progress_dm_is_not_sent_twice(app):
    app.slack.failures["card_after"] = SlackFailure(uncertain=True)
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.run_once()
    assert app.store.inquiry(ident)["status"] == "awaiting_approval"
    assert app.store.inquiry(ident)["card_state"] == "unknown"
    assert len(app.slack.cards) == 1
    assert len([c for c in app.slack.calls if c[0] == "card"]) == 1


def test_reaction_cleanup_survives_worker_recreation_and_partial_failure(app):
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.notify(ident)
    app.slack.reactions.add(("CTEST", "1001.000001", "memo"))
    app.store.audit(ident, "system", "reaction_intent", "memo")
    app.slack.failures["remove_reaction"] = SlackFailure(uncertain=True)
    app.worker.run_once()
    # Retry only the cosmetic state, never the answer or posting operation.
    worker = Worker(app.store, app.cfg, app.engine, app.slack)
    worker.notify(ident)
    assert app.slack.reactions == {
        ("CTEST", "1001.000001", "dougle"),
        ("CTEST", "1001.000001", "hourglass_flowing_sand"),
    }


@pytest.mark.parametrize(
    "configured,expected",
    [
        ("eyes", "dougle"),
        ("hourglass_flowing_sand", "dougle"),
        ("", None),
        ("custom_hourglass", "custom_hourglass"),
    ],
)
def test_legacy_reaction_and_explicit_disable(configured, expected):
    assert reaction_names("queued", configured) == (
        (expected, "hourglass_flowing_sand") if expected else ()
    )


def test_disabled_reactions_still_allow_progress_dm(app):
    app.worker.config = replace(app.cfg, slack=replace(app.cfg.slack, ack_reaction=""))
    ready(app)
    assert not app.slack.reactions
    assert app.slack.cards
    assert not any("reaction" in c[0] for c in app.slack.calls)


@pytest.mark.parametrize(
    "method,code",
    [
        ("reaction", "already_reacted"),
        ("remove_reaction", "no_reaction"),
        ("remove_reaction", "invalid_name"),
    ],
)
def test_slack_idempotent_reaction_errors_are_safe(method, code):
    gw = BoltSlackGateway("xoxb-synthetic")

    def fault(*args, **kwargs):
        raise SlackFailure(code)

    gw.call = fault
    getattr(gw, method)("CTEST", "1001.000001", "memo")


def test_maximum_normal_sources_fit_card_and_stay_private(app):
    ref = ready(app)
    draft = app.store.current(app.store.inquiry(ref[0]))
    draft["sources_json"] = json.dumps(
        [{"title": "가상 자료" * 25, "location": "https://example.test/" + "a" * 960}] * 30
    )
    _, blocks = cards.render(app.store.inquiry(ref[0]), draft)
    assert len(blocks) <= 45
    assert all(len(b.get("text", {}).get("text", "")) <= 3000 for b in blocks)
    assert "example.test" not in cards.final_text(draft)


def test_status_exposes_elapsed_and_state_without_question_or_source(app):
    from dougle_bot.config import save

    save(app.cfg, app.cfg.home / "config.toml")
    ident = receive(app.store, app.cfg, event(), "UBOT")
    result = status(app.cfg.home, app.cfg.home / "config.toml")
    active = result["active_inquiries"]
    assert active[0]["id"] == ident
    assert active[0]["status"] == "queued"
    assert active[0]["elapsed_seconds"] >= 0
    assert "question_text" not in active[0]


def test_new_top_level_message_never_inherits_channel_session(app):
    ready(app)
    app.engine.results = [
        EngineResult("22222222-2222-4333-8444-555555555555", answer(), 0.02, SKILLS, READS)
    ]
    ready(app, ts="1002.000001")
    assert app.engine.calls[0][1] is None
    assert app.engine.calls[1][1] is None


def test_old_approval_card_requires_updated_preview_before_post(app):
    ref = ready(app)
    with app.store.tx() as db:
        db.execute("DELETE FROM audit WHERE action='card_preview'")
    with pytest.raises(BotError, match="preview_updated_review_again"):
        post(app, ref)
    assert not any(c[0] == "post" for c in app.slack.calls)
    post(app, ref)
    assert app.store.inquiry(ref[0])["status"] == "posted"


def test_old_unsent_payload_cannot_republish_sources_after_upgrade(app):
    ref = ready(app)
    inquiry = app.store.inquiry(ref[0])
    app.store.claim_post(
        ref[0],
        inquiry["current_draft_id"],
        ["예전 본문입니다.\n출처\nhttps://example.test\n✦ dougle"],
        "UAPPROVER",
    )
    with app.store.tx() as db:
        db.execute("UPDATE deliveries SET state='not_sent'")
        db.execute("UPDATE inquiries SET status='awaiting_approval' WHERE id=?", (ref[0],))
    with pytest.raises(BotError, match="publication_changed_regenerating"):
        post(app, ref)
    assert app.store.inquiry(ref[0])["status"] == "queued"
    assert not any(c[0] == "post" for c in app.slack.calls)


@pytest.mark.parametrize("version,expected", [(None, True), ("0.1.4", True), ("0.1.10", False)])
def test_status_checks_daemon_version_not_cli_version(app, monkeypatch, version, expected):
    import dougle_bot.main as main
    from dougle_bot.config import save

    save(app.cfg, app.cfg.home / "config.toml")
    if version:
        app.store.audit(None, "system", "runtime_started", version)
    monkeypatch.setattr(main, "running", lambda home: True)
    state = main.status(app.cfg.home, app.cfg.home / "config.toml")
    assert state["update_required"] is expected
    assert state["running_version"] == version


def test_bootstrap_upgrade_preserves_existing_config_and_requires_prepare(tmp_path):
    script = Path(__file__).resolve().parents[2] / "skills/dougle-bot/scripts/bot.sh"
    config = tmp_path / "config.toml"
    config.write_text("existing configuration untouched")
    proc = subprocess.run(
        ["sh", str(script), "status"],
        env={**os.environ, "DOUGLE_BOT_HOME": str(tmp_path)},
        capture_output=True,
        text=True,
        check=True,
    )
    state = json.loads(proc.stdout)
    assert state["configured"] is True
    assert state["runtime_installed"] is False
    assert state["next"] == "prepare"
    assert config.read_text() == "existing configuration untouched"


def test_absent_dougle_emoji_falls_back_and_restored_identity_keeps_loading(app):
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.slack.failures["reaction"] = SlackFailure("invalid_name")
    app.worker.notify(ident)
    assert app.slack.reactions == {("CTEST", "1001.000001", "hourglass_flowing_sand")}
    assert app.store.inquiry(ident)["status"] == "queued"
    # Emoji has been re-added; a later stage refresh can use it again.
    app.worker.notify(ident)
    assert app.slack.reactions == {
        ("CTEST", "1001.000001", "dougle"),
        ("CTEST", "1001.000001", "hourglass_flowing_sand"),
    }
    app.worker.run_once()
    assert app.slack.reactions == {
        ("CTEST", "1001.000001", "dougle"),
        ("CTEST", "1001.000001", "hourglass_flowing_sand"),
    }


@pytest.mark.parametrize("code", ["missing_scope", "ratelimited", "slack_connection_failed"])
def test_non_emoji_failure_does_not_try_a_second_emoji(app, code):
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.slack.failures["reaction"] = SlackFailure(code)
    app.worker.notify(ident)
    assert not app.slack.reactions
    assert len([c for c in app.slack.calls if c[0] == "reaction"]) == 1
    assert len(app.slack.cards) == 1


def test_deleted_emoji_is_replaced_on_next_inquiry(app):
    ready(app)
    ident = receive(app.store, app.cfg, event(ts="1002.000001"), "UBOT")
    app.slack.failures["reaction"] = SlackFailure("invalid_name")
    app.worker.notify(ident)
    assert ("CTEST", "1002.000001", "hourglass_flowing_sand") in app.slack.reactions
    assert ("CTEST", "1001.000001", "dougle") in app.slack.reactions


@pytest.mark.parametrize(
    "status,state",
    [
        (status, "hourglass_flowing_sand")
        for status in ("queued", "drafting", "awaiting_approval", "needs_lookup", "posting")
    ]
    + [
        ("posted", "white_check_mark"),
        ("failed", "warning"),
        ("post_unknown", "warning"),
        ("rejected", "no_entry_sign"),
    ],
)
def test_identity_stays_while_only_state_changes(app, status, state):
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.notify(ident)
    inquiry = app.store.inquiry(ident)
    inquiry["status"] = status
    app.worker.update_reaction(inquiry)
    assert app.slack.reactions == {("CTEST", "1001.000001", name) for name in ("dougle", state)}
    assert not any(c[0] == "remove_reaction" and c[-1] == "dougle" for c in app.slack.calls)
    before = len(app.slack.calls)
    app.worker.update_reaction(inquiry)
    assert len(app.slack.calls) == before


def test_second_reaction_failure_reconciles_after_restart(app):
    ident = receive(app.store, app.cfg, event(), "UBOT")
    original = app.slack.reaction

    def fail_state(*args):
        if args[-1] == "hourglass_flowing_sand":
            raise SlackFailure(uncertain=True)
        original(*args)

    app.slack.reaction = fail_state
    app.worker.notify(ident)
    assert app.slack.reactions == {("CTEST", "1001.000001", "dougle")}
    app.slack.reaction = original
    Worker(app.store, app.cfg, app.engine, app.slack).notify(ident)
    assert app.slack.reactions == {
        ("CTEST", "1001.000001", "dougle"),
        ("CTEST", "1001.000001", "hourglass_flowing_sand"),
    }
    assert not app.engine.calls


def test_posted_loading_cleanup_failure_does_not_duplicate_reply(app):
    ref = ready(app)
    app.slack.failures["remove_reaction"] = SlackFailure(uncertain=True)
    post(app, ref)
    assert app.store.inquiry(ref[0])["status"] == "posted"
    Worker(app.store, app.cfg, app.engine, app.slack).notify(ref[0])
    assert app.slack.reactions == {
        ("CTEST", "1001.000001", "dougle"),
        ("CTEST", "1001.000001", "white_check_mark"),
    }
    assert len([c for c in app.slack.calls if c[0] == "post"]) == 1


def test_missing_identity_at_completion_still_removes_loading(app):
    ref = ready(app)
    app.slack.reactions.discard(("CTEST", "1001.000001", "dougle"))
    app.slack.failures["reaction"] = SlackFailure("invalid_name")
    post(app, ref)
    assert app.slack.reactions == {("CTEST", "1001.000001", "white_check_mark")}


def test_legacy_single_reaction_record_migrates_without_removing_identity(app):
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.slack.reactions.update({("CTEST", "1001.000001", "memo"), ("CTEST", "1001.000001", "eyes")})
    app.store.audit(ident, "system", "reaction_intent", "memo")
    app.store.audit(ident, "system", "reaction_applied", "memo")
    app.worker.notify(ident)
    assert app.slack.reactions == {
        ("CTEST", "1001.000001", "dougle"),
        ("CTEST", "1001.000001", "hourglass_flowing_sand"),
    }
