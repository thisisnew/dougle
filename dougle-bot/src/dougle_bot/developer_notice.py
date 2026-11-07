"""Public progress after durable developer input; no business content is exposed."""

import json
import uuid

from .slack_gateway import SlackFailure

RESULT_NOTICE = "개발자로부터 확인 내용을 받았습니다. 내용을 확인하고 정리해 답변드리겠습니다."
TAKEOVER_NOTICE = "담당 개발자가 직접 답변드릴 예정입니다."


def eligible(store, config, ident, event):
    row = store.inquiry(ident)
    if (
        not row
        or config.slack.require_approval
        or store.control()["desired"] != "running"
        or row["channel_id"] not in config.slack.allowed_channel_ids
    ):
        return None
    if event["action"] == "developer_takeover":
        return row if row["status"] == "cancelled" and row["human_takeover"] else None
    job = store.one("SELECT * FROM jobs WHERE inquiry_id=? ORDER BY id DESC LIMIT 1", (ident,))
    if (
        row["status"] not in ("queued", "drafting")
        or not job
        or str(job["id"]) != event["detail"]
        or job["kind"] != "lookup_result"
        or job["status"] not in ("queued", "running")
    ):
        return None
    return row


def notify(store, config, slack, ident):
    with store.tx() as db:
        event = store.one(
            "SELECT * FROM audit WHERE inquiry_id=? AND action IN "
            "('developer_result_received','developer_takeover') ORDER BY id DESC LIMIT 1",
            (ident,),
        )
        if not event or not eligible(store, config, ident, event):
            return
        if store.one(
            "SELECT 1 FROM audit WHERE inquiry_id=? AND action='developer_notice_claim' AND detail=?",
            (ident, str(event["id"])),
        ):
            return
        delivery_id = str(uuid.uuid4())
        for action, detail in (
            ("developer_notice_claim", str(event["id"])),
            ("developer_notice_intent", delivery_id),
        ):
            db.execute(
                "INSERT INTO audit(inquiry_id,actor,action,detail,at) VALUES(?,'system',?,?,?)",
                (ident, action, detail, store.clock()),
            )
    try:
        store.record_post_intent(delivery_id)
    except Exception:
        store.audit(ident, "system", "developer_notice_not_sent", "delivery_ledger_failed")
        return
    row = eligible(store, config, ident, event)
    if not row:
        store.audit(ident, "system", "developer_notice_not_sent", "state_changed")
        return
    text = TAKEOVER_NOTICE if event["action"] == "developer_takeover" else RESULT_NOTICE
    try:
        ts = slack.post(row["channel_id"], row["thread_ts"], text, delivery_id)
        if not isinstance(ts, str) or not ts:
            raise SlackFailure("invalid_notice_receipt", uncertain=True)
    except SlackFailure as exc:
        store.audit(
            ident,
            "system",
            "developer_notice_unknown" if exc.uncertain else "developer_notice_not_sent",
            exc.code,
        )
    except Exception:
        store.audit(ident, "system", "developer_notice_unknown", "delivery_uncertain")
    else:
        store.audit(
            ident, "system", "developer_notice_sent", json.dumps({"id": delivery_id, "ts": ts})
        )
