import hashlib
import json
import uuid
from decimal import Decimal
from typing import Protocol

from .images import MAX_IMAGE_BYTES, download_image
from .safety import BotError


class SlackFailure(BotError):
    def __init__(self, code="slack_failed", *, uncertain=False):
        super().__init__(code)
        self.uncertain = uncertain


class SlackGateway(Protocol):
    def history(self, channel, thread, limit): ...
    def image(self, file_id): ...
    def open_dm(self, user): ...
    def card(self, channel, text, blocks, ts=None): ...
    def post(self, channel, thread, text, delivery_id): ...
    def permalink(self, channel, ts): ...
    def reaction(self, channel, ts, name): ...
    def remove_reaction(self, channel, ts, name): ...
    def modal(self, trigger, view): ...


def history_hash(messages):
    rows = []
    for message in messages:
        if message.get("bot_id"):
            continue
        row = {k: message.get(k) for k in ("ts", "user", "text", "edited")}
        if message.get("files"):
            row["files"] = message["files"]
        blocks = message.get("blocks", [])
        if isinstance(blocks, list):
            images = [b for b in blocks if isinstance(b, dict) and b.get("type") == "image"]
            if images:
                row["image_blocks"] = images
        elif blocks is not None:
            row["invalid_blocks"] = True
        rows.append(row)
    return hashlib.sha256(json.dumps(rows, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


class BoltSlackGateway:
    def __init__(self, token, client=None, *, image_downloader=download_image):
        if client is None:
            from slack_sdk import WebClient

            client = WebClient(token=token, retry_handlers=[], timeout=15)
        self.client = client
        self._token = token
        self._image_downloader = image_downloader
        self.cancelled = lambda: False

    def call(self, method, **kwargs):
        from slack_sdk.errors import SlackApiError

        try:
            if self.cancelled():
                raise SlackFailure("bot_stopped")
            result = getattr(self.client, method)(**kwargs)
            if not result.get("ok", False):
                raise SlackFailure()
            return result
        except SlackApiError as exc:
            code = exc.response.get("error", "")
            known = {
                "file_not_found",
                "access_denied",
                "channel_not_found",
                "not_in_channel",
                "missing_scope",
                "invalid_auth",
                "token_revoked",
                "is_archived",
                "message_not_found",
                "thread_not_found",
                "invalid_blocks",
                "invalid_arguments",
                "ratelimited",
                "user_not_found",
                "cannot_dm_bot",
                "expired_trigger_id",
                "trigger_exchanged",
                "already_reacted",
                "no_reaction",
                "invalid_name",
            }
            raise SlackFailure(
                code if code in known else "slack_failed", uncertain=code not in known
            ) from None
        except SlackFailure:
            raise
        except Exception:
            raise SlackFailure("slack_connection_failed", uncertain=True) from None

    def identity(self):
        return self.call("auth_test")

    def history(self, channel, thread, limit):
        cursor = None
        seen = set()
        rows = {}
        for _ in range(100):
            response = self.call(
                "conversations_replies",
                channel=channel,
                ts=thread,
                limit=100,
                cursor=cursor,
                include_all_metadata=True,
            )
            for row in response.get("messages", []):
                if isinstance(row, dict) and isinstance(row.get("ts"), str):
                    rows[row["ts"]] = row
            cursor = response.get("response_metadata", {}).get("next_cursor")
            if not cursor:
                if response.get("has_more"):
                    raise SlackFailure("incomplete_history")
                values = sorted(rows.values(), key=lambda x: Decimal(x["ts"]))
                if len(values) > limit:
                    # Do not silently call a truncated thread complete.
                    raise SlackFailure("thread_history_limit")
                return values
            if cursor in seen:
                raise SlackFailure("repeated_cursor")
            seen.add(cursor)
        raise SlackFailure("thread_history_limit")

    def image(self, file_id):
        try:
            info = self.call("files_info", file=file_id).get("file", {})
        except SlackFailure as exc:
            if exc.code == "bot_stopped":
                raise
            if exc.code in ("missing_scope", "file_not_found", "access_denied"):
                raise BotError("image_access_denied") from None
            raise BotError("image_download_failed") from None
        if not isinstance(info, dict) or info.get("id") != file_id:
            raise BotError("image_metadata_invalid")
        if info.get("is_external") or info.get("mode") != "hosted":
            raise BotError("image_attachment_unsupported")
        mime = info.get("mimetype")
        if mime not in ("image/png", "image/jpeg"):
            raise BotError("image_type_unsupported")
        size = info.get("size")
        if type(size) is not int or not 0 < size <= MAX_IMAGE_BYTES:
            raise BotError("image_size_limit")
        if self.cancelled():
            raise SlackFailure("bot_stopped")
        data, received_mime = self._image_downloader(info.get("url_private"), self._token)
        if received_mime != mime or len(data) != size:
            raise BotError("image_data_invalid")
        return data, mime

    def open_dm(self, user):
        return self.call("conversations_open", users=user)["channel"]["id"]

    def card(self, channel, text, blocks, ts=None):
        args = dict(channel=channel, text=text, blocks=blocks)
        if ts:
            args["ts"] = ts
            return self.call("chat_update", **args)["ts"]
        return self.call("chat_postMessage", **args, unfurl_links=False, unfurl_media=False)["ts"]

    def post(self, channel, thread, text, delivery_id):
        return self.call(
            "chat_postMessage",
            channel=channel,
            thread_ts=thread,
            text=text,
            metadata={"event_type": "dougle_reply", "event_payload": {"delivery_id": delivery_id}},
            client_msg_id=delivery_id,
            unfurl_links=False,
            unfurl_media=False,
            parse="none",
        )["ts"]

    def permalink(self, channel, ts):
        return self.call("chat_getPermalink", channel=channel, message_ts=ts)["permalink"]

    def reaction(self, channel, ts, name):
        try:
            self.call("reactions_add", channel=channel, timestamp=ts, name=name)
        except SlackFailure as exc:
            if exc.code != "already_reacted":
                raise

    def remove_reaction(self, channel, ts, name):
        try:
            self.call("reactions_remove", channel=channel, timestamp=ts, name=name)
        except SlackFailure as exc:
            if exc.code not in ("no_reaction", "invalid_name"):
                raise

    def modal(self, trigger, view):
        return self.call("views_open", trigger_id=trigger, view=view)

    def warn(self, channel, user, text):
        try:
            self.call("chat_postEphemeral", channel=channel, user=user, text=text)
        except SlackFailure:
            pass


class FakeSlack:
    def __init__(self):
        self.messages = {}
        self.cards = {}
        self.calls = []
        self.failures = {}
        self.count = 0
        self.reactions = set()
        self.images = {}

    def fault(self, kind):
        exc = self.failures.pop(kind, None)
        if exc:
            raise exc

    def stamp(self):
        self.count += 1
        return f"1900000000.{self.count:06d}"

    def history(self, channel, thread, limit):
        self.calls.append(("history", channel, thread))
        self.fault("history")
        rows = list(self.messages.get((channel, thread), []))
        if len(rows) > limit:
            raise SlackFailure("thread_history_limit")
        return rows

    def image(self, file_id):
        self.calls.append(("image", file_id))
        self.fault("image")
        if file_id not in self.images:
            raise BotError("image_access_denied")
        return self.images[file_id]

    def open_dm(self, user):
        self.fault("dm")
        return "DTEST"

    def card(self, channel, text, blocks, ts=None):
        self.fault("card_before")
        ts = ts or self.stamp()
        self.cards[(channel, ts)] = {"text": text, "blocks": blocks}
        self.calls.append(("card", channel, ts))
        self.fault("card_after")
        return ts

    def post(self, channel, thread, text, delivery_id):
        self.fault("post_before")
        ts = self.stamp()
        self.messages.setdefault((channel, thread), []).append(
            {
                "ts": ts,
                "text": text,
                "bot_id": "BTEST",
                "user": "UBOT",
                "metadata": {
                    "event_type": "dougle_reply",
                    "event_payload": {"delivery_id": delivery_id},
                },
            }
        )
        self.calls.append(("post", channel, thread, text, delivery_id))
        self.fault("post_after")
        return ts

    def permalink(self, channel, ts):
        self.fault("permalink")
        return f"https://example.slack.com/archives/{channel}/p{ts.replace('.', '')}"

    def reaction(self, *args):
        self.calls.append(("reaction", *args))
        self.fault("reaction")
        self.reactions.add(args)

    def remove_reaction(self, *args):
        self.calls.append(("remove_reaction", *args))
        self.fault("remove_reaction")
        self.reactions.discard(args)

    def modal(self, trigger, view):
        self.calls.append(("modal", trigger, view))
        self.fault("modal")
        return {"view": {"id": str(uuid.uuid4())}}

    def warn(self, channel, user, text):
        self.calls.append(("warn", channel, user, text))
