"""Reconcile only positive delivery evidence. Absence is never proof of non-delivery."""

from .safety import BotError


def reconcile(store, slack, ident, limit=500):
    inquiry = store.inquiry(ident)
    if not inquiry or inquiry["status"] != "post_unknown":
        raise BotError("not_unknown_delivery")
    expected_user = store.control()["bot_user"]
    if not expected_user:
        raise BotError("bot_identity_unverified")
    messages = slack.history(inquiry["channel_id"], inquiry["thread_ts"], limit)
    parts = store.all(
        "SELECT * FROM deliveries WHERE inquiry_id=? AND draft_id=? ORDER BY part",
        (ident, inquiry["current_draft_id"]),
    )
    for part in parts:
        if part["state"] == "sent":
            continue
        matches = [
            m
            for m in messages
            if m.get("bot_id")
            and m.get("user") == expected_user
            and m.get("text") == part["text"]
            and m.get("metadata", {}).get("event_type") == "dougle_reply"
            and m.get("metadata", {}).get("event_payload", {}).get("delivery_id") == part["id"]
        ]
        if len(matches) == 1:
            with store.tx() as db:
                db.execute(
                    "UPDATE deliveries SET state='sent',ts=? WHERE id=?",
                    (matches[0]["ts"], part["id"]),
                )
    parts = store.all(
        "SELECT * FROM deliveries WHERE inquiry_id=? AND draft_id=? ORDER BY part",
        (ident, inquiry["current_draft_id"]),
    )
    if parts and all(p["state"] == "sent" for p in parts):
        with store.tx() as db:
            db.execute(
                "UPDATE inquiries SET status='posted',posted_ts=?,error=NULL WHERE id=?",
                (parts[-1]["ts"], ident),
            )
        return True
    return False
