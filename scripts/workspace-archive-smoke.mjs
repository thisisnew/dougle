import assert from 'node:assert/strict'
import { execFile, spawn } from 'node:child_process'
import { once } from 'node:events'
import { access, mkdir, mkdtemp, readFile, realpath, rm, stat } from 'node:fs/promises'
import { randomBytes, randomUUID, createHash } from 'node:crypto'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'

const root = fileURLToPath(new URL('../', import.meta.url))
const jar = resolve(root, 'backend/target/knowledge-forge-0.1.0.jar')
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex')
const catalogKey = '00000000-0000-0000-0000-000000000001'
const catalogPath = 'documents/' + catalogKey + '.bin'
if (process.argv.length === 3 && process.argv[2] === '--self-test') {
  const identifier = '33333333-3333-3333-3333-333333333333'
  const content = Buffer.from('SYNTHETIC_FIXTURE_ONLY')
  const catalog = { version: 1, workspaceName: 'fixture', materials: [], threads: [] }
  const encoded = archive(catalog, new Map([[identifier, content]]))
  assert.deepEqual(encoded, archive(catalog, new Map([[identifier, content]])))
  const decoded = unzipStored(encoded)
  assert.deepEqual(decoded.get('documents/' + identifier + '.bin'), content)
  assert.deepEqual(JSON.parse(decoded.get(catalogPath)), catalog)
  const manifest = decoded.get('manifest.bin')
  assert.equal(manifest.readUInt32BE(0), 0x4b465441)
  assert.equal(manifest.readUInt32BE(4), 1)
  assert.equal(manifest.readUInt32BE(8), 2)
  assert.equal(manifest.length, 116)
  assert.equal(manifest.readUInt32BE(28), content.length)
  assert.equal(manifest.subarray(32, 64).toString('hex'), sha256(content))
  const corrupt = Buffer.from(encoded)
  corrupt[corrupt.indexOf(content)] ^= 1
  assert.throws(() => unzipStored(corrupt))
  assert.equal(crc32(Buffer.from('123456789')), 0xcbf43926)
  const hit = { entryId: 'fresh', kind: 'ORIGINAL', sourceId: null, textHash: sha256(content), snippet: content.toString() }
  assertSearchHits([hit], 'fresh', ['old'], sha256(content), content.toString())
  assertSearchHits([{ ...hit, sourceId: '' }], 'fresh', ['old'], sha256(content), content.toString())
  for (const invalid of [[], [{ ...hit, entryId: 'old' }], [{ ...hit, textHash: 'stale' }],
    [{ ...hit, snippet: 'unrelated' }], [{ ...hit, kind: 'KNOWLEDGE' }], [{ ...hit, sourceId: 'old-source' }]]) {
    assert.throws(() => assertSearchHits(invalid, 'fresh', ['old'], sha256(content), content.toString()))
  }
  let clock = 0
  let attempts = 0
  const timing = { timeoutMs: 1000, now: () => clock, sleep: async () => { clock += 500 } }
  assert.equal(await boundedPoll(async () => ++attempts === 2 ? 'ready' : null, timing), 'ready')
  clock = 0
  await assert.rejects(boundedPoll(async () => null, timing), /Timed out/)
  await assert.rejects(boundedPoll(async () => { throw new Error('model ERROR') }, timing), /model ERROR/)
  console.log('PASS synthetic ZIP/manifest/CRC, search fresh-ID/text/hash rejection, bounded polling and fail-fast checks; no backend/model/DB opened')
  process.exit(0)
}
if (process.argv.length !== 3 || process.argv[2] !== '--run') {
  console.error('Usage: node scripts/workspace-archive-smoke.mjs --self-test | --run (runtime requires approved stable JAR)')
  process.exit(2)
}
await access(jar)
const jarSha256 = createHash('sha256').update(await readFile(jar)).digest('hex')
const modelDirectory = process.env.FORGE_ARCHIVE_SEARCH_MODELS
  ? await realpath(resolve(process.env.FORGE_ARCHIVE_SEARCH_MODELS)) : null
