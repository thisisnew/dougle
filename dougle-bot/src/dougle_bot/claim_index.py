"""Derived, source-bound heading pointers. Candidate discovery never proves coverage."""

import hashlib
import json
import os
import re
import tempfile
from pathlib import Path

from .memory import protected_metadata, safe_path
from .safety import BotError


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def terms(text):
    return set(re.findall(r'[a-z0-9_]+|[가-힣]{2,}', text.lower()))


def build_index(memory_root, destination):
    root = Path(memory_root)
    if root.is_symlink() or not root.is_dir():
        raise BotError('unsafe_memory_path')
    root = root.resolve()
    records = []
    for file in sorted(root.rglob('*.md')):
        relative = file.relative_to(root).as_posix()
        if protected_metadata(relative):
            continue
        file = safe_path(root, relative)
        raw = file.read_bytes()
        lines = raw.decode('utf-8').splitlines()
        headings = [(i, len(m[1]), m[2]) for i, line in enumerate(lines)
                    if (m := re.match(r'^(#{1,6})\s+(.+)$', line))]
        if not headings:
            headings = [(0, 1, relative)]
        for pos, (start, level, title) in enumerate(headings):
            end = next((i for i, depth, _ in headings[pos + 1:] if depth <= level), len(lines))
            if end <= start:
                continue
            records.append({'path': relative, 'sha256': digest(raw), 'start_line': start + 1,
                            'end_line': end, 'title': title,
                            'terms': sorted(terms('\n'.join(lines[start:end])))})
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, stage = tempfile.mkstemp(dir=destination.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump({'version': 1, 'root': str(root), 'records': records}, stream, ensure_ascii=False)
        os.replace(stage, destination)
    finally:
        Path(stage).unlink(missing_ok=True)
    return {'status': 'ready', 'sections': len(records), 'files': len({r['path'] for r in records})}


def candidates(memory_root, index_path, question, max_bytes=2048):
    try:
        index = Path(index_path)
        if index.is_symlink() or index.stat().st_size > 16 * 1024 * 1024:
            return ''
        data = json.loads(index.read_text())
        root = Path(memory_root).resolve()
        if not isinstance(data, dict) or data.get('version') != 1 or data.get('root') != str(root):
            return ''
        records = data.get('records')
        if not isinstance(records, list) or any(
            not isinstance(r, dict) or not isinstance(r.get('terms'), list)
            or not all(isinstance(t, str) for t in r['terms'])
            or not isinstance(r.get('path'), str) or not isinstance(r.get('title'), str)
            or type(r.get('start_line')) is not int or type(r.get('end_line')) is not int
            or not 1 <= r['start_line'] <= r['end_line']
            or not isinstance(r.get('sha256'), str) for r in records
        ):
            return ''
        query = terms(question)
        ranked = sorted(data['records'], key=lambda r: (-len(query & set(r['terms'])), r['path'], r['start_line']))
        result, hashes = [], {}
        for row in ranked:
            if not query & set(row['terms']):
                continue
            path = row['path']
            if protected_metadata(path):
                continue
            if path not in hashes:
                hashes[path] = digest(safe_path(root, path).read_bytes())
            if hashes[path] != row['sha256']:
                continue
            pointer = {k: row[k] for k in ('path', 'sha256', 'start_line', 'end_line', 'title')}
            trial = json.dumps(result + [pointer], ensure_ascii=False)
            if len(trial.encode()) > max_bytes:
                break
            result.append(pointer)
        return json.dumps({'candidates': result, 'complete': False,
                           'rule': 'Read full sections and dependencies. Widen search if coverage is uncertain. Recheck source bytes; pointers are not evidence.'}, ensure_ascii=False) if result else ''
    except (OSError, ValueError, KeyError, TypeError, BotError):
        return ''
