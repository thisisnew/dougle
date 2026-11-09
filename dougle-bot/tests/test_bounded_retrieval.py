import json

import pytest
from test_github_reader import COMMIT, REF, REPO, fixture_reader

from dougle_bot.claim_index import build_index, candidates
from dougle_bot.evidence import github_read_metadata
from dougle_bot.github_reader import ReaderError, dispatch


def test_remote_range_keeps_verified_blob_and_actual_coverage():
    reader, runner, _ = fixture_reader(b'first\nsecond\nthird\n')
    args = dict(repository=REPO, ref=REF, path='src/code.py', expected_commit=COMMIT,
                start_line=2, end_line=3)
    data = reader.read_file(**args)
    assert data['content'] == 'second\nthird\n'
    assert github_read_metadata(args, data)['line_range'] == [2, 3]
    assert data['total_lines'] == 3
    wrong = {**data, 'start_line': 1}
    assert github_read_metadata(args, wrong) is None


def test_remote_range_mcp_rejects_boolean_and_outside_file():
    reader, _, _ = fixture_reader(b'first\nsecond\n')
    result = dispatch(reader, 'tools/call', {'name': 'read_file', 'arguments': {
        'repository': REPO, 'ref': REF, 'path': 'src/code.py', 'start_line': True, 'end_line': 2}})
    assert result['isError']
    with pytest.raises(ReaderError, match='invalid_line_range'):
        reader.read_file(REPO, REF, 'src/code.py', start_line=2, end_line=99)


def test_claim_pointer_includes_nested_exceptions_and_rejects_changed_note(tmp_path):
    root = tmp_path / 'memory'
    root.mkdir()
    note = root / 'orders.md'
    note.write_text('# Orders\n## 입고\n확정 뒤 저장합니다.\n### 예외\n보류는 제외합니다.\n## 기타\n다른 내용\n')
    index = tmp_path / 'cache/index.json'
    build_index(root, index)
    result = json.loads(candidates(root, index, '입고'))
    selected = next(r for r in result['candidates'] if r['title'] == '입고')
    assert selected['end_line'] == 5
    assert result['complete'] is False
    note.write_text(note.read_text() + '\n추가 예외')
    assert candidates(root, index, '입고') == ''


@pytest.mark.parametrize('malformed', [[], {}, {'records':[None]}, {'records':[{'terms':[{}]}]}])
def test_corrupt_claim_index_is_optional_and_never_breaks_answers(tmp_path, malformed):
    root = tmp_path / 'memory'
    root.mkdir()
    index = tmp_path / 'index.json'
    value = {'version':1, 'root':str(root), **malformed} if isinstance(malformed, dict) else malformed
    index.write_text(json.dumps(value))
    assert candidates(root, index, '입고') == ''