if (modelDirectory) {
  assert((await stat(modelDirectory)).isDirectory(), 'Search model path must be a local directory')
  for (const name of ['model.onnx', 'tokenizer.json', 'reranker.onnx', 'reranker-tokenizer.json']) {
    const asset = await stat(resolve(modelDirectory, name))
    assert(asset.isFile() && asset.size > 0, 'Missing local search model asset: ' + name)
  }
  await promisify(execFile)('sqlite3', ['--version'], { timeout: 3000 })
}
const temporary = await mkdtemp(resolve(tmpdir(), 'dougle-workspace-archive-smoke-'))
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
    `--forge.search-model-directory=${modelDirectory ?? resolve(temporary, 'missing-search-models')}`,
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


const checks = []
const indexVerification = { enabled: !!modelDirectory, status: modelDirectory ? 'NOT_RUN' : 'SKIPPED', observations: [] }
const record = name => { checks.push(name); console.error('PASS ' + name) }

async function boundedPoll(probe, { timeoutMs = 120000, now = Date.now,
  sleep = milliseconds => new Promise(done => setTimeout(done, milliseconds)) } = {}) {
  const deadline = now() + timeoutMs
  while (now() < deadline) {
    const result = await probe()
    if (result) return result
    await sleep(500)
  }
  throw new Error('Timed out waiting for local model/index/search readiness')
}

function assertSearchHits(hits, expectedId, forbiddenIds, textHash, expectedText) {
  assert(Array.isArray(hits) && hits.length > 0, 'Expected restored material search hits')
  for (const hit of hits) {
    assert.equal(hit.entryId, expectedId, 'Search leaked another workspace or stale material ID')
    assert(!forbiddenIds.includes(hit.entryId), 'Search returned an old workspace material ID')
    assert.equal(hit.kind, 'ORIGINAL')
    assert(hit.sourceId === null || hit.sourceId === '', 'Search retained an external source binding')
    assert.equal(hit.textHash, textHash)
    assert.equal(typeof hit.snippet, 'string')
    assert(hit.snippet.includes(expectedText), 'Search did not return original restored text')
  }
}

async function verifySearch(workspaceId, expectedId, forbiddenIds, textHash, expectedText, phase) {
  if (!modelDirectory) return
  await boundedPoll(async () => {
    const model = await api('/search-model')
    assert(['STARTING', 'LOADING', 'INDEXING', 'READY'].includes(model.state), 'Local search model not usable: ' + JSON.stringify(model))
    return model.state === 'READY'
  })
  const database = resolve(data, 'knowledge-forge.sqlite')
  await boundedPoll(async () => {
    const { stdout } = await promisify(execFile)('sqlite3', ['-readonly', database, 'SELECT count(*) FROM search_outbox;'],
      { timeout: 3000, maxBuffer: 4096 })
    assert(/^\d+$/.test(stdout.trim()), 'Invalid index queue count')
    if (Number(stdout.trim()) !== 0) return false
    const hits = await api('/workspaces/' + workspaceId + '/search?query=' + encodeURIComponent('SYNTHETIC_ARCHIVE_ONLY'))
    assert(Array.isArray(hits), 'Invalid search response')
    if (!hits.length) return false
    assertSearchHits(hits, expectedId, forbiddenIds, textHash, expectedText)
    return true
  })
  indexVerification.observations.push({ phase, workspaceId, entryId: expectedId, textHash, modelState: 'READY', pendingIndexChanges: 0 })
  record('local model READY, index queue drained, original text/fresh scoped search ID: ' + phase)
}

function crc32(bytes) {
  let crc = 0xffffffff
  for (const byte of bytes) {
    crc ^= byte
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0)
  }
  return (crc ^ 0xffffffff) >>> 0
}

