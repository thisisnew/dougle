import assert from 'node:assert/strict'
import { execFile, spawn } from 'node:child_process'
import { createHash, randomBytes, randomUUID } from 'node:crypto'
import { once } from 'node:events'
import { constants } from 'node:fs'
import { access, chmod, copyFile, lstat, mkdir, mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { dirname, isAbsolute, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'
import { childEnvironment, startupDiagnostic } from './readonly-runtime-smoke.mjs'
import { sandboxProfile } from './e50-archive-fixtures.mjs'
import { isolationPlan, validateArtifactBinding, validateIsolationObservation } from './expert-e37-isolation.mjs'
import { verifySearchModels } from './expert-live-search-models.mjs'

export const approvedHash = '5cf78b6441a99f893ecbaf6e81babd3e17e01e46b154f3c772e43abe138ca22a'
export const artifactHashes = Object.freeze({ '0.1.25': approvedHash,
  '0.1.38': '97c02f213455511766c475eeb5d934cac50614b7b0d864726b6213a76d36dcbc' })
const execute = promisify(execFile)
const pause = milliseconds => new Promise(done => setTimeout(done, milliseconds))
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex')

export function runtimeOptions(args) {
  assert.equal(args[0], '--run-approved-isolated')
  assert.equal(args.length, 13)
  assert.deepEqual(args.filter((value, index) => index % 2 === 1), ['--version', '--source-jar', '--sha256', '--java', '--models', '--report'])
  const options = { version: args[2], sourceJar: args[4], hash: args[6], java: args[8], models: args[10], report: args[12] }
  assert(Object.hasOwn(artifactHashes, options.version)); assert.equal(options.hash, artifactHashes[options.version])
  for (const key of ['sourceJar', 'java', 'models', 'report']) assert(isAbsolute(options[key]) && !options[key].split('/').includes('..'))
  assert(options.report.endsWith('.json'))
  assert(!options.report.split('/').includes('target'), 'Never write build output')
  return options
}

export async function snapshotArtifact(source, destination, expectedHash, version = '0.1.25') {
  assert(Object.hasOwn(artifactHashes, version)); assert.equal(expectedHash, artifactHashes[version])
  assert((await lstat(source)).isFile(), 'Regular source JAR required')
  validateArtifactBinding({ version, jar: destination, sha256: expectedHash })
  assert.equal(sha256(await readFile(source)), expectedHash, 'Source artifact pin mismatch')
  await copyFile(source, destination, constants.COPYFILE_EXCL)
  await chmod(destination, 0o400)
  assert.equal(sha256(await readFile(destination)), expectedHash, 'Snapshot pin mismatch')
  assert.equal(sha256(await readFile(source)), expectedHash, 'Source changed during snapshot')
  return destination
}

export function terminalSql(fixture) {
  isolationPlan(fixture)
  assert.match(fixture.bundleDigest, /^[a-f0-9]{64}$/)
  assert.match(fixture.canary, /^E37_[A-Z0-9_]+$/)
  const literal = value => value === null ? 'NULL' : `'${String(value).replaceAll("'", "''")}'`
  const insert = (table, row) => `INSERT INTO ${table} (${Object.keys(row).join(',')}) VALUES (${Object.values(row).map(literal).join(',')});`
  const now = '2026-10-26T00:00:00Z'
  const shared = { workspace_id: fixture.ownerWorkspaceId, bundle_id: fixture.bundleId,
    created_at: now, updated_at: now, status: 'SUCCEEDED' }
  const answer = insert('answer_job', { ...shared, id: fixture.answerJobId, approval_id: 'synthetic-' + fixture.answerJobId,
    provider: 'CODEX', requested_model: 'synthetic-no-dispatch', digest: fixture.bundleDigest, answer: fixture.canary, error: null })
  const plan = insert('investigation_plan', { ...shared, digest: sha256('plan-' + fixture.planJobId), provider: 'CODEX',
    requested_model: 'synthetic-no-dispatch', binding_digest: fixture.bundleDigest, prompt: 'Synthetic seeded terminal record; never executed',
    expires_at: '2026-10-26T00:00:01Z', request_id: fixture.planJobId, approved_at: now, decision: 'SEARCH',
    query: 'E37_COMMON', output: fixture.canary, truncated: 0, tool_status: 'OK' })
  const browser = insert('browser_investigation', { ...shared, digest: sha256('browser-' + fixture.browserJobId),
    bundle_digest: fixture.bundleDigest, scope_json: JSON.stringify({ url: 'https://synthetic.invalid/e37', allowedGetPaths: ['/e37'] }),
    expires_at: '2026-10-26T00:00:01Z', request_id: fixture.browserJobId, approved_at: now,
    result_json: JSON.stringify({ url: 'https://synthetic.invalid/e37', capturedAt: now, title: 'Synthetic seeded browser snapshot',
      text: fixture.canary, observations: [], truncated: false, status: 'OK' }) })
  const proposals = proposalContracts(fixture).map(proposal => insert('investigation_plan', { ...shared,
    digest: sha256('proposal-' + proposal.jobId), provider: 'CODEX', requested_model: 'synthetic-no-dispatch',
    binding_digest: fixture.bundleDigest, prompt: 'Synthetic seeded proposal; not model-generated; no execution',
    expires_at: '2026-10-26T00:00:01Z', request_id: proposal.jobId, approved_at: now,
    decision: 'PROPOSE_' + proposal.kind, query: null, output: null, truncated: 0, tool_status: null,
  })).join('\n')
  return `PRAGMA foreign_keys=ON; BEGIN IMMEDIATE;\n${answer}\n${plan}\n${browser}\n${proposals}\nCOMMIT;`
}

export function proposalContracts(fixture) {
  const ids = [fixture.mysqlProposalId, fixture.datadogProposalId]
  for (const id of ids) assert(typeof id === 'string' && /^[A-Za-z0-9_-]{12,128}$/.test(id))
  assert.equal(new Set([...ids, fixture.planJobId]).size, 3, 'Proposal records must be separate from each other and SEARCH')
  return [
    { kind: 'MYSQL_READ', jobId: ids[0], requiredFields: ['table', 'columns', 'equalities', 'limit'] },
    { kind: 'DATADOG_LOGS', jobId: ids[1], requiredFields: ['environment', 'service', 'indexes', 'from', 'to', 'limit'] },
  ].map(proposal => ({ ...proposal, workspaceId: fixture.ownerWorkspaceId, bundleId: fixture.bundleId, executionAllowed: false }))
}

export function verifyProposal(value, expected) {
  assert.deepEqual(value, expected, 'Proposal must exactly match owner/job/bundle/kind/required fields and forbid execution')
}

export function verifyProposalDenial(response, requestedPath, fixture, expected) {
  assert.equal(response.status, 404)
  assert(response.body && typeof response.body === 'object' && !Array.isArray(response.body))
  assert.equal(response.body.status, 404)
  for (const key of Object.keys(response.body)) {
    assert(['type', 'title', 'status', 'detail', 'instance'].includes(key), 'Unexpected ProblemDetail extension')
  }
  for (const key of ['type', 'title', 'detail']) {
    if (Object.hasOwn(response.body, key)) assert.equal(typeof response.body[key], 'string', 'ProblemDetail text field must be a string')
  }
  const content = { ...response.body }
  if (Object.hasOwn(content, 'instance')) {
    assert.equal(content.instance, requestedPath, 'Only exact requested-path reflection is allowed')
    delete content.instance
  }
  const text = JSON.stringify(content)
  for (const value of [fixture.canary, fixture.bundleId, fixture.ownerWorkspaceId, expected.jobId]) {
    assert(!text.includes(value), 'Foreign data outside exact request-path reflection')
  }
}

export function proposalDenialDiagnostics(response, requestedPath, fixture, expected) {
  const body = response.body
  const markers = { ownerCanary: fixture.canary, ownerBundleId: fixture.bundleId, ownerWorkspaceId: fixture.ownerWorkspaceId, suppliedJobId: expected.jobId }
  const fields = body && typeof body === 'object' && !Array.isArray(body) ? Object.entries(body) : [['NON_OBJECT_BODY', body]]
  return { instanceMatchesRequest: body?.instance === requestedPath,
    matches: fields.flatMap(([field, value]) => Object.entries(markers)
      .filter(([, marker]) => typeof marker === 'string' && marker.length > 0 && JSON.stringify(value)?.includes(marker))
      .map(([marker]) => ({ field: ['type', 'title', 'status', 'detail', 'instance'].includes(field) ? field : 'EXTENSION_OR_INVALID_BODY', marker }))) }
}

export function verifyHits(hits, owned, foreign) {
  assert(Array.isArray(hits) && hits.length > 0, 'Positive indexed owner hit required')
  assert(hits.some(hit => hit.entryId === owned.entryId && hit.snippet?.includes(owned.canary)))
  for (const hit of hits) {
    assert.equal(hit.entryId, owned.entryId)
    assert(!JSON.stringify(hit).includes(foreign.canary)); assert.notEqual(hit.entryId, foreign.entryId)
  }
}

export async function run(config) {
  assert(Object.hasOwn(artifactHashes, config.version)); assert.equal(config.hash, artifactHashes[config.version])
  await access(config.report).then(() => { throw Error('Report exists; refusing overwrite') }, error => { if (error.code !== 'ENOENT') throw error })
  const report = { criterion: 'E37', result: 'INCOMPLETE', mode: 'simulated', artifactSha256: config.hash, version: config.version,
    startedAt: new Date().toISOString(), observations: [], proposalObservations: [], backendPids: [], aiDispatches: 0,
    limitations: ['Seeded terminal jobs are not AI/browser executions',
      config.version === '0.1.38' ? 'Populated MySQL/operational capture memory state deferred; Datadog integration removed'
        : 'Populated MySQL/Datadog/operational capture memory state deferred', 'No UI checkpoint or full E37 verdict'], }
  let modelDirectory

  assert.equal(process.platform, 'darwin', 'This offline harness requires macOS sandbox-exec; no unsandboxed fallback')
  for (const executable of [config.java, '/usr/bin/sandbox-exec', '/usr/bin/sqlite3']) await access(executable)
  const temporary = await mkdtemp(join(tmpdir(), 'dougle-e37-runtime-'))
  await chmod(temporary, 0o700)
  const jar = join(temporary, `frozen-${config.version}.jar`)
  const home = join(temporary, 'home')
  const data = join(temporary, 'data')
  const database = join(data, 'dougle.sqlite')
  await mkdir(home, { mode: 0o700 }); await mkdir(data, { mode: 0o700 })
  let child, base, token, spawnError
  let startupOutput = ''
  let aborted = false
  let proxyRequests = 0
  const proxy = createServer((request, response) => { proxyRequests++; response.writeHead(502); response.end() })
  proxy.on('connect', (request, socket) => { proxyRequests++; socket.end('HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n') })
  const interrupt = () => { aborted = true }
  process.on('SIGINT', interrupt); process.on('SIGTERM', interrupt)
  const overallDeadline = setTimeout(interrupt, 12 * 60 * 1000)
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
    assert.equal(sha256(await readFile(config.java)), report.javaSha256, 'Approved Java changed')
    const profile = sandboxProfile(await realpath(temporary), dirname(dirname(await realpath(config.java))), modelDirectory)
    report.sandboxProfileSha256 = sha256(profile)
    report.networkAndFiles = 'Existing E50 strict file allowlist and loopback-only network; no fallback or added permission'
    const childState = { exitCode: null, signal: null, stdoutBytes: 0, stderrBytes: 0, ready: false }
    report.startup = childState
    child = spawn('/usr/bin/sandbox-exec', ['-p', profile, config.java,
      '-Djava.net.preferIPv4Stack=true',
      `-Duser.home=${home}`, `-Djava.io.tmpdir=${temporary}`, '-Dhttp.proxyHost=127.0.0.1', `-Dhttp.proxyPort=${proxyPort}`,
      '-Dhttps.proxyHost=127.0.0.1', `-Dhttps.proxyPort=${proxyPort}`, '-Dhttp.nonProxyHosts=localhost|127.*|[::1]',
      '-jar', jar, '--server.port=0', '--server.address=127.0.0.1', '--forge.desktop=true', `--forge.data-dir=${data}`,
      `--forge.search-model-directory=${modelDirectory}`,
      `--forge.ai.codex-command=${join(temporary, 'disabled-codex')}`,
      `--forge.ai.claude-command=${join(temporary, 'disabled-claude')}`,
      `--forge.ai.codex-stream-home=${join(temporary, 'isolated-codex')}`],
    { cwd: temporary, detached: true, env: childEnvironment(home, data), stdio: ['pipe', 'pipe', 'pipe'] })
    if (child.pid) report.backendPids.push(child.pid)
    child.on('exit', (code, signal) => { childState.exitCode = code; childState.signal = signal })
    child.stdout.on('data', chunk => { childState.stdoutBytes += chunk.length })
    child.stderr.on('data', chunk => { childState.stderrBytes += chunk.length })
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
        try { if ((await request('/session')).status === 200) { childState.ready = true; return } } catch {}
      }
      await pause(250)
    }
    throw new Error('Isolated backend startup deadline exceeded: ' + startupDiagnostic(startupOutput, token))
  }
  async function request(path, method = 'GET', body) {
    assert(!aborted && base && path.startsWith('/') && !path.startsWith('//'))
    const multipart = body instanceof FormData
    const response = await fetch(`${base}${path}`, { method, redirect: 'error', signal: AbortSignal.timeout(path.endsWith('/events') ? 5000 : 10000),
      headers: { 'X-Forge-Session': token, 'X-Forge-Client': '1', Origin: new URL(base).origin,
        ...(multipart ? {} : { 'Content-Type': 'application/json' }) }, body: multipart ? body : body === undefined ? undefined : JSON.stringify(body) })
    let bytes = 0; const chunks = []
    if (response.body) for await (const chunk of response.body) {
      bytes += chunk.length; assert(bytes <= 512000, 'Response bound exceeded'); chunks.push(chunk)
    }
    const text = Buffer.concat(chunks).toString('utf8')
    let value
    try { value = JSON.parse(text) } catch { value = text }
    return { status: response.status, body: value, text }
  }
  async function api(path, method = 'GET', body, expected = 200) {
    const result = await request(path, method, body)
    assert.equal(result.status, expected, `${method} ${path} unexpected status`)
    return result.body
  }
  async function ready() {
    const deadline = Date.now() + 120000
    while (Date.now() < deadline) {
      const state = (await api('/search-model')).state
      if (state === 'READY') return
      assert(['STARTING', 'LOADING', 'INDEXING'].includes(state), 'Search model unusable: ' + state)
      await pause(500)
    }
    throw Error('Model readiness timeout')
  }
  async function db(sql) {
    assert(!alive(), 'Never open SQLite while backend is live')
    const info = await lstat(database)
    assert(info.isFile() && !info.isSymbolicLink())
    assert.equal(await realpath(database), join(await realpath(data), 'dougle.sqlite'))
    const result = await execute('/usr/bin/sqlite3', ['-batch', '-bail', '-json', database, sql], { timeout: 5000, maxBuffer: 128000 })
    return result.stdout.trim() ? JSON.parse(result.stdout) : null
  }
  const rows = async () => {
    const result = {}
    for (const table of ['evidence_bundle', 'question_attachment', 'answer_job', 'generation_job', 'investigation_plan', 'browser_investigation']) {
      result[table] = await db(`SELECT * FROM ${table} ORDER BY rowid;`)
    }
    return result
  }
  try {
    await snapshotArtifact(config.sourceJar, jar, config.hash, config.version)
    report.javaSha256 = sha256(await readFile(config.java))
    const models = await verifySearchModels(process.cwd(), config.models)
    modelDirectory = models.directory; report.models = models
    report.snapshotJar = jar
    proxy.listen(0, '127.0.0.1'); await once(proxy, 'listening')
    await start(); await ready()
    const fixtures = []
    for (const suffix of ['A', 'B']) {
      const workspace = await api('/workspaces', 'POST', { name: 'E37 synthetic ' + suffix }, 201)
      const canary = 'E37_PRIVATE_' + suffix + '_' + randomBytes(8).toString('hex').toUpperCase()
      const form = new FormData()
      form.append('file', new Blob(['E37_COMMON ' + canary], { type: 'text/plain' }), 'fixture-' + suffix + '.txt')
      const entry = await api('/workspaces/' + workspace.id + '/files', 'POST', form, 201)
      const attachmentForm = new FormData()
      attachmentForm.append('file', new Blob(['E37 attachment ' + canary], { type: 'text/plain' }), 'attached-' + suffix + '.txt')
      const attachment = await api('/workspaces/' + workspace.id + '/attachments', 'POST', attachmentForm, 201)
      fixtures.push({ ownerWorkspaceId: workspace.id, entryId: entry.id, attachmentId: attachment.id, canary,
        browserJobId: randomUUID(), answerJobId: randomUUID(), planJobId: randomUUID(),
        mysqlProposalId: randomUUID(), datadogProposalId: randomUUID() })
    }
    for (const [index, fixture] of fixtures.entries()) {
      const foreign = fixtures[1 - index]; fixture.otherWorkspaceId = foreign.ownerWorkspaceId
      const deadline = Date.now() + 120000
      let indexed = false
      while (Date.now() < deadline) {
        const hits = await api('/workspaces/' + fixture.ownerWorkspaceId + '/search?query=E37_COMMON')
        if (hits.length) { verifyHits(hits, fixture, foreign); indexed = true; break }
        await pause(500)
      }
      assert(indexed, 'Positive owner indexing timeout')
      const bundle = await api('/workspaces/' + fixture.ownerWorkspaceId + '/chat-threads', 'POST',
        { requestId: randomUUID(), question: 'E37_COMMON', attachmentIds: [fixture.attachmentId] }, 201)
      assert(JSON.stringify(bundle).includes(fixture.canary), 'Bundle must contain its actual owner evidence')
      assert(!JSON.stringify(bundle).includes(foreign.canary))
      fixture.bundleId = bundle.id; fixture.bundleDigest = bundle.digest; fixture.threadId = bundle.payload.chatContext.threadId
    }
    await stop()
    for (const fixture of fixtures) await db(terminalSql(fixture))
    const baseline = await rows()
    report.fixtures = fixtures
    for (const phase of ['before-restart', 'after-restart']) {
      await start(); await ready()
      for (const [index, fixture] of fixtures.entries()) {
        const foreign = fixtures[1 - index]
        const plan = isolationPlan(fixture)
        report.deferred = plan.deferred
        for (const active of plan.matrix[0].activeWorkspaceSequence) {
          await api('/workspaces/' + active + '/active', 'PUT', undefined, 204)
          for (const check of plan.matrix) {
            const owner = await request(check.ownerPath.slice(4))
            const denied = await request(check.foreignPath.slice(4))
            const expectedId = check.name.startsWith('entry') ? fixture.entryId
              : check.name === 'thread' ? fixture.threadId : check.name.startsWith('answer') ? fixture.answerJobId
              : check.name.startsWith('browser') ? fixture.browserJobId : check.name === 'plan-job' ? fixture.planJobId : fixture.bundleId
            const contentOnly = ['entry-content', 'entry-preview'].includes(check.name)
            if (['thread', 'answer-job', 'answer-events', 'browser-job', 'browser-list', 'plan-job'].includes(check.name)) {
              assert(owner.text.includes(fixture.canary), 'Populated owner canary must be returned, not merely an ID')
            }
            const observation = { ownerStatus: owner.status, foreignStatus: denied.status,
              ownerIdentityVerified: owner.text.includes(contentOnly ? fixture.canary : expectedId),
              foreignBody: denied.text, ownerUnchanged: true, aiDispatches: 0, externalRequests: proxyRequests }
            validateIsolationObservation(check, observation, [fixture.canary])
            assert(!owner.text.includes(foreign.canary), 'Owner response contains foreign content')
            report.observations.push({ phase, active, workspaceId: fixture.ownerWorkspaceId, name: check.name,
              ownerStatus: owner.status, foreignStatus: denied.status, ownerResponseSha256: sha256(owner.text), foreignResponseSha256: sha256(denied.text) })
          }
          verifyHits(await api('/workspaces/' + fixture.ownerWorkspaceId + '/search?query=E37_COMMON'), fixture, foreign)
          for (const expected of proposalContracts(fixture)) {
            const suffix = '/investigation-plans/jobs/' + expected.jobId + '/tool-proposal'
            const owner = await request('/workspaces/' + fixture.ownerWorkspaceId + suffix)
            const removed = config.version === '0.1.38' && expected.kind === 'DATADOG_LOGS'
            assert.equal(owner.status, removed ? 409 : 200)
            if (!removed) verifyProposal(owner.body, expected)
            else {
              assert.equal(owner.body.status, 409)
              assert(!owner.text.includes(fixture.canary))
              assert(!Object.hasOwn(owner.body, 'executionAllowed'))
            }
            const denied = await request('/workspaces/' + fixture.otherWorkspaceId + suffix)
            report.proposalLastProbe = { phase, active, kind: expected.kind, ownerStatus: owner.status, foreignStatus: denied.status,
              ownerExactVerified: !removed, removedFeatureDenied: removed, foreignResponseSha256: sha256(denied.text),
              problemKeys: denied.body && typeof denied.body === 'object' ? Object.keys(denied.body).sort() : [],
              ...proposalDenialDiagnostics(denied, '/api/workspaces/' + fixture.otherWorkspaceId + suffix, fixture, expected) }
            verifyProposalDenial(denied, '/api/workspaces/' + fixture.otherWorkspaceId + suffix, fixture, expected)
            validateIsolationObservation({ ownerStatus: removed ? 409 : 200, foreignStatus: 404 }, {
              ownerStatus: owner.status, ownerIdentityVerified: true, foreignStatus: denied.status,
              foreignBody: denied.text, ownerUnchanged: true, aiDispatches: 0, externalRequests: proxyRequests,
            }, [fixture.canary, fixture.bundleId])
            report.proposalObservations.push({ phase, active, expected, ownerStatus: owner.status, foreignStatus: denied.status,
              ownerResponseSha256: sha256(owner.text), foreignResponseSha256: sha256(denied.text), removedFeatureDenied: removed,
              provenance: 'STOPPED_DB_SEEDED_NOT_MODEL_GENERATED' })
          }
          const before = await api('/workspaces/' + fixture.ownerWorkspaceId + '/chat-threads/' + fixture.threadId)
          await api('/workspaces/' + foreign.ownerWorkspaceId + '/chat-threads', 'POST',
            { requestId: randomUUID(), question: 'E37_COMMON', threadId: fixture.threadId, parentBundleId: fixture.bundleId }, 404)
          await api('/workspaces/' + foreign.ownerWorkspaceId + '/chat-threads', 'POST',
            { requestId: randomUUID(), question: 'E37_COMMON', attachmentIds: [fixture.attachmentId] }, 404)
          assert.deepEqual(await api('/workspaces/' + fixture.ownerWorkspaceId + '/chat-threads/' + fixture.threadId), before)
          report.observations.push({ phase, active, workspaceId: fixture.ownerWorkspaceId, name: 'search-positive-and-foreign-thread-attachment-denials', status: 'CHECKED' })
        }
      }
      await stop()
      assert.deepEqual(await rows(), baseline, 'Scoped checks changed persisted owner data')
    }
    assert.equal(proxyRequests, 0)
    report.result = 'SCOPED_CHECKS_COMPLETED_NOT_FULL_E37'
    report.persistedRowsUnchanged = true
    report.proposalReadPairs = report.proposalObservations.length
    assert.equal(report.proposalReadPairs, 16)
    report.deferred = report.deferred.filter(value => value.scope !== 'tool proposal payload')
    if (config.version === '0.1.38') report.deferred = report.deferred.map(value => ({ ...value,
      scope: value.scope.replace('MYSQL/DATADOG', 'MYSQL') }))
    report.readPairs = report.observations.filter(value => value.foreignStatus === 404).length
    report.localSearchAndInjectionGroups = report.observations.filter(value => value.name === 'search-positive-and-foreign-thread-attachment-denials').length
  } catch (error) {
    report.result = 'FAILED_OR_INCOMPLETE'
    report.failure = String(error.message).replaceAll(token ?? 'NO_SESSION', '[SESSION]').slice(0, 1000)
    throw error
  } finally {
    try { await stop() }
    finally {
      clearTimeout(overallDeadline)
      process.off('SIGINT', interrupt); process.off('SIGTERM', interrupt)
      proxy.closeAllConnections(); if (proxy.listening) await new Promise(done => proxy.close(done))
      report.ownedBackendStopped = !alive(); report.observedProxyRequests = proxyRequests
      report.snapshotHashUnchanged = await readFile(jar).then(bytes => sha256(bytes) === config.hash, () => false)
      report.sourceHashUnchanged = await readFile(config.sourceJar).then(bytes => sha256(bytes) === config.hash, () => false)
      report.javaUnchanged = await readFile(config.java).then(bytes => sha256(bytes) === report.javaSha256, () => false)
      if (!alive()) await rm(temporary, { recursive: true, force: true })
      report.ownedTemporaryRemoved = await access(temporary).then(() => false, error => error.code === 'ENOENT')
      report.finishedAt = new Date().toISOString()
      report.harnessSha256 = sha256(await readFile(fileURLToPath(import.meta.url)))
      await writeFile(config.report, JSON.stringify(report, null, 2) + '\n', { flag: 'wx', mode: 0o600 })
      console.log(JSON.stringify({ report: config.report, result: report.result, cleaned: report.ownedTemporaryRemoved }))
    }
  }
  return report
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { await run(runtimeOptions(process.argv.slice(2))) }
  catch (error) { console.error(error.message); process.exitCode = 1 }
}
