#!/usr/bin/env python3
"""Install self-contained Dougle skills without changing host AI permissions."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tempfile

NAMES = ('dougle-connect', 'dougle-ask')
MARKER = '.dougle-install.json'

def hashes(path):
    result = {}
    for p in sorted(path.rglob('*')):
        if p.is_symlink():
            raise ValueError('Refusing symlink in skill: ' + str(p))
        if p.is_file() and p.name != MARKER and '__pycache__' not in p.parts:
            result[str(p.relative_to(path))] = hashlib.sha256(p.read_bytes()).hexdigest()
    return result

def install(home, agent, uninstall=False):
    root = Path(__file__).resolve().parents[1]
    bases = []
    if agent in ('both', 'codex'):
        bases.append(home / '.agents/skills')
    if agent in ('both', 'claude'):
        bases.append(home / '.claude/skills')
    targets = [base / name for base in bases for name in NAMES]
    if not uninstall:
        for name in NAMES:
            source = root / 'skills' / name
            hashes(source)
            for relative in ('SKILL.md', 'scripts/dougle.py', 'references/tools.md'):
                if not (source / relative).is_file():
                    raise ValueError('Incomplete skill package: ' + name)
    # Complete preflight before touching any installed skill.
    for target in targets:
        if target.is_symlink():
            raise ValueError('Refusing to replace a symlink: ' + str(target))
        if target.exists():
            marker = target / MARKER
            if not marker.is_file() or marker.is_symlink():
                raise ValueError('Existing skill is not owned by this installer: ' + str(target))
            recorded = json.loads(marker.read_text())
            if recorded.get('owner') != 'dougle-skills' or recorded.get('files') != hashes(target):
                raise ValueError('Installed skill was modified; back it up or move it before updating: ' + str(target))
    for target in targets:
        if uninstall:
            if target.exists():
                shutil.rmtree(target)
            continue
        source = root / 'skills' / target.name
        source_hashes = hashes(source)
        if not source_hashes or not (source / 'SKILL.md').is_file():
            raise ValueError('Incomplete skill package')
        target.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.dougle-install-', dir=target.parent) as temp:
            stage, backup = Path(temp) / 'new', Path(temp) / 'old'
            shutil.copytree(source, stage, ignore=shutil.ignore_patterns('__pycache__'))
            (stage / MARKER).write_text(json.dumps({'owner': 'dougle-skills', 'version': '0.2.0', 'files': source_hashes}, indent=2))
            if target.exists():
                target.rename(backup)
            try:
                stage.rename(target)
            except Exception:
                if backup.exists():
                    backup.rename(target)
                raise
    return [str(t) for t in targets]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--agent', choices=('both', 'codex', 'claude'), default='both')
    parser.add_argument('--home', type=Path, default=Path.home(), help='Home directory (isolated testing or custom host profiles)')
    parser.add_argument('--uninstall', action='store_true')
    args = parser.parse_args()
    try:
        import sqlite3
        check = sqlite3.connect(':memory:')
        check.execute('CREATE VIRTUAL TABLE check_fts USING fts5(text)')
        check.close()
        targets = install(args.home.expanduser().resolve(), args.agent, args.uninstall)
        print(json.dumps({'ok': True, 'action': 'uninstalled' if args.uninstall else 'installed', 'skills': targets, 'data_preserved': True}))
        return 0
    except (ValueError, OSError, sqlite3.Error) as e:
        print(json.dumps({'ok': False, 'error': str(e)}))
        return 2

if __name__ == '__main__':
    raise SystemExit(main())
