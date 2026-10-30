"""Transfer a verified app through a file delta; validate both complete trees."""
import hashlib, json, os, pathlib, shutil, stat, subprocess, sys, tempfile, zipfile
P = pathlib.Path

def sha(p):
    h = hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda: f.read(1024 * 1024), b''): h.update(b)
    return h.hexdigest()

def tree(root):
    result = {}
    for base, dirs, files in os.walk(root, followlinks=False):
        for name in sorted(dirs + files):
            p = P(base) / name
            mode = p.lstat().st_mode
            value = {'mode': stat.S_IMODE(mode)}
            if p.is_symlink(): value.update(kind='link', target=os.readlink(p))
            elif p.is_dir(): value.update(kind='directory')
            else:
                assert stat.S_ISREG(mode)
                value.update(kind='file', sha256=sha(p))
            result[p.relative_to(root).as_posix()] = value
    return result

def safe(root, name):
    rel = P(name)
    assert not rel.is_absolute() and '..' not in rel.parts
    p = root / rel
    assert all(not q.is_symlink() for q in p.parents if q != root and root in q.parents)
    return p

mode, old_arg, other_arg, output_arg = sys.argv[1:]
old, other, output = map(P, (old_arg, other_arg, output_arg))
assert old.name == 'Dougle.app'
if mode == 'create':
    assert other.name == 'Dougle.app' and not output.exists()
    before, after = tree(old), tree(other)
    manifest = {'before': before, 'after': after, 'patches': {}}
    with tempfile.TemporaryDirectory() as d:
        with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as z:
            for name, info in after.items():
                if info == before.get(name) or info['kind'] != 'file': continue
                patch = P(d) / 'patch.zst'
                args = ['zstd', '-q', '-f', '-3', '--long=30']
                if before.get(name, {}).get('kind') == 'file': args += ['--patch-from=' + str(safe(old, name))]
                subprocess.run(args + [str(safe(other, name)), '-o', str(patch)], check=True)
                member = 'patches/' + str(len(manifest['patches']))
                z.write(patch, member)
                manifest['patches'][name] = member
            z.writestr('manifest.json', json.dumps(manifest, sort_keys=True))
    print(json.dumps({'bytes': output.stat().st_size, 'changedFiles': len(manifest['patches']), 'treeEntries': len(after)}))
elif mode == 'apply':
    assert not output.exists()
    with zipfile.ZipFile(other) as z, tempfile.TemporaryDirectory() as d:
        m = json.loads(z.read('manifest.json'))
        assert tree(old) == m['before'], 'Base app differs from verified release'
        subprocess.run(['ditto', '--norsrc', '--noextattr', str(old), str(output)], check=True)
        for name in sorted(m['before'], key=lambda x: -len(P(x).parts)):
            if name not in m['after'] or m['before'][name]['kind'] != m['after'][name]['kind']:
                p = safe(output, name)
                if p.is_dir() and not p.is_symlink(): p.rmdir()
                else: p.unlink()
        for name, info in sorted(m['after'].items(), key=lambda x: len(P(x[0]).parts)):
            p = safe(output, name)
            if info['kind'] == 'directory': p.mkdir(exist_ok=True)
            elif info['kind'] == 'link':
                if p.is_symlink(): p.unlink()
                assert not P(info['target']).is_absolute()
                assert (p.parent / info['target']).resolve().is_relative_to(output.resolve())
                p.symlink_to(info['target'])
            elif name in m['patches']:
                patch = P(d) / 'patch.zst'; patch.write_bytes(z.read(m['patches'][name]))
                args = ['zstd', '-q', '-f', '-d', '--long=30']
                if m['before'].get(name, {}).get('kind') == 'file': args += ['--patch-from=' + str(safe(old, name))]
                subprocess.run(args + [str(patch), '-o', str(p)], check=True)
            if info['kind'] != 'link': p.chmod(info['mode'])
        assert tree(output) == m['after'], 'Reconstructed app differs from tested app'
        subprocess.run(['codesign', '--verify', '--deep', '--strict', str(output)], check=True)
        print(json.dumps({'status': 'PASS', 'entries': len(m['after']), 'changedFiles': len(m['patches'])}))
else: raise ValueError('Expected create or apply')
