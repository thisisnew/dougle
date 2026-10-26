import assert from 'node:assert/strict'
import { execFile, spawn } from 'node:child_process'
import { createHash, randomBytes, randomUUID } from 'node:crypto'
import { once } from 'node:events'
import { constants } from 'node:fs'
import { access, chmod, lstat, mkdir, mkdtemp, open, readFile, realpath, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'
import { assertReadOnly, childEnvironment, denialCases, legacyReceipts, sandboxProfile, seedSql, startupDiagnostic } from './readonly-runtime-smoke.mjs'

export const artifactSha256 = 'e0e43e1084177ea4d28530547cc762bce339d2de2f5721d21ce70d0ef4eeb58d'
const resources = '/Applications/Knowledge Forge.app/Contents/Resources/resources'
const execute = promisify(execFile)
const pause = milliseconds => new Promise(done => setTimeout(done, milliseconds))
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex')
export const observations = ['threeExactReceiptBodies', 'historicalStatusesOnly', 'noOutgoingReplyControls', 'emptyWorkspaceNoStaleReceipt', 'returnedWorkspaceReceiptsPreserved']

export function fixtures(workspaceId) {
  const receipts = legacyReceipts(workspaceId).map(receipt => ({ ...receipt,
    body: `E35 ${receipt.status}: 합성 과거 기록입니다. 실제 Slack 전송 없음.\n원문 보존 확인 <not-html> & 'quoted'`,
  }))
  const item = { id: 'synthetic-inbox', workspaceId, event: { eventId: 'E35SYNTHETIC', teamId: 'TSYNTHETIC',
    channelId: 'CSYNTHETIC', userId: 'USYNTHETIC', timestamp: '1800000000.000001', threadTimestamp: '1800000000.000001',
    revisionTimestamp: '1800000000.000001', deleted: false, text: 'E35 isolated synthetic inbox — no Slack connection' },
  receivedAt: '2026-10-26T00:00:00Z', sentQuestion: null, sent: null }
  const literal = value => `'${value.replaceAll("'", "''")}'`
  const sql = seedSql(receipts).replace('\nCOMMIT;', `\nINSERT INTO slack_inbox_item (id,workspace_id,payload_json) VALUES (${literal(item.id)},${literal(workspaceId)},${literal(JSON.stringify(item))});\nCOMMIT;`)
  return { receipts, item, sql }
}

export function validateAcknowledgment(ack, checkpoint, now = Date.now()) {
  assert(now < checkpoint.expiresAt, 'Checkpoint expired')
  assert.deepEqual(Object.keys(ack).sort(), ['checkpointId', 'artifactSha256', 'phase', 'verdict', 'reviewer', 'observed', 'screenshotPath'].sort())
  for (const field of ['checkpointId', 'artifactSha256', 'phase']) assert.deepEqual(ack[field], checkpoint[field], 'Checkpoint binding mismatch')
  assert.equal(ack.verdict, 'PASS', 'Manual review did not pass; stop without retry')
  assert.equal(ack.reviewer, 'PARENT', 'Independent parent review required')
  assert.deepEqual(ack.observed, observations)
  assert.match(ack.screenshotPath, /^docs\/screenshots\/[A-Za-z0-9_-]+\.png$/)
}

export function allowedRequest(method, url, workspaceIds) {
  const path = url.pathname
  if (method === 'POST' && !url.search && /^\/api\/ai\/providers\/(CODEX|CLAUDE)\/connection$/.test(path)) return 'BLOCK_CONNECTION'
  if (workspaceIds.some(id => path === `/api/workspaces/${id}/active`) && method === 'PUT' && !url.search) return 'LOCAL_SWITCH'
  if (!['GET', 'HEAD'].includes(method)) return 'DENY'
  if (!url.search && (path === '/' || /^\/assets\/[A-Za-z0-9_.-]+$/.test(path) || /^\/[A-Za-z0-9_.-]+\.(svg|png|ico)$/.test(path))) return 'READ'
  if (!url.search && ['/api/session', '/api/workspaces', '/api/search-model', '/api/ai/settings', '/api/ai/providers',
    '/api/plugins', '/api/slack/status', '/api/slack/routes'].includes(path)) return 'READ'
  if (!url.search && /^\/api\/ai\/providers\/(CODEX|CLAUDE)\/connection$/.test(path)) return 'READ'
  for (const id of workspaceIds) {
    const workspace = `/api/workspaces/${id}`
    const slack = `/api/slack/workspaces/${id}`
    if (!url.search && [workspace + '/chat-threads', workspace + '/sources', workspace + '/source-polling', slack + '/inbox', slack + '/inbox-count', `/api/plugins/datadog/${id}`].includes(path)) return 'READ'
    if (path === workspace + '/entries' && url.search === '?area=ORIGINAL') return 'READ'
    if (path === slack + '/replies' && url.search === '?inboxItemId=synthetic-inbox') return 'READ'
    if (!url.search && ['preview', 'sent', 'uncertain'].some(status => path === slack + '/replies/synthetic-legacy-' + status)) return 'READ'
  }
  return 'DENY'
}

export async function createBridge({ backend, session, workspaceIds }) {
  const target = new URL(backend)
  assert.equal(target.protocol, 'http:'); assert.equal(target.hostname, '127.0.0.1')
  assert(!target.username && !target.password && !target.search && !target.hash)
  assert.match(session, /^[a-f0-9]{64}$/)
  assert.equal(workspaceIds.length, 2); assert.equal(new Set(workspaceIds).size, 2)
  workspaceIds.forEach(id => assert.match(id, /^[A-Za-z0-9_-]{1,128}$/))
  const trace = []
  const shutdown = new AbortController()
  let origin, violation = false
  const server = createServer(async (request, response) => {
    const reject = (status, detail) => { response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); response.end(JSON.stringify({ detail })) }
    try {
      const url = new URL(request.url, origin)
      if (request.headers.host !== new URL(origin).host || url.origin !== origin
        || request.headers.origin && request.headers.origin !== origin || request.headers['sec-fetch-site'] === 'cross-site') return reject(403, 'Local origin required')
      if (trace.length >= 2000) { violation = true; return reject(429, 'Request bound exceeded') }
      const action = allowedRequest(request.method, url, workspaceIds)
      const entry = { method: request.method, path: url.pathname, action, status: null }
      trace.push(entry)
      if (action === 'BLOCK_CONNECTION') { entry.status = 409; return reject(409, '격리 검사: AI 연결 확인을 실행하지 않습니다.') }
      if (action === 'DENY' || request.headers['transfer-encoding'] || Number(request.headers['content-length'] ?? 0) !== 0) {
        violation = true; entry.status = 403; return reject(403, 'Receipt-only checkpoint: request forbidden')
      }
      const upstream = await fetch(new URL(url.pathname + url.search, target), { method: request.method, redirect: 'error',
        headers: { 'X-Forge-Session': session, 'X-Forge-Client': '1', Origin: target.origin },
        signal: AbortSignal.any([shutdown.signal, AbortSignal.timeout(10000)]) })
      const chunks = []; let bytes = 0
      if (upstream.body) for await (const chunk of upstream.body) {
        bytes += chunk.length; assert(bytes <= 8 * 1024 * 1024, 'Response bound exceeded'); chunks.push(chunk)
      }
      entry.status = upstream.status
      response.writeHead(upstream.status, { 'Content-Type': upstream.headers.get('content-type') || 'application/octet-stream',
        'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer',
        'Content-Security-Policy': "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; form-action 'none'; base-uri 'none'; object-src 'none'" })
      response.end(Buffer.concat(chunks))
    } catch {
      if (!shutdown.signal.aborted) violation = true
      if (!response.headersSent) reject(502, 'Isolated UI bridge failed'); else response.destroy()
    }
  })
  server.requestTimeout = 15000; server.headersTimeout = 10000
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve) })
  origin = `http://127.0.0.1:${server.address().port}`
  return { origin, trace, check() { assert(!violation, 'UI attempted forbidden request or transport failed') }, verify() {
    this.check()
    const good = (method, path) => trace.some(entry => entry.method === method && entry.path === path && entry.status >= 200 && entry.status < 300)
    for (const id of workspaceIds) {
      assert(good('GET', `/api/slack/workspaces/${id}/inbox`), 'Both workspace inboxes must be inspected')
      assert(good('PUT', `/api/workspaces/${id}/active`), 'Workspace switch and return required')
    }
    for (const status of ['preview', 'sent', 'uncertain']) assert(good('GET', `/api/slack/workspaces/${workspaceIds[0]}/replies/synthetic-legacy-${status}`), 'Each receipt must be explicitly refreshed')
  }, async close() { shutdown.abort(); server.closeAllConnections(); await new Promise(done => server.close(done)) } }
}

