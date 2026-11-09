"""Acceptance gate for complete paired trials, including every failed attempt.

Review facts against originals independently before supplying reviewed_facts.
This gate validates measurements and review records; it cannot review semantics.
"""
import argparse
import json
from collections import defaultdict
from pathlib import Path


def tokens(attempt):
    usage = attempt.get('usage')
    if not isinstance(usage, dict):
        return None
    keys = ('input_tokens', 'output_tokens')
    if attempt.get('provider') == 'claude':
        keys += ('cache_read_input_tokens', 'cache_creation_input_tokens')
    elif attempt.get('provider') != 'codex':
        return None
    if not all(type(usage.get(k)) is int and usage[k] >= 0 for k in keys):
        return None
    return sum(usage[k] for k in keys)


def evaluate(plan, trials):
    errors, groups = [], defaultdict(dict)
    baseline_findings = []
    expected = {(row['case'], repeat) for row in plan['cases'] for repeat in range(plan['repeats'])}
    gold = {row['case']: row['facts'] for row in plan['cases']}
    totals = {'baseline': 0, 'candidate': 0}
    failures = {'baseline': 0, 'candidate': 0}
    repeated = defaultdict(list)
    contexts = defaultdict(set)
    for row in trials:
        key, mode = (row['case'], row['repeat']), row['mode']
        if key not in expected or mode not in totals or mode in groups[key]:
            errors.append('unexpected_or_duplicate_trial')
            continue
        groups[key][mode] = row
        attempts = row.get('attempts', [])
        if not attempts or row.get('attempt_count') != len(attempts):
            errors.append('missing_attempts')
        seen = set()
        for attempt in attempts:
            if not attempt.get('id') or attempt['id'] in seen:
                errors.append('duplicate_or_missing_attempt_id')
            seen.add(attempt.get('id'))
            count = tokens(attempt)
            if count is None:
                errors.append('unknown_usage')
            else:
                totals[mode] += count
            if attempt.get('outcome') not in ('success', 'failed'):
                errors.append('unknown_outcome')
            if attempt.get('outcome') == 'failed':
                failures[mode] += 1
        if not attempts or attempts[-1].get('outcome') != 'success' or not row.get('answer'):
            errors.append('no_final_answer')
        # Full source-based review, including unsupported additions, is external.
        if (row.get('review_status') != 'source_checked' or not row.get('review_reference')
            or not isinstance(row.get('reviewed_facts'), list)
            or not isinstance(row.get('unsupported_claims'), list)
            or row.get('evidence_verified') is not True):
            errors.append('accuracy_not_verified')
        elif row['reviewed_facts'] != gold[row['case']] or row['unsupported_claims']:
            if mode == 'candidate':
                errors.append('accuracy_not_verified')
            else:
                baseline_findings.append('incomplete_or_incorrect_baseline_facts')
        repeated[(mode, row['case'])].append(row.get('reviewed_facts'))
        contexts[row['case']].add(tuple(row.get(k) for k in ('question_sha256', 'source_sha256', 'initial_memory_sha256', 'model', 'effort', 'tools_sha256')))
    for key in expected:
        pair = groups[key]
        if set(pair) != {'baseline', 'candidate'}:
            errors.append('missing_pair')
            continue
        for field in ('question_sha256', 'source_sha256', 'initial_memory_sha256',
                      'model', 'effort', 'tools_sha256'):
            if not pair['baseline'].get(field) or pair['baseline'][field] != pair['candidate'].get(field):
                errors.append('incomparable_' + field)
    if any(len(values) != 1 for values in contexts.values()):
        errors.append('incomparable_repetitions')
    for (mode, _), facts in repeated.items():
        if any(value != facts[0] for value in facts):
            if mode == 'candidate':
                errors.append('inconsistent_facts')
            else:
                baseline_findings.append('inconsistent_baseline_facts')
    if failures['candidate'] > failures['baseline']:
        errors.append('more_failed_attempts')
    reduction = 1 - totals['candidate'] / totals['baseline'] if totals['baseline'] else None
    if reduction is None or reduction < 0.5:
        errors.append('token_reduction_below_50_percent')
    return {'accepted': not errors, 'errors': sorted(set(errors)), 'tokens': totals,
            'failed_attempts': failures, 'token_reduction': reduction,
            'baseline_findings': sorted(set(baseline_findings)),
            'scope': 'provided trials and external source review only; not production certification'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('plan', type=Path)
    parser.add_argument('trials', type=Path)
    args = parser.parse_args()
    result = evaluate(json.loads(args.plan.read_text()), json.loads(args.trials.read_text()))
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result['accepted'] else 1)
