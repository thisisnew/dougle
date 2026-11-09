import copy
import hashlib
import json
import threading
import time
from dataclasses import asdict
from decimal import Decimal
from pathlib import Path

from . import cards, developer_notice
from .compact_policy import COMPACT_POLICY
from .evidence import (
    KINDS,
    QUESTION_KINDS,
    explicit_query_requirements,
    failure_summary,
    validate_verification,
    verification_diagnostics,
)
from .freshness import convert_unverified_live
from .handoff import reported_evidence, route_unresolved
from .handoff_notice import after_developer_dm
from .images import collect_images, prompt_messages
from .monitor import Monitor
from .navigation import read_navigation_index
from .presentation import REACTION_FALLBACKS, reaction_names
from .progress import Progress
from .prompts import prompt, validate_output
from .safety import BotError, check_content
from .slack_gateway import SlackFailure, history_hash
from .source_registry import SourceRegistryError, discover_github_sources, discover_local_sources
from .static_claims import scope_static_timing


class Worker:
    progress_interval = 30

    def __init__(self, store, config, engine, slack, *, memory_root=None):
        self.store, self.config, self.engine, self.slack = store, config, engine, slack
        self.memory_root = Path(memory_root) if memory_root is not None else Path.home() / ".dougle"
        self.cards_lock = threading.RLock()
        self.monitor = Monitor(config.home)

    def _execution_setup_confirmed(self, result):
        if self.config.engine.compact_policy:
            expected = hashlib.sha256(COMPACT_POLICY.encode("utf-8")).hexdigest()
            return getattr(result, "policy_digest", None) == expected
        return set(self.config.engine.required_skills) <= result.activated

    def _session_fingerprint(self, inquiry):
        """Bind a resumed session to its execution settings and registered source scope.

        An unreadable registry or index cannot certify that an old session still has
        the same source access. The current call may proceed, but its session is not
        reusable until a complete fingerprint can be computed.
        """
        try:
            github = sorted(discover_github_sources(self.memory_root))
            local = sorted(discover_local_sources(self.memory_root))
        except SourceRegistryError:
            return None
        index = read_navigation_index(self.memory_root)
        from .graphify_index import graph_inventory

        if index is None and (self.memory_root / "index.md").exists():
            return None
        scope = {
            "version": 1,
            "engine": asdict(self.config.engine),
            "slack": asdict(self.config.slack),
            "asker": inquiry["asker_user_id"],
            "channel": inquiry["channel_id"],
            "thread": inquiry["thread_key"],
            "registered_github": github,
            "registered_local": local,
            "graph_inventory": graph_inventory(self.config.home / "graphify"),
            "index_sha256": hashlib.sha256(index.encode("utf-8")).hexdigest() if index else None,
            "compact_policy_sha256": (
                hashlib.sha256(COMPACT_POLICY.encode("utf-8")).hexdigest()
                if self.config.engine.compact_policy else None
            ),
        }
        return hashlib.sha256(
            json.dumps(scope, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest()

    def _session_policy_audit(self, fingerprint):
        action = (
            "compact_policy_supplied"
            if self.config.engine.compact_policy else "required_skills_verified"
        )
        detail = json.dumps(
            {"version": 1, "session_fingerprint": fingerprint,
             "policy_digest": (
                 hashlib.sha256(COMPACT_POLICY.encode("utf-8")).hexdigest()
                 if self.config.engine.compact_policy else None
             )},
            separators=(",", ":"),
        )
        return action, detail

    def _session_policy_compatible(self, thread_key, fingerprint):
        """Do not resume a session with changed policy, settings, or source scope."""
        if fingerprint is None:
            return False
        row = self.store.one(
            "SELECT a.action,a.detail FROM audit a JOIN inquiries i ON i.id=a.inquiry_id "
            "WHERE i.thread_key=? AND a.action IN "
            "('compact_policy_supplied','required_skills_verified') "
            "ORDER BY a.id DESC LIMIT 1",
            (thread_key,),
        )
        if not row or row["action"] != self._session_policy_audit(fingerprint)[0]:
            return False
        try:
            detail = json.loads(row["detail"])
        except (TypeError, ValueError):
            return False
        return (
            isinstance(detail, dict)
            and detail.get("version") == 1
            and detail.get("session_fingerprint") == fingerprint
            and detail.get("policy_digest") == (
                hashlib.sha256(COMPACT_POLICY.encode("utf-8")).hexdigest()
                if self.config.engine.compact_policy else None
            )
        )

    def update_reaction(self, inquiry):
        status = "needs_lookup" if inquiry.get("human_takeover") else inquiry["status"]
        targets = reaction_names(status, self.config.slack.ack_reaction)
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
        intent = set(targets) | {REACTION_FALLBACKS[t] for t in targets if t in REACTION_FALLBACKS}
        self.store.audit(ident, "system", "reaction_intent", json.dumps(sorted(intent)))
        args = (inquiry["channel_id"], inquiry["trigger_ts"])
        actual = set()
        for target in targets:
            try:
                self.slack.reaction(*args, target)
                actual.add(target)
            except SlackFailure as exc:
                if exc.code != "invalid_name":
                    raise
                if target in REACTION_FALLBACKS:
                    fallback = REACTION_FALLBACKS[target]
                    self.slack.reaction(*args, fallback)
                    actual.add(fallback)
                elif target != "dougle":
                    raise
                # A missing identity does not hide the state. Only invalid_name permits fallback.
        for previous in attempted - actual:
            self.slack.remove_reaction(*args, previous)
        self.store.audit(ident, "system", "reaction_applied", json.dumps(sorted(actual)))

    def notify(self, ident):
        # A progress display failure must not prevent answering or create another card.
        try:
            if self.store.inquiry(ident)["status"] == "queued":
                self.monitor.emit(ident, Progress().snapshot(), "queued")
            self.show_card(ident)
        except BotError:
            pass

    def show_card(self, ident):
        with self.cards_lock:
            inquiry = self.store.inquiry(ident)
            try:
                developer_notice.notify(self.store, self.config, self.slack, ident)
            except Exception:
                self.store.audit(ident, "system", "developer_notice_failed")
            try:
                self.update_reaction(inquiry)
            except BotError:
                self.store.audit(ident, "system", "reaction_update_failed")
            if (
                not self.config.slack.require_approval
                and not inquiry["card_ts"]
                and inquiry["status"] not in ("needs_lookup", "failed", "post_unknown")
            ):
                return
            if inquiry["card_state"] == "unknown":
                return
            draft = self.store.current(inquiry)
            if draft and inquiry["status"] == "needs_lookup":
                inquiry["sql_withheld"] = bool(
                    self.store.one(
                        "SELECT 1 FROM audit WHERE inquiry_id=? AND action='lookup_sql_withheld' "
                        "AND detail=(SELECT CAST(MAX(id) AS TEXT) FROM engine_runs WHERE inquiry_id=?) LIMIT 1",
                        (ident, ident),
                    )
                )
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
            if inquiry["status"] in ("queued", "drafting", "failed"):
                job = self.store.one(
                    "SELECT id FROM jobs WHERE inquiry_id=? AND status IN ('queued','running','failed','done') ORDER BY id DESC LIMIT 1",
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

            after_developer_dm(self.store, self.config, self.slack, inquiry)

    def publish_ready(self):
        if self.config.slack.require_approval:
            return False
        pending = self.store.pending_auto_reply()
        if not pending:
            return False
        from .actions import Actions

        Actions(self.store, self.config, self.slack, self).post_automatic(pending["id"])
        return True

    def notify_recovered(self):
        # One startup pass, not a polling loop. Uncertain DM delivery remains held.
        for row in self.store.all(
            "SELECT id FROM inquiries WHERE (status IN ('needs_lookup','failed','post_unknown') "
            "OR (status='cancelled' AND EXISTS(SELECT 1 FROM audit a WHERE a.inquiry_id=inquiries.id "
            "AND a.action='developer_takeover'))) AND card_state!='unknown'"
        ):
            self.notify(row["id"])

    def run_once(self):
        if self.publish_ready():
            return True
        job = self.store.claim_job()
        if job is None:
            return False
        progress = Progress(observer=lambda value: self.monitor.emit(job["inquiry_id"], value))
        queue_ms = max(0, (self.store.clock() - job["created_at"]) * 1000)
        stop = threading.Event()

        def report():
            last_card = time.monotonic()
            while not stop.wait(min(5, self.progress_interval)):
                self.monitor.emit(job["inquiry_id"], progress.snapshot())
                if time.monotonic() - last_card >= self.progress_interval:
                    self.report_progress(job["inquiry_id"], progress)
                    last_card = time.monotonic()

        reporter = threading.Thread(target=report, daemon=True)
        reporter.start()
        try:
            return self.process_job(job, progress)
        finally:
            stop.set()
            try:
                self.monitor.emit(
                    job["inquiry_id"],
                    progress.snapshot(),
                    self.store.inquiry(job["inquiry_id"])["status"],
                )
            except Exception:
                pass
            # A slow Slack update must not delay pipe reads or keep a completed job running.
            reporter.join(timeout=0.1)
            try:
                self.store.audit(
                    job["inquiry_id"],
                    "system",
                    "answer_timing",
                    json.dumps(progress.profile(queue_ms)),
                )
            except Exception:
                pass

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
        memory_batches = {}
        accepted_batches = set()
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
            session_fingerprint = self._session_fingerprint(inquiry)
            session = inquiry["engine_session_id"]
            if session and not self._session_policy_compatible(
                inquiry["thread_key"], session_fingerprint
            ):
                session = None
                self.store.audit(ident, "system", "engine_session_reset", "policy_changed")
            restored = False
            correction = ""
            schema_retried = False
            presentation_repair = None
            verification_repair = None
            correction_detail = None
            repair_receipts = []
            repair_reads = None
            repair_run_id = None
            required_evidence = explicit_query_requirements(inquiry["question_text"])
            execution_policy_audit = None
            attempt = 0
            saved_requirements = self.store.one(
                "SELECT detail FROM audit WHERE inquiry_id=? AND action='research_requirements' ORDER BY id DESC LIMIT 1",
                (ident,),
            )
            if saved_requirements:
                required_evidence.update(json.loads(saved_requirements["detail"]))
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
                progress.phase("prepare")
                text = prompt(
                    self.config.engine,
                    inquiry,
                    prompt_messages(context_messages),
                    instruction=payload.get("instruction", ""),
                    lookup=lookup,
                    restored=restored,
                    correction=correction,
                    required_evidence=required_evidence,
                    presentation_repair=presentation_repair,
                    verification_repair=verification_repair,
                    repair_receipts=repair_receipts,
                    correction_detail=correction_detail,
                    image_context=[
                        {"file_id": img.file_id, "message_ts": img.message_ts} for img in images
                    ],
                )
                progress.sizes(prompt=len(text), prompt_bytes=len(text.encode("utf-8")))
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
                    if getattr(result, "memory_batch", None):
                        memory_batches[run_id] = result.memory_batch
                    if getattr(result, "memory_error", None) in {
                        "memory_finalize_failed",
                        "memory_staging_unavailable",
                    }:
                        self.store.audit(
                            ident, "system", "memory_batch_failed", result.memory_error
                        )
                    progress.usage(result.usage)
                    if progress.remaining(self.config.engine.timeout_seconds) <= 0:
                        raise BotError("engine_timeout")
                except BotError as exc:
                    self.store.finish_run(
                        run_id, error=exc.code, duration=int((time.monotonic() - start) * 1000)
                    )
                    if exc.code == "session_not_found" and session and not restored:
                        progress.retry("session")
                        self.store.audit(
                            ident,
                            "system",
                            "answer_retry",
                            json.dumps({"attempt": attempt, "reason": "session", "code": "session_not_found"}),
                        )
                        session = None
                        presentation_repair = repair_reads = None
                        verification_repair = None
                        repair_receipts = []
                        repair_run_id = None
                        restored = True
                        continue
                    raise
                finally:
                    progress.finish_attempt()
                self.store.finish_run(
                    run_id,
                    cost=result.cost_usd,
                    session=result.session_id,
                    ok=True,
                    duration=int((time.monotonic() - start) * 1000),
                )
                if self.store.inquiry(ident)["status"] == "cancelled":
                    raise BotError("inquiry_cancelled")
                progress.sizes(output=len(json.dumps(result.output, ensure_ascii=False)))
                session = result.session_id
                progress.phase("validation")
                try:
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
                    declared_checks = (
                        verification.get("checks", []) if isinstance(verification, dict) else []
                    )
                    if isinstance(declared_checks, list):
                        required_evidence.update(
                            check["kind"]
                            for check in declared_checks
                            if isinstance(check, dict)
                            and isinstance(check.get("kind"), str)
                            and check["kind"] in KINDS
                        )
                    category = (
                        verification.get("question_kind")
                        if isinstance(verification, dict)
                        else None
                    )
                    if isinstance(category, str):
                        required_evidence.update(QUESTION_KINDS.get(category, set()))
                    self.store.audit(
                        ident,
                        "system",
                        "research_requirements",
                        json.dumps(sorted(required_evidence)),
                    )
                    if not self._execution_setup_confirmed(result):
                        raise BotError(
                            "compact_policy_not_supplied"
                            if self.config.engine.compact_policy
                            else "required_skill_not_activated"
                        )
                    try:
                        output = validate_output(
                            result.output,
                            lookup_basis=reported_evidence(lookup),
                            lookup_time=lookup["observed_at"]
                            if reported_evidence(lookup)
                            else None,
                        )
                    except BotError as invalid:
                        if invalid.code != "unsafe_lookup_sql":
                            raise
                        # Withhold only the optional SQL. All evidence gates still run below.
                        # The runtime never executes suggested SQL or posts a lookup request.
                        output = copy.deepcopy(result.output)
                        output["lookup"]["sql"] = ""
                        output["lookup"]["schema_verified"] = False
                        output = validate_output(output)
                        self.store.audit(ident, "system", "lookup_sql_withheld", str(run_id))
                    if presentation_repair is not None and any(
                        output[key] != presentation_repair[key]
                        for key in ("status", "sources", "lookup", "verification")
                    ):
                        raise BotError("repair_changed_evidence")
                    if verification_repair is not None:
                        if any(
                            output[key] != verification_repair[key]
                            for key in ("status", "body", "sources", "lookup")
                        ) or (
                            not isinstance(output["verification"], dict)
                            or output["verification"].get("question_kind")
                            != verification_repair["verification"]["question_kind"]
                            or "live" in required_evidence
                        ):
                            raise BotError("repair_changed_evidence")
                    validate_verification(
                        output,
                        repair_reads
                        if presentation_repair is not None or verification_repair is not None
                        else result.reads,
                        image_ids={img.file_id for img in images},
                        report=lookup or False,
                        required_floor=required_evidence,
                        question=inquiry["question_text"],
                    )
                    evidence_reads = (
                        repair_reads
                        if presentation_repair is not None or verification_repair is not None
                        else result.reads
                    )
                    static_scoped = scope_static_timing(
                        output, evidence_reads, question=inquiry["question_text"],
                        report=lookup or False,
                        context_clear=len(context_messages) <= 1,
                    )
                    if static_scoped is not output:
                        output = validate_output(
                            static_scoped,
                            lookup_basis=reported_evidence(lookup),
                            lookup_time=lookup["observed_at"]
                            if reported_evidence(lookup)
                            else None,
                        )
                        validate_verification(
                            output, evidence_reads,
                            image_ids={img.file_id for img in images},
                            report=lookup or False,
                            required_floor=required_evidence,
                            question=inquiry["question_text"],
                        )
                        self.store.audit(ident, "system", "static_claim_scoped", str(run_id))
                    scoped = convert_unverified_live(
                        output, evidence_reads, report=lookup or False,
                        question=inquiry["question_text"],
                    )
                    if scoped is not output:
                        output = validate_output(
                            scoped,
                            lookup_basis=reported_evidence(lookup),
                            lookup_time=lookup["observed_at"]
                            if reported_evidence(lookup)
                            else None,
                        )
                        validate_verification(
                            output,
                            evidence_reads,
                            image_ids={img.file_id for img in images},
                            report=lookup or False,
                            required_floor=required_evidence,
                            question=inquiry["question_text"],
                        )
                        self.store.audit(ident, "system", "live_query_scoped", str(run_id))
                    self.store.audit(
                        ident,
                        "system",
                        "evidence_checks_verified",
                        json.dumps(output["verification"]),
                    )
                    if presentation_repair is not None:
                        self.store.audit(
                            ident,
                            "system",
                            "presentation_evidence_reused",
                            json.dumps({"evidence_run_id": repair_run_id, "repair_run_id": run_id}),
                        )
                    if verification_repair is not None:
                        self.store.audit(
                            ident,
                            "system",
                            "verification_record_repaired",
                            json.dumps({"evidence_run_id": repair_run_id, "repair_run_id": run_id}),
                        )
                    execution_policy_audit = self._session_policy_audit(session_fingerprint)
                    break
                except BotError as exc:
                    if exc.code == "invalid_verification":
                        correction_detail = verification_diagnostics(result.output)
                        self.store.audit(
                            ident,
                            "system",
                            "verification_record_invalid",
                            json.dumps(correction_detail),
                        )
                    if exc.code == "required_read_not_verified":
                        correction_detail = getattr(exc, "issues", [])
                        self.store.audit(
                            ident,
                            "system",
                            "read_verification_failed",
                            json.dumps(
                                failure_summary(
                                    result.output,
                                    repair_reads
                                    if presentation_repair is not None
                                    or verification_repair is not None
                                    else result.reads,
                                    issues=correction_detail,
                                    attempt=attempt,
                                    run_id=run_id,
                                )
                            ),
                        )
                    if schema_retried:
                        raise
                    schema_retried = True
                    correction = exc.code
                    reason = (
                        "research"
                        if exc.code
                        in {
                            "invalid_verification",
                            "verification_requirements_missing",
                            "required_read_not_verified",
                            "invalid_read_trace",
                        }
                        else "activation"
                        if exc.code == "required_skill_not_activated"
                        else "schema"
                    )
                    if exc.code == "invalid_verification" and not restored:
                        # A malformed record is not proof. Reuse only the immediately preceding
                        # successful non-live reads; the repaired record must pass every gate.
                        try:
                            candidate = validate_output(
                                result.output,
                                lookup_basis=reported_evidence(lookup),
                                lookup_time=lookup["observed_at"]
                                if reported_evidence(lookup)
                                else None,
                            )
                            record = candidate["verification"]
                            available = set().union(
                                *(set(kinds) for kinds in result.reads.values())
                            )
                            if (
                                candidate["status"] == "answered"
                                and isinstance(record, dict)
                                and isinstance(record.get("question_kind"), str)
                                and record["question_kind"] in QUESTION_KINDS
                                and record["question_kind"]
                                not in {"current_state", "clarification"}
                                and isinstance(record.get("required"), list)
                                and 0 < len(record["required"]) <= 4
                                and all(
                                    isinstance(kind, str) and kind in KINDS
                                    for kind in record["required"]
                                )
                                and isinstance(record.get("checks"), list)
                                and 0 < len(record["checks"]) <= 30
                                and all(
                                    isinstance(check, dict)
                                    and isinstance(check.get("kind"), str)
                                    and check["kind"] in KINDS
                                    for check in record["checks"]
                                )
                                and record.get("missing") == []
                                and required_evidence
                                and "live" not in required_evidence
                                and required_evidence <= available
                                and 0 < len(result.reads) <= 100
                                and self._execution_setup_confirmed(result)
                            ):
                                verification_repair = copy.deepcopy(candidate)
                                repair_reads = copy.deepcopy(result.reads)
                                repair_run_id = run_id
                                repair_receipts = [
                                    {
                                        "receipt": receipt,
                                        "kinds": sorted(set(kinds) & (KINDS - {"live"})),
                                    }
                                    for receipt, kinds in repair_reads.items()
                                    if isinstance(receipt, str)
                                    and 1 <= len(receipt) <= 200
                                    and set(kinds) & (KINDS - {"live"})
                                ]
                                reason = "schema"
                        except (BotError, TypeError, KeyError):
                            pass
                    if exc.code in {"polite_language_required", "sources_belong_in_review_field"}:
                        # Reuse proof only after independent validation; never reuse live reads,
                        # partial investigation, an earlier inquiry or a lost engine session.
                        try:
                            candidate = validate_output(
                                result.output,
                                lookup_basis=reported_evidence(lookup),
                                lookup_time=lookup["observed_at"]
                                if reported_evidence(lookup)
                                else None,
                                presentation=False,
                            )
                            validate_verification(
                                candidate,
                                result.reads,
                                image_ids={img.file_id for img in images},
                                report=lookup or False,
                                required_floor=required_evidence,
                            )
                            if (
                                "live" not in candidate["verification"]["required"]
                                and not candidate["verification"]["missing"]
                                and candidate["status"] == "answered"
                                and self._execution_setup_confirmed(result)
                            ):
                                presentation_repair = copy.deepcopy(candidate)
                                repair_reads = copy.deepcopy(result.reads)
                                repair_run_id = run_id
                                reason = "presentation"
                        except (BotError, TypeError, KeyError):
                            pass
                    progress.retry(reason)
                    self.store.audit(
                        ident,
                        "system",
                        "answer_retry",
                        json.dumps({"attempt": attempt, "reason": reason, "code": exc.code}),
                    )
            output = route_unresolved(output)
            if self._session_fingerprint(inquiry) != session_fingerprint:
                raise BotError("source_scope_changed")
            last_seen = max((m["ts"] for m in messages), key=Decimal, default=inquiry["trigger_ts"])
            progress.phase("persist")
            verified_batches = {
                memory_batches[run]
                for run in (run_id, repair_run_id)
                if run in memory_batches
                and output["status"] == "answered"
                and "live" not in output["verification"]["required"]
            }
            self.store.save_draft(
                ident,
                output,
                job["kind"],
                history_hash(messages),
                last_seen,
                session,
                payload.get("instruction"),
                job_id=job["id"],
                auto_reply=not self.config.slack.require_approval,
                memory_batches=sorted(verified_batches),
                execution_policy_audit=execution_policy_audit,
            )
            accepted_batches = verified_batches
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
        finally:
            # Only enqueue metadata here. Filesystem validation/commits never hold up delivery.
            for batch in set(memory_batches.values()) - accepted_batches:
                try:
                    self.store.audit(ident, "system", "memory_batch_discard", batch)
                except Exception:
                    pass  # An unaccepted batch stays held; it cannot be committed implicitly.
        progress.phase("delivery")
        self.publish_ready()
        try:
            self.show_card(ident)
        except BotError:
            pass
        return True
