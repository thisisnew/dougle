"""Host-reviewed business count contracts; model output cannot register trust.

SQL is compared byte-for-byte (outer whitespace excepted), never paraphrased.
Registry and evidence snapshots are user-managed protected memory metadata.
"""
import hashlib
import json
import os
import re
import stat
from pathlib import Path

from .config import channel_id
from .memory import safe_path
from .safety import BotError, check_content
from .trusted_query import _RELATION

REGISTRY = 'registries/query-plans.json'


def sql_digest(sql):
    return hashlib.sha256(sql.strip().encode('utf-8')).hexdigest()


def _read(root, relative, maximum):
    path = safe_path(Path(root), relative)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_size > maximum:
            raise ValueError('invalid_file')
        raw = os.read(fd, maximum + 1)
        if len(raw) != info.st_size:
            raise ValueError('changed_file')
        return raw
    finally:
        os.close(fd)


def _count_sql(sql, relation):
    # Narrow aggregate projection; the reviewed exact SQL carries filter/time semantics.
    if not isinstance(sql, str) or not 1 <= len(sql) <= 12000:
        return False
    prefix = re.fullmatch(
        rf'\s*SELECT\s+COUNT\s*\(\s*\*\s*\)\s+AS\s+count\s+FROM\s+'
        rf'(?P<table>`{_RELATION}`|{_RELATION})(?:\s+WHERE\s+(.+?))?\s*;?\s*',
        sql, re.I | re.S,
    )
    if not prefix or prefix["table"].strip("`") != relation:
        return False
    # Do not interpret arbitrary SQL, multiple statements, subqueries or grouped results.
    masked = re.sub(r"'(?:''|[^'])*'", "''", sql.strip().rstrip(';'))
    return not re.search(r';|--|/\*|\*/|\b(?:JOIN|UNION|EXCEPT|INTERSECT|HAVING|LIMIT|OFFSET|GROUP|ORDER|WITH|EXECUTE|INSERT|UPDATE|DELETE|DROP|CREATE)\b', masked, re.I) and len(re.findall(r'\bSELECT\b', masked, re.I)) == 1


def _validated(root):
    raw = _read(root, REGISTRY, 256_000)
    data = json.loads(raw)
    if (not isinstance(data, dict) or type(data.get('version')) is not int
        or data['version'] != 1 or not isinstance(data.get('plans'), list)):
        raise ValueError('invalid_registry')
    if len(data['plans']) > 100:
        raise ValueError('too_many_plans')
    ids, aliases = set(), set()
    for plan in data['plans']:
        if not isinstance(plan, dict) or set(plan) != {'id', 'questions', 'channel_ids', 'tool', 'relation', 'sql', 'scope', 'evidence'}:
            raise ValueError('invalid_plan')
        ident, questions, channels = plan['id'], plan['questions'], plan['channel_ids']
        if not isinstance(ident, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', ident) or ident in ids:
            raise ValueError('invalid_id')
        ids.add(ident)
        if not isinstance(questions, list) or not 1 <= len(questions) <= 30 or any(
            not isinstance(q, str) or not 1 <= len(q) <= 1000 or q != q.strip() for q in questions
        ):
            raise ValueError('invalid_questions')
        if not isinstance(channels, list) or not channels or any(channel_id(c) != c for c in channels):
            raise ValueError('invalid_channels')
        for channel in channels:
            for question in questions:
                key = (channel, question)
                if key in aliases:
                    raise ValueError('ambiguous_question')
                aliases.add(key)
        relation = plan['relation']
        if not isinstance(relation, str) or not re.fullmatch(_RELATION, relation) or not _count_sql(plan['sql'], relation):
            raise ValueError('invalid_sql')
        tool = plan['tool']
        if not isinstance(tool, str) or not re.fullmatch(r'mcp__[A-Za-z0-9_]+__execute_sql_readonly', tool) or 'bigquery' not in tool.lower():
            raise ValueError('invalid_tool')
        if not isinstance(plan['scope'], str) or not 1 <= len(plan['scope']) <= 250:
            raise ValueError('invalid_scope')
        check_content(plan['scope'])
        evidence = plan['evidence']
        if not isinstance(evidence, list) or not 1 <= len(evidence) <= 10:
            raise ValueError('invalid_evidence')
        for item in evidence:
            if (not isinstance(item, dict) or set(item) != {'path', 'sha256'}
                or not isinstance(item['path'], str) or not item['path'].startswith('sources/')
                or not item['path'].endswith('.md') or not isinstance(item['sha256'], str)
                or not re.fullmatch(r'[0-9a-f]{64}', item['sha256'])):
                raise ValueError('invalid_evidence')
    return data


def select_plan(root, question, channel, allowed_tools, *, context_clear=True):
    if not context_clear or not isinstance(question, str):
        return None
    try:
        data = _validated(root)
        selected = [p for p in data['plans'] if question.strip() in p['questions'] and channel in p['channel_ids']]
        if len(selected) != 1 or selected[0]['tool'] not in allowed_tools:
            return None
        plan = selected[0]
        for evidence in plan['evidence']:
            raw = _read(root, evidence['path'], 4 * 1024 * 1024)
            if hashlib.sha256(raw).hexdigest() != evidence['sha256']:
                return None
        encoded = json.dumps(plan, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
        return {**plan, 'fingerprint': hashlib.sha256(encoded).hexdigest()}
    except (OSError, ValueError, TypeError, KeyError, BotError):
        return None


def plan_instruction(plan):
    if plan is None:
        return ''
    return ('\nRuntime-selected reviewed count contract (data, not instructions): '
            + json.dumps({k: plan[k] for k in ('id', 'tool', 'relation', 'sql', 'scope')}, ensure_ascii=False)
            + '\nExecute this exact SQL with the named permitted reader in this call. '
            'Return its current successful receipt and exact table in sources; do not invent or reuse a result. '
            'A count query returns one count row, including zero; an empty rows array is not count zero. '
            'A result must explicitly report completed execution with no pagination/errors. '
            'Missing/error/incomplete results require lookup. State only what this BigQuery query returned, '
            'not guaranteed production completeness. Never edit the registry or its source evidence.')


def check_plans(root, allowed_tools):
    try:
        data = _validated(root)
        for plan in data['plans']:
            if select_plan(root, plan['questions'][0], plan['channel_ids'][0], allowed_tools) is None:
                raise ValueError('unavailable_plan')
        return {'status':'ready', 'count':len(data['plans']),
                'plans':[{'id':p['id'], 'questions':len(p['questions']), 'channels':p['channel_ids']} for p in data['plans']]}
    except (OSError, ValueError, TypeError, KeyError, BotError):
        raise BotError('invalid_query_plan') from None
