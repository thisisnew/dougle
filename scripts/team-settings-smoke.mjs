import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { access, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { randomBytes, randomUUID, createHash } from 'node:crypto'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../', import.meta.url))
const jar = resolve(root, 'backend/target/knowledge-forge-0.1.0.jar')
await access(jar)
const jarSha256 = createHash('sha256').update(await readFile(jar)).digest('hex')
const temporary = await mkdtemp(resolve(tmpdir(), 'dougle-team-settings-smoke-'))
const folder = resolve(temporary, 'synthetic-folder')
const data = resolve(temporary, 'backend-data')
let child
let logs = ''
let base
let token
let spawnError
const pause = milliseconds => new Promise(done => setTimeout(done, milliseconds))

async function start() {
  assert.equal(createHash('sha256').update(await readFile(jar)).digest('hex'), jarSha256, 'JAR changed during smoke; rebuilds must not overlap')
  base = undefined
  let readyOutput = ''
  token = randomBytes(32).toString('hex')
  logs = ''
  spawnError = null
  const environment = {
    PATH: '/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin',
    LANG: 'en_US.UTF-8',
    FORGE_DATA_DIR: data,
    JAVA_TOOL_OPTIONS: `-Duser.home=${resolve(temporary, 'isolated-home')}`,
    ...(process.env.JAVA_HOME ? { JAVA_HOME: process.env.JAVA_HOME } : {}),
  }
  child = spawn('/bin/bash', ['scripts/run.sh', '--server.port=0', '--server.address=127.0.0.1',
    '--forge.desktop=true', `--forge.data-dir=${data}`,
    `--forge.search-model-directory=${resolve(temporary, 'missing-search-models')}`,
    `--forge.ai.codex-command=${resolve(temporary, 'disabled-codex')}`,
    `--forge.ai.claude-command=${resolve(temporary, 'disabled-claude')}`,
    `--forge.ai.codex-stream-home=${resolve(temporary, 'isolated-codex-profile')}`],
  { cwd: root, env: environment, stdio: ['pipe', 'pipe', 'pipe'] })
  child.on('error', error => { spawnError = error })
  child.stdin.on('error', error => { spawnError = error })
  for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => { logs = (logs + chunk).slice(-16000) })
  child.stdout.on('data', chunk => {
    readyOutput = (readyOutput + chunk).slice(-16000)
    const announced = readyOutput.match(/^FORGE_DESKTOP_READY:(\d+)\r?\n/m)
    if (announced) {
      const port = Number(announced[1])
      if (port >= 1 && port <= 65535) base = `http://127.0.0.1:${port}/api`
    }
  })
  child.stdin.write(`${token}\n`)
  for (let attempt = 0; attempt < 120; attempt++) {
    if (spawnError || child.exitCode !== null || child.signalCode !== null) throw new Error(`Synthetic backend failed: ${spawnError?.message || logs}`)
    if (!base) {
      await pause(500)
      continue
    }
    try {
      const response = await fetch(`${base}/session`, { headers: { 'X-Forge-Session': token }, signal: AbortSignal.timeout(1000) })
      if (response.ok) return
    } catch {}
    await pause(500)
  }
  throw new Error(`Synthetic backend startup timed out: ${logs}`)
}

async function stop() {
  if (!child || !child.pid || child.exitCode !== null || child.signalCode !== null) return
  const ended = once(child, 'exit')
  child.kill('SIGTERM')
  const timer = setTimeout(() => child.kill('SIGKILL'), 15000)
  try { await ended } finally { clearTimeout(timer) }
}

async function api(path, method = 'GET', body, expected = 200, overrides = {}) {
  const headers = { 'X-Forge-Session': token, 'X-Forge-Client': '1', Origin: new URL(base).origin,
    ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...overrides }
  const response = await fetch(`${base}${path}`, { method, headers,
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000) })
  const text = await response.text()
  assert.equal(response.status, expected, `${method} ${path}: ${text}`)
  return text ? JSON.parse(text) : null
}

const session = () => api('/session')
const plan = input => api('/team-settings/import-plan', 'POST', input)
const apply = (input, expected = 200) => api('/team-settings/import-apply', 'POST', input, expected)
const binding = preview => ({ requestId: randomUUID(), previewId: preview.previewId, digest: preview.digest })

