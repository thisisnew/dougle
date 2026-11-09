"""Deterministic current-call receipt aliases and unique exact-locator repair.

Never infer semantic relevance, widen coverage or substitute a different revision.
"""

import copy
import hashlib

from .evidence import _locator, _source_mismatch


def handle(ident):
    return 'r_' + hashlib.sha256(ident.encode()).hexdigest()[:20]


def bind_receipts(output, reads):
    value = copy.deepcopy(output)
    changes = []
    sources = value.get('sources', [])
    checks = value.get('verification', {}).get('checks', [])
    if not isinstance(sources, list) or not isinstance(checks, list):
        return output, changes
    aliases = {}
    for ident in reads:
        aliases.setdefault(handle(ident), []).append(ident)
    for index, check in enumerate(checks):
        if not isinstance(check, dict):
            continue
        kind, ident, source_index = check.get('kind'), check.get('receipt'), check.get('source_index')
        if not isinstance(ident, str) or kind not in {'knowledge', 'code'}:
            continue
        if type(source_index) is not int or not 0 <= source_index < len(sources):
            continue
        source = sources[source_index]
        if not isinstance(source, dict):
            continue
        if ident in reads and kind in reads[ident] and _source_mismatch(reads[ident], source, kind) is None:
            continue
        alias = aliases.get(ident, [])
        if len(alias) == 1:
            candidate = alias[0]
            if kind in reads[candidate] and _source_mismatch(reads[candidate], source, kind) is None:
                check['receipt'] = candidate
                changes.append(index)
            continue
        cited = _locator(source.get('location'))
        if not cited:
            continue
        candidates = []
        for key, receipt in reads.items():
            metadata = getattr(receipt, 'metadata', {})
            actual = metadata.get('locator')
            if (kind in receipt and actual and actual['type'] == cited['type']
                and actual['target'] == cited['target']
                and metadata.get('content_type') == 'content'
                and _source_mismatch(receipt, source, kind) is None):
                # A citation with lines needs observed coverage, never just requested lines.
                if cited.get('lines') and not metadata.get('line_range'):
                    continue
                candidates.append(key)
        if len(candidates) == 1:
            check['receipt'] = candidates[0]
            changes.append(index)
    return (value if changes else output), changes
