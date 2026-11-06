"""Current and pre-upgrade card interactions against actual Bolt and SQLite."""

import json

import pytest
from slack_bolt.request import BoltRequest
from test_boundaries import bolt
from test_runtime import ready

from dougle_bot import cards
from dougle_bot.safety import BotError


def action_ids(card):
    return [
        item["action_id"]
        for block in card["blocks"]
        if block["type"] == "actions"
        for item in block.get("elements", [])
    ]


def button_request(ref, action, **changes):
    body = {
        "type": "block_actions",
        "team": {"id": "TTEST"},
        "user": {"id": "UAPPROVER"},
        "channel": {"id": ref[2]},
        "message": {"ts": ref[3]},
        "actions": [{"action_id": action, "value": ref[1]}],
    }
    body.update(changes)
    return BoltRequest(body=body, mode="socket_mode")


def test_short_approval_card_has_only_post_edit_reject(app):
    ref = ready(app)
    assert action_ids(app.slack.cards[(ref[2], ref[3])]) == ["post", "edit", "reject"]


def test_lookup_card_has_no_post_edit_or_regenerate(app):
    ref = ready(app)
    row = app.store.inquiry(ref[0])
    draft = app.store.current(row)
    row["status"] = "needs_lookup"
    draft["lookup_json"] = json.dumps(
        {
            "target": "가상 시스템",
            "reason": "현재 상태를 알려 주세요.",
            "sql": "",
            "schema_verified": False,
        }
    )
    _, blocks = cards.render(row, draft)
    assert action_ids({"blocks": blocks}) == ["lookup", "reject"]


def test_reject_button_commits_before_ack_without_modal_reason_or_engine(app):
    ref = ready(app)
    before = len(app.engine.calls)
    built, executor = bolt(app)
    response = built.dispatch(button_request(ref, "reject"))
    assert response.status == 200
    assert app.store.inquiry(ref[0])["status"] == "rejected"
    assert len(app.engine.calls) == before
    assert len(executor.calls) == 1
    fn, *args = executor.calls[0]
    fn(*args)
    assert action_ids(app.slack.cards[(ref[2], ref[3])]) == []
    assert not any(c[0] in ("modal", "post") for c in app.slack.calls)
    rows = app.store.all("SELECT * FROM audit WHERE action='reject'")
    assert len(rows) == 1 and rows[0]["actor"] == "UAPPROVER" and rows[0]["detail"] is None


@pytest.mark.parametrize(
    "changes",
    [
        {"user": {"id": "UOTHER"}},
        {"team": {"id": "TOTHER"}},
        {"channel": {"id": "DOTHER"}},
        {"message": {"ts": "900.000001"}},
    ],
)
def test_wrong_identity_or_card_cannot_reject(app, changes):
    ref = ready(app)
    built, _ = bolt(app)
    built.dispatch(button_request(ref, "reject", **changes))
    assert app.store.inquiry(ref[0])["status"] == "awaiting_approval"
    assert not app.store.all("SELECT * FROM audit WHERE action='reject'")


def test_double_reject_only_records_one_transition(app):
    ref = ready(app)
    app.actions.reject("UAPPROVER", *ref[1:])
    with pytest.raises(BotError, match="stale_draft"):
        app.actions.reject("UAPPROVER", *ref[1:])
    assert len(app.store.all("SELECT * FROM audit WHERE action='reject'")) == 1


def test_old_regenerate_button_does_not_open_modal_or_schedule_engine(app):
    ref = ready(app)
    before = app.store.all("SELECT * FROM jobs")
    built, executor = bolt(app)
    response = built.dispatch(button_request(ref, "regenerate"))
    assert response.status == 200
    assert app.store.all("SELECT * FROM jobs") == before
    assert app.store.inquiry(ref[0])["status"] == "awaiting_approval"
    assert not any(c[0] == "modal" for c in app.slack.calls)
    # First deferred task refreshes the obsolete card, second shows the explanation.
    fn, *args = executor.calls[0]
    fn(*args)
    assert "regenerate" not in action_ids(app.slack.cards[(ref[2], ref[3])])


def test_old_regenerate_modal_submission_is_rejected_without_work(app):
    ref = ready(app)
    metadata = json.dumps({**json.loads(ref[1]), "channel": ref[2], "ts": ref[3]})
    with pytest.raises(BotError, match="action_unavailable"):
        app.actions.submit("UAPPROVER", metadata, "regenerate", "더 쉽게 작성해 주세요.")
    assert not app.store.all("SELECT * FROM jobs WHERE status='queued'")


@pytest.mark.parametrize("action", ["reject", "regenerate"])
def test_removed_modals_cannot_be_constructed(app, action):
    ref = ready(app)
    row = app.store.inquiry(ref[0])
    with pytest.raises(BotError, match="action_unavailable"):
        cards.modal(action, row, app.store.current(row))


def test_already_open_old_reject_modal_can_finish_without_reason(app):
    ref = ready(app)
    metadata = json.dumps({**json.loads(ref[1]), "channel": ref[2], "ts": ref[3]})
    app.actions.submit("UAPPROVER", metadata, "reject", "")
    assert app.store.inquiry(ref[0])["status"] == "rejected"
