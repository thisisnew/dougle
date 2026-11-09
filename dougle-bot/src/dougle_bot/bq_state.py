"""Classify observed SQL errors without inferring ingestion state from row times."""

import json
import re


def query_failure(body, _depth=0):
    if _depth > 32:
        return "query_error"
    if isinstance(body, list):
        return next((code for part in body if (code := query_failure(part, _depth + 1))), None)
    if isinstance(body, str):
        try:
            parsed = json.loads(body)
        except ValueError:
            parsed = None
        if isinstance(parsed, (dict, list)):
            return query_failure(parsed, _depth + 1)
        if re.match(r"\s*(?:403\b|access\s*denied\b|permission\s*denied\b)", body, re.I):
            return "access_denied"
        if re.match(r"\s*(?:404\b|not\s*found\b)", body, re.I):
            return "not_found"  # Never means 'not synchronized'.
        if re.match(r"\s*(?:400\b|error\s*:|invalid\s*query\b|syntax\s*error\b)", body, re.I):
            return "query_error"
        return None
    if not isinstance(body, dict):
        return None
    if body.get('type') == 'text':
        return query_failure(body.get('text'), _depth + 1)
    reasons = {'accessDenied': 'access_denied', 'notFound': 'not_found',
               'invalidQuery': 'query_error', 'invalid': 'query_error'}
    reason = body.get('reason')
    if isinstance(reason, str) and reason in reasons:
        return reasons[reason]
    for key in ('error', 'errors', 'errorResult'):
        if body.get(key):
            return query_failure(body[key], _depth + 1) or 'query_error'
    for key in ('content', 'structuredContent', 'status'):
        if key in body and isinstance(body[key], (dict, list)):
            reason = query_failure(body[key], _depth + 1)
            if reason:
                return reason
    if body.get('isError') or body.get('is_error'):
        return 'query_error'
    if body.get('jobComplete') is False or body.get('job_complete') is False:
        return 'incomplete'
    return None


def failed_queries(raw):
    pending, failures = {}, []
    for line in raw.splitlines():
        try:
            event = json.loads(line)
        except (ValueError, UnicodeError):
            continue
        if not isinstance(event, dict) or not isinstance(event.get('message'), dict):
            continue
        blocks = event['message'].get('content', [])
        if not isinstance(blocks, list):
            continue
        for block in blocks:
            if not isinstance(block, dict):
                continue
            ident = block.get('id')
            if block.get('type') == 'tool_use' and isinstance(ident, str):
                name = block.get('name', '')
                if isinstance(name, str) and name.endswith('__execute_sql_readonly'):
                    pending[ident] = True
            ident = block.get('tool_use_id')
            if block.get('type') == 'tool_result' and isinstance(ident, str) and ident in pending:
                pending.pop(ident)
                reason = query_failure(block.get('content'))
                if block.get('is_error') and reason is None:
                    reason = 'query_error'
                if reason and len(failures) < 30:
                    failures.append({'reason': reason})
    return failures
