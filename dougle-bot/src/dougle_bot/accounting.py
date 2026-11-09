"""Provider estimates, with explicit scope and unknown-baseline handling."""

import json
import math

FIELDS = ("input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")
TTL_FIELDS = ("ephemeral_5m_input_tokens", "ephemeral_1h_input_tokens")


def bounded_metrics(value, *, ttl=False):
    if not isinstance(value, dict):
        return None
    return {k: value[k] if type(value.get(k)) is int and 0 <= value[k] <= 10**12 else None
            for k in (TTL_FIELDS if ttl else FIELDS)}


def incremental_cost(reported, scope, resumed, previous):
    if type(reported) not in (int, float) or not math.isfinite(reported) or reported < 0:
        return None, "unknown_cost"
    if scope == "call":
        return reported, "call_reported"
    if scope != "session":
        return None, "unknown_scope"
    if not resumed:
        return reported, "new_session"
    if previous is None:
        return None, "unknown_baseline"
    if reported < previous:
        return None, "counter_reset"
    return round(reported - previous, 12), "session_delta"


def report_runs(store, day, *, legacy_session_totals=False):
    """Read-only correction preview. Legacy semantics must be explicitly selected.

    Raw engine_runs rows are never rewritten. Incomplete baselines remain unknown.
    """
    has_table = store.one("SELECT name FROM sqlite_master WHERE name='engine_accounting'")
    metrics = {r["run_id"]: r for r in store.all("SELECT * FROM engine_accounting")} if has_table else {}
    previous = {}
    result = []
    for row in store.all("SELECT * FROM engine_runs ORDER BY id"):
        metric = metrics.get(row["id"])
        raw = metric["reported_cost"] if metric else row["cost_usd"]
        scope = metric["scope"] if metric else "session" if legacy_session_totals else "unknown"
        session = row["session_id"]
        delta, quality = incremental_cost(raw, scope, bool(row["resumed"]), previous.get(session))
        if scope == "session" and session:
            previous[session] = raw
        if row["day"] == day:
            result.append({
                "run_id": row["id"], "inquiry_id": row["inquiry_id"], "resumed": bool(row["resumed"]),
                "reported_cost_usd": raw, "estimated_increment_usd": delta, "quality": quality,
                "usage": bounded_metrics(json.loads(metric["usage_json"])) if metric else None,
                "cache_creation": bounded_metrics(json.loads(metric["ttl_json"]), ttl=True) if metric else None,
            })
    return result
