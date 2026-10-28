import argparse
import io
import json
import os
import zipfile
from pathlib import Path


def trim_library(content, prefix, selected):
    output = io.BytesIO()
    retained = 0
    removed = 0
    with zipfile.ZipFile(io.BytesIO(content)) as source, zipfile.ZipFile(output, 'w') as target:
        for entry in source.infolist():
            name = entry.filename
            platform_entry = name.startswith(prefix) and '/' in name[len(prefix):]
            if platform_entry and (not name.startswith(selected) or '.dSYM/' in name):
                removed += entry.file_size
                continue
            if name.startswith(selected) and not entry.is_dir():
                retained += 1
            target.writestr(entry, source.read(entry))
    if not retained:
        raise ValueError('Missing native libraries for selected platform')
    return output.getvalue(), removed


def trim(source, destination, architecture):
    if architecture not in ('arm64', 'x86_64'):
        raise ValueError('Unsupported architecture')
    if source.resolve() == destination.resolve() or destination.is_symlink():
        raise ValueError('Output must be a different regular path')
    arch = 'aarch64' if architecture == 'arm64' else 'x86_64'
    rules = {
        'onnxruntime-': ('ai/onnxruntime/native/', 'ai/onnxruntime/native/osx-' + ('aarch64' if architecture == 'arm64' else 'x64') + '/'),
        'tokenizers-': ('native/lib/', 'native/lib/osx-' + arch + '/'),
        'sqlite-jdbc-': ('org/sqlite/native/', 'org/sqlite/native/Mac/' + arch + '/'),
    }
    found = set()
    temporary = destination.with_suffix('.partial')
    if temporary.exists() or temporary.is_symlink():
        raise ValueError('Stale temporary output; inspect before retrying')
    try:
        with zipfile.ZipFile(source) as original, zipfile.ZipFile(temporary, 'w') as output:
            for entry in original.infolist():
                content = original.read(entry)
                for name, (prefix, selected) in rules.items():
                    if entry.filename.startswith('BOOT-INF/lib/' + name) and entry.filename.endswith('.jar'):
                        content, removed = trim_library(content, prefix, selected)
                        found.add(name)
                output.writestr(entry, content)
        if found != set(rules):
            raise ValueError('Expected all three native dependencies')
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)
    return {'architecture': architecture, 'before': source.stat().st_size, 'after': destination.stat().st_size}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=Path)
    parser.add_argument('destination', type=Path)
    parser.add_argument('architecture', choices=['arm64', 'x86_64'])
    arguments = parser.parse_args()
    print(json.dumps(trim(arguments.source, arguments.destination, arguments.architecture)))
