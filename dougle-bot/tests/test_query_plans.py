"""Synthetic host-reviewed definitions; never read user knowledge or execute real SQL."""
import copy
import hashlib
import json

import pytest

from dougle_bot.query_plans import REGISTRY, select_plan

TOOL = 'mcp__claude_ai_Google_Cloud_BigQuery__execute_sql_readonly'
QUESTION = '현재 입하 이슈는 몇 건인가요?'
RELATION = 'synthetic.replica.receiving_issues'
SQL = "SELECT COUNT(*) AS count FROM synthetic.replica.receiving_issues WHERE status = 'OPEN'"


def install_plan(app):
    root = app.worker.memory_root
    evidence = root / 'sources/receiving-count.md'
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text('Synthetic reviewed definition: 입하 이슈 = receiving_issues의 OPEN 행. 전체 기간, CTEST 업무 범위. 원본 정의·필터·스키마를 검토한 합성 자료입니다.')
    plan = {'id':'receiving-open-count', 'questions':[QUESTION], 'channel_ids':['CTEST'],
            'tool':TOOL, 'relation':RELATION, 'sql':SQL,
            'scope':'미해결 입하 이슈(전체 기간)',
            'evidence':[{'path':'sources/receiving-count.md', 'sha256':hashlib.sha256(evidence.read_bytes()).hexdigest()}]}
    registry = root / REGISTRY
    registry.parent.mkdir(parents=True, exist_ok=True)
    registry.write_text(json.dumps({'version':1,'plans':[plan]}, ensure_ascii=False))
    return plan, registry, evidence


def selected(app, **kwargs):
    return select_plan(app.worker.memory_root, kwargs.get('question', QUESTION), kwargs.get('channel', 'CTEST'),
                       kwargs.get('tools', app.cfg.engine.allowed_tools), context_clear=kwargs.get('context_clear', True))


def test_definition_is_bound_to_exact_question_scope_tool_and_source(app):
    plan, _, evidence = install_plan(app)
    assert selected(app)['sql'] == SQL
    for kwargs in [dict(question='지난주 입하 이슈는 몇 건인가요?'), dict(channel='COTHER'),
                   dict(tools=[]), dict(context_clear=False)]:
        assert selected(app, **kwargs) is None
    evidence.write_text('changed business rule')
    assert selected(app) is None


@pytest.mark.parametrize('change', ['duplicate','bad_shape','symlink','missing_evidence','unprotected_evidence','other_projection','extra_statement'])
def test_invalid_or_ambiguous_definitions_never_authorize(app, change, tmp_path):
    plan, registry, evidence = install_plan(app)
    data = {'version':1,'plans':[plan]}
    if change == 'duplicate':
        second = copy.deepcopy(plan)
        second['id'] = 'another'
        data['plans'].append(second)
    elif change == 'bad_shape':
        data = []
    elif change == 'symlink':
        target = tmp_path / 'elsewhere'
        target.write_bytes(registry.read_bytes())
        registry.unlink()
        registry.symlink_to(target)
        assert selected(app) is None
        return
    elif change == 'missing_evidence':
        evidence.unlink()
    elif change == 'unprotected_evidence':
        plan['evidence'][0]['path'] = 'knowledge/topic.md'
    elif change == 'other_projection':
        plan['sql'] = SQL.replace('COUNT(*)', 'MAX(status)')
    elif change == 'extra_statement':
        plan['sql'] += '; SELECT 1'
    registry.write_text(json.dumps(data))
    assert selected(app) is None


@pytest.mark.parametrize('variant', ['wrong_table','wrong_filter','wrong_period','incomplete','empty_count_rows','error','wrong_question','wrong_channel','no_registry','extra_read'])
def test_worker_holds_unmatched_and_unfinished_queries(app, variant):
    from test_answer_consistency_contract import SESSION_A, _automatic, _enqueue, _live_result
    _automatic(app)
    _, registry, _ = install_plan(app)
    kwargs, question, channel = {}, QUESTION, 'CTEST'
    rows = [{'count':0}]
    if variant == 'wrong_table':
        kwargs['query'] = SQL.replace('receiving_issues','centers')
    elif variant == 'wrong_filter':
        kwargs['query'] = SQL.replace('OPEN','CLOSED')
    elif variant == 'wrong_period':
        kwargs['query'] = SQL + " AND created_at > TIMESTAMP '2026-11-01 00:00:00+00'"
    elif variant == 'incomplete':
        kwargs['complete'] = False
    elif variant == 'empty_count_rows':
        rows = []
    elif variant == 'error':
        kwargs['error'] = True
    elif variant == 'wrong_question':
        question = '지난주 입하 이슈는 몇 건인가요?'
    elif variant == 'wrong_channel':
        channel = 'COTHER'
    elif variant == 'no_registry':
        registry.unlink()
    result = _live_result('0건입니다.', rows, SESSION_A, **kwargs)
    if variant == 'extra_read':
        result.reads['another'] = copy.deepcopy(result.reads['query'])
    app.engine.results = [result, result]
    ident = _enqueue(app,'1001.000001',question=question,channel=channel)
    app.worker.run_once()
    assert app.store.inquiry(ident)['status'] != 'posted'
    assert not app.store.one("SELECT 1 FROM audit WHERE action='query_plan_verified'")


