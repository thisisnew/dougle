import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { randomUUID } from 'node:crypto'
import { constants } from 'node:fs'
import { mkdtemp, chmod, writeFile, open, rm } from 'node:fs/promises'
import { join } from 'node:path'

export function validateAcknowledgment(ack, checkpoint, now = Date.now()) {
  assert.ok(now < checkpoint.expiresAt, 'UI checkpoint expired')
  assert.deepEqual(Object.keys(ack).sort(), ['checkpointId', 'artifactSha256', 'workspaceId', 'threadId', 'variant',
    'bundleIds', 'bundleDigests', 'observed', 'screenshotPath'].sort(), 'Unexpected acknowledgment fields')
  for (const key of ['checkpointId', 'artifactSha256', 'workspaceId', 'threadId', 'variant', 'bundleIds', 'bundleDigests']) {
    assert.deepEqual(ack[key], checkpoint[key], 'Stale or mismatched UI acknowledgment')
  }
  assert.equal(ack.observed, 'HISTORY_REOPENED_NO_SUBMIT')
  assert.ok(typeof ack.screenshotPath === 'string' && ack.screenshotPath.startsWith('docs/screenshots/')
    && !ack.screenshotPath.includes('..') && /^[A-Za-z0-9/_ .-]+\.png$/.test(ack.screenshotPath), 'Synthetic screenshot path required')
}

export async function createReadOnlyBridge({ backend, session, workspaceId, threadId }) {
  const target = new URL(backend)
  assert.equal(target.protocol, 'http:')
  assert.equal(target.hostname, '127.0.0.1')
  assert.match(session, /^[a-f0-9]{64}$/)
  const trace = []
  let observedHistory = false
  let observedThread = false
  let violation = false
  let origin
  const shutdown = new AbortController()
  const prefix = `/api/workspaces/${workspaceId}`
  const allowedApi = path => ['/api/session', '/api/workspaces', '/api/search-model', '/api/ai/settings', '/api/ai/providers',
    '/api/plugins'].includes(path) || path === `${prefix}/chat-threads` || path === `${prefix}/chat-threads/${threadId}`
    || path.startsWith(`${prefix}/evidence-bundles/`) && /^[-a-zA-Z0-9/]+$/.test(path)
    || path.startsWith(`${prefix}/answer-jobs`) && /^[-a-zA-Z0-9/]+$/.test(path)
    || path === `${prefix}/sources` || path === `${prefix}/entries`
    || /^\/api\/ai\/providers\/(CODEX|CLAUDE)\/connection$/.test(path)
  const server = createServer(async (request, response) => {
    const reject = (status, message) => { response.writeHead(status, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' }); response.end(message) }
    try {
      const url = new URL(request.url, origin)
      if (request.headers.host !== new URL(origin).host || url.origin !== origin
        || request.headers.origin && request.headers.origin !== origin
        || request.headers['sec-fetch-site'] === 'cross-site') return reject(403, 'Local UI origin required')
      if (trace.length >= 1000) { violation = true; return reject(429, 'UI checkpoint request limit') }
      const entry = { method: request.method, path: url.pathname, status: null }
      trace.push(entry)
      if (request.method === 'POST' && !url.search && /^\/api\/ai\/providers\/(CODEX|CLAUDE)\/connection$/.test(url.pathname)) {
        entry.status = 409
        entry.action = 'CONNECTION_CHECK_NOT_PERFORMED'
        return reject(409, 'Read-only checkpoint: connection check not performed')
      }
      if (!['GET', 'HEAD'].includes(request.method)) {
        violation = true
        entry.status = 405
        return reject(405, 'Read-only checkpoint: submission forbidden')
      }
      const api = url.pathname.startsWith('/api/')
      const entriesQuery = url.pathname === `${prefix}/entries` && url.search === '?area=ORIGINAL'
      if (url.search && !entriesQuery || api && !allowedApi(url.pathname)
        || !api && !(url.pathname === '/' || /^\/assets\/[A-Za-z0-9_.-]+$/.test(url.pathname) || /^\/[A-Za-z0-9_.-]+\.(svg|png|ico)$/.test(url.pathname))) {
        entry.status = 403
        return reject(403, 'Route outside UI checkpoint')
      }
      const upstream = await fetch(new URL(url.pathname + url.search, target), { method: request.method, redirect: 'error',
        headers: { 'X-Forge-Session': session, 'X-Forge-Client': '1' }, signal: AbortSignal.any([shutdown.signal, AbortSignal.timeout(10000)]) })
      const chunks = []
      let bytes = 0
      if (upstream.body) for await (const chunk of upstream.body) {
        bytes += chunk.length
        if (bytes > 8 * 1024 * 1024) throw Error('UI response bound')
        chunks.push(chunk)
      }
      entry.status = upstream.status
      response.writeHead(upstream.status, { 'Content-Type': upstream.headers.get('content-type') || 'application/octet-stream',
        'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer',
        'Content-Security-Policy': "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; form-action 'none'" })
      response.end(Buffer.concat(chunks))
      if (upstream.ok && request.method === 'GET') {
        if (url.pathname === `${prefix}/chat-threads`) observedHistory = true
        if (url.pathname === `${prefix}/chat-threads/${threadId}`) observedThread = true
      }
    } catch {
      violation = true
      if (!response.headersSent) reject(502, 'UI bridge request failed')
      else response.destroy()
    }
  })
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve) })
  origin = `http://127.0.0.1:${server.address().port}`
  return { origin, trace, assertNoViolation() {
    assert.equal(violation, false, 'UI attempted submission or checkpoint transport failed')
  }, verify() {
    assert.equal(violation, false, 'UI attempted submission or checkpoint transport failed')
    assert.ok(observedHistory && observedThread, 'UI history and exact thread GETs not observed')
  }, async close() {
    shutdown.abort()
    server.closeAllConnections()
    await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()))
  } }
}

