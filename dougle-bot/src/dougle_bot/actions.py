import json
import threading

from . import cards
from .prompts import validate_output, validate_report
from .safety import BotError, check_content
from .slack_gateway import SlackFailure, history_hash


class Actions:
    def __init__(self, store, config, slack, worker):
        self.store, self.config, self.slack, self.worker = store, config, slack, worker
        self.lock = threading.RLock()

    def authorized(self, user):
        if user != self.config.slack.approver_user_id:
            raise BotError("approver_only")

    def ensure_research_record(self, inquiry, draft):
        if draft.get("verification_json"):
            return json.loads(draft["verification_json"])
        # Pre-upgrade drafts have no research contract. Never infer proof from their prose.
        self.store.enqueue(
            inquiry["id"],
            draft["id"],
            "regenerated",
            json.dumps(
                {
                    "instruction": "필요한 코드·현재 상태 근거를 확인하고 새 검토용 초안을 작성해 주세요."
                },
                ensure_ascii=False,
            ),
        )
        raise BotError("research_required_regenerating")

    def resolve(self, user, value, channel=None, ts=None):
        self.authorized(user)
        if self.store.control()["desired"] != "running":
            raise BotError("bot_stopped")
        try:
            data = json.loads(value) if isinstance(value, str) else value
            ident, draft_id = data["inquiry_id"], data["draft_id"]
            if type(ident) is not int or type(draft_id) is not int or ident <= 0 or draft_id <= 0:
                raise ValueError()
        except (ValueError, TypeError, KeyError):
            raise BotError("invalid_action") from None
        inquiry = self.store.inquiry(ident)
        if not inquiry or inquiry["current_draft_id"] != draft_id:
            raise BotError("stale_draft")
        if (
            channel != inquiry["card_channel_id"]
            or ts != inquiry["card_ts"]
            or not channel
            or not ts
        ):
            raise BotError("wrong_card")
        draft = self.store.current(inquiry)
        if not draft:
            raise BotError("stale_draft")
        return inquiry, draft

    def open_modal(self, user, value, channel, ts, trigger, action):
        inquiry, draft = self.resolve(user, value, channel, ts)
        if action not in ("edit", "lookup"):
            raise BotError("action_unavailable")
        if inquiry["status"] not in ("awaiting_approval", "needs_lookup"):
            raise BotError("stale_draft")
        if action == "lookup" and inquiry["status"] != "needs_lookup":
            raise BotError("lookup_not_requested")
        if action == "edit" and inquiry["status"] != "awaiting_approval":
            raise BotError("lookup_not_editable")
        self.slack.modal(trigger, cards.modal(action, inquiry, draft))

    def cancel(self, user, value, channel, ts):
        self.authorized(user)
        if self.store.control()["desired"] != "running":
            raise BotError("bot_stopped")
        try:
            data = json.loads(value) if isinstance(value, str) else value
            ident, job_id = data["inquiry_id"], data["job_id"]
            if type(ident) is not int or type(job_id) is not int or ident <= 0 or job_id <= 0:
                raise ValueError()
        except (ValueError, TypeError, KeyError):
            raise BotError("invalid_action") from None
        self.store.cancel(ident, job_id, user, channel, ts)
        return ident

    def reject(self, user, value, channel, ts):
        with self.lock:
            inquiry, draft = self.resolve(user, value, channel, ts)
            self.store.reject(inquiry["id"], draft["id"], user)
            return inquiry["id"]

    def submit(self, user, metadata, action, content, observed_at=None):
        with self.lock:
            self.authorized(user)
            try:
                data = json.loads(metadata)
            except (ValueError, TypeError):
                raise BotError("invalid_action") from None
            inquiry, draft = self.resolve(user, data, data.get("channel"), data.get("ts"))
            ident = inquiry["id"]
            if action == "regenerate":
                raise BotError("action_unavailable")
            if inquiry["status"] not in (
                ("awaiting_approval", "needs_lookup", "queued", "drafting", "failed")
                if action == "reject"
                else ("awaiting_approval", "needs_lookup")
            ):
                raise BotError("stale_draft")
            if not isinstance(content, str) or len(content) > 3000:
                raise BotError("invalid_action")
            check_content(content, personal=True)
            if action == "edit":
                if inquiry["status"] != "awaiting_approval":
                    raise BotError("lookup_not_editable")
                output = {
                    "verification": self.ensure_research_record(inquiry, draft),
                    "status": draft["engine_status"],
                    "body": content,
                    "sources": json.loads(draft["sources_json"]),
                    "lookup": None,
                }
                validate_output(
                    output,
                    lookup_basis=bool(inquiry["lookup_basis"]),
                    lookup_time=json.loads(inquiry["lookup_basis"])["observed_at"]
                    if inquiry["lookup_basis"]
                    else None,
                )
                self.store.save_draft(
                    ident,
                    output,
                    "manual_edit",
                    inquiry["history_hash"],
                    inquiry["last_seen_ts"],
                    expected_draft=draft["id"],
                )
            elif action == "reject":
                self.store.reject(ident, draft["id"], user)
            elif action == "lookup":
                if inquiry["status"] != "needs_lookup":
                    raise BotError("lookup_not_requested")
                report = validate_report(content, observed_at, self.store.clock())
                self.store.enqueue(
                    ident,
                    draft["id"],
                    "lookup_result",
                    json.dumps({"lookup": report}, ensure_ascii=False),
                )
            else:
                raise BotError("invalid_action")
        # Bolt handler performs only durable transitions before returning; card refresh is deferred.
        return ident

    def post(self, user, value, channel, ts):
        with self.lock:
            inquiry, draft = self.resolve(user, value, channel, ts)
            ident = inquiry["id"]
            if inquiry["status"] != "awaiting_approval":
                raise BotError("stale_draft")
            if inquiry["channel_id"] not in self.config.slack.allowed_channel_ids:
                raise BotError("channel_not_allowed")
            messages = self.slack.history(
                inquiry["channel_id"], inquiry["thread_ts"], self.config.slack.thread_history_limit
            )
            if history_hash(messages) != inquiry["history_hash"]:
                self.store.enqueue(
                    ident,
                    draft["id"],
                    "regenerated",
                    json.dumps(
                        {
                            "instruction": "스레드의 최신 질문과 정정 내용을 확인하여 존댓말로 새 승인용 답변을 작성해 주세요."
                        },
                        ensure_ascii=False,
                    ),
                )
                raise BotError("thread_changed_regenerating")
            output = {
                "verification": self.ensure_research_record(inquiry, draft),
                "status": draft["engine_status"],
                "body": draft["body"],
                "sources": json.loads(draft["sources_json"]),
                "lookup": json.loads(draft["lookup_json"]),
            }
            validate_output(
                output,
                lookup_basis=bool(inquiry["lookup_basis"]),
                lookup_time=json.loads(inquiry["lookup_basis"])["observed_at"]
                if inquiry["lookup_basis"]
                else None,
            )
            parts = cards.split_text(cards.final_text(draft))
            previous = self.store.all(
                "SELECT text FROM deliveries WHERE inquiry_id=? AND draft_id=? ORDER BY part",
                (ident, draft["id"]),
            )
            if previous and [row["text"] for row in previous] != parts:
                self.store.enqueue(
                    ident,
                    draft["id"],
                    "regenerated",
                    json.dumps(
                        {
                            "instruction": "게시 형식이 변경되었습니다. 출처는 검토용 sources에 보존하고, 현재 질문에 대해 간결한 업무 설명으로 새 초안을 작성해 주세요."
                        },
                        ensure_ascii=False,
                    ),
                )
                self.worker.notify(ident)
                raise BotError("publication_changed_regenerating")
            receipt = self.store.one(
                "SELECT detail FROM audit WHERE inquiry_id=? AND action='card_preview' ORDER BY id DESC LIMIT 1",
                (ident,),
            )
            if not receipt or receipt["detail"] != cards.preview_receipt(draft):
                self.worker.show_card(ident)
                raise BotError("preview_updated_review_again")
            self.store.claim_post(ident, draft["id"], parts, user)
            deliveries = self.store.all(
                "SELECT * FROM deliveries WHERE inquiry_id=? AND draft_id=? ORDER BY part",
                (ident, draft["id"]),
            )
            successful = 0
            for delivery in deliveries:
                if self.store.control()["desired"] != "running":
                    self._unknown(ident, "stopped_during_post")
                    break
                self.store.record_post_intent(delivery["id"])
                with self.store.tx() as db:
                    db.execute(
                        "UPDATE deliveries SET state='sending' WHERE id=?", (delivery["id"],)
                    )
                try:
                    posted = self.slack.post(
                        inquiry["channel_id"],
                        inquiry["thread_ts"],
                        delivery["text"],
                        delivery["id"],
                    )
                except SlackFailure as exc:
                    with self.store.tx() as db:
                        db.execute(
                            "UPDATE deliveries SET state=? WHERE id=?",
                            ("unknown" if exc.uncertain else "not_sent", delivery["id"]),
                        )
                        state = (
                            "post_unknown" if exc.uncertain or successful else "awaiting_approval"
                        )
                        db.execute(
                            "UPDATE inquiries SET status=?,error=? WHERE id=?",
                            (state, exc.code, ident),
                        )
                    break
                except Exception:
                    self._unknown(ident, "delivery_uncertain")
                    break
                with self.store.tx() as db:
                    db.execute(
                        "UPDATE deliveries SET state='sent',ts=? WHERE id=?",
                        (posted, delivery["id"]),
                    )
                successful += 1
            else:
                with self.store.tx() as db:
                    db.execute(
                        "UPDATE inquiries SET status='posted',posted_ts=?,error=NULL WHERE id=?",
                        (posted, ident),
                    )
        try:
            self.worker.show_card(ident)
        except BotError:
            pass

    def _unknown(self, ident, error):
        with self.store.tx() as db:
            db.execute(
                "UPDATE inquiries SET status='post_unknown',error=? WHERE id=?", (error, ident)
            )
