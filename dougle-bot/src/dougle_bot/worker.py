import json
import threading
import time
from decimal import Decimal

from . import cards
from .prompts import prompt, validate_output
from .safety import BotError, check_content
from .slack_gateway import SlackFailure, history_hash


class Worker:
    def __init__(self, store, config, engine, slack):
        self.store, self.config, self.engine, self.slack = store, config, engine, slack
        self.cards_lock = threading.RLock()

    def show_card(self, ident):
        with self.cards_lock:
            inquiry = self.store.inquiry(ident)
            if inquiry["card_state"] == "unknown":
                return
            draft = self.store.current(inquiry)
            link = None
            if inquiry["status"] == "posted" and inquiry["posted_ts"]:
                try:
                    link = self.slack.permalink(inquiry["channel_id"], inquiry["posted_ts"])
                except SlackFailure:
                    pass
            text, blocks = cards.render(inquiry, draft, link)
            channel = inquiry["card_channel_id"] or self.slack.open_dm(
                self.config.slack.approver_user_id
            )
            if not inquiry["card_ts"]:
                with self.store.tx() as db:
                    db.execute(
                        "UPDATE inquiries SET card_state='sending',card_channel_id=? WHERE id=?",
                        (channel, ident),
                    )
            try:
                ts = self.slack.card(channel, text, blocks, inquiry["card_ts"])
                with self.store.tx() as db:
                    db.execute(
                        "UPDATE inquiries SET card_channel_id=?,card_ts=?,card_state='sent' WHERE id=?",
                        (channel, ts, ident),
                    )
            except SlackFailure as exc:
                with self.store.tx() as db:
                    state = (
                        "unknown"
                        if exc.uncertain and not inquiry["card_ts"]
                        else inquiry["card_state"]
                    )
                    db.execute(
                        "UPDATE inquiries SET card_state=?,error=? WHERE id=?",
                        (state, "card_delivery_failed", ident),
                    )
                raise

    def run_once(self):
        job = self.store.claim_job()
        if job is None:
            return False
        ident = job["inquiry_id"]
        inquiry = self.store.inquiry(ident)
        try:
            if inquiry["channel_id"] not in self.config.slack.allowed_channel_ids:
                raise BotError("channel_not_allowed")
            messages = self.slack.history(
                inquiry["channel_id"], inquiry["thread_ts"], self.config.slack.thread_history_limit
            )
            check_content(json.dumps(messages, ensure_ascii=False), personal=True)
            payload = json.loads(job["payload"]) if job["payload"] else {}
            lookup = payload.get("lookup") or (
                json.loads(inquiry["lookup_basis"]) if inquiry["lookup_basis"] else None
            )
            if payload.get("lookup"):
                with self.store.tx() as db:
                    db.execute(
                        "UPDATE inquiries SET lookup_basis=? WHERE id=?",
                        (json.dumps(lookup, ensure_ascii=False), ident),
                    )
            session = inquiry["engine_session_id"]
            restored = False
            correction = ""
            schema_retried = False
            while True:
                if self.store.control()["desired"] != "running":
                    raise BotError("bot_stopped")
                run_id, budget = self.store.reserve(
                    ident,
                    self.config.engine.max_budget_usd_per_call,
                    self.config.engine.daily_budget_usd,
                    bool(session),
                )
                context_messages = messages
                if session and inquiry["last_seen_ts"]:
                    context_messages = [
                        m for m in messages if Decimal(m["ts"]) > Decimal(inquiry["last_seen_ts"])
                    ]
                text = prompt(
                    self.config.engine,
                    inquiry,
                    context_messages,
                    instruction=payload.get("instruction", ""),
                    lookup=lookup,
                    restored=restored,
                    correction=correction,
                )
                start = time.monotonic()
                try:
                    result = self.engine.run(text, session, budget)
                except BotError as exc:
                    self.store.finish_run(
                        run_id, error=exc.code, duration=int((time.monotonic() - start) * 1000)
                    )
                    if exc.code == "session_not_found" and session and not restored:
                        session = None
                        restored = True
                        continue
                    raise
                self.store.finish_run(
                    run_id,
                    cost=result.cost_usd,
                    session=result.session_id,
                    ok=True,
                    duration=int((time.monotonic() - start) * 1000),
                )
                session = result.session_id
                try:
                    if (
                        not {self.config.engine.ask_skill, self.config.engine.style_skill}
                        <= result.activated
                    ):
                        raise BotError("required_skill_not_activated")
                    output = validate_output(
                        result.output,
                        lookup_basis=bool(lookup),
                        lookup_time=lookup["observed_at"] if lookup else None,
                    )
                    self.store.audit(
                        ident,
                        "system",
                        "required_skills_verified",
                        json.dumps(sorted(result.activated)),
                    )
                    break
                except BotError as exc:
                    if schema_retried:
                        raise
                    schema_retried = True
                    correction = exc.code
            last_seen = max((m["ts"] for m in messages), key=Decimal, default=inquiry["trigger_ts"])
            self.store.save_draft(
                ident,
                output,
                job["kind"],
                history_hash(messages),
                last_seen,
                session,
                payload.get("instruction"),
            )
            self.store.finish_job(job["id"])
            if restored:
                with self.store.tx() as db:
                    db.execute(
                        "UPDATE inquiries SET error='session_restored_from_thread' WHERE id=?",
                        (ident,),
                    )
        except BotError as exc:
            self.store.fail_job(job, exc.code)
        except Exception:
            self.store.fail_job(job, "processing_failed")
        try:
            self.show_card(ident)
        except BotError:
            pass
        return True
