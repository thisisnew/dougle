#!/usr/bin/env python3
"""Build a small, deterministic skill archive; never include app/user state."""
import hashlib
from pathlib import Path
import re
import subprocess
import sys
import zipfile
root = Path(__file__).resolve().parents[1]
subprocess.run([sys.executable, str(root/'scripts/build-skills.py'), '--check'], check=True)
release = root/'release'
release.mkdir(exist_ok=True)
output = release/'Dougle_Skills_0.2.0.zip'
files = [p for p in (root/'skills').rglob('*') if p.is_file() and '__pycache__' not in p.parts]
files += [root/'scripts/install-skills.py', root/'README.md']
with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted(files):
        if path.is_symlink():
            raise ValueError('No symlinks in release')
        content = path.read_bytes()
        if path.name == 'README.md':
            text = content.decode()
            text = re.sub(r'\]\((docs/[^)]+)\)', r'](https://github.com/thisisnew/dougle/blob/main/\1)', text)
            content = text.encode()
        name = 'Dougle_Skills_0.2.0/' + path.relative_to(root).as_posix()
        info = zipfile.ZipInfo(name, (2026, 11, 4, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o100644 << 16
        archive.writestr(info, content)
sha = hashlib.sha256(output.read_bytes()).hexdigest()
(release/'Dougle_Skills_0.2.0_SHA256SUMS').write_text(sha+'  '+output.name+'\n')
print(output.name, output.stat().st_size, 'bytes', sha)
