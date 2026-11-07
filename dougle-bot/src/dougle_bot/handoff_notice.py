"""One best-effort customer notice after a confirmed private developer request.

The audit claim and independent post ledger precede Slack. An interrupted or
ambiguous attempt is never replayed; it does not block the developer's answer.
"""

import json
import uuid

from .slack_gateway import SlackFailure

NOTICE = (
    "추가 확인이 필요해 담당 개발자에게 요청했습니다. 확인되면 이 스레드에 이어서 답변드리겠습니다."
)


def eligible(store, config, inquiry):
    current = store.inquiry(inquiry["id"])
    if (
        config.slack.require_approval
        or store.control()["desired"] != "running"
        or inquiry["channel_id"] not in config.slack.allowed_channel_ids
        or not current
        or current["status"] not in ("needs_lookup", "failed")
        or current["card_state"] != "sent"
        or not current["card_ts"]
        or any(
            current[key] != inquiry[key]
            for key in ("status", "current_draft_id", "channel_id", "thread_ts")
        )
    ):
        return False
    if inquiry["status"] == "failed":
        job = store.one(
            "SELECT id FROM jobs WHERE inquiry_id=? ORDER BY id DESC LIMIT 1", (inquiry["id"],)
        )
        if not job or job["id"] != inquiry.get("job_id"):
            return False
    return True


def after_developer_dm(store, config, slack, inquiry):
    """Called only after Slack confirms the help card send/update succeeded."""
    ident = inquiry["id"]
    # BEGIN IMMEDIATE serializes claims even across separate Store/Worker instances.
    with store.tx() as db:
        if (
            not eligible(store, config, inquiry)
            or db.execute(
                "SELECT 1 FROM audit WHERE inquiry_id=? AND action='handoff_notice_intent'",
                (ident,),
            ).fetchone()
        ):
            return
        delivery_id = str(uuid.uuid4())
        db.execute(
            "INSERT INTO audit(inquiry_id,actor,action,detail,at) VALUES(?,'system','handoff_notice_intent',?,?)",
            (ident, delivery_id, store.clock()),
        )
    try:
        store.record_post_intent(delivery_id)
    except Exception:
        store.audit(ident, "system", "handoff_notice_not_sent", "delivery_ledger_failed")
        return
    # Recheck after durable I/O: stop, removal or a result submission can race the DM.
    if not eligible(store, config, inquiry):
        store.audit(ident, "system", "handoff_notice_not_sent", "handoff_no_longer_active")
        return
    try:
        ts = slack.post(inquiry["channel_id"], inquiry["thread_ts"], NOTICE, delivery_id)
        if not isinstance(ts, str) or not ts:
            raise SlackFailure("invalid_notice_receipt", uncertain=True)
    except SlackFailure as exc:
        store.audit(
            ident,
            "system",
            "handoff_notice_unknown" if exc.uncertain else "handoff_notice_not_sent",
            exc.code,
        )
    except Exception:
        store.audit(ident, "system", "handoff_notice_unknown", "delivery_uncertain")
    else:
        store.audit(
            ident, "system", "handoff_notice_sent", json.dumps({"id": delivery_id, "ts": ts})
        )
