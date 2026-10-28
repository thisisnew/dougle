import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createHash, randomBytes } from 'node:crypto'
import { once } from 'node:events'
import { chmod, copyFile, mkdir, mkdtemp, readFile, realpath, writeFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { dirname, isAbsolute, join } from 'node:path'
import { Readable } from 'node:stream'
import { fileURLToPath } from 'node:url'
import { sandboxProfile, boundedPoll } from './e50-archive-fixtures.mjs'

export const artifactSha256 = 'f88d6f01b115c5951a98c35dd6723b447c4bce1da03843666a8ad5f64f368093'
export const candidateArtifactSha256 = 'b647ff9815037ddef3780246d712a944d4534a43060f881cdcbab8c2e6f3a612'
export function artifactOptions(args) {
  if (args.length === 0) return {
    jar: '/Applications/Dougle.app/Contents/Resources/resources/dougle.jar', sha256: artifactSha256,
  }
  assert.equal(args.length, 4, 'Candidate requires --jar and --sha256 together')
  const options = {}
  for (let index = 0; index < args.length; index += 2) {
    const name = args[index]
    assert(['--jar', '--sha256'].includes(name) && !Object.hasOwn(options, name), 'Unknown or duplicate option')
    options[name] = args[index + 1]
  }
  assert(typeof options['--jar'] === 'string' && isAbsolute(options['--jar'])
    && !/[\r\n\0]/.test(options['--jar']) && options['--jar'].endsWith('.jar'), 'Explicit absolute JAR path required')
  assert.match(options['--sha256'], /^[a-f0-9]{64}$/, 'Explicit SHA256 required')
  assert([artifactSha256, candidateArtifactSha256].includes(options['--sha256']), 'Artifact is not an approved .26/.27 snapshot')
  return { jar: options['--jar'], sha256: options['--sha256'] }
}

export function counts(text) {
  const lines = text.trim() ? text.trim().split('\n') : []
  assert(lines.every(line => ['plan', 'answer'].includes(line)))
  return { plan: lines.filter(line => line === 'plan').length, answer: lines.filter(line => line === 'answer').length }
}

export function allowed(method, path, workspaceId) {
  if (method === 'GET') return path === '/' || /^\/assets\/[\w.-]+$/.test(path)
    || /^\/[\w.-]+\.(svg|ico|png)$/.test(path)
    || /^\/api\/(session|workspaces|search-model|plugins|slack\/status|slack\/routes|ai\/settings|ai\/providers)$/.test(path)
    || /^\/api\/ai\/providers\/CODEX\/connection$/.test(path)
    || path.startsWith(`/api/workspaces/${workspaceId}/`)
    || path === `/api/slack/workspaces/${workspaceId}/inbox-count`
  if (method === 'POST' && path === '/api/ai/providers/CODEX/connection') return true
  if (method === 'PUT' && path === `/api/workspaces/${workspaceId}/active`) return true
  if (method !== 'POST') return false
  const prefix = `/api/workspaces/${workspaceId}/`
  if (!path.startsWith(prefix)) return false
  return /^(chat-threads|investigation-plans\/preview|investigation-plans\/jobs|investigation-plans\/jobs\/[\w-]+\/(capture|cancel)|evidence-bundles\/[\w-]+\/(answer-preview|answer-approval|answer-jobs)|evidence-bundles\/[\w-]+\/answer-jobs\/[\w-]+\/cancel)$/.test(path.slice(prefix.length))
}

export async function run() {
  assert.equal(process.platform, 'darwin', 'macOS strict sandbox required')
  const artifact = artifactOptions(process.argv.slice(2))
  const resources = '/Applications/Dougle.app/Contents/Resources/resources'
  const java = await realpath(join(resources, 'runtime/bin/java'))
  const models = await realpath(join(resources, 'search-model-e5-v1'))
  const source = await readFile(artifact.jar)
  assert.equal(createHash('sha256').update(source).digest('hex'), artifact.sha256, 'Frozen artifact mismatch')
  const directory = await realpath(await mkdtemp(join(tmpdir(), 'dougle-conversation-ui-')))
  await chmod(directory, 0o700)
  const home = join(directory, 'home'), data = join(directory, 'data'), scratch = join(directory, 'scratch')
  for (const path of [home, data, scratch]) await mkdir(path, { mode: 0o700 })
  const jar = join(directory, 'frozen.jar'), fixture = join(directory, 'fake-conversation-codex.sh')
  await writeFile(jar, source, { mode: 0o600 })
  await copyFile(fileURLToPath(new URL('./fixtures/fake-conversation-codex.sh', import.meta.url)), fixture)
  await chmod(fixture, 0o700)
  await writeFile(join(directory, 'conversation-fixture-only'), 'Synthetic only; no real credentials or AI.\n', { mode: 0o600 })
  let child, backend, bridge, origin, workspaceId, stopping = false, restarting = false
  const session = randomBytes(32).toString('hex')
  const trace = []
  const readCounts = async () => counts(await readFile(join(directory, 'generation.calls'), 'utf8').catch(error => {
    if (error.code === 'ENOENT') return ''; throw error
  }))
  async function api(path, method = 'GET', body) {
    const multipart = body instanceof FormData
    const response = await fetch(backend + '/api' + path, { method, redirect: 'error',
      headers: { 'X-Forge-Session': session, 'X-Forge-Client': '1', Origin: backend,
        ...(!multipart && body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
      body: multipart ? body : body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000) })
    assert(response.ok, 'Synthetic setup HTTP failure: ' + response.status)
    return response.json()
  }
  async function stopBackend() {
    if (!child || child.exitCode !== null || child.signalCode !== null) return
    const ended = once(child, 'exit')
    process.kill(-child.pid, 'SIGTERM')
    const timer = setTimeout(() => { try { process.kill(-child.pid, 'SIGKILL') } catch {} }, 10000)
    try { await ended } finally { clearTimeout(timer) }
  }
  async function startBackend() {
    backend = undefined
    let output = '', failure = false
    child = spawn('/usr/bin/sandbox-exec', ['-p', sandboxProfile(directory, dirname(dirname(java)), models), java,
      '-Djava.net.preferIPv4Stack=true', '-Duser.home=' + home, '-Djava.io.tmpdir=' + scratch, '-jar', jar,
      '--server.port=0', '--server.address=127.0.0.1', '--forge.desktop=true', '--forge.data-dir=' + data,
      '--forge.search-model-directory=' + models, '--forge.ai.codex-command=' + fixture,
      '--forge.ai.codex-model=conversation-fixture', '--forge.ai.codex-stream-home=', '--forge.ai.claude-command=/usr/bin/false'],
    { cwd: directory, detached: true, stdio: ['pipe', 'pipe', 'pipe'],
      env: { PATH: '/usr/bin:/bin:/usr/sbin:/sbin', HOME: home, TMPDIR: scratch, LANG: 'en_US.UTF-8', FORGE_DATA_DIR: data } })
    child.on('error', () => { failure = true })
    child.stdin.on('error', () => { failure = true })
    child.stderr.on('data', () => {})
    child.stdout.on('data', chunk => {
      output = (output + chunk).slice(-16000)
      const match = output.match(/^FORGE_DESKTOP_READY:(\d+)\r?\n/m)
      if (match) backend = 'http://127.0.0.1:' + Number(match[1])
    })
    child.stdin.write(session + '\n')
    await boundedPoll(async () => {
      assert(!stopping && !failure && child.exitCode === null && child.signalCode === null, 'Isolated backend startup failed')
      if (!backend) return false
      return (await api('/search-model')).state === 'READY'
    })
    assert.equal((await api('/ai/providers/CODEX/connection', 'POST')).state, 'AUTHENTICATED', 'Synthetic CLI protocol mismatch')
  }
  async function cleanup() {
    if (stopping) return
    stopping = true
    if (bridge) { bridge.closeAllConnections(); await new Promise(done => bridge.close(done)) }
    await stopBackend()
    await writeFile(join(directory, 'observations.json'), JSON.stringify({ artifactSha256: artifact.sha256, counts: await readCounts(), trace, cleanedUp: true }, null, 2), { mode: 0o600 })
    console.log(JSON.stringify({ event: 'STOPPED', runnerPid: process.pid, directory, activeChildren: 0 }))
  }
  const onStop = () => { cleanup().catch(() => { process.exitCode = 1 }) }
  process.once('SIGINT', onStop); process.once('SIGTERM', onStop)
  let expiry
  try {
    await startBackend()
    workspaceId = (await api('/workspaces', 'POST', { name: '합성 대화 검증' })).id
    const form = new FormData()
    form.set('file', new Blob(['Synthetic Strategy delegates hand selection to Strategy.\nThis is an isolated synthetic conversation fixture, not company data.\n']), 'Strategy.txt')
    await api(`/workspaces/${workspaceId}/files`, 'POST', form)
    assert.deepEqual(await readCounts(), { plan: 0, answer: 0 })
    bridge = createServer(async (request, response) => {
      const deny = status => { response.writeHead(status); response.end() }
      try {
        const url = new URL(request.url, origin)
        if (request.headers.host !== new URL(origin).host || url.origin !== origin
          || request.headers.origin && request.headers.origin !== origin || request.headers['sec-fetch-site'] === 'cross-site') return deny(403)
        if (url.pathname === '/__fixture/counts' && request.method === 'GET') {
          response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
          return response.end(JSON.stringify(await readCounts()))
        }
        if (stopping || restarting || !allowed(request.method, url.pathname, workspaceId) || trace.length >= 5000) return deny(403)
        const chunks = []; let bytes = 0
        for await (const chunk of request) { bytes += chunk.length; assert(bytes <= 65536); chunks.push(chunk) }
        const upstream = await fetch(backend + url.pathname + url.search, { method: request.method, redirect: 'error',
          headers: { 'X-Forge-Session': session, 'X-Forge-Client': '1', Origin: backend,
            ...(request.headers['content-type'] ? { 'Content-Type': request.headers['content-type'] } : {}) },
          body: chunks.length ? Buffer.concat(chunks) : undefined, signal: AbortSignal.timeout(120000) })
        trace.push({ method: request.method, path: url.pathname, status: upstream.status })
        response.writeHead(upstream.status, { 'Content-Type': upstream.headers.get('content-type') || 'application/octet-stream',
          'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer',
          'Content-Security-Policy': "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; object-src 'none'" })
        if (!upstream.body) return response.end()
        const stream = Readable.fromWeb(upstream.body)
        response.on('close', () => stream.destroy())
        stream.on('error', () => response.destroy())
        stream.pipe(response)
      } catch { if (!response.headersSent) deny(502); else response.destroy() }
    })
    await new Promise(done => bridge.listen(0, '127.0.0.1', done))
    origin = 'http://127.0.0.1:' + bridge.address().port
    const ready = { event: 'READY', base: origin, backendPort: new URL(backend).port, directory, fixture,
      runnerPid: process.pid, backendPid: child.pid, workspaceId, artifactSha256: artifact.sha256,
      countsUrl: origin + '/__fixture/counts', configuredModel: 'conversation-fixture', uiModel: 'gpt-6.1-sol',
      instructions: 'Parent Ego only: submit Strategy, await answer (1 plan/1 answer). Submit Strategy followup (2/2). Open history/reopen: counts unchanged. SIGUSR1 runner explicitly restarts backend; reload/reopen without submit: counts unchanged. SIGTERM runner cleans up. No actual AI; CLI auth is synthetic. Raw planner capture Bundle, not operational attachment receipt. Temp evidence preserved.' }
    await writeFile(join(directory, 'ready.json'), JSON.stringify(ready, null, 2), { mode: 0o600 })
    console.log(JSON.stringify(ready))
    process.on('SIGUSR1', async () => {
      if (restarting || stopping) return
      restarting = true
      try {
        await stopBackend(); await startBackend()
        console.log(JSON.stringify({ event: 'RESTARTED', base: origin, backendPid: child.pid, counts: await readCounts() }))
      } catch { console.error('RESTART_FAILED; no automatic retry'); await cleanup() }
      finally { restarting = false }
    })
    expiry = setTimeout(onStop, 1800000)
    await new Promise(done => bridge.once('close', done))
  } finally {
    clearTimeout(expiry)
    await cleanup()
    process.removeListener('SIGINT', onStop); process.removeListener('SIGTERM', onStop)
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) run().catch(() => { console.error('CONVERSATION_FIXTURE_FAILED; no automatic retry; no raw logs exposed'); process.exitCode = 1 })
