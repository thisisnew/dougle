import json

import pytest

from dougle_bot.bq_state import query_failure
from dougle_bot.evidence import read_receipts
from dougle_bot.memory import protected_metadata


@pytest.mark.parametrize('body,expected', [
    ('403 Access Denied: private', 'access_denied'),
    ('404 Not Found: private', 'not_found'),
    ({'errors': [{'reason': 'invalidQuery'}]}, 'query_error'),
    ({'jobComplete': False}, 'incomplete'),
    ({'rows': [], 'jobComplete': True}, None),
])
def test_errors_never_become_sql_receipts(body, expected):
    assert query_failure(body) == expected
    raw = '\n'.join(map(json.dumps, [
        {'type': 'assistant', 'message': {'content': [{'type': 'tool_use', 'id': 'q',
         'name': 'mcp__bigquery__execute_sql_readonly', 'input': {'query': 'SELECT * FROM p.d.t'}}]}},
        {'type': 'user', 'message': {'content': [{'type': 'tool_result', 'tool_use_id': 'q', 'content': body}]}}
    ])).encode()
    if expected:
        assert not read_receipts(raw)


@pytest.mark.parametrize('path', ['sharing/inbound-knowledge/binding.md', 'sources/S1.md', 'ledger/op.json', 'knowledge/bq-sync-registry.md'])
def test_metadata_paths_protected(path):
    assert protected_metadata(path)
    assert not protected_metadata('topics/orders.md')


def test_guard_denies_original_and_staged_metadata_but_allows_read(tmp_path):
    from dougle_bot.guard import decision
    from dougle_bot.memory import MemorySession
    memory = tmp_path / 'knowledge'
    target = memory / 'sharing/binding.md'
    target.parent.mkdir(parents=True)
    target.write_text('base commit')
    session = MemorySession(tmp_path / 'bot', memory)
    policy = {'memory': str(memory), 'runtime_home': str(tmp_path / 'bot'),
              'allowed': ['Read', 'Write'], 'skills': [], **session.policy_fields()}
    read = decision(policy, {'tool_name': 'Read', 'tool_input': {'file_path': str(target)}})['hookSpecificOutput']
    assert read['permissionDecision'] == 'allow'
    for path in (str(target), read['updatedInput']['file_path']):
        result = decision(policy, {'tool_name': 'Write', 'tool_input': {'file_path': path, 'content': 'bad'}})
        assert result['hookSpecificOutput']['permissionDecision'] == 'deny'
    assert target.read_text() == 'base commit'


def test_wrapped_connector_errors_and_case_variants_are_protected():
    assert query_failure({'content':[{'type':'text','text':'403 Access Denied'}]}) == 'access_denied'
    assert query_failure({'status':{'errorResult':{'reason':'notFound'}}}) == 'not_found'
    assert query_failure({'rows':[{'error':'business column value'}]}) is None
    assert protected_metadata('SHARING/BINDING.MD')