def test_definition_change_during_run_is_not_posted(app):
    from test_answer_consistency_contract import SESSION_A, _automatic, _enqueue, _live_result
    _automatic(app)
    _, _, evidence = install_plan(app)
    app.engine.results = [_live_result('1건입니다.', [{'count':1}], SESSION_A)]
    original = app.engine.run
    def changed(*args, **kwargs):
        result = original(*args, **kwargs)
        evidence.write_text('filter changed')
        return result
    app.engine.run = changed
    ident = _enqueue(app,'1001.000001',question=QUESTION)
    app.worker.run_once()
    assert app.store.inquiry(ident)['status'] == 'failed'
    assert app.store.inquiry(ident)['error'] == 'query_plan_changed'


def test_definition_change_after_persist_is_checked_before_post(app, monkeypatch):
    from test_answer_consistency_contract import SESSION_A, _automatic, _enqueue, _live_result
    _automatic(app)
    _, registry, _ = install_plan(app)
    app.engine.results = [_live_result('1건입니다.', [{'count':1}], SESSION_A)]
    monkeypatch.setattr(app.worker, 'publish_ready', lambda: False)
    ident = _enqueue(app,'1001.000001',question=QUESTION)
    app.worker.run_once()
    assert app.store.inquiry(ident)['status'] == 'awaiting_approval'
    registry.unlink()
    app.actions.post_automatic(ident)
    assert app.store.inquiry(ident)['status'] == 'failed'
    assert app.store.inquiry(ident)['error'] == 'query_plan_changed'


def test_reviewed_period_is_exact_and_recomputed_not_cached(app):
    from test_answer_consistency_contract import (
        SESSION_A,
        _automatic,
        _draft,
        _enqueue,
        _live_result,
    )
    _automatic(app)
    plan, registry, _ = install_plan(app)
    question = '최근 7일 동안 등록된 입하 이슈는 몇 건인가요?'
    sql = SQL + ' AND created_at >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 7 DAY)'
    plan.update(questions=[question], sql=sql, scope='최근 7일에 등록된 미해결 입하 이슈')
    registry.write_text(json.dumps({'version':1,'plans':[plan]}))
    app.engine.results = [_live_result('0건입니다.', [{'count':7}], SESSION_A, query=sql)]
    ident = _enqueue(app,'1001.000001',question=question)
    app.worker.run_once()
    assert app.store.inquiry(ident)['status'] == 'posted'
    assert '7건' in _draft(app,ident)['body']
    assert '최근 7일에 등록된' in _draft(app,ident)['body']
    assert sql in app.engine.calls[0][0]


def test_registry_check_command_does_not_write_or_query(app, monkeypatch):
    from pathlib import Path

    from dougle_bot.config import save
    from dougle_bot.main import execute, parser
    from dougle_bot.safety import BotError
    _, registry, evidence = install_plan(app)
    monkeypatch.setattr(Path, 'home', classmethod(lambda cls: app.worker.memory_root.parent))
    path = app.cfg.home / 'config.toml'
    save(app.cfg, path)
    before = registry.read_bytes()
    args = parser().parse_args(['--home',str(app.cfg.home),'query-plans-check'])
    assert execute(args,secrets=object())['count'] == 1
    assert registry.read_bytes() == before
    assert app.engine.calls == []
    evidence.write_text('changed')
    with pytest.raises(BotError, match='invalid_query_plan'):
        execute(args,secrets=object())


def test_table_identity_is_case_sensitive_even_when_sql_keywords_are_not(app):
    plan, registry, _ = install_plan(app)
    plan['sql'] = SQL.replace('receiving_issues','Receiving_Issues')
    registry.write_text(json.dumps({'version':1,'plans':[plan]}))
    assert selected(app) is None
