from dougle_bot.evidence import ReadReceipt
from dougle_bot.evidence_binding import bind_receipts, handle


def receipt(path='/a.py', lines=(1, 20)):
    return ReadReceipt({'code'}, metadata={'content_type': 'content',
        'locator': {'type': 'file', 'target': path, 'lines': None}, 'line_range': lines})


def output(location='/a.py#L2-L8', ident='typo'):
    return {'sources': [{'location': location}], 'verification': {'checks': [
        {'kind': 'code', 'receipt': ident, 'source_index': 0}]}}


def test_unique_exact_locator_and_alias_bind_without_changing_content():
    for ident in ('typo', handle('actual')):
        original = output(ident=ident)
        result, changed = bind_receipts(original, {'actual': receipt()})
        assert changed == [0]
        assert result['verification']['checks'][0]['receipt'] == 'actual'
        assert original['verification']['checks'][0]['receipt'] == ident


def test_wrong_mapping_can_only_bind_another_exact_read():
    original = output(ident='wrong')
    reads = {'wrong': receipt('/b.py'), 'right': receipt()}
    result, changed = bind_receipts(original, reads)
    assert changed == [0] and result['verification']['checks'][0]['receipt'] == 'right'


def test_ambiguity_unread_lines_and_different_source_do_not_bind():
    for reads in ({'a': receipt(), 'b': receipt()}, {'a': receipt(lines=(10, 20))}, {'a': receipt('/b.py')}):
        original = output()
        result, changed = bind_receipts(original, reads)
        assert result is original and not changed