export async function holdAtRestart({ ownedDirectory, binding, backend, session, timeoutMs = 900000,
  signal, notify = () => {}, snapshot, before, pollMs = 200 }) {
  assert.ok(Number.isSafeInteger(timeoutMs) && timeoutMs > 0 && timeoutMs <= 900000, 'Hold timeout must be bounded')
  const directory = await mkdtemp(join(ownedDirectory, 'ui-checkpoint-'))
  await chmod(directory, 0o700)
  let bridge
  try {
    bridge = await createReadOnlyBridge({ backend, session, workspaceId: binding.workspaceId, threadId: binding.threadId })
    const checkpoint = { ...binding, checkpointId: randomUUID(), expiresAt: Date.now() + timeoutMs }
    async function withinDeadline(promise) {
      signal?.throwIfAborted()
      let timer
      let onAbort
      try {
        return await Promise.race([promise, new Promise((resolve, reject) => {
          timer = setTimeout(() => reject(Error('UI checkpoint expired; no followup authorized')), Math.max(0, checkpoint.expiresAt - Date.now()))
          onAbort = () => reject(Error('UI checkpoint aborted'))
          signal?.addEventListener('abort', onAbort, { once: true })
        })])
      } finally {
        clearTimeout(timer)
        if (onAbort) signal?.removeEventListener('abort', onAbort)
      }
    }
    const controlPath = join(directory, 'checkpoint.json')
    const acknowledgmentPath = join(directory, 'ack.json')
    await writeFile(controlPath, JSON.stringify({ ...checkpoint, uiUrl: bridge.origin, acknowledgmentPath,
      instruction: 'Reopen exact history row, inspect prior answer/evidence, save synthetic screenshot, acknowledge without submitting.' }, null, 2), { flag: 'wx', mode: 0o600 })
    await withinDeadline(Promise.resolve().then(() => notify({ controlPath, uiUrl: bridge.origin, workspaceId: binding.workspaceId, threadId: binding.threadId, expiresAt: checkpoint.expiresAt })))
    while (Date.now() < checkpoint.expiresAt) {
      signal?.throwIfAborted()
      bridge.assertNoViolation()
      let file
      try { file = await open(acknowledgmentPath, constants.O_RDONLY | constants.O_NOFOLLOW) }
      catch (error) { if (error.code !== 'ENOENT') throw Error('Unsafe UI acknowledgment file') }
      if (file) {
        let ack
        try {
          const stat = await file.stat()
          assert.ok(stat.isFile() && stat.size <= 16384 && (stat.mode & 0o077) === 0 && stat.uid === process.getuid(), 'Private regular acknowledgment required')
          const buffer = Buffer.alloc(16385)
          const { bytesRead } = await file.read(buffer, 0, buffer.length, 0)
          assert.ok(bytesRead <= 16384, 'Acknowledgment exceeds bound')
          ack = JSON.parse(buffer.subarray(0, bytesRead).toString('utf8'))
        } finally { await file.close() }
        validateAcknowledgment(ack, checkpoint)
        bridge.verify()
        assert.deepEqual(await withinDeadline(Promise.resolve().then(snapshot)), before, 'UI reopening changed persisted state or jobs')
        validateAcknowledgment(ack, checkpoint)
        return { checkpointId: checkpoint.checkpointId, screenshotPath: ack.screenshotPath,
          trace: bridge.trace, uiReopenObserved: true, followupSubmittedBy: 'harness-after-read-only-UI-ack' }
      }
      await new Promise(resolve => setTimeout(resolve, Math.min(pollMs, Math.max(0, checkpoint.expiresAt - Date.now()))))
    }
    throw Error('UI checkpoint expired; no followup authorized')
  } finally {
    try { if (bridge) await bridge.close() }
    finally { await rm(directory, { recursive: true, force: true }) }
  }
}
