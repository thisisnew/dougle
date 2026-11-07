"""Durable transitions. All mutations use BEGIN IMMEDIATE, never read-check-write alone."""

import json
import os
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path

from .config import private_dir
from .images import image_files
from .safety import BotError, redact

SCHEMA = """
CREATE TABLE control(id INTEGER PRIMARY KEY CHECK(id=1), desired TEXT NOT NULL, started REAL NOT NULL, heartbeat REAL NOT NULL, pid INTEGER, workspace TEXT, error TEXT, bot_user TEXT);
INSERT INTO control VALUES(1,'stopped',0,0,NULL,NULL,NULL,NULL);
CREATE TABLE processed_events(event_key TEXT PRIMARY KEY, received_at REAL NOT NULL);
CREATE TABLE threads(thread_key TEXT PRIMARY KEY,channel_id TEXT NOT NULL,thread_ts TEXT NOT NULL,engine_session_id TEXT,last_seen_ts TEXT,created_at REAL NOT NULL,updated_at REAL NOT NULL);
CREATE TABLE inquiries(id INTEGER PRIMARY KEY,thread_key TEXT NOT NULL REFERENCES threads(thread_key),trigger_ts TEXT NOT NULL,asker_user_id TEXT NOT NULL,question_text TEXT NOT NULL,status TEXT NOT NULL,current_draft_id INTEGER,card_channel_id TEXT,card_ts TEXT,card_state TEXT NOT NULL DEFAULT 'none',posted_ts TEXT,error TEXT,history_hash TEXT,lookup_basis TEXT,created_at REAL NOT NULL,updated_at REAL NOT NULL);
CREATE TABLE drafts(id INTEGER PRIMARY KEY,inquiry_id INTEGER NOT NULL REFERENCES inquiries(id),revision INTEGER NOT NULL,kind TEXT NOT NULL,engine_status TEXT NOT NULL,body TEXT NOT NULL,sources_json TEXT NOT NULL,lookup_json TEXT,approver_notes TEXT,created_at REAL NOT NULL,UNIQUE(inquiry_id,revision));
CREATE TABLE jobs(id INTEGER PRIMARY KEY,inquiry_id INTEGER NOT NULL REFERENCES inquiries(id),kind TEXT NOT NULL,payload TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'queued',created_at REAL NOT NULL);
CREATE UNIQUE INDEX one_live_job ON jobs(inquiry_id) WHERE status IN ('queued','running');
CREATE TABLE engine_runs(id INTEGER PRIMARY KEY,inquiry_id INTEGER REFERENCES inquiries(id),session_id TEXT,resumed INTEGER NOT NULL,ok INTEGER NOT NULL DEFAULT 0,cost_usd REAL,reserved REAL NOT NULL,duration_ms INTEGER,error TEXT,started_at REAL NOT NULL,day TEXT NOT NULL,finished INTEGER NOT NULL DEFAULT 0);
CREATE TABLE deliveries(id TEXT PRIMARY KEY,inquiry_id INTEGER NOT NULL REFERENCES inquiries(id),draft_id INTEGER NOT NULL REFERENCES drafts(id),part INTEGER NOT NULL,text TEXT NOT NULL,state TEXT NOT NULL,ts TEXT,UNIQUE(inquiry_id,draft_id,part));
CREATE TABLE audit(id INTEGER PRIMARY KEY,inquiry_id INTEGER,actor TEXT NOT NULL,action TEXT NOT NULL,detail TEXT,at REAL NOT NULL);
CREATE UNIQUE INDEX one_thread_session ON threads(engine_session_id) WHERE engine_session_id IS NOT NULL;
CREATE TRIGGER draft_belongs_to_inquiry BEFORE UPDATE OF current_draft_id ON inquiries
WHEN NEW.current_draft_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM drafts WHERE id=NEW.current_draft_id AND inquiry_id=NEW.id)
BEGIN SELECT RAISE(ABORT,'draft_inquiry_mismatch'); END;
"""