function zip(entries) {
  const local = []
  const central = []
  let offset = 0
  for (const [path, content] of entries) {
    const name = Buffer.from(path)
    const header = Buffer.alloc(30)
    header.writeUInt32LE(0x04034b50)
    header.writeUInt16LE(20, 4)
    header.writeUInt16LE(33, 12)
    header.writeUInt32LE(crc32(content), 14)
    header.writeUInt32LE(content.length, 18)
    header.writeUInt32LE(content.length, 22)
    header.writeUInt16LE(name.length, 26)
    const directory = Buffer.alloc(46)
    directory.writeUInt32LE(0x02014b50)
    directory.writeUInt16LE(20, 4)
    header.copy(directory, 6, 4, 28)
    directory.writeUInt32LE(offset, 42)
    local.push(header, name, content)
    central.push(directory, name)
    offset += header.length + name.length + content.length
  }
  const directory = Buffer.concat(central)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50)
  end.writeUInt16LE(entries.size, 8)
  end.writeUInt16LE(entries.size, 10)
  end.writeUInt32LE(directory.length, 12)
  end.writeUInt32LE(offset, 16)
  return Buffer.concat([...local, directory, end])
}

function unzipStored(bytes) {
  const entries = new Map()
  let offset = 0
  while (bytes.readUInt32LE(offset) === 0x04034b50) {
    assert.equal(bytes.readUInt16LE(offset + 6) & 8, 0)
    assert.equal(bytes.readUInt16LE(offset + 8), 0, 'Backend export must use STORED ZIP entries')
    const size = bytes.readUInt32LE(offset + 18)
    const nameLength = bytes.readUInt16LE(offset + 26)
    const extraLength = bytes.readUInt16LE(offset + 28)
    const path = bytes.subarray(offset + 30, offset + 30 + nameLength).toString()
    const start = offset + 30 + nameLength + extraLength
    const content = bytes.subarray(start, start + size)
    assert.equal(content.length, size)
    assert.equal(crc32(content), bytes.readUInt32LE(offset + 14))
    assert(!entries.has(path))
    entries.set(path, content)
    offset = start + size
  }
  assert.equal(bytes.readUInt32LE(offset), 0x02014b50)
  return entries
}

function archive(catalog, files) {
  const documents = new Map(files)
  documents.set(catalogKey, Buffer.from(JSON.stringify(catalog)))
  const manifest = Buffer.alloc(12 + documents.size * 52)
  manifest.writeUInt32BE(0x4b465441)
  manifest.writeUInt32BE(1, 4)
  manifest.writeUInt32BE(documents.size, 8)
  let offset = 12
  for (const [id, bytes] of documents) {
    Buffer.from(id.replaceAll('-', ''), 'hex').copy(manifest, offset)
    manifest.writeUInt32BE(bytes.length, offset + 16)
    Buffer.from(sha256(bytes), 'hex').copy(manifest, offset + 20)
    offset += 52
  }
  return zip(new Map([['manifest.bin', manifest], ...[...documents].map(([id, bytes]) => ['documents/' + id + '.bin', bytes])]))
}

async function api(path, method = 'GET', body, expected = 200, overrides = {}) {
  const raw = Buffer.isBuffer(body)
  const multipart = body instanceof FormData
  const headers = { 'X-Forge-Session': token, 'X-Forge-Client': '1', Origin: new URL(base).origin,
    ...(body === undefined || multipart ? {} : { 'Content-Type': raw ? 'application/zip' : 'application/json' }), ...overrides }
  const response = await fetch(base + path, { method, headers,
    body: body === undefined ? undefined : raw || multipart ? body : JSON.stringify(body),
    signal: AbortSignal.timeout(20000) })
  const bytes = Buffer.from(await response.arrayBuffer())
  assert.equal(response.status, expected, method + ' ' + path + ': ' + bytes.toString().slice(0, 1500))
  if (response.headers.get('content-type')?.includes('application/zip') || path.endsWith('/content')) return bytes
  return bytes.length ? JSON.parse(bytes.toString()) : null
}

const session = () => api('/session')
const preview = (bytes, name, expected = 200, headers) =>
  api('/workspace-archives/import-preview?workspaceName=' + encodeURIComponent(name), 'POST', bytes, expected, headers)
