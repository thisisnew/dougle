"""A separate serial knowledge writer; answering never waits for this worker."""

from .safety import BotError


def dispatch_batches(store, home, *, limit=16):
    """Retire completed/invalid entries; rotate past retryable failures in bounded sweeps.

    The single memory consumer owns this in-process cursor. Restarting from zero is
    safe: durable dispatched records remain authoritative; the cursor only provides
    fairness, not permission to commit. False means no progress, so the loop backs off.
    """
    from .memory import accept_batch, discard_batch

    after = getattr(store, "_memory_dispatch_after", 0)
    rows = store.all(
        "SELECT a.id,a.inquiry_id,a.action,a.detail FROM audit a "
        "WHERE a.action IN ('memory_batch_ready','memory_batch_discard') "
        "AND NOT EXISTS(SELECT 1 FROM audit done WHERE "
        "done.action='memory_batch_dispatched' AND done.detail=CAST(a.id AS TEXT)) "
        "ORDER BY CASE WHEN a.id>? THEN 0 ELSE 1 END,a.id LIMIT ?",
        (after, limit),
    )
    progressed = False
    for row in rows:
        store._memory_dispatch_after = row["id"]
        try:
            if row["action"] == "memory_batch_ready":
                inquiry = store.inquiry(row["inquiry_id"])
                if inquiry["status"] in {"cancelled", "rejected", "failed"} or inquiry.get(
                    "human_takeover"
                ):
                    discard_batch(home, row["detail"])
                else:
                    accept_batch(home, row["detail"])
            else:
                discard_batch(home, row["detail"])
        except BotError as exc:
            # The proposal stays held/conflicted; this never fails the customer's answer.
            store.audit(row["inquiry_id"], "system", "memory_batch_failed", exc.code)
        except (FileNotFoundError, ValueError, TypeError, KeyError):
            # Missing/malformed manifests cannot recover through repeated acceptance.
            # Logically isolate this dispatch entry, retaining any proposal bytes for
            # inspection. Never include exception text, paths or manifest contents.
            store.audit(row["inquiry_id"], "system", "memory_batch_failed", "invalid_memory_batch")
            store.audit(row["inquiry_id"], "system", "memory_batch_isolated", str(row["id"]))
        except OSError:
            # Retry later, but do not pin the first page of the queue or spin merely
            # because failed rows were returned. Other entries in this sweep can run.
            continue
        store.audit(row["inquiry_id"], "system", "memory_batch_dispatched", str(row["id"]))
        progressed = True
    return progressed


def run_memory_worker(store, home, stop):
    from .memory import drain_one

    while not stop.is_set():
        try:
            dispatched = dispatch_batches(store, home)
            if stop.is_set():
                return
            committed = drain_one(home)
            if dispatched or committed:
                continue
        except Exception:
            # Leave pending work on disk for recovery. Never stop inquiry agents for this.
            pass
        stop.wait(1)