async function checkpoint({ directory, phase, bridge, workspaceIds, receipts, aborted }) {
  const control = { checkpointId: randomUUID(), artifactSha256, phase, expiresAt: Date.now() + 900000,
    uiUrl: bridge.origin, workspaceIds, receipts: receipts.map(({ id, body, status }) => ({ id, body, status })),
    acknowledgmentPath: join(directory, `ack-${phase}.json`),
    instruction: 'Parent: inspect actual bundled React in Ego. Settings > Slack, open historical receipts, inspect exact three bodies/statuses and refresh each. No outgoing reply controls. Switch E35 receipts -> E35 empty (no stale receipt) -> E35 receipts; reopen and confirm preserved. Screenshot. Never connect Slack, submit questions, or invoke AI. ACK only after manual review.',
    acknowledgmentTemplate: { checkpointId: null, artifactSha256, phase, verdict: 'PASS', reviewer: 'PARENT', observed: observations, screenshotPath: `docs/screenshots/e35-${phase}.png` } }
  control.acknowledgmentTemplate.checkpointId = control.checkpointId
  const controlPath = join(directory, `checkpoint-${phase}.json`)
  await writeFile(controlPath, JSON.stringify(control, null, 2), { flag: 'wx', mode: 0o600 })
  console.log(JSON.stringify({ event: 'WAIT_E35_PARENT_UI_REVIEW', controlPath, base: bridge.origin, phase, runnerPid: process.pid, expiresAt: control.expiresAt }))
  while (Date.now() < control.expiresAt) {
    assert(!aborted(), 'Interrupted'); bridge.check()
    let file
    try { file = await open(control.acknowledgmentPath, constants.O_RDONLY | constants.O_NOFOLLOW) }
    catch (error) { if (error.code !== 'ENOENT') throw error }
    if (file) {
      let ack
      try {
        const stat = await file.stat()
        assert(stat.isFile() && stat.uid === process.getuid() && (stat.mode & 0o077) === 0 && stat.size <= 16384)
        const buffer = Buffer.alloc(16385); const { bytesRead } = await file.read(buffer, 0, buffer.length, 0)
        assert(bytesRead <= 16384); ack = JSON.parse(buffer.subarray(0, bytesRead).toString('utf8'))
      } finally { await file.close() }
      validateAcknowledgment(ack, control); bridge.verify()
      return { ...ack, trace: bridge.trace, reviewedAt: new Date().toISOString() }
    }
    await pause(200)
  }
  throw Error('Manual UI checkpoint expired; no automatic approval')
}