const binding = value => ({ requestId: randomUUID(), previewId: value.previewId, digest: value.digest })
const apply = (request, expected = 200) => api('/workspace-archives/import-apply', 'POST', request, expected)
const exportArchive = id => api('/workspace-archives/export/' + id)

async function idle(id) {
  assert.deepEqual(await api('/workspaces/' + id + '/sources'), [])
  assert.deepEqual(await api('/workspaces/' + id + '/generation-jobs'), [])
  assert.equal((await api('/workspaces/' + id + '/source-polling')).enabled, false)
  const jobs = await api('/workspaces/' + id + '/answer-jobs')
  assert(jobs.every(job => job.status === 'SUCCEEDED'))
}

function normalized(catalog) {
  const materialKeys = new Map(catalog.materials.map((material, index) => [material.key, index]))
  return {
    materials: catalog.materials.map(material => ({ ...material, key: materialKeys.get(material.key),
      parentKey: material.parentKey === null ? null : materialKeys.get(material.parentKey) })),
    threads: catalog.threads.map(thread => {
      const turnKeys = new Map(thread.turns.map((turn, index) => [turn.key, index]))
      return { turns: thread.turns.map(turn => ({ ...turn, key: turnKeys.get(turn.key),
        evidence: turn.evidence.map(item => ({ ...item, materialKey: materialKeys.get(item.materialKey) })),
        history: turn.history.map(item => ({ ...item, turnKey: turnKeys.get(item.turnKey) })) })) }
    }),
  }
}