class Store:
    def __init__(self, path: Path, clock=time.time, *, migrate=True):
        self.path, self.clock = Path(path), clock
        private_dir(self.path.parent)
        if self.path.is_symlink():
            raise BotError("unsafe_database_path")
        self.lock = threading.RLock()
        self.db = sqlite3.connect(path, timeout=2, isolation_level=None, check_same_thread=False)
        self.path.chmod(0o600)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.execute("PRAGMA journal_mode=WAL")
        version = self.db.execute("PRAGMA user_version").fetchone()[0]
        if version > 2:
            self.db.close()
            raise BotError("database_newer_than_runtime")
        if version == 0:
            if not migrate:
                self.db.close()
                raise BotError("database_not_initialized")
            self.db.executescript("BEGIN IMMEDIATE;" + SCHEMA + "PRAGMA user_version=1;COMMIT;")
        if version < 2 and migrate:
            self.db.executescript(
                "BEGIN IMMEDIATE;ALTER TABLE drafts ADD COLUMN verification_json TEXT;"
                "PRAGMA user_version=2;COMMIT;"
            )
        try:
            self.check_post_intents()
        except Exception:
            self.db.close()
            raise

    def check_post_intents(self):
        ledger = self.path.parent / "post-intents.log"
        if ledger.is_symlink():
            raise BotError("unsafe_delivery_ledger")
        if not ledger.exists():
            return
        if ledger.stat().st_size > 10_000_000:
            raise BotError("delivery_ledger_review_required")
        try:
            rows = ledger.read_text().splitlines()
            for ident in rows:
                uuid.UUID(ident)
                if not self.one("SELECT id FROM deliveries WHERE id=?", (ident,)):
                    raise BotError("restored_database_requires_review")
        except (ValueError, UnicodeError):
            raise BotError("invalid_delivery_ledger") from None

    def record_post_intent(self, delivery_id):
        # Independent of SQLite: rolling back only bot.db must not make a prior send invisible.
        uuid.UUID(delivery_id)
        ledger = self.path.parent / "post-intents.log"
        fd = os.open(ledger, os.O_WRONLY | os.O_APPEND | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        try:
            pending = (delivery_id + "\n").encode()
            while pending:
                written = os.write(fd, pending)
                if not written:
                    raise OSError("delivery_ledger_write_failed")
                pending = pending[written:]
            os.fsync(fd)
        finally:
            os.close(fd)

    @contextmanager
    def tx(self):
        with self.lock:
            self.db.execute("BEGIN IMMEDIATE")
            try:
                yield self.db
                self.db.execute("COMMIT")
            except BaseException:
                self.db.execute("ROLLBACK")
                raise

    def one(self, sql, params=()):
        with self.lock:
            row = self.db.execute(sql, params).fetchone()
            return dict(row) if row else None

    def all(self, sql, params=()):
        with self.lock:
            return [dict(r) for r in self.db.execute(sql, params).fetchall()]

    def audit(self, inquiry, actor, action, detail=None):
        with self.tx() as db:
            db.execute(
                "INSERT INTO audit(inquiry_id,actor,action,detail,at) VALUES(?,?,?,?,?)",
                (inquiry, actor, action, redact(detail), self.clock()),
            )

    def control(self):
        return self.one("SELECT * FROM control WHERE id=1")

    def begin_run(self, pid, workspace, bot_user=None):
        with self.tx() as db:
            old = db.execute("SELECT workspace FROM control WHERE id=1").fetchone()[0]
            old_bot = db.execute("SELECT bot_user FROM control WHERE id=1").fetchone()[0]
            if old_bot and bot_user and old_bot != bot_user:
                raise BotError("bot_identity_changed")
            if old and old != workspace:
                raise BotError("workspace_changed")
            db.execute(
                "UPDATE control SET desired='running',started=?,heartbeat=?,pid=?,workspace=?,error='slack_connecting' WHERE id=1",
                (self.clock(), self.clock(), pid, workspace),
            )

            if bot_user:
                db.execute("UPDATE control SET bot_user=? WHERE id=1", (bot_user,))

    def heartbeat(self, connected=True):
        with self.tx() as db:
            db.execute(
                "UPDATE control SET heartbeat=?,error=? WHERE id=1",
                (self.clock(), None if connected else "slack_disconnected"),
            )

    def stop(self, error=None):
        with self.tx() as db:
            db.execute(
                "UPDATE control SET desired='stopped',error=COALESCE(?,error),heartbeat=? WHERE id=1",
                (error, self.clock()),
            )

    def intake(self, event, question):
        now = self.clock()
        with self.tx() as db:
            ctl = db.execute("SELECT * FROM control WHERE id=1").fetchone()
            if ctl["desired"] != "running" or Decimal(event["ts"]) < Decimal(str(ctl["started"])):
                return None
            key = f"{event['channel']}:{event['ts']}"
            if not db.execute(
                "INSERT OR IGNORE INTO processed_events VALUES(?,?)", (key, now)
            ).rowcount:
                return None
            root = event.get("thread_ts") or event["ts"]
            thread = f"{event['channel']}:{root}"
            db.execute(
                "INSERT OR IGNORE INTO threads VALUES(?,?,?,NULL,NULL,?,?)",
                (thread, event["channel"], root, now, now),
            )
            ident = db.execute(
                "INSERT INTO inquiries(thread_key,trigger_ts,asker_user_id,question_text,status,created_at,updated_at) VALUES(?,?,?,?,'queued',?,?)",
                (thread, event["ts"], event["user"], question, now, now),
            ).lastrowid
            attachment_context = {}
            try:
                refs = image_files([event])
                if refs:
                    attachment_context["expected_image_ids"] = [row[0]["id"] for row in refs]
            except BotError as exc:
                attachment_context["attachment_error"] = exc.code
            db.execute(
                "INSERT INTO jobs(inquiry_id,kind,payload,created_at) VALUES(?,'engine',?,?)",
                (ident, json.dumps(attachment_context) if attachment_context else "", now),
            )
            return ident

    def inquiry(self, ident):
        return self.one(
            "SELECT i.*,t.channel_id,t.thread_ts,t.engine_session_id,t.last_seen_ts FROM inquiries i JOIN threads t USING(thread_key) WHERE i.id=?",
            (ident,),
        )

    def current(self, inquiry):
        return self.one(
            "SELECT * FROM drafts WHERE id=? AND inquiry_id=?",
            (inquiry["current_draft_id"], inquiry["id"]),
        )

    def claim_job(self):
        with self.tx() as db:
            if db.execute("SELECT desired FROM control WHERE id=1").fetchone()[0] != "running":
                return None
            row = db.execute(
                "SELECT * FROM jobs WHERE status='queued' ORDER BY id LIMIT 1"
            ).fetchone()
            if not row:
                return None
            state = db.execute(
                "SELECT status FROM inquiries WHERE id=?", (row["inquiry_id"],)
            ).fetchone()[0]
            if state in ("posted", "rejected", "posting", "post_unknown", "cancelled"):
                db.execute("UPDATE jobs SET status='cancelled' WHERE id=?", (row["id"],))
                return None
            db.execute("UPDATE jobs SET status='running' WHERE id=?", (row["id"],))
            db.execute(
                "UPDATE inquiries SET status='drafting',error=NULL WHERE id=?", (row["inquiry_id"],)
            )
            return dict(row)

    def enqueue(self, ident, draft, kind, payload):
        with self.tx() as db:
            row = db.execute("SELECT * FROM inquiries WHERE id=?", (ident,)).fetchone()
            if (
                not row
                or row["current_draft_id"] != draft
                or row["status"] not in ("awaiting_approval", "needs_lookup", "failed")
            ):
                raise BotError("stale_draft")
            try:
                db.execute(
                    "INSERT INTO jobs(inquiry_id,kind,payload,created_at) VALUES(?,?,?,?)",
                    (ident, kind, payload, self.clock()),
                )
            except sqlite3.IntegrityError:
                raise BotError("already_processing") from None
            db.execute("UPDATE inquiries SET status='queued' WHERE id=?", (ident,))

    def reserve(self, inquiry, maximum, daily, resumed):
        now = self.clock()
        day = datetime.fromtimestamp(now, UTC).date().isoformat()
        with self.tx() as db:
            used = db.execute(
                "SELECT COALESCE(SUM(COALESCE(cost_usd,reserved)),0) FROM engine_runs WHERE day=?",
                (day,),
            ).fetchone()[0]
            amount = min(maximum, daily - used)
            if amount < 0.01:
                raise BotError("daily_budget_exceeded")
            ident = db.execute(
                "INSERT INTO engine_runs(inquiry_id,resumed,reserved,started_at,day) VALUES(?,?,?,?,?)",
                (inquiry, int(resumed), amount, now, day),
            ).lastrowid
            return ident, amount

    def finish_run(self, ident, *, cost=None, session=None, ok=False, error=None, duration=0):
        with self.tx() as db:
            db.execute(
                "UPDATE engine_runs SET cost_usd=?,session_id=?,ok=?,error=?,duration_ms=?,finished=1 WHERE id=? AND finished=0",
                (cost, session, int(ok), error, duration, ident),
            )

    def save_draft(
        self,
        ident,
        result,
        kind,
        history_hash,
        last_seen,
        session=None,
        notes=None,
        expected_draft=None,
        job_id=None,
    ):
        with self.tx() as db:
            row = db.execute("SELECT * FROM inquiries WHERE id=?", (ident,)).fetchone()
            if not row or row["status"] in (
                "posted",
                "rejected",
                "posting",
                "post_unknown",
                "cancelled",
            ):
                raise BotError("inquiry_closed")
            if expected_draft is not None and row["current_draft_id"] != expected_draft:
                raise BotError("stale_draft")
            if (
                job_id is not None
                and not db.execute(
                    "UPDATE jobs SET status='done' WHERE id=? AND inquiry_id=? AND status='running'",
                    (job_id, ident),
                ).rowcount
            ):
                raise BotError("stale_job")
            rev = db.execute(
                "SELECT COALESCE(MAX(revision),0)+1 FROM drafts WHERE inquiry_id=?", (ident,)
            ).fetchone()[0]
            draft = db.execute(
                "INSERT INTO drafts(inquiry_id,revision,kind,engine_status,body,sources_json,lookup_json,approver_notes,created_at,verification_json) VALUES(?,?,?,?,?,?,?,?,?,?)",
                (
                    ident,
                    rev,
                    kind,
                    result["status"],
                    result["body"],
                    json.dumps(result["sources"], ensure_ascii=False),
                    json.dumps(result.get("lookup"), ensure_ascii=False),
                    notes,
                    self.clock(),
                    json.dumps(result["verification"], ensure_ascii=False),
                ),
            ).lastrowid
            status = "needs_lookup" if result["status"] == "needs_lookup" else "awaiting_approval"
            db.execute(
                "UPDATE inquiries SET current_draft_id=?,status=?,history_hash=?,error=NULL,updated_at=? WHERE id=?",
                (draft, status, history_hash, self.clock(), ident),
            )
            db.execute(
                "UPDATE threads SET engine_session_id=COALESCE(?,engine_session_id),last_seen_ts=?,updated_at=? WHERE thread_key=?",
                (session, last_seen, self.clock(), row["thread_key"]),
            )
            return draft

    def cancel(self, ident, job_id, actor, channel, ts):
        with self.tx() as db:
            row = db.execute("SELECT * FROM inquiries WHERE id=?", (ident,)).fetchone()
            job = db.execute(
                "SELECT * FROM jobs WHERE id=? AND inquiry_id=?", (job_id, ident)
            ).fetchone()
            if not row or not job:
                raise BotError("invalid_action")
            if not channel or not ts or channel != row["card_channel_id"] or ts != row["card_ts"]:
                raise BotError("wrong_card")
            if row["status"] == "cancelled" and job["status"] == "cancelled":
                return
            if row["status"] not in ("queued", "drafting") or job["status"] not in (
                "queued",
                "running",
            ):
                raise BotError("stale_job")
            db.execute(
                "UPDATE inquiries SET status='cancelled',error=NULL,updated_at=? WHERE id=?",
                (self.clock(), ident),
            )
            db.execute("UPDATE jobs SET status='cancelled' WHERE id=?", (job_id,))
            db.execute(
                "INSERT INTO audit(inquiry_id,actor,action,detail,at) VALUES(?,?,?,?,?)",
                (ident, actor, "inquiry_cancelled", str(job_id), self.clock()),
            )

    def fail_job(self, job, error):
        with self.tx() as db:
            db.execute(
                "UPDATE jobs SET status='failed' WHERE id=? AND status='running'", (job["id"],)
            )
            db.execute(
                "UPDATE inquiries SET status='failed',error=? WHERE id=? AND status='drafting'",
                (error, job["inquiry_id"]),
            )

    def finish_job(self, ident):
        with self.tx() as db:
            db.execute("UPDATE jobs SET status='done' WHERE id=? AND status='running'", (ident,))

    def recover(self):
        with self.tx() as db:
            db.execute(
                "UPDATE inquiries SET status='post_unknown',error='delivery_uncertain' WHERE status='posting'"
            )
            db.execute("UPDATE deliveries SET state='unknown' WHERE state='sending'")
            db.execute("UPDATE jobs SET status='queued' WHERE status='running'")
            db.execute("UPDATE inquiries SET status='queued' WHERE status='drafting'")
            db.execute("UPDATE inquiries SET card_state='unknown' WHERE card_state='sending'")

    def reject(self, ident, draft, actor):
        with self.tx() as db:
            result = db.execute(
                "UPDATE inquiries SET status='rejected' WHERE id=? AND current_draft_id=? AND status IN ('awaiting_approval','needs_lookup','drafting','queued','failed')",
                (ident, draft),
            )
            if not result.rowcount:
                raise BotError("stale_draft")
            db.execute(
                "UPDATE jobs SET status='cancelled' WHERE inquiry_id=? AND status='queued'",
                (ident,),
            )
            db.execute(
                "INSERT INTO audit(inquiry_id,actor,action,at) VALUES(?,?,'reject',?)",
                (ident, actor, self.clock()),
            )

    def claim_post(self, ident, draft, parts, actor):
        with self.tx() as db:
            if db.execute("SELECT desired FROM control WHERE id=1").fetchone()[0] != "running":
                raise BotError("bot_stopped")
            if not db.execute(
                "UPDATE inquiries SET status='posting' WHERE id=? AND current_draft_id=? AND status='awaiting_approval'",
                (ident, draft),
            ).rowcount:
                raise BotError("stale_draft")
            for n, text in enumerate(parts):
                db.execute(
                    "INSERT INTO deliveries(id,inquiry_id,draft_id,part,text,state) VALUES(?,?,?,?,?,'pending') ON CONFLICT(inquiry_id,draft_id,part) DO UPDATE SET state='pending' WHERE deliveries.state='not_sent'",
                    (str(uuid.uuid4()), ident, draft, n, text),
                )
            db.execute(
                "INSERT INTO audit(inquiry_id,actor,action,at) VALUES(?,?,'approve',?)",
                (ident, actor, self.clock()),
            )

    def close(self):
        self.db.close()
