import assert from 'node:assert/strict'
import { execFile, spawn } from 'node:child_process'
import { createHash, randomBytes, randomUUID } from 'node:crypto'
import { once } from 'node:events'
import { access, chmod, lstat, mkdir, mkdtemp, readFile, realpath, rm } from 'node:fs/promises'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { isAbsolute, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'

export const obsoleteJar = '675a7c59e13edc8ee849b23977699e600331555d8f53c669ef8e7f10406e3489'
export const fixtureVersion = 'readonly-slack-receipts-v1'
export const sandboxProfile = '(version 1) (allow default) (deny network*) (allow network-bind (local ip "localhost:*")) (allow network-inbound (local ip "localhost:*")) (allow network-outbound (remote ip "localhost:*"))'
export function childEnvironment(home, data) {
  assert(isAbsolute(home) && isAbsolute(data))
  return { HOME: home, FORGE_DATA_DIR: data, PATH: '/usr/bin:/bin:/usr/sbin:/sbin', LANG: 'en_US.UTF-8' }
}
const execute = promisify(execFile)
const pause = milliseconds => new Promise(done => setTimeout(done, milliseconds))
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex')

export function startupDiagnostic(output, sessionToken) {
  return output.slice(-16000).split(/\r?\n/)
    .filter(line => /sandbox-exec:|sandbox_init|sandbox_apply|Operation not permitted|Permission denied|Exception|Error:|APPLICATION FAILED|Caused by:|sun\.nio\.ch\.Net\.(bind|listen|socket)|sun\.nio\.ch\.ServerSocketChannelImpl\.(bind|netBind)|java\.net\.PlainSocketImpl\.socketBind/.test(line))
    .slice(-8).map(line => line.replaceAll(sessionToken, '[REDACTED_SESSION]')
      .replace(/(?:Bearer|Basic)\s+\S+/gi, '[REDACTED_AUTH]')
      .replace(/\b(?:xox[baprs]-|xapp-|sk-)[A-Za-z0-9_-]+/g, '[REDACTED_TOKEN]').slice(0, 400)).join('\n')
    || 'No recognized startup diagnostic; raw application output withheld'
}

export function options(args) {
  assert.equal(args[0], '--run-approved-artifact', 'Explicit new-artifact approval flag required')
  assert.equal(args.length, 7, 'Usage: --run-approved-artifact --jar ABSOLUTE_JAR --sha256 HASH --java ABSOLUTE_JAVA')
  assert.equal(args[1], '--jar'); assert.equal(args[3], '--sha256'); assert.equal(args[5], '--java')
  assert(isAbsolute(args[2]) && isAbsolute(args[6]), 'Absolute JAR and Java paths required')
  assert.match(args[4], /^[a-f0-9]{64}$/)
  assert.notEqual(args[4], obsoleteJar, 'Obsolete pre-read-only .21 JAR is blocked')
  return { jar: args[2], hash: args[4], java: args[6] }
}

export function denialCases(workspaceId, receiptId, requestId, digest) {
  for (const identifier of [workspaceId, receiptId, requestId]) assert.match(identifier, /^[A-Za-z0-9_-]{1,128}$/)
  assert.match(digest, /^[a-f0-9]{64}$/)
  const prefix = `/slack/workspaces/${workspaceId}/replies`
  return [
    { path: `${prefix}/preview`, body: JSON.stringify({ requestId, inboxItemId: 'synthetic-inbox', body: 'Synthetic never-posted reply' }) },
    { path: `${prefix}/${receiptId}/execute`, body: JSON.stringify({ requestId, digest, approved: true }) },
    { path: `${prefix}/${receiptId}/acknowledge-uncertain`, body: '{}' },
  ].flatMap(candidate => [candidate, { ...candidate, body: '{invalid-json' }, { ...candidate, body: undefined },
    { ...candidate, body: JSON.stringify({ requestId, digest, approved: true }) },
    { ...candidate, body: JSON.stringify({ requestId: [], approved: true, digest: { malformed: true } }) }])
}

export function assertReadOnly(status, body) {
  assert.equal(status, 403, 'Must deny before body parsing or receipt lookup')
  assert.match(body?.detail ?? '', /^READ_ONLY:/)
}

export function legacyReceipts(workspaceId) {
  assert.match(workspaceId, /^[A-Za-z0-9_-]{1,128}$/)
  const now = '2026-10-26T00:00:00Z'
  return ['PREVIEW', 'SENT', 'UNCERTAIN'].map((status, index) => ({
    id: `synthetic-legacy-${status.toLowerCase()}`, workspace_id: workspaceId, inbox_item_id: 'synthetic-inbox',
    preview_request_id: `synthetic-preview-${index}`, scope_hash: 'b'.repeat(64),
    scope_json: JSON.stringify({ workspaceId, inboxItemId: 'synthetic-inbox', sourceDigest: 'c'.repeat(64),
      teamId: 'TSYNTHETIC', channelId: 'CSYNTHETIC', threadTimestamp: '1800000000.000001',
      botUserId: 'USYNTHETIC', connectionId: 'synthetic-offline-connection' }),
    body: 'Synthetic legacy receipt; no Slack operation was performed', digest: 'a'.repeat(64), status,
    execute_request_id: index === 0 ? null : `synthetic-execute-${index}`, dispatch_count: index === 0 ? 0 : 1,
    slack_message_ts: status === 'SENT' ? '1800000000.000002' : null,
    failure_code: status === 'UNCERTAIN' ? 'SYNTHETIC_LEGACY_UNCERTAIN' : null,
    created_at: now, expires_at: '2099-01-01T00:00:00Z', updated_at: now,
  }))
}

export function seedSql(receipts) {
  const literal = value => value === null ? 'NULL' : typeof value === 'number' ? String(value) : `'${value.replaceAll("'", "''")}'`
  return 'PRAGMA foreign_keys=ON; BEGIN IMMEDIATE;\n' + receipts.map(receipt =>
    `INSERT INTO slack_reply (${Object.keys(receipt).join(',')}) VALUES (${Object.values(receipt).map(literal).join(',')});`).join('\n') + '\nCOMMIT;'
}

export async function run(config) {
  assert.equal(process.platform, 'darwin', 'This offline harness requires macOS sandbox-exec; no unsandboxed fallback')
  for (const executable of [config.java, '/usr/bin/sandbox-exec', '/usr/bin/sqlite3']) await access(executable)
  const jar = await realpath(config.jar)
  assert.equal(sha256(await readFile(jar)), config.hash, 'Artifact hash mismatch')
  assert.notEqual(config.hash, obsoleteJar)
  const temporary = await mkdtemp(join(tmpdir(), 'dougle-readonly-runtime-'))
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
    const created = await request('/workspaces', 'POST', JSON.stringify({ name: 'readonly synthetic' }))
    assert.equal(created.status, 201)
    const workspaceId = created.body.id
    await denyAll(workspaceId); await denyAll('synthetic-missing-workspace'); await idle(workspaceId)
    await history(workspaceId, [])
    await stop()
    assert.deepEqual(await db('SELECT count(*) AS total FROM slack_reply;'), [{ total: 0 }])
    const receipts = legacyReceipts(workspaceId)
    await db(seedSql(receipts))
    const baseline = await db('SELECT * FROM slack_reply ORDER BY id;')
    let previousHistory
    for (let restart = 0; restart < 2; restart++) {
      await start(); restarts++
      const currentHistory = await history(workspaceId, receipts)
      if (previousHistory) assert.deepEqual(currentHistory, previousHistory)
      previousHistory = currentHistory
      for (const receipt of receipts) await denyAll(workspaceId, receipt.id)
      await denyAll(workspaceId); await idle(workspaceId)
      assert.deepEqual(await history(workspaceId, receipts), previousHistory)
      await stop()
      assert.deepEqual(await db('SELECT * FROM slack_reply ORDER BY id;'), baseline)
      for (const table of ['answer_job', 'generation_job', 'investigation_plan']) {
        assert.deepEqual(await db(`SELECT count(*) AS total FROM ${table};`), [{ total: 0 }])
      }
    }
    assert.equal(proxyRequests, 0, 'Unexpected request observed at rejecting JVM proxy')
    assert.equal(sha256(await readFile(jar)), config.hash)
    const report = { fixtureVersion, executedAt: new Date().toISOString(), jarSha256: config.hash, result: 'PASS',
      deniedRequests, restarts, legacyReceiptCount: receipts.length, legacyRowsUnchanged: true, jobRows: 0,
      observedProxyRequests: proxyRequests, networkPolicy: 'macOS sandbox loopback-only; JVM HTTP(S) rejecting proxy',
      limits: 'Proxy observation does not count bypassed/blocked transport attempts. No real Slack, credentials, external AI, user DB, or whole-product E35 acceptance. Legacy SENT is seeded synthetic data, not a real send. No acceptance inventory promotion.' }
    console.log(JSON.stringify(report, null, 2))
    return report
  } finally {
    try { await stop() }
    finally {
      process.off('SIGINT', interrupt); process.off('SIGTERM', interrupt)
      proxy.closeAllConnections(); if (proxy.listening) await new Promise(done => proxy.close(done))
      if (!alive()) await rm(temporary, { recursive: true, force: true })
      else console.error('Owned backend still active; isolated data retained for manual cleanup')
    }
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { await run(options(process.argv.slice(2))) }
  catch (error) { console.error(error.message); process.exitCode = 1 }
}