try {
  await mkdir(resolve(temporary, 'isolated-home'))
  await start()
  assert.deepEqual((await session()).workspaces, [])
  await api('/session', 'GET', undefined, 403, { 'X-Forge-Session': '' })
  const original = await api('/workspaces', 'POST', { name: 'archive original' }, 201)
  const directory = await api('/workspaces/' + original.id + '/folders', 'POST', { name: 'synthetic', parentId: null }, 201)
  const content = Buffer.from('SYNTHETIC_ARCHIVE_ONLY: retry budget is three.\n')
  const form = new FormData()
  form.append('file', new Blob([content], { type: 'text/plain' }), 'synthetic.txt')
  const entry = await api('/workspaces/' + original.id + '/files?parentId=' + directory.id, 'POST', form, 201)
  const analysis = await api('/workspaces/' + original.id + '/entries/' + entry.id + '/analysis')
  assert(analysis.text.includes('SYNTHETIC_ARCHIVE_ONLY'))
  const originalZip = await exportArchive(original.id)
  const originalEntries = unzipStored(originalZip)
  const catalog = JSON.parse(originalEntries.get(catalogPath))
  const material = catalog.materials.find(item => item.kind === 'FILE')
  assert(material)
  const files = new Map([[material.key, content]])
  const when = '2026-01-01T00:00:00Z'
  const first = '11111111-1111-1111-1111-111111111111'
  const second = '22222222-2222-2222-2222-222222222222'
  const answer = 'Synthetic archived answer: three retries.'
  const evidence = { materialKey: material.key, contentHash: sha256(content), textHash: sha256(analysis.text),
    startLine: 1, endLine: 1, quote: analysis.text.split('\n')[0] }
  catalog.threads = [{ turns: [
    { key: first, createdAt: when, question: 'Synthetic retry budget?', retrievalQuery: 'retry budget',
      evidence: [evidence], history: [], answers: [{ provider: 'CODEX', model: 'synthetic-no-execution',
        text: answer, createdAt: when, updatedAt: when }] },
    { key: second, createdAt: '2026-01-01T00:00:01Z', question: 'Synthetic follow-up?', retrievalQuery: 'retry follow-up',
      evidence: [evidence], history: [{ turnKey: first, question: 'Synthetic retry budget?', answer }], answers: [] },
  ] }]
  const fixture = archive(catalog, files)
  assert.deepEqual(JSON.parse(unzipStored(fixture).get(catalogPath)), catalog)
  const before = await session()
  await preview(fixture, 'archive imported', 403, { Origin: 'https://foreign.invalid' })
  await preview(fixture, 'archive imported', 403, { 'X-Forge-Client': '' })
  const planned = await preview(fixture, 'archive imported')
  assert.deepEqual([planned.materials, planned.threads, planned.turns, planned.answers], [2, 1, 2, 1])
  assert.deepEqual(await session(), before)
  const request = binding(planned)
  await apply({ ...request, digest: '0'.repeat(64) }, 409)
  assert.deepEqual(await session(), before)
  const imported = await apply(request)
  assert.notEqual(imported.workspaceId, original.id)
  const after = await session()
  assert.equal(after.workspaces.length, before.workspaces.length + 1)
  assert.equal(after.activeWorkspaceId, before.activeWorkspaceId)
  assert.deepEqual(await apply(request), imported)
  await apply({ ...request, requestId: randomUUID() }, 409)
  assert.deepEqual(await session(), after)
  record('authenticated preview, explicit new workspace apply, no activation, same-request idempotency')

  const prefix = '/workspaces/' + imported.workspaceId
  const detail = await api(prefix + '/chat-threads/' + imported.threadIds[0])
  assert.equal(detail.bundles.length, 2)
  assert.equal(detail.jobs.length, 1)
  const followup = detail.bundles.find(bundle => bundle.payload.question === 'Synthetic follow-up?')
  const previous = detail.bundles.find(bundle => bundle.payload.question === 'Synthetic retry budget?')
  assert.equal(followup.payload.chatContext.history[0].bundleId, previous.id)
  assert.equal(followup.payload.chatContext.history[0].bundleDigest, previous.digest)
  assert.equal(detail.jobs[0].answer, answer)
  assert.equal(detail.jobs[0].status, 'SUCCEEDED')
  const item = previous.payload.items[0]
  assert.equal(item.sourceId, null)
  assert.equal(item.contentHash, sha256(content))
  assert.equal(item.textHash, sha256(analysis.text))
  assert.deepEqual(await api(prefix + '/entries/' + item.entryId + '/content'), content)
  assert.equal((await api(prefix + '/entries/' + item.entryId + '/analysis')).text, analysis.text)
  assert.equal(imported.indexStatus, 'QUEUED_LOCAL_REBUILD')
  await idle(imported.workspaceId)
  record('file bytes, local analysis, two-turn history digest rebinding, historical answer only')
  await verifySearch(imported.workspaceId, item.entryId, [entry.id], sha256(analysis.text), evidence.quote, 'first import')

  const exported = await exportArchive(imported.workspaceId)
  const restoredEntries = unzipStored(exported)
  const restoredCatalog = JSON.parse(restoredEntries.get(catalogPath))
  assert.deepEqual(normalized(restoredCatalog), normalized(catalog))
  const copyPlan = await preview(exported, 'archive roundtrip')
  const copied = await apply(binding(copyPlan))
  assert.notEqual(copied.workspaceId, imported.workspaceId)
  assert.deepEqual(normalized(JSON.parse(unzipStored(await exportArchive(copied.workspaceId)).get(catalogPath))), normalized(catalog))
  await idle(copied.workspaceId)
  record('export and second import preserve supported catalog data')
  const copiedThread = await api('/workspaces/' + copied.workspaceId + '/chat-threads/' + copied.threadIds[0])
  const copiedEntryId = copiedThread.bundles.find(bundle => bundle.payload.question === 'Synthetic retry budget?').payload.items[0].entryId
  await verifySearch(copied.workspaceId, copiedEntryId, [entry.id, item.entryId], sha256(analysis.text), evidence.quote, 'roundtrip import')

  const stable = await session()
  await preview(fixture, original.name, 409)
  for (const change of [
    value => { value.version = 999 },
    value => { value.materials[0].name = '../escape' },
    value => { value.credentials = 'SYNTHETIC_FORBIDDEN' },
    value => { value.threads[0].turns[0].evidence[0].quote = 'fabricated quote' },
  ]) {
    const unsafe = structuredClone(catalog)
    change(unsafe)
    await preview(archive(unsafe, files), 'unsafe fixture', 400)
  }
  const badHash = unzipStored(fixture)
  badHash.set('documents/' + material.key + '.bin', Buffer.from('tampered'))
  await preview(zip(badHash), 'unsafe fixture', 400)
  const unsafePath = unzipStored(fixture)
  unsafePath.set('../escape', Buffer.from('synthetic'))
  await preview(zip(unsafePath), 'unsafe fixture', 400)
  assert.deepEqual(await session(), stable)
  const originalAgain = unzipStored(await exportArchive(original.id))
  assert.deepEqual(normalized(JSON.parse(originalAgain.get(catalogPath))), normalized(JSON.parse(originalEntries.get(catalogPath))))
  assert.deepEqual(await api('/workspaces/' + original.id + '/entries/' + entry.id + '/content'), content)
  record('unsupported version, traversal, unknown fields, false quote, checksum rejection without workspace creation')

  const slack = await api('/slack/status')
  assert.equal(slack.state, 'NOT_CONFIGURED')
  assert.equal(slack.teamId, null)
  assert.equal(slack.inboxCount, 0)
  assert.deepEqual(await api('/slack/routes'), [])
  assert.deepEqual(await api('/slack/workspaces/' + imported.workspaceId + '/inbox'), [])
  assert.deepEqual(await api(prefix + '/browser-investigations/jobs?bundleId=' + previous.id), [])
  record('Slack disconnected/empty and browser job list empty; no connection or browser launch')

  const stale = binding(await preview(exported, 'unapplied restart'))
  await stop()
  await start()
  assert.deepEqual(await apply(request), imported)
  await apply(stale, 409)
  await apply({ ...request, requestId: randomUUID() }, 409)
  await apply({ ...request, digest: 'f'.repeat(64) }, 409)
  assert.deepEqual(await session(), stable)
  assert.deepEqual(await api(prefix + '/chat-threads/' + imported.threadIds[0]), detail)
  await idle(imported.workspaceId)
  await verifySearch(imported.workspaceId, item.entryId, [entry.id, copiedEntryId], sha256(analysis.text), evidence.quote, 'restart')
  await api(prefix, 'DELETE', undefined, 204)
  assert.deepEqual(await apply(request), imported)
  await apply({ ...request, requestId: randomUUID() }, 409)
  assert(!(await session()).workspaces.some(workspace => workspace.id === imported.workspaceId))
  record('durable restart receipt, expired stage rejection, deleted-workspace no recreation')
  if (modelDirectory) {
    await verifySearch(copied.workspaceId, copiedEntryId, [entry.id, item.entryId], sha256(analysis.text), evidence.quote, 'after other workspace deletion')
    indexVerification.status = 'PASS'
  }
  console.log(JSON.stringify({ status: 'PASS', mode: 'simulated', fixtureOnly: true,
    fixtureVersion: 'workspace-archive-smoke-v2', executedAt: new Date().toISOString(), jarSha256,
    scriptSha256: sha256(await readFile(fileURLToPath(import.meta.url))), checks, indexVerification,
    acceptanceUpdated: false,
    limitation: 'Synthetic local runtime only; answers are fabricated archive records, never AI output. '
      + (modelDirectory ? 'Opt-in local model READY, drained index queue and exact fixture text/fresh-ID search checked; broader semantic search quality is not evaluated. '
        : 'Index rebuild is queued and extracted text verified, not search readiness; local models disabled. ')
      + 'No external accounts, browser navigation, Slack events, cross-account transfer, migration from historical user data, or E50 acceptance completion. External AI commands are disabled; local models are read only when explicitly supplied, no prepare/download endpoint is called. This is not an OS network sandbox.' }, null, 2))
} catch (error) {
  if (modelDirectory) indexVerification.status = 'FAIL'
  console.error(JSON.stringify({ status: 'FAIL', mode: 'simulated', jarSha256, checks, indexVerification, error: error.message, backendLogTail: logs }, null, 2))
  process.exitCode = 1
} finally {
  try { await stop() } finally { await rm(temporary, { recursive: true, force: true }) }
}
