import copy
import importlib.util
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('evaluate_pairs', Path(__file__).resolve().parents[2] / 'scripts/scenarios/evaluate_pairs.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def fixture():
    plan = {'repeats': 3, 'cases': [{'case': 'email', 'facts': ['approved -> next day 09:00', 'no address -> no mail']}]}
    rows = []
    for repeat in range(3):
        for mode, count in [('baseline', 100), ('candidate', 40)]:
            row = dict(case='email', repeat=repeat, mode=mode, answer='checked', attempt_count=1,
                review_status='source_checked', review_reference='fixture.md#mail',
                reviewed_facts=plan['cases'][0]['facts'][:], unsupported_claims=[], evidence_verified=True,
                attempts=[dict(id='run', provider='codex', outcome='success', usage=dict(input_tokens=count, output_tokens=1))])
            row.update({k: 'fixed' for k in ('question_sha256', 'source_sha256', 'initial_memory_sha256', 'model', 'effort', 'tools_sha256')})
            rows.append(row)
    return plan, rows


def test_complete_repeated_pairs_pass_and_failed_retry_cost_counts():
    plan, rows = fixture()
    assert MODULE.evaluate(plan, rows)['accepted']
    retry = copy.deepcopy(rows[1]['attempts'][0])
    retry.update(id='failed', outcome='failed')
    retry['usage']['input_tokens'] = 300
    rows[1]['attempts'].insert(0, retry)
    rows[1]['attempt_count'] = 2
    result = MODULE.evaluate(plan, rows)
    assert not result['accepted'] and result['tokens']['candidate'] == 424
    assert 'more_failed_attempts' in result['errors']


def test_missing_cost_accuracy_repetition_and_changed_snapshot_fail():
    for mutation, expected in [
        (lambda rows: rows.pop(), 'missing_pair'),
        (lambda rows: rows[1]['attempts'][0].pop('usage'), 'unknown_usage'),
        (lambda rows: rows[1].update(reviewed_facts=['wrong']), 'accuracy_not_verified'),
        (lambda rows: rows[1].update(reviewed_facts=['wrong']), 'inconsistent_facts'),
        (lambda rows: rows[1].update(source_sha256='changed'), 'incomparable_source_sha256'),
        (lambda rows: rows[1].update(unsupported_claims=['made up']), 'accuracy_not_verified'),
        (lambda rows: rows[1].update(attempt_count=2), 'missing_attempts'),
    ]:
        plan, rows = fixture()
        mutation(rows)
        assert expected in MODULE.evaluate(plan, rows)['errors']


def test_provider_usage_does_not_double_count_codex_cache():
    assert MODULE.tokens({'provider':'codex','usage':{'input_tokens':100,'output_tokens':3,'cached_input_tokens':90}}) == 103
    assert MODULE.tokens({'provider':'claude','usage':{'input_tokens':10,'output_tokens':3,'cache_read_input_tokens':90,'cache_creation_input_tokens':20}}) == 123
    assert MODULE.tokens({'provider':'claude','usage':{'input_tokens':10,'output_tokens':3}}) is None


def test_preexisting_baseline_omission_is_reported_without_excusing_candidate():
    plan, rows = fixture()
    rows[0]['reviewed_facts'] = ['incomplete']
    result = MODULE.evaluate(plan, rows)
    assert result['accepted']
    assert 'incomplete_or_incorrect_baseline_facts' in result['baseline_findings']
    rows[1]['reviewed_facts'] = ['incomplete']
    assert not MODULE.evaluate(plan, rows)['accepted']
