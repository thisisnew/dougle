import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { cp, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { performance } from 'node:perf_hooks'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const directory = await mkdtemp(resolve(tmpdir(), 'forge-smoke-'))
const portServer = createServer()
portServer.listen(0, '127.0.0.1')
await once(portServer, 'listening')
const port = portServer.address().port
await new Promise(resolveClose => portServer.close(resolveClose))
const base = `http://127.0.0.1:${port}`
let child
let logs = ''
let configuredModel = 'server-default-fixture'

async function start() {
  logs = ''
  const started = performance.now()
  child = spawn('bash', ['scripts/run.sh',
    `--forge.ai.codex-command=${resolve(root, 'scripts/fixtures/fake-codex.sh')}`,
    `--forge.ai.codex-model=${configuredModel}`,
    `--forge.ai.claude-command=${resolve(root, 'scripts/fixtures/fake-codex.sh')}`], {
    cwd: root,
    env: { ...process.env, PORT: String(port), FORGE_DATA_DIR: directory },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  child.on('error', error => { logs += error.message })
  child.stdout.on('data', chunk => { logs += chunk.toString() })
  child.stderr.on('data', chunk => { logs += chunk.toString() })
  for (let attempt = 0; attempt < 200; attempt++) {
    if (child.exitCode !== null) throw new Error(`Server exited: ${logs}`)
    try {
      const response = await fetch(`${base}/api/session`, { signal: AbortSignal.timeout(1000) })
      if (response.ok) return performance.now() - started
    } catch {}
    await new Promise(resolveWait => setTimeout(resolveWait, 150))
  }
  throw new Error(`Server startup timeout: ${logs}`)
}

async function stop() {
  if (child && child.exitCode === null) {
    const ended = once(child, 'exit')
    child.kill('SIGTERM')
    const timeout = setTimeout(() => child.kill('SIGKILL'), 5000)
    await ended
    clearTimeout(timeout)
  }
}

async function api(path, method = 'GET', body) {
  const headers = { 'X-Forge-Client': '1' }
  if (body && !(body instanceof FormData)) headers['Content-Type'] = 'application/json'
  const response = await fetch(`${base}/api${path}`, {
    method, headers, body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(path.endsWith('/sync') ? 180000 : 10000),
  })
  assert.ok(response.ok, `${method} ${path}: ${response.status} ${response.ok ? '' : await response.text()}`)
  return response.status === 204 ? null : response.json()
}

const measure = async action => {
  const started = performance.now()
  await action()
  return performance.now() - started
}
const percentile = (values, quantile) => [...values].sort((left, right) => left - right)[Math.ceil(values.length * quantile) - 1]

async function waitForJob(path, predicate) {
  const deadline = Date.now() + 15000
  while (Date.now() < deadline) {
    const job = await api(path)
    if (predicate(job)) return job
    await new Promise(resolveWait => setTimeout(resolveWait, 100))
  }
  throw new Error(`Job did not reach expected state: ${path}`)
}

try {
  const startupMs = await start()
  const cliStatus = await api('/ai/providers/CODEX/probe', 'POST')
  assert.equal(cliStatus.status, 'AVAILABLE')
  assert.equal(cliStatus.version, '0.0.0-test-fixture')
  assert.equal(cliStatus.requestedModel, configuredModel)
  assert.equal(cliStatus.authentication, 'UNKNOWN')
  assert.equal(cliStatus.remoteConnection, 'NOT_CHECKED')
  const page = await fetch(base)
  assert.equal(page.status, 200)
  assert.match(await page.text(), /Knowledge Forge/)
  const workspace = await api('/workspaces', 'POST', { name: '가상 온실 연구' })
  const folder = await api(`/workspaces/${workspace.id}/folders`, 'POST', { name: '가상 자료' })
  const bytes = await readFile(resolve(root, 'fixtures/research-notes.md'))
  const form = new FormData()
  form.append('file', new Blob([bytes]), 'research-notes.md')
  form.append('parentId', folder.id)
  const file = await api(`/workspaces/${workspace.id}/files`, 'POST', form)
  const contentPath = `/workspaces/${workspace.id}/entries/${file.id}`
  const preview = await api(`${contentPath}/preview`)
  assert.equal(preview.text, bytes.toString('utf8'))
  assert.deepEqual(Buffer.from(await (await fetch(`${base}/api${contentPath}/content`)).arrayBuffer()), bytes)
  const sourceDirectory = resolve(directory, 'synthetic-project')
  await cp(resolve(root, 'fixtures/synthetic-project'), sourceDirectory, { recursive: true })
  const source = await api(`/workspaces/${workspace.id}/sources`, 'POST', {
    name: '가상 프로젝트', kind: 'LOCAL_FOLDER', location: sourceDirectory, domain: '실험',
  })
  assert.equal(source.pollingEnabled, false)
  let synced
  const sourceSyncMs = await measure(async () => {
    synced = await api(`/workspaces/${workspace.id}/sources/${source.id}/sync`, 'POST')
    assert.equal(synced.status, 'READY', synced.error)
  })
  const searchPath = `/workspaces/${workspace.id}/search?query=measureTemperature&domains=${encodeURIComponent('실험')}`
  const symbolHits = await api(searchPath)
  assert.equal(symbolHits.length, 1)
  const analysis = await api(`/workspaces/${workspace.id}/entries/${symbolHits[0].entryId}/analysis`)
  assert.ok(analysis.symbols.some(symbol => symbol.name === 'measureTemperature' && symbol.line === 4))
  const knowledgeBase = `/workspaces/${workspace.id}/knowledge`
  const draft = await api(knowledgeBase, 'POST', {
    title: '가상 실험 초안', markdown: '검토가 필요한 수동 메모',
    citations: [{ entryId: symbolHits[0].entryId, startLine: 1, endLine: 4 }],
  })
  assert.equal(draft.version.status, 'DRAFT')
  const knowledgePath = `${knowledgeBase}/${draft.card.id}`
  const serverDefault = await api(`${knowledgePath}/generation/preview`, 'POST', { provider: 'CODEX' })
  assert.equal(serverDefault.requestedModel, configuredModel)
  const transmission = await api(`${knowledgePath}/generation/preview`, 'POST', { provider: 'CODEX', requestedModel: 'synthetic-smoke-model' })
  assert.equal(transmission.requestedModel, 'synthetic-smoke-model')
  assert.notEqual(transmission.digest, serverDefault.digest)
  assert.ok(transmission.prompt.includes('E1'))
  assert.equal(transmission.digest.length, 64)
  for (const [approved, digest, status] of [[false, transmission.digest, 403], [true, 'wrong', 409]]) {
    const rejected = await fetch(`${base}/api${knowledgePath}/generation`, {
      method: 'POST', headers: { 'X-Forge-Client': '1', 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: 'CODEX', expectedVersion: 1, approved, digest }),
    })
    assert.equal(rejected.status, status)
  }
  assert.equal((await api(knowledgeBase)).length, 1)
  const jobBase = `/workspaces/${workspace.id}/generation-jobs`
  const generation = { requestId: crypto.randomUUID(), provider: 'CODEX', requestedModel: transmission.requestedModel, expectedVersion: 1, digest: transmission.digest, approved: true }
  const submitted = await api(`${knowledgePath}/generation/jobs`, 'POST', generation)
  assert.equal(submitted.id, generation.requestId)
  assert.equal(submitted.requestedModel, 'synthetic-smoke-model')
  assert.equal((await api(`${knowledgePath}/generation/jobs`, 'POST', generation)).id, submitted.id)
  const completed = await waitForJob(`${jobBase}/${submitted.id}`, job => !['QUEUED', 'RUNNING', 'CANCELLING'].includes(job.status))
  assert.equal(completed.status, 'SUCCEEDED', completed.error)
  assert.equal(completed.requestedModel, 'synthetic-smoke-model')
  assert.equal(completed.cliVersion, '0.0.0-test-fixture')
  assert.equal(completed.cliVersionStatus, 'AVAILABLE')
  assert.ok(completed.cliCheckedAt)
  assert.equal((await api(`${knowledgeBase}/${completed.resultCardId}`)).version.title, 'TEST FIXTURE 초안')
  assert.equal((await api(`${knowledgeBase}/${completed.resultCardId}`)).version.manuallyEdited, false)
  assert.ok((await api(`${knowledgeBase}/${completed.resultCardId}`)).version.markdown.includes('요청 모델: synthetic-smoke-model'))
  assert.ok((await api(`${knowledgeBase}/${completed.resultCardId}`)).version.markdown.includes(transmission.digest))
  assert.equal((await api(knowledgePath)).version.markdown, draft.version.markdown)
  const cancelledRequest = { ...generation, requestId: crypto.randomUUID() }
  const cancellable = await api(`${knowledgePath}/generation/jobs`, 'POST', cancelledRequest)
  await waitForJob(`${jobBase}/${cancellable.id}`, job => job.status === 'RUNNING' && job.cliCheckedAt)
  await api(`${jobBase}/${cancellable.id}/cancel`, 'POST')
  assert.equal((await waitForJob(`${jobBase}/${cancellable.id}`, job => job.status === 'CANCELLED')).resultCardId, null)
  assert.equal((await api(knowledgeBase)).length, 2)
  const snapshotPath = `${knowledgePath}/evidence/${draft.version.evidence[0].snapshotId}/content`
  const publicationPath = `${knowledgeBase}/${completed.resultCardId}`
  const review = await api(`${publicationPath}/review`, 'POST', {
    expectedVersion: 1, reviewer: 'Synthetic smoke reviewer', note: 'Synthetic fixture contract only; not semantic AI validation.',
    claimsSupported: true, uncertaintyRemoved: true,
  })
  assert.equal(review.publication, null)
  assert.equal((await api(`${publicationPath}/publication`, 'POST', { expectedVersion: 1, expectedPublicationRevision: 0 })).publication.version, 1)
  const originalJava = await readFile(resolve(sourceDirectory, 'src/Greenhouse.java'))
  assert.deepEqual(Buffer.from(await (await fetch(`${base}/api${snapshotPath}`)).arrayBuffer()), originalJava)
  await stop()
  configuredModel = 'changed-server-default-fixture'
  const restartMs = await start()
  assert.equal((await api(`${publicationPath}/publication`)).review.reviewer, 'Synthetic smoke reviewer')
  assert.equal((await api(`${publicationPath}/published`)).version.number, 1)
  await api(`${publicationPath}/versions`, 'POST', { expectedVersion: 1, title: 'Synthetic revised draft', markdown: 'Synthetic fixture [E1]' })
  assert.equal((await api(`${publicationPath}/published`)).version.number, 1)
  assert.equal((await api(`${publicationPath}/publication`)).review, null)
  assert.equal((await api(`${jobBase}/${completed.id}`)).resultCardId, completed.resultCardId)
  assert.equal((await api(`${knowledgePath}/generation/jobs`, 'POST', generation)).status, 'SUCCEEDED')
  assert.equal((await api(`${knowledgePath}/generation/jobs`, 'POST', cancelledRequest)).status, 'CANCELLED')
  assert.equal((await api(`${jobBase}/${completed.id}`)).requestedModel, 'synthetic-smoke-model')
  assert.equal((await api(`${jobBase}/${cancellable.id}`)).requestedModel, 'synthetic-smoke-model')
  assert.equal((await api(`${jobBase}/${completed.id}`)).cliVersion, '0.0.0-test-fixture')
  assert.equal((await api(`${jobBase}/${completed.id}`)).cliCheckedAt, completed.cliCheckedAt)
  assert.equal((await api(`${jobBase}/${cancellable.id}`)).cliVersion, '0.0.0-test-fixture')
  assert.equal((await api(`${knowledgePath}/generation/preview`, 'POST', { provider: 'CODEX', requestedModel: 'synthetic-smoke-model' })).digest, transmission.digest)
  assert.equal((await api(knowledgeBase)).length, 2)
  assert.equal((await api('/session')).activeWorkspaceId, workspace.id)
  assert.equal((await api(`${contentPath}/preview`)).text, bytes.toString('utf8'))
  assert.equal((await api(searchPath)).length, 1)
  assert.equal((await api(knowledgePath)).version.markdown, '검토가 필요한 수동 메모')
  const edited = await api(`${knowledgePath}/versions`, 'POST', {
    expectedVersion: 1, title: '가상 실험 초안', markdown: '사람이 수정한 두 번째 초안',
  })
  assert.equal(edited.version.number, 2)
  assert.equal((await api(`${knowledgePath}/versions/1`)).version.markdown, draft.version.markdown)
  const conflict = await fetch(`${base}/api${knowledgePath}/versions`, {
    method: 'POST', headers: { 'X-Forge-Client': '1', 'Content-Type': 'application/json' },
    body: JSON.stringify({ expectedVersion: 1, title: '충돌', markdown: '덮어쓰면 안 되는 내용' }),
  })
  assert.equal(conflict.status, 409)
  const same = await api(`/workspaces/${workspace.id}/sources/${source.id}/sync`, 'POST')
  assert.equal(same.revision, synced.revision)
  assert.equal((await api(searchPath))[0].entryId, symbolHits[0].entryId)
  await rm(sourceDirectory, { recursive: true })
  const failed = await api(`/workspaces/${workspace.id}/sources/${source.id}/sync`, 'POST')
  assert.equal(failed.status, 'ERROR')
  assert.equal(failed.revision, synced.revision)
  assert.equal((await api(searchPath)).length, 1)
  await api(`/workspaces/${workspace.id}/sources/${source.id}`, 'DELETE')
  assert.equal((await api(searchPath)).length, 0)
  assert.equal((await api(knowledgePath)).version.evidence[0].freshness, 'MISSING')
  assert.equal((await api(`${publicationPath}/published`)).version.evidence[0].freshness, 'MISSING')
  assert.equal((await api(`${publicationPath}/publication`)).publication.version, 1)
  assert.deepEqual(Buffer.from(await (await fetch(`${base}/api${snapshotPath}`)).arrayBuffer()), originalJava)
  const livePublic = []
  if (process.argv.includes('--live-public')) {
    for (const registration of [
      { name: '공개 HTTPS 검증', kind: 'URL', location: 'https://example.org', domain: '검증' },
      { name: '공개 GitHub 검증', kind: 'GITHUB', location: 'https://github.com/thisisnew/knowledge-forge', domain: '검증' },
    ]) {
      const registered = await api(`/workspaces/${workspace.id}/sources`, 'POST', registration)
      const collected = await api(`/workspaces/${workspace.id}/sources/${registered.id}/sync`, 'POST')
      assert.equal(collected.status, 'READY', `${registration.kind}: ${collected.error}`)
      livePublic.push({ kind: registration.kind, location: registration.location, revision: collected.revision })
      await api(`/workspaces/${workspace.id}/sources/${registered.id}`, 'DELETE')
    }
  }
  const uploadTimes = []
  for (let index = 0; index < 100; index++) {
    const synthetic = new FormData()
    synthetic.append('file', new Blob([`가상 측정 기록 ${index}\n`.repeat(100)]), `sample-${index}.txt`)
    uploadTimes.push(await measure(() => api(`/workspaces/${workspace.id}/files`, 'POST', synthetic)))
  }
  const listPath = `/workspaces/${workspace.id}/entries`
  for (let index = 0; index < 5; index++) await api(listPath)
  const listTimes = []
  for (let index = 0; index < 50; index++) {
    listTimes.push(await measure(async () => assert.equal((await api(listPath)).length, 101)))
  }
  const searchTimes = []
  for (let index = 0; index < 30; index++) {
    searchTimes.push(await measure(async () => {
      assert.ok((await api(`/workspaces/${workspace.id}/search?query=${encodeURIComponent('측정')}`)).length > 0)
    }))
  }
  assert.deepEqual(await api(`${listPath}?area=KNOWLEDGE`), [])
  await api(`/workspaces/${workspace.id}`, 'DELETE')
  assert.equal((await api('/session')).activeWorkspaceId, null)
  await stop()
  await start()
  assert.deepEqual((await api('/session')).workspaces, [])
  const result = {
    measuredAt: new Date().toISOString(),
    platform: `${process.platform}/${process.arch}`,
    java: '21',
    springBoot: '4.1.1',
    dataset: '100 synthetic UTF-8 files (~2.5 KB each) + 1 nested fixture; sequential HTTP requests',
    checks: ['packaged UI', 'upload/download byte equality', 'UTF-8 preview', 'restart persistence',
      'active workspace persistence', 'independent knowledge area', 'cascade deletion', 'deletion persists after restart',
      'local folder collection', 'Java symbol line numbers', 'search survives restart', 'unchanged revision preserves entry ids',
      'failed collection preserves prior index', 'source deletion removes search hits', 'Korean Nori search',
      'manual knowledge draft and snapshot', 'knowledge survives restart', 'immutable prior version',
      'optimistic edit conflict', 'source deletion preserves historical evidence bytes',
      'CLI payload preview without external call', 'unapproved and mismatched AI transmission rejected',
      'fake CLI async generation and cancellation', 'idempotent job replay across restart', 'completed job and draft persist',
      'per-request model overrides server default', 'requested model history survives changed defaults and restart',
      'pre-inference CLI observation persists for completed and cancelled jobs',
      'separate human review and publication persist across restart',
      'new draft and missing originals preserve prior publication with freshness warnings'],
    sourceSyncMs,
    livePublic,
    startupMs: Math.round(startupMs),
    restartMs: Math.round(restartMs),
    upload100: { p50Ms: percentile(uploadTimes, 0.5), p95Ms: percentile(uploadTimes, 0.95) },
    list101Entries50RequestsAfter5Warmups: { p50Ms: percentile(listTimes, 0.5), p95Ms: percentile(listTimes, 0.95) },
    search100Files30Requests: { p50Ms: percentile(searchTimes, 0.5), p95Ms: percentile(searchTimes, 0.95) },
    limitations: 'Single local process, small synthetic dataset, no concurrency; AI jobs use an explicit fake CLI fixture, not real LLM performance.',
  }
  await writeFile(resolve(root, 'docs/performance-phase3-foundation.json'), JSON.stringify(result, null, 2) + '\n')
  console.log(JSON.stringify(result, null, 2))
} catch (error) {
  console.error(logs.slice(-12000))
  throw error
} finally {
  await stop()
  await rm(directory, { recursive: true, force: true })
}
