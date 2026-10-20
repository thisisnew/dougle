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

async function start() {
  logs = ''
  const started = performance.now()
  child = spawn('bash', ['scripts/run.sh'], {
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

try {
  const startupMs = await start()
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
  await stop()
  const restartMs = await start()
  assert.equal((await api('/session')).activeWorkspaceId, workspace.id)
  assert.equal((await api(`${contentPath}/preview`)).text, bytes.toString('utf8'))
  assert.equal((await api(searchPath)).length, 1)
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
      'failed collection preserves prior index', 'source deletion removes search hits', 'Korean Nori search'],
    sourceSyncMs,
    livePublic,
    startupMs: Math.round(startupMs),
    restartMs: Math.round(restartMs),
    upload100: { p50Ms: percentile(uploadTimes, 0.5), p95Ms: percentile(uploadTimes, 0.95) },
    list101Entries50RequestsAfter5Warmups: { p50Ms: percentile(listTimes, 0.5), p95Ms: percentile(listTimes, 0.95) },
    search100Files30Requests: { p50Ms: percentile(searchTimes, 0.5), p95Ms: percentile(searchTimes, 0.95) },
    limitations: 'Single local process, small synthetic dataset, no concurrency; not LLM performance.',
  }
  await writeFile(resolve(root, 'docs/performance-phase2.json'), JSON.stringify(result, null, 2) + '\n')
  console.log(JSON.stringify(result, null, 2))
} catch (error) {
  console.error(logs.slice(-12000))
  throw error
} finally {
  await stop()
  await rm(directory, { recursive: true, force: true })
}
