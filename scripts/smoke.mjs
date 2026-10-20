import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
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
    signal: AbortSignal.timeout(10000),
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
  await stop()
  const restartMs = await start()
  assert.equal((await api('/session')).activeWorkspaceId, workspace.id)
  assert.equal((await api(`${contentPath}/preview`)).text, bytes.toString('utf8'))
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
      'active workspace persistence', 'independent knowledge area', 'cascade deletion', 'deletion persists after restart'],
    startupMs: Math.round(startupMs),
    restartMs: Math.round(restartMs),
    upload100: { p50Ms: percentile(uploadTimes, 0.5), p95Ms: percentile(uploadTimes, 0.95) },
    list101Entries50RequestsAfter5Warmups: { p50Ms: percentile(listTimes, 0.5), p95Ms: percentile(listTimes, 0.95) },
    limitations: 'Single local process, warm filesystem cache, no concurrency; not search/LLM performance.',
  }
  await writeFile(resolve(root, 'docs/performance.json'), JSON.stringify(result, null, 2) + '\n')
  console.log(JSON.stringify(result, null, 2))
} finally {
  await stop()
  await rm(directory, { recursive: true, force: true })
}
