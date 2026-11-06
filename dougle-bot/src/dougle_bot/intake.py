import re
from decimal import Decimal, InvalidOperation

from .config import identifier
from .safety import BotError, check_content


def receive(store, config, body, bot_user):
    event = body.get("event", {})
    if not isinstance(event, dict) or event.get("type") != "app_mention":
        return None
    if body.get("team_id") != store.control()["workspace"]:
        return None
    if event.get("channel") not in config.slack.allowed_channel_ids:
        return None
    if (
        event.get("bot_id")
        or event.get("user") == bot_user
        or not identifier(event.get("user"), "U")
    ):
        return None
    for key in ("ts", "thread_ts"):
        value = event.get(key)
        if key == "thread_ts" and value is None:
            continue
        if not isinstance(value, str) or not re.fullmatch(r"\d{1,12}\.\d{1,6}", value):
            return None
        try:
            stamp = Decimal(value)
        except InvalidOperation:
            return None
        if stamp > Decimal(str(store.clock() + 60)):
            return None
    text = event.get("text")
    if not isinstance(text, str) or len(text) > 12000 or f"<@{bot_user}>" not in text:
        return None
    question = text.replace(f"<@{bot_user}>", "").strip()
    if not question:
        return None
    try:
        check_content(question, personal=True)
    except BotError:
        store.audit(None, "system", "sensitive_inquiry_rejected")
        return None
    return store.intake(event, question)
