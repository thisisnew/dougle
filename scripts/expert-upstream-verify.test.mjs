import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdir, mkdtemp, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { verifyUpstream } from './expert-upstream-verify.mjs'

async function fixture(context) {
  const root = await mkdtemp(join(tmpdir(), 'expert-upstream-test-'))
  context.after(() => rm(root, { recursive: true, force: true }))
  const directory = join(root, 'third-party/expert-sources')
  await mkdir(join(directory, 'example'), { recursive: true })
  const bytes = 'Synthetic license bytes\n'
  const commit = 'a'.repeat(40)
  const source = {
    repository: 'https://github.com/example/project', commit,
    commit_url: `https://github.com/example/project/commit/${commit}`, license: 'MIT',
    files: [{ upstream_path: 'LICENSE', local_path: 'third-party/expert-sources/example/LICENSE',
      sha256: createHash('sha256').update(bytes).digest('hex'),
      source_url: `https://raw.githubusercontent.com/example/project/${commit}/LICENSE` }],
  }
  const manifest = { schema_version: 1, sources: [source] }
  await writeFile(join(root, source.files[0].local_path), bytes)
  const save = () => writeFile(join(directory, 'manifest.json'), JSON.stringify(manifest))
  await save()
  return { root, directory, source, manifest, save, bytes }
}

test('verifies local bytes and consistent pins offline', async context => {
  const { root } = await fixture(context)
  const result = await verifyUpstream(root)
  assert.equal(result.filesVerified, 1)
  assert.equal(result.sourcesVerified, 1)
  assert.equal(result.offline, true)
})

test('rejects invalid hashes, pins, URL drift, duplicate paths and invalid schema', async context => {
  const mutations = [
    state => { state.source.commit = 'main' },
    state => { state.source.commit = 'a'.repeat(39) },
    state => { state.source.files[0].sha256 = 'g'.repeat(64) },
    state => { state.source.files[0].sha256 = '0'.repeat(64) },
    state => { state.source.commit_url += '?ref=main' },
    state => { state.source.files[0].source_url = 'https://raw.githubusercontent.com/example/project/main/LICENSE' },
    state => { state.source.files.push({ ...state.source.files[0] }) },
    state => { state.source.files.push({ ...state.source.files[0], local_path: state.source.files[0].local_path.toUpperCase() }) },
    state => { state.manifest.sources.push(structuredClone(state.source)) },
    state => { state.manifest.schema_version = 2 },
    state => { state.source.files = [] },
    state => { state.manifest.sources = [] },
    state => { state.manifest.dependency_inspection = { repository: state.source.repository, tag_commit: 'v1', version: '1' } },
  ]
  for (const mutate of mutations) {
    const state = await fixture(context)
    mutate(state)
    await state.save()
    await assert.rejects(verifyUpstream(state.root))
  }
})

test('rejects traversal, absolute, noncanonical and out-of-scope paths', async context => {
  for (const path of ['/tmp/LICENSE', '../LICENSE', 'third-party/expert-sources/../LICENSE',
    'third-party/expert-sources/./example/LICENSE', 'third-party/expert-sources//example/LICENSE',
    'third-party/expert-sources-other/LICENSE', 'docs/LICENSE', 'C:\\LICENSE',
    'third-party/expert-sources/example/LICENSE\u0000']) {
    const state = await fixture(context)
    state.source.files[0].local_path = path
    await state.save()
    await assert.rejects(verifyUpstream(state.root))
  }
})

test('rejects missing files, directories and altered bytes', async context => {
  for (const kind of ['missing', 'directory', 'tampered']) {
    const state = await fixture(context)
    const path = join(state.root, state.source.files[0].local_path)
    await rm(path)
    if (kind === 'directory') await mkdir(path)
    if (kind === 'tampered') await writeFile(path, 'changed')
    await assert.rejects(verifyUpstream(state.root))
  }
})

test('rejects symlink file and parent escapes, including manifest symlinks', async context => {
  for (const kind of ['file', 'parent', 'manifest', 'source-root']) {
    const state = await fixture(context)
    const outside = await mkdtemp(join(tmpdir(), 'expert-upstream-outside-'))
    context.after(() => rm(outside, { recursive: true, force: true }))
    await writeFile(join(outside, 'LICENSE'), state.bytes)
    await writeFile(join(outside, 'manifest.json'), JSON.stringify(state.manifest))
    const target = kind === 'file' ? join(state.directory, 'example/LICENSE')
      : kind === 'parent' ? join(state.directory, 'example')
        : kind === 'manifest' ? join(state.directory, 'manifest.json') : state.directory
    await rm(target, { recursive: true })
    await symlink(kind === 'file' ? join(outside, 'LICENSE') : kind === 'manifest' ? join(outside, 'manifest.json') : outside, target)
    await assert.rejects(verifyUpstream(state.root), /Symlinks/)
  }
})

test('verifies repository manifest without executing upstream contents', async () => {
  const result = await verifyUpstream()
  assert.ok(result.sourcesVerified > 0)
  assert.ok(result.filesVerified >= result.sourcesVerified)
})
