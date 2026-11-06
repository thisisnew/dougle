import json
import threading
import time
from decimal import Decimal

from . import cards
from .evidence import KINDS, QUESTION_KINDS, validate_verification
from .images import collect_images, prompt_messages
from .presentation import reaction_names
from .progress import Progress
from .prompts import prompt, validate_output
from .safety import BotError, check_content
from .slack_gateway import SlackFailure, history_hash


class Worker:
    progress_interval = 30

    def __init__(self, store, config, engine, slack):
        self.store, self.config, self.engine, self.slack = store, config, engine, slack
        self.cards_lock = threading.RLock()

    def update_reaction(self, inquiry):
        targets = reaction_names(inquiry["status"], self.config.slack.ack_reaction)
        if not targets:
            return
        ident = inquiry["id"]

        def names(detail):
            # Previous releases recorded a single raw name, not a JSON array.
            return set(json.loads(detail)) if detail.startswith("[") else {detail}

        applied = self.store.one(
            "SELECT action,detail FROM audit WHERE inquiry_id=? AND action IN ('reaction_applied','reaction_intent') ORDER BY id DESC LIMIT 1",
            (ident,),
        )
        if (
            applied
            and applied["action"] == "reaction_applied"
            and names(applied["detail"]) == set(targets)
        ):
            return
        attempted = {"eyes"}
        for row in self.store.all(
            "SELECT DISTINCT detail FROM audit WHERE inquiry_id=? AND action='reaction_intent'",
            (ident,),
        ):
            attempted.update(names(row["detail"]))
        # Record the whole desired set before any remote write. Partial adds/removals
        # can then be reconciled after a worker restart without losing identity.
        self.store.audit(ident, "system", "reaction_intent", json.dumps(targets))
        args = (inquiry["channel_id"], inquiry["trigger_ts"])
        actual = set()
        for target in targets:
            try:
                self.slack.reaction(*args, target)
                actual.add(target)
            except SlackFailure as exc:
                if target != "dougle" or exc.code != "invalid_name":
                    raise
                # The built-in state emoji still works when the custom identity is absent.
        for previous in attempted - actual:
            self.slack.remove_reaction(*args, previous)
        self.store.audit(ident, "system", "reaction_applied", json.dumps(sorted(actual)))

    def notify(self, ident):
        # A progress display failure must not prevent answering or create another card.
        try:
            self.show_card(ident)
        except BotError:
            pass

    def show_card(self, ident):
        with self.cards_lock:
            inquiry = self.store.inquiry(ident)
            try:
                self.update_reaction(inquiry)
            except BotError:
                self.store.audit(ident, "system", "reaction_update_failed")
            if inquiry["card_state"] == "unknown":
                return
            draft = self.store.current(inquiry)
            link = None
            if inquiry["status"] == "posted" and inquiry["posted_ts"]:
                try:
                    link = self.slack.permalink(inquiry["channel_id"], inquiry["posted_ts"])
                except SlackFailure:
                    pass
            if inquiry["status"] == "drafting":
                latest = self.store.one(
                    "SELECT detail FROM audit WHERE inquiry_id=? AND action='answer_progress' ORDER BY id DESC LIMIT 1",
                    (ident,),
                )
                if latest:
                    try:
                        inquiry["progress"] = json.loads(latest["detail"])
                    except (ValueError, TypeError):
                        pass
            if inquiry["status"] in ("queued", "drafting"):
                job = self.store.one(
                    "SELECT id FROM jobs WHERE inquiry_id=? AND status IN ('queued','running') ORDER BY id DESC LIMIT 1",
                    (ident,),
                )
                inquiry["job_id"] = job["id"] if job else None
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
                if draft and inquiry["status"] == "awaiting_approval":
                    self.store.audit(ident, "system", "card_preview", cards.preview_receipt(draft))
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
        progress = Progress()
        stop = threading.Event()

        def report():
            while not stop.wait(self.progress_interval):
                self.report_progress(job["inquiry_id"], progress)

        reporter = threading.Thread(target=report, daemon=True)
        reporter.start()
        try:
            return self.process_job(job, progress)
        finally:
            stop.set()
            # A slow Slack update must not delay pipe reads or keep a completed job running.
            reporter.join(timeout=0.1)

    def report_progress(self, ident, progress):
        try:
            with self.cards_lock:
                if self.store.inquiry(ident)["status"] != "drafting":
                    return
                self.store.audit(
                    ident, "system", "answer_progress", json.dumps(progress.snapshot())
                )
                self.notify(ident)
        except Exception:
            # Observability cannot authorize a draft or prevent research/timeout handling.
            pass

    def process_job(self, job, progress):
        ident = job["inquiry_id"]
        inquiry = self.store.inquiry(ident)
        self.report_progress(ident, progress)
        try:
            if inquiry["channel_id"] not in self.config.slack.allowed_channel_ids:
                raise BotError("channel_not_allowed")
            messages = self.slack.history(
                inquiry["channel_id"], inquiry["thread_ts"], self.config.slack.thread_history_limit
            )
            check_content(json.dumps(messages, ensure_ascii=False), personal=True)
            payload = json.loads(job["payload"]) if job["payload"] else {}
            if payload.get("attachment_error"):
                raise BotError(payload["attachment_error"])
            progress.phase("attachments")
            images = collect_images(self.slack, messages)
            if not set(payload.get("expected_image_ids", [])) <= {img.file_id for img in images}:
                raise BotError("image_access_denied")
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
            required_evidence = set()
            attempt = 0
            previous_draft = self.store.current(inquiry)
            if previous_draft and previous_draft.get("verification_json"):
                previous_checks = json.loads(previous_draft["verification_json"])
                required_evidence.update(previous_checks["required"])
            while True:
                if self.store.inquiry(ident)["status"] == "cancelled":
                    raise BotError("inquiry_cancelled")
                if self.store.control()["desired"] != "running":
                    raise BotError("bot_stopped")
                remaining = progress.remaining(self.config.engine.timeout_seconds)
                if remaining <= 0:
                    raise BotError("engine_timeout")
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
                    prompt_messages(context_messages),
                    instruction=payload.get("instruction", ""),
                    lookup=lookup,
                    restored=restored,
                    correction=correction,
                    required_evidence=required_evidence,
                    image_context=[
                        {"file_id": img.file_id, "message_ts": img.message_ts} for img in images
                    ],
                )
                start = time.monotonic()
                try:
                    attempt += 1
                    progress.phase("retry" if attempt > 1 else "engine", attempt=attempt)
                    result = self.engine.run(
                        text,
                        session,
                        budget,
                        images=images,
                        on_progress=progress.activity,
                        cancel_requested=lambda: self.store.inquiry(ident)["status"] == "cancelled",
                        timeout_seconds=progress.remaining(self.config.engine.timeout_seconds),
                    )
                    if progress.remaining(self.config.engine.timeout_seconds) <= 0:
                        raise BotError("engine_timeout")
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
                if self.store.inquiry(ident)["status"] == "cancelled":
                    raise BotError("inquiry_cancelled")
                session = result.session_id
                progress.phase("validation")
                try:
                    if not set(self.config.engine.required_skills) <= result.activated:
                        raise BotError("required_skill_not_activated")
                    # Retain declared obligations across the one allowed correction attempt.
                    verification = (
                        result.output.get("verification", {})
                        if isinstance(result.output, dict)
                        else {}
                    )
                    declared = (
                        verification.get("required", []) if isinstance(verification, dict) else []
                    )
                    if isinstance(declared, list):
                        required_evidence.update(
                            x for x in declared if isinstance(x, str) and x in KINDS
                        )
                    category = (
                        verification.get("question_kind")
                        if isinstance(verification, dict)
                        else None
                    )
                    if isinstance(category, str):
                        required_evidence.update(QUESTION_KINDS.get(category, set()))
                    output = validate_output(
                        result.output,
                        lookup_basis=bool(lookup),
                        lookup_time=lookup["observed_at"] if lookup else None,
                    )
                    validate_verification(
                        output,
                        result.reads,
                        image_ids={img.file_id for img in images},
                        report=bool(lookup),
                        required_floor=required_evidence,
                    )
                    self.store.audit(
                        ident, "system", "evidence_checks_verified", json.dumps(verification)
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
