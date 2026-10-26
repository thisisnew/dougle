import assert from 'node:assert/strict'
import test from 'node:test'
import { createHash } from 'node:crypto'
import { mkdtemp, readFile, rm, stat, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { modelManifest, searchModelPath, verifyModelFiles, verifySearchModels } from './expert-live-search-models.mjs'

test('default model path is architecture-specific cache, not shared desktop staging', () => {
  assert.equal(searchModelPath('/repo', undefined, 'x64'), '/repo/.tools/search-model/x86_64')
  assert.equal(searchModelPath('/repo', undefined, 'arm64'), '/repo/.tools/search-model/arm64')
  assert.equal(searchModelPath('/repo', '/private/verified-models', 'x64'), '/private/verified-models')
  assert.throws(() => searchModelPath('/repo', undefined, 'ia32'))
  assert.throws(() => searchModelPath('/repo', '', 'x64'))
})

test('four pinned assets match staging manifest, including architecture-specific reranker', async () => {
  const staging = await readFile(new URL('./stage-search-model.mjs', import.meta.url), 'utf8')
  for (const architecture of ['x64', 'arm64']) {
    const manifest = modelManifest(architecture)
    assert.equal(manifest.length, 4)
    for (const asset of manifest) {
      assert.ok(staging.includes(asset.sha256))
      assert.ok(staging.includes(String(asset.size)))
    }
  }
  assert.notEqual(modelManifest('x64')[2].sha256, modelManifest('arm64')[2].sha256)
})

test('verification is read-only and fails closed on corruption, replacement or linked paths', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'expert-model-verification-'))
  const linked = directory + '-link'
  const path = join(directory, 'model.onnx')
  const body = 'synthetic model only'
  const manifest = [{ name: 'model.onnx', size: Buffer.byteLength(body), sha256: createHash('sha256').update(body).digest('hex') }]
  try {
    await writeFile(path, body)
    const before = await stat(path)
    const canonical = await verifyModelFiles(directory, manifest)
    assert.ok(canonical.endsWith(directory.split('/').at(-1)))
    assert.equal((await stat(path)).mtimeMs, before.mtimeMs)
    await writeFile(path, 'x'.repeat(body.length))
    await assert.rejects(verifyModelFiles(directory, manifest), /checksum/)
    await assert.rejects(verifySearchModels(directory, directory, 'x64'), /size mismatch/)
    await rm(path)
    await assert.rejects(verifyModelFiles(directory, manifest))
    await writeFile(join(directory, 'target'), body)
    await symlink(join(directory, 'target'), path)
    await assert.rejects(verifyModelFiles(directory, manifest), /regular model/)
    await symlink(directory, linked)
    await assert.rejects(verifyModelFiles(linked, manifest), /unlinked directory/)
  } finally {
    await rm(linked, { force: true })
    await rm(directory, { recursive: true, force: true })
  }
})

test('runner verifies assets before process start and every counted external dispatch, without staging', async () => {
  const runner = await readFile(new URL('./expert-live-eval.mjs', import.meta.url), 'utf8')
  const start = runner.slice(runner.indexOf('async function startRuntime()'), runner.indexOf('async function stopRuntime()'))
  assert.ok(start.indexOf('await verifySearchModels') < start.indexOf('spawn('))
  const dispatch = runner.slice(runner.indexOf('async function dispatch('), runner.indexOf('async function api('))
  assert.ok(dispatch.indexOf('await verifySearchModels') < dispatch.indexOf('report.dispatched++'))
  assert.doesNotMatch(runner, /desktop\/src-tauri\/resources\/search-model-e5-v1|stage-search-model/)
})