export async function run() {
  const config = { jar: join(resources, 'knowledge-forge.jar'), java: join(resources, 'runtime/bin/java'), hash: artifactSha256 }
  const report = { version: 1, criterion: 'E35', result: 'INCOMPLETE', mode: 'manual', artifactSha256,
    startedAt: new Date().toISOString(), checkpoints: [], backendPids: [], externalBusinessCallsAuthorized: 0, actualAiCalls: 0,
    limits: 'Synthetic legacy receipts, including SENT; no actual Slack account/send. Bundled React through a restricted loopback bridge, not native WebKit. No whole-criterion promotion. Direct internal dispatch requires separate source/test evidence. Proxy count cannot count blocked transport attempts.' }
  const reportPath = resolve('docs/evaluations/e35-isolated-ui-20261026.json')
  await access(reportPath).then(() => { throw Error('Evidence already exists; do not overwrite') }, error => { if (error.code !== 'ENOENT') throw error })
  let bridge

  assert.equal(process.platform, 'darwin', 'This offline harness requires macOS sandbox-exec; no unsandboxed fallback')
  for (const executable of [config.java, '/usr/bin/sandbox-exec', '/usr/bin/sqlite3']) await access(executable)
  const jar = await realpath(config.jar)
  assert.equal(sha256(await readFile(jar)), config.hash, 'Artifact hash mismatch')
  const temporary = await mkdtemp(join(tmpdir(), 'dougle-e35-ui-'))
  await chmod(temporary, 0o700)
  const home = join(temporary, 'home')
  const data = join(temporary, 'data')
  const database = join(data, 'knowledge-forge.sqlite')
  await mkdir(home, { mode: 0o700 }); await mkdir(data, { mode: 0o700 })
  let child, base, token, spawnError
  let startupOutput = ''
  let aborted = false
  let proxyRequests = 0
  let deniedRequests = 0
  let restarts = 0
  const proxy = createServer((request, response) => { proxyRequests++; response.writeHead(502); response.end() })
  proxy.on('connect', (request, socket) => { proxyRequests++; socket.end('HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n') })
  const interrupt = () => { aborted = true }
  process.on('SIGINT', interrupt); process.on('SIGTERM', interrupt)
  const alive = () => Boolean(child?.pid) && child.exitCode === null && child.signalCode === null
  function killOwned(signal) {
    if (alive() && child.pid) {
      try { process.kill(-child.pid, signal) } catch (error) { if (error.code !== 'ESRCH') throw error }
    }
  }
  async function stop() {
    if (!alive()) return
    const ended = once(child, 'exit')
    killOwned('SIGTERM')
    const timer = setTimeout(() => killOwned('SIGKILL'), 15000)
    let deadline
    try { await Promise.race([ended, new Promise((resolve, reject) => {
      deadline = setTimeout(() => reject(new Error('Owned backend did not exit; preserving isolated directory')), 20000)
    })]) }
    finally { clearTimeout(timer); clearTimeout(deadline) }
    assert(!alive(), 'Backend must be closed before fixture DB access')
  }
  async function start() {
    assert(!alive())
    assert.equal(sha256(await readFile(jar)), config.hash, 'JAR changed during smoke')
    base = undefined; spawnError = undefined; startupOutput = ''; token = randomBytes(32).toString('hex')
    const proxyPort = proxy.address().port
    child = spawn('/usr/bin/sandbox-exec', ['-p', sandboxProfile, config.java,
      '-Djava.net.preferIPv4Stack=true',
      `-Duser.home=${home}`, `-Djava.io.tmpdir=${temporary}`, '-Dhttp.proxyHost=127.0.0.1', `-Dhttp.proxyPort=${proxyPort}`,
      '-Dhttps.proxyHost=127.0.0.1', `-Dhttps.proxyPort=${proxyPort}`, '-Dhttp.nonProxyHosts=localhost|127.*|[::1]',
      '-jar', jar, '--server.port=0', '--server.address=127.0.0.1', '--forge.desktop=true', `--forge.data-dir=${data}`,
      `--forge.search-model-directory=${join(temporary, 'missing-models')}`,
      `--forge.ai.codex-command=${join(temporary, 'disabled-codex')}`,
      `--forge.ai.claude-command=${join(temporary, 'disabled-claude')}`,
      `--forge.ai.codex-stream-home=${join(temporary, 'isolated-codex')}`],
    { cwd: temporary, detached: true, env: childEnvironment(home, data), stdio: ['pipe', 'pipe', 'pipe'] })
    if (child.pid) report.backendPids.push(child.pid)
    child.on('error', error => { spawnError = error })
    child.stdin.on('error', error => { spawnError = error })
    for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => {
      startupOutput = (startupOutput + chunk).slice(-16000)
    })
    let ready = ''
    child.stdout.on('data', chunk => {
      ready = (ready + chunk).slice(-16000)
      const match = ready.match(/^FORGE_DESKTOP_READY:(\d+)\r?\n/m)
      if (match && Number(match[1]) > 0 && Number(match[1]) <= 65535) base = `http://127.0.0.1:${match[1]}/api`
    })
    child.stdin.write(`${token}\n`)
    const deadline = Date.now() + 90000
    while (Date.now() < deadline) {
      if (aborted || spawnError || !alive()) throw new Error('Backend startup failed/aborted: ' + startupDiagnostic(startupOutput, token))
      if (base) {
        try { if ((await request('/session')).status === 200) return } catch {}
      }
      await pause(250)
    }
    throw new Error('Isolated backend startup deadline exceeded: ' + startupDiagnostic(startupOutput, token))
  }
  async function request(path, method = 'GET', body) {
    assert(!aborted && base && path.startsWith('/') && !path.startsWith('//'))
    const response = await fetch(`${base}${path}`, { method, redirect: 'error', signal: AbortSignal.timeout(10000),
      headers: { 'X-Forge-Session': token, 'X-Forge-Client': '1', Origin: new URL(base).origin, 'Content-Type': 'application/json' }, body })
    const text = await response.text()
    assert(text.length <= 128000, 'Unexpected response size')
    return { status: response.status, body: text ? JSON.parse(text) : null }
  }
  async function db(sql) {
    assert(!alive(), 'Never open SQLite while backend is live')
    const info = await lstat(database)
    assert(info.isFile() && !info.isSymbolicLink())
    assert.equal(await realpath(database), join(await realpath(data), 'knowledge-forge.sqlite'))
    const result = await execute('/usr/bin/sqlite3', ['-batch', '-bail', '-json', database, sql], { timeout: 5000, maxBuffer: 128000 })
    return result.stdout.trim() ? JSON.parse(result.stdout) : null
  }
  async function denyAll(workspaceId, receiptId = 'synthetic-missing-receipt') {
    const cases = denialCases(workspaceId, receiptId, 'synthetic-repeated-request', 'a'.repeat(64))
    for (let repeat = 0; repeat < 2; repeat++) for (const testCase of cases) {
      const result = await request(testCase.path, 'POST', testCase.body)
      assertReadOnly(result.status, result.body); deniedRequests++
    }
  }
  async function idle(workspaceId) {
    for (const suffix of ['answer-jobs', 'generation-jobs']) {
      const response = await request(`/workspaces/${workspaceId}/${suffix}`)
      assert.equal(response.status, 200); assert.deepEqual(response.body, [])
    }
  }
  async function history(workspaceId, receipts) {
    const prefix = `/slack/workspaces/${workspaceId}/replies`
    const listed = await request(`${prefix}?inboxItemId=synthetic-inbox`)
    assert.equal(listed.status, 200); assert.equal(listed.body.length, receipts.length)
    for (const receipt of receipts) {
      const fetched = await request(`${prefix}/${receipt.id}`)
      assert.equal(fetched.status, 200)
      const view = fetched.body
      assert.equal(view.body, receipt.body); assert.equal(view.status, receipt.status)
      assert.equal(view.digest, receipt.digest); assert.equal(view.dispatchCount, receipt.dispatch_count)
      assert.equal(view.failureCode, receipt.failure_code); assert.equal(view.updatedAt, receipt.updated_at)
      assert.deepEqual(listed.body.find(item => item.id === receipt.id), view)
    }
    return listed.body
  }
  try {
    proxy.listen(0, '127.0.0.1'); await once(proxy, 'listening')
    await start()
    const workspaceIds = []
    for (const name of ['E35 receipts', 'E35 empty']) {
      const created = await request('/workspaces', 'POST', JSON.stringify({ name }))
      assert.equal(created.status, 201); workspaceIds.push(created.body.id)
    }
    assert.equal((await request(`/workspaces/${workspaceIds[0]}/active`, 'PUT')).status, 204)
    await stop()
    const fixture = fixtures(workspaceIds[0])
    await db(fixture.sql)
    const before = await db('SELECT * FROM slack_reply ORDER BY id;')
    const inboxBefore = await db('SELECT * FROM slack_inbox_item ORDER BY id;')
    for (const phase of ['initial', 'restart']) {
      await start(); restarts++
      assert.equal((await request(`/workspaces/${workspaceIds[0]}/active`, 'PUT')).status, 204)
      await history(workspaceIds[0], fixture.receipts)
      await history(workspaceIds[1], [])
      for (const receipt of fixture.receipts) await denyAll(workspaceIds[0], receipt.id)
      await denyAll('synthetic-missing-workspace')
      await idle(workspaceIds[0]); await idle(workspaceIds[1])
      bridge = await createBridge({ backend: new URL(base).origin, session: token, workspaceIds })
      report.checkpoints.push(await checkpoint({ directory: temporary, phase, bridge, workspaceIds, receipts: fixture.receipts, aborted: () => aborted }))
      await bridge.close(); bridge = undefined
      await history(workspaceIds[0], fixture.receipts)
      await stop()
      assert.deepEqual(await db('SELECT * FROM slack_reply ORDER BY id;'), before)
      assert.deepEqual(await db('SELECT * FROM slack_inbox_item ORDER BY id;'), inboxBefore)
      for (const table of ['answer_job', 'generation_job', 'investigation_plan']) {
        assert.deepEqual(await db(`SELECT count(*) AS total FROM ${table};`), [{ total: 0 }])
      }
    }
    assert.equal(proxyRequests, 0, 'Unexpected rejecting proxy traffic')
    report.result = 'SCOPED_CHECKS_COMPLETED_PENDING_ACCEPTANCE_REVIEW'
    report.receiptRowsUnchanged = true
    report.inboxRowsUnchanged = true
    report.jobRows = 0
  } catch (error) {
    report.result = 'FAILED_OR_INCOMPLETE'
    report.failure = String(error.message).replaceAll(token ?? 'NO_SESSION', '[SESSION]').slice(0, 1000)
    throw error
  } finally {
    try { if (bridge) await bridge.close(); await stop() }
    finally {
      process.off('SIGINT', interrupt); process.off('SIGTERM', interrupt)
      proxy.closeAllConnections(); if (proxy.listening) await new Promise(done => proxy.close(done))
      report.deniedRequests = deniedRequests; report.restarts = restarts; report.observedProxyRequests = proxyRequests
      report.networkPolicy = 'macOS sandbox loopback-only; rejecting HTTP(S) proxy; UI CSP self-only; bridge narrow route allowlist'
      report.jarUnchanged = sha256(await readFile(jar)) === config.hash
      report.ownedBackendStopped = !alive()
      if (!alive()) await rm(temporary, { recursive: true, force: true })
      report.ownedTemporaryRemoved = await access(temporary).then(() => false, error => error.code === 'ENOENT')
      report.finishedAt = new Date().toISOString()
      report.harnessSha256 = sha256(await readFile(fileURLToPath(import.meta.url)))
      report.sharedHelperSha256 = sha256(await readFile(new URL('./readonly-runtime-smoke.mjs', import.meta.url)))
      await writeFile(reportPath, JSON.stringify(report, null, 2) + '\n', { flag: 'wx', mode: 0o600 })
      console.log(JSON.stringify({ event: 'E35_FINISHED', reportPath, result: report.result,
        ownedBackendStopped: report.ownedBackendStopped, ownedTemporaryRemoved: report.ownedTemporaryRemoved, jarUnchanged: report.jarUnchanged }))
    }
  }
  return report
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    assert.deepEqual(process.argv.slice(2), ['--run-approved-isolated-ui'], 'Explicit isolated UI opt-in required')
    await run()
  } catch (error) { console.error(error.message); process.exitCode = 1 }
}
