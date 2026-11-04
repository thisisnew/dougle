#!/usr/bin/env python3
"""Keep each installable skill self-contained, with the same tested runtime."""
from pathlib import Path
import argparse
import shutil
root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--check', action='store_true')
a = p.parse_args()
for name in ('dougle-connect', 'dougle-ask'):
    for origin, relative in [('skill-runtime/dougle.py', 'scripts/dougle.py'), ('skill-runtime/tools.md', 'references/tools.md')]:
        source, target = root / origin, root / 'skills' / name / relative
        if a.check:
            assert target.read_bytes() == source.read_bytes(), str(target) + ' is out of date'
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
print('PASS: self-contained skills share the exact runtime and contract')