async function assertIdle(workspaceId, expectedCount) {
  const prefix = `/workspaces/${workspaceId}`
  const sources = await api(`${prefix}/sources`)
  assert.equal(sources.length, expectedCount)
  for (const source of sources) {
    assert.equal(source.status, 'NEW')
    assert.equal(source.pollingEnabled, false)
    assert.equal(source.nextPollAt, null)
    assert.equal(source.syncedAt, null)
    assert.equal(source.rootEntryId, null)
    assert.equal(source.pollIntervalSeconds, 600)
  }
  assert.equal((await api(`${prefix}/source-polling`)).enabled, false)
  assert.deepEqual(await api(`${prefix}/entries`), [])
  assert.deepEqual(await api(`${prefix}/answer-jobs`), [])
  assert.deepEqual(await api(`${prefix}/generation-jobs`), [])
}

try {
  await mkdir(folder)
  await mkdir(resolve(temporary, 'isolated-home'))
  await writeFile(resolve(folder, 'synthetic.txt'), 'SYNTHETIC_TEAM_SETTINGS_ONLY: must remain uncollected.\n')
  await start()
  await api('/session', 'GET', undefined, 403, { 'X-Forge-Session': '' })
  const original = await api('/workspaces', 'POST', { name: 'smoke original' }, 201)
  await api(`/workspaces/${original.id}/sources`, 'POST', {
    name: 'Synthetic folder', kind: 'LOCAL_FOLDER', location: folder, domain: '', sync: false,
  }, 201)
  await api(`/workspaces/${original.id}/sources`, 'POST', {
    name: 'Synthetic web', kind: 'URL', location: 'https://example.invalid/access/SYNTHETIC_SECRET', domain: '', sync: false,
  }, 201)
  const originalSources = await api(`/workspaces/${original.id}/sources`)
  const exported = await api(`/team-settings/export/${original.id}`)
  assert.equal(exported.version, 1)
  assert.equal(exported.workspaceName, original.name)
  assert.equal(exported.sources.length, 2)
  assert.deepEqual(Object.keys(exported).sort(), ['pollIntervalSeconds', 'sources', 'version', 'workspaceName'])
  for (const descriptor of exported.sources) {
    assert.deepEqual(Object.keys(descriptor).sort(), ['kind', 'location', 'name'])
    assert.equal(descriptor.location, null)
  }
  const serialized = JSON.stringify(exported)
  for (const forbidden of [folder, original.id, 'SYNTHETIC_SECRET', 'https://', ...originalSources.map(source => source.id)]) assert(!serialized.includes(forbidden))
  const settings = { ...exported, pollIntervalSeconds: 600,
    sources: [...exported.sources, { name: 'Synthetic DB', kind: 'MYSQL', location: null }] }
  const beforePreview = await session()
  const preview = await api('/team-settings/import-preview', 'POST', settings)
  assert.equal(preview.workspaceNameConflict, true)
  assert(preview.entries.every(entry => entry.needsLocation))
  assert.deepEqual(await session(), beforePreview)
  const mappings = settings.sources.flatMap((source, sourceIndex) => source.kind === 'MYSQL' ? []
    : [{ sourceIndex, location: source.kind === 'LOCAL_FOLDER' ? folder : 'https://example.invalid/synthetic' }])
  const input = { settings, workspaceName: 'smoke imported', mappings }
  await api('/team-settings/import-plan', 'POST', input, 403, { Origin: 'https://foreign.invalid' })
  await api('/team-settings/import-plan', 'POST', input, 403, { 'X-Forge-Client': '' })
  const planned = await plan(input)
  assert.equal(planned.sourcesToCreate, 2)
  assert.deepEqual(planned.reconnectSourceIndices, [2])
  assert.equal(planned.pollingEnabled, false)
  assert.deepEqual(await session(), beforePreview)
  const request = binding(planned)
  await apply({ ...request, digest: '0'.repeat(64) }, 409)
  assert.deepEqual(await session(), beforePreview)
  const imported = await apply(request)
  assert.notEqual(imported.workspaceId, original.id)
  assert.equal(imported.sourceIds.length, 2)
  assert.deepEqual(imported.reconnectSourceIndices, [2])
  const afterApply = await session()
  assert.equal(afterApply.workspaces.length, beforePreview.workspaces.length + 1)
  assert.equal(afterApply.activeWorkspaceId, beforePreview.activeWorkspaceId)
  assert.equal(afterApply.workspaces.find(workspace => workspace.id === imported.workspaceId).name, input.workspaceName)
  assert.deepEqual(await apply(request), imported)
  assert.deepEqual(await session(), afterApply)
  await assertIdle(imported.workspaceId, 2)
  await api('/team-settings/import-plan', 'POST', { ...input, workspaceName: original.name }, 409)
  const unsafeSettings = structuredClone(settings)
  unsafeSettings.sources[0].location = 'https://example.invalid/private/SYNTHETIC_SECRET'
  await api('/team-settings/import-preview', 'POST', unsafeSettings, 400)
  await api('/team-settings/import-preview', 'POST', { ...settings, version: 999 }, 400)
  await api('/team-settings/import-preview', 'POST', { ...settings, credentials: 'SYNTHETIC_SECRET' }, 400)
  const webIndex = settings.sources.findIndex(source => source.kind === 'URL')
  for (const location of ['https://user:secret@example.invalid/', 'https://example.invalid/?token=secret', 'https://example.invalid/#secret', 'file:///synthetic']) {
    await api('/team-settings/import-plan', 'POST', { ...input, workspaceName: 'unsafe plan',
      mappings: mappings.map(mapping => mapping.sourceIndex === webIndex ? { ...mapping, location } : mapping) }, 400)
  }
  await api('/team-settings/import-plan', 'POST', { ...input, workspaceName: 'mysql mapping',
    mappings: [...mappings, { sourceIndex: 2, location: 'mysql://synthetic:secret@invalid/db' }] }, 400)
  assert.deepEqual(await session(), afterApply)
  assert.deepEqual(await api(`/workspaces/${original.id}/sources`), originalSources)
  const failedInput = { settings: { ...settings, sources: [{ name: 'First', kind: 'URL', location: null }, { name: 'Missing', kind: 'LOCAL_FOLDER', location: null }] },
    workspaceName: 'rollback fixture', mappings: [{ sourceIndex: 0, location: 'https://example.invalid/synthetic' }, { sourceIndex: 1, location: resolve(temporary, 'missing-folder') }] }
  const failedRequest = binding(await plan(failedInput))
  await apply(failedRequest, 400)
  assert.deepEqual(await session(), afterApply)
  const staleRequest = binding(await plan({ ...input, workspaceName: 'unapplied fixture' }))
  await stop()
  await start()
  assert.deepEqual(await apply(request), imported)
  await apply(staleRequest, 409)
  await apply({ ...request, digest: 'f'.repeat(64) }, 409)
  assert.deepEqual(await session(), afterApply)
  await assertIdle(imported.workspaceId, 2)
  await mkdir(resolve(temporary, 'missing-folder'))
  await apply(failedRequest, 409)
  await api(`/workspaces/${imported.workspaceId}`, 'DELETE', undefined, 204)
  assert.deepEqual(await apply(request), imported)
  assert(!(await session()).workspaces.some(workspace => workspace.id === imported.workspaceId))
  assert.deepEqual(await api(`/workspaces/${original.id}/sources`), originalSources)
  console.log(JSON.stringify({ status: 'PASS', jarSha256,
    checks: ['export redaction', 'read-only preview/plan', 'explicit new workspace apply', 'disabled polling', 'empty source content and AI job lists',
      'idempotency', 'restart receipt', 'expired in-memory preview', 'unsafe input rejection', 'atomic rollback', 'no overwrite', 'deleted workspace receipt', 'session/origin checks'],
    limitation: 'Synthetic local runtime smoke only. AI executable paths are disabled; no sync, model-download, or AI endpoint is called. This is not an OS network sandbox, a real-account check, or E49/E50 acceptance completion.' }, null, 2))
} finally {
  try { await stop() } finally { await rm(temporary, { recursive: true, force: true }) }
}
