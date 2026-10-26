import assert from 'node:assert/strict'
import { spawn, execFileSync } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'
import { once } from 'node:events'
import { chmod, copyFile, mkdir, mkdtemp, readFile, realpath, rm, stat, writeFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { setTimeout as pause } from 'node:timers/promises'

async function localModels(input) {
  if (!input) return null
  const directory = await realpath(resolve(input))
  assert((await stat(directory)).isDirectory(), 'Explicit search model path must be a directory')
  const hashes = {
    'model.onnx': 'dd476dd0c2514e9b9be83aeb3853fac0763e0bdf4a71645407587d77c48a2d88',
    'tokenizer.json': '0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39',
    'reranker.onnx': process.arch === 'arm64' ? '1825907d6c1a9001ff78124780bbde20a614a8c3df3b63409cf3c72c6fe5c8b4'
      : '6c2513767fb63d008a4377bef7a7a3555433d9436342bb53e35a3a72ffc52d4b',
    'reranker-tokenizer.json': '62c24cdc13d4c9952d63718d6c9fa4c287974249e16b7ade6d5a85e7bbb75626',
  }
  for (const [name, hash] of Object.entries(hashes)) {
    const file = resolve(directory, name)
    assert((await stat(file)).isFile(), 'Missing model file: ' + name)
    assert.equal(createHash('sha256').update(await readFile(file)).digest('hex'), hash, 'Local model checksum mismatch: ' + name)
  }
  return { directory, hashes }
}

function modelReady(status) {
  assert(status && ['STARTING', 'LOADING', 'INDEXING', 'READY'].includes(status.state), 'Local search model not usable: ' + JSON.stringify(status))
  return status.state === 'READY'
}

if (process.argv.length === 3 && process.argv[2] === '--self-test') {
  const temporary = await mkdtemp(resolve(tmpdir(), 'dougle-investigation-fixture-test-'))
  try {
    const executable = resolve(temporary, 'fake-investigation-codex.sh')
    await copyFile(fileURLToPath(new URL('./fixtures/fake-investigation-codex.sh', import.meta.url)), executable)
    await writeFile(resolve(temporary, 'investigation-fixture-only'), 'Synthetic fixture test only\n')
    const schema = resolve(temporary, 'schema.json')
    const output = resolve(temporary, 'answer.json')
    const planningSchema = { type: 'object', properties: { decision: { enum: ['SEARCH', 'STOP', 'PROPOSE_MYSQL_READ', 'PROPOSE_DATADOG_LOGS'] }, query: { type: ['string', 'null'] } } }
    await writeFile(schema, JSON.stringify(planningSchema))
    const run = (model, input) => execFileSync('/bin/sh', [executable, 'exec', '--model', model,
      '--output-schema', schema, '--output-last-message', output], { cwd: temporary, input, encoding: 'utf8', timeout: 3000,
      env: { PATH: '/usr/bin:/bin', HOME: temporary }, stdio: ['pipe', 'pipe', 'pipe'] })
    run('investigation-search-fixture', 'synthetic ordinary search')
    assert.deepEqual(JSON.parse(await readFile(output, 'utf8')), { decision: 'SEARCH', query: 'Strategy' })
    for (const [model, decision] of [['investigation-mysql-proposal-fixture', 'PROPOSE_MYSQL_READ'], ['investigation-datadog-proposal-fixture', 'PROPOSE_DATADOG_LOGS']]) {
      run(model, 'Synthetic explicit marker SYNTHETIC_' + decision)
      assert.deepEqual(JSON.parse(await readFile(output, 'utf8')), { decision, query: null })
      assert.throws(() => run(model, 'Routine database/log documentation without a fixture marker'))
    }
    await writeFile(schema, JSON.stringify({ properties: { claims: {}, evidenceIds: {} } }))
    run('investigation-answer-fixture', 'synthetic approved answer')
    assert.equal(JSON.parse(await readFile(output, 'utf8')).claims[0].evidenceIds[0], 'R1')
    assert.throws(() => run('investigation-mysql-proposal-fixture', 'SYNTHETIC_PROPOSE_MYSQL_READ'))
    assert.throws(() => run('unrecognized-fixture', 'synthetic'))
    assert.equal(await localModels(undefined), null)
    assert.equal(await localModels(''), null)
    await assert.rejects(localModels(resolve(temporary, 'missing-models')))
    await writeFile(resolve(temporary, 'model.onnx'), 'synthetic invalid model')
    await assert.rejects(localModels(temporary), /checksum mismatch/)
    for (const state of ['STARTING', 'LOADING', 'INDEXING']) assert.equal(modelReady({ state }), false)
    assert.equal(modelReady({ state: 'READY' }), true)
    for (const state of ['ERROR', 'NOT_READY', 'DOWNLOADING', 'UNKNOWN']) assert.throws(() => modelReady({ state }))
    assert.throws(() => modelReady(null))
    console.log('PASS fixture-only checks: SEARCH/answer and explicit proposals; marker/schema/model rejection; default models disabled; local asset validation and READY/error states. No JAR, DB, network or real AI used.')
  } finally { await rm(temporary, { recursive: true, force: true }) }
  process.exit(0)
}

assert(process.argv.slice(2).every(argument => argument === '--hold'), 'Only --hold is supported')
assert.notEqual(process.platform, 'win32', 'This fixture requires POSIX process groups')
const hold = process.argv.includes('--hold')
const root = fileURLToPath(new URL('../', import.meta.url))
const jar = resolve(root, 'backend/target/knowledge-forge-0.1.0.jar')
assert((await stat(jar)).isFile(), 'Build the production JAR before running this smoke')
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex')
const jarSha256 = sha256(await readFile(jar))
const models = await localModels(process.env.FORGE_INVESTIGATION_SEARCH_MODELS)
const modelReadiness = []
const javaHome = process.env.JAVA_HOME || (process.platform === 'darwin'
  ? execFileSync('/usr/libexec/java_home', ['-v', '21'], { encoding: 'utf8' }).trim() : '')
const java = javaHome ? resolve(javaHome, 'bin/java') : 'java'
const directory = await mkdtemp(resolve(tmpdir(), 'dougle-investigation-smoke-'))
const fixture = resolve(directory, 'fake-investigation-codex.sh')
const dataDirectory = resolve(directory, 'data')
const model = 'investigation-search-fixture'
const answerModel = 'investigation-answer-fixture'
const cancelModel = 'investigation-cancel-fixture'
const mysqlModel = 'investigation-mysql-proposal-fixture'
const datadogModel = 'investigation-datadog-proposal-fixture'
const shutdown = new AbortController()
const onSignal = () => shutdown.abort()
process.on('SIGINT', onSignal)
process.on('SIGTERM', onSignal)
let child
let base
let launchFailed = false

async function until(action, predicate, label, milliseconds = 30000) {
  const deadline = Date.now() + milliseconds
  while (Date.now() < deadline) {
    shutdown.signal.throwIfAborted()
    const value = await action()
    if (predicate(value)) return value
    await pause(100, undefined, { signal: shutdown.signal })
  }
  throw new Error(`Timed out waiting for ${label}`)
}

async function api(path, method = 'GET', body, expected = 200) {
  const multipart = body instanceof FormData
  const response = await fetch(`${base}/api${path}`, {
    method, redirect: 'error',
    headers: { 'X-Forge-Client': '1', ...(!multipart && body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
    body: multipart ? body : body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.any([shutdown.signal, AbortSignal.timeout(15000)]),
  })
  assert.equal(response.status, expected, `${method} ${path}: unexpected HTTP status`)
  const text = await response.text()
  return text ? JSON.parse(text) : null
}

async function start() {
  assert.equal(sha256(await readFile(jar)), jarSha256, 'JAR changed during smoke; do not overlap builds')
  const reservation = createServer()
  try {
    reservation.listen(0, '127.0.0.1')
    await once(reservation, 'listening')
    base = `http://127.0.0.1:${reservation.address().port}`
  } finally {
    if (reservation.listening) await new Promise((done, reject) => reservation.close(error => error ? reject(error) : done()))
  }
  launchFailed = false
  child = spawn(java, [`-Djava.io.tmpdir=${directory}`, `-Duser.home=${directory}`, '-jar', jar,
    '--forge.desktop=false', '--server.address=127.0.0.1', `--server.port=${new URL(base).port}`,
    `--forge.data-dir=${dataDirectory}`, `--forge.search-model-directory=${models?.directory ?? ''}`,
    `--forge.ai.codex-command=${fixture}`, '--forge.ai.codex-stream-home=',
    `--forge.ai.claude-command=${resolve(directory, 'disabled-claude')}`], {
    cwd: directory, detached: true, stdio: ['ignore', 'ignore', 'ignore'],
    env: { PATH: '/usr/bin:/bin:/usr/sbin:/sbin', LANG: 'en_US.UTF-8', TMPDIR: directory, FORGE_DATA_DIR: dataDirectory },
  })
  child.on('error', () => { launchFailed = true })
  await until(async () => {
    assert(!launchFailed && child.exitCode === null && child.signalCode === null, 'Isolated backend failed to start')
    try {
      return (await fetch(`${base}/api/session`, {
        redirect: 'error', signal: AbortSignal.any([shutdown.signal, AbortSignal.timeout(1000)]),
      })).ok
    } catch {
      shutdown.signal.throwIfAborted()
      return false
    }
  }, ready => ready, 'isolated backend startup', 90000)
  if (models) {
    const ready = await until(() => api('/search-model'), modelReady, 'bundled model READY', 120000)
    modelReadiness.push({ state: ready.state, observedAt: new Date().toISOString() })
  }
}

function killOwned(owned, signal) {
  if (!owned?.pid) return
  try { process.kill(-owned.pid, signal) }
  catch (error) { if (error.code !== 'ESRCH') throw error }
}

async function stop() {
  const owned = child
  if (!owned?.pid) return
  if (owned.exitCode !== null || owned.signalCode !== null) return
  const ended = once(owned, 'exit')
  killOwned(owned, 'SIGTERM')
  const timer = setTimeout(() => killOwned(owned, 'SIGKILL'), 15000)
  try { await ended } finally { clearTimeout(timer) }
}

async function calls(selectedModel) {
  try {
    return (await readFile(resolve(directory, `${selectedModel}.calls`), 'utf8')).split('\n').filter(Boolean).length
  } catch (error) {
    if (error.code === 'ENOENT') return 0
    throw error
  }
}

async function expectCalls(searchCount, answerCount, cancelCount = 0, mysqlCount = 0, datadogCount = 0) {
  assert.deepEqual(await Promise.all([calls(model), calls(answerModel), calls(cancelModel), calls(mysqlModel), calls(datadogModel)]),
    [searchCount, answerCount, cancelCount, mysqlCount, datadogCount], 'Unexpected fake generation count (version/login probes are excluded)')
  assert.equal(await calls('all-generation'), searchCount + answerCount + cancelCount + mysqlCount + datadogCount, 'Unexpected generation model or extra invocation')
}

async function terminal(path, expected = 'SUCCEEDED') {
  const job = await until(() => api(path), value => !['QUEUED', 'RUNNING', 'CANCELLING'].includes(value.status), 'terminal job')
  assert.equal(job.status, expected, `Unexpected terminal state for ${path}`)
  return job
}

try {
  await mkdir(dataDirectory)
  await copyFile(resolve(root, 'scripts/fixtures/fake-investigation-codex.sh'), fixture)
  await chmod(fixture, 0o700)
  await writeFile(resolve(directory, 'investigation-fixture-only'), 'Synthetic smoke fixture only\n', { mode: 0o600 })
  await start()
  const connection = await api('/ai/providers/CODEX/connection', 'POST')
  assert.equal(connection.state, 'AUTHENTICATED')
  await expectCalls(0, 0)
  const workspace = await api('/workspaces', 'POST', { name: '조사 가상 검증' }, 201)
  const other = await api('/workspaces', 'POST', { name: '격리 가상 검증' }, 201)
  const prefix = `/workspaces/${workspace.id}`
  const plans = `${prefix}/investigation-plans`
  const form = new FormData()
  form.set('file', new Blob(['Synthetic Strategy evidence delegates hand selection to Strategy.\nThis document is a synthetic smoke fixture only.\n']), 'Strategy.txt')
  await api(`${prefix}/files`, 'POST', form, 201)
  const original = await api(`${prefix}/chat-threads`, 'POST', { requestId: randomUUID(), question: 'Strategy' }, 201)
  assert.equal(original.payload.status, 'READY')
  assert.equal(original.payload.items[0].title, 'Strategy.txt')
  const threadId = original.payload.chatContext.threadId
  const preview = await api(`${plans}/preview`, 'POST', { bundleId: original.id, provider: 'CODEX', requestedModel: model })
  assert.equal(preview.maxCalls, 1)
  assert.equal(preview.maxToolCalls, 1)
  assert.equal(preview.requestedModel, model)
  assert(preview.prompt.includes('"decision":"SEARCH"'))
  const startRequest = { requestId: randomUUID(), bundleId: original.id, provider: preview.provider,
    requestedModel: preview.requestedModel, digest: preview.digest, approved: true }
  await api(`${plans}/jobs`, 'POST', { ...startRequest, approved: false }, 403)
  await api(`${plans}/jobs`, 'POST', { ...startRequest, requestedModel: answerModel }, 409)
  assert.deepEqual(await api(`${plans}/jobs?bundleId=${original.id}`), [])
  await expectCalls(0, 0)
  const started = await api(`${plans}/jobs`, 'POST', startRequest, 202)
  assert.equal(started.id, startRequest.requestId)
  const planPath = `${plans}/jobs/${started.id}`
  const completed = await terminal(planPath)
  assert.equal(completed.decision, 'SEARCH')
  assert.equal(completed.query, 'Strategy')
  assert.equal(completed.toolStatus, 'OK')
  assert.equal(completed.truncated, false)
  assert(completed.output.includes('Synthetic Strategy evidence'))
  await api(`${planPath}/tool-proposal`, 'GET', undefined, 409)
  await expectCalls(1, 0)
  assert.deepEqual(await api(`${plans}/jobs`, 'POST', startRequest, 202), completed)
  await api(`${plans}/jobs`, 'POST', { ...startRequest, requestId: randomUUID() }, 409)
  await api(`${plans}/jobs`, 'POST', { ...startRequest, requestedModel: answerModel }, 409)
  await api(`/workspaces/${other.id}/investigation-plans/jobs/${started.id}`, 'GET', undefined, 404)
  const captureRequest = { requestId: randomUUID() }
  const captured = await api(`${planPath}/capture`, 'POST', captureRequest, 201)
  assert.notEqual(captured.id, original.id)
  assert.equal(captured.payload.chatContext.parentBundleId, original.id)
  assert.equal(captured.payload.chatContext.threadId, threadId)
  assert.equal(captured.payload.chatContext.retrievalQuery, 'Strategy')
  assert.equal(captured.payload.items[0].title, 'Strategy.txt')
  assert.deepEqual(await api(`${planPath}/capture`, 'POST', captureRequest, 201), captured)
  const answerPath = `${prefix}/evidence-bundles/${captured.id}`
  assert.equal((await api(`${answerPath}/answer-approval`)).status, 'NONE')
  assert.deepEqual(await api(`${prefix}/answer-jobs`), [])
  await api(`${answerPath}/answer-jobs`, 'POST', { requestId: randomUUID(), approvalId: preview.digest,
    digest: preview.digest, approved: true }, 403)
  await expectCalls(1, 0)
  const answerPreview = await api(`${answerPath}/answer-preview`, 'POST', { provider: 'CODEX', requestedModel: answerModel })
  assert.notEqual(answerPreview.digest, preview.digest)
  const approveRequest = { provider: 'CODEX', requestedModel: answerModel, digest: answerPreview.digest,
    acknowledged: true, expectedApprovalId: null }
  await api(`${answerPath}/answer-approval`, 'POST', { ...approveRequest, digest: preview.digest }, 409)
  await api(`${answerPath}/answer-approval`, 'POST', { ...approveRequest, acknowledged: false }, 400)
  const approval = await api(`${answerPath}/answer-approval`, 'POST', approveRequest)
  assert.equal(approval.status, 'APPROVED')
  await expectCalls(1, 0)
  const answerRequest = { requestId: randomUUID(), approvalId: approval.approval.id, digest: answerPreview.digest, approved: true }
  await api(`${answerPath}/answer-jobs`, 'POST', answerRequest, 202)
  const answer = await terminal(`${prefix}/answer-jobs/${answerRequest.requestId}`)
  assert(answer.answer.includes('Synthetic Strategy evidence'))
  assert(answer.answer.includes('[R1]'))
  assert.deepEqual(await api(`${answerPath}/answer-jobs`, 'POST', answerRequest, 202), answer)
  await api(`${answerPath}/answer-jobs`, 'POST', { ...answerRequest, requestId: randomUUID() }, 409)
  await expectCalls(1, 1)

  const cancelPreview = await api(`${plans}/preview`, 'POST', { bundleId: captured.id, provider: 'CODEX', requestedModel: cancelModel })
  const cancelRequest = { requestId: randomUUID(), bundleId: captured.id, provider: 'CODEX', requestedModel: cancelModel,
    digest: cancelPreview.digest, approved: true }
  await api(`${plans}/jobs`, 'POST', cancelRequest, 202)
  await until(() => calls(cancelModel), count => count === 1, 'cancellable fake generation entry')
  const cancelPath = `${plans}/jobs/${cancelRequest.requestId}`
  await api(`${cancelPath}/cancel`, 'POST')
  const cancelled = await terminal(cancelPath, 'CANCELLED')
  assert.equal(cancelled.output, null)
  assert.equal(cancelled.toolStatus, null)
  assert.deepEqual(await api(`${plans}/jobs`, 'POST', cancelRequest, 202), cancelled)
  await api(`${plans}/jobs`, 'POST', { ...cancelRequest, requestId: randomUUID() }, 409)
  await expectCalls(1, 1, 1)

  const proposals = []
  const proposalWorkspace = hold ? await api('/workspaces', 'POST', { name: '제안 UI 가상 검증' }, 201) : workspace
  const proposalPrefix = `/workspaces/${proposalWorkspace.id}`
  const proposalPlans = `${proposalPrefix}/investigation-plans`
  const connections = await api('/plugins')
  const datadogConnection = await api(`/plugins/datadog/${workspace.id}`)
  assert.equal(connections.mysqlConnected, false)
  assert.equal(datadogConnection.configured, false)
  const beforeProposals = await api(`${prefix}/answer-jobs`)
  for (const [kind, selectedModel, requiredFields] of [
    ['MYSQL_READ', mysqlModel, ['table', 'columns', 'equalities', 'limit']],
    ['DATADOG_LOGS', datadogModel, ['environment', 'service', 'indexes', 'from', 'to', 'limit']],
  ]) {
    const proposalBundle = await api(`${proposalPrefix}/chat-threads`, 'POST', { requestId: randomUUID(),
      question: `Strategy actual current request incident evidence SYNTHETIC_PROPOSE_${kind}` }, 201)
    if (hold) assert.equal(proposalBundle.payload.status, 'NO_EVIDENCE', 'Hold proposals must expose manual plan UI without an unapproved answer')
    const proposalPreview = await api(`${proposalPlans}/preview`, 'POST', { bundleId: proposalBundle.id, provider: 'CODEX', requestedModel: selectedModel })
    const request = { requestId: randomUUID(), bundleId: proposalBundle.id, provider: 'CODEX', requestedModel: selectedModel,
      digest: proposalPreview.digest, approved: true }
    await api(`${proposalPlans}/jobs`, 'POST', request, 202)
    const path = `${proposalPlans}/jobs/${request.requestId}`
    const job = await terminal(path)
    assert.equal(job.decision, `PROPOSE_${kind}`)
    for (const field of ['query', 'output', 'toolStatus']) assert.equal(job[field], null)
    assert.equal(job.truncated, false)
    const proposal = await api(`${path}/tool-proposal`)
    assert.deepEqual(proposal, { jobId: job.id, workspaceId: proposalWorkspace.id, bundleId: proposalBundle.id,
      kind, requiredFields, executionAllowed: false })
    await api(`${path}/capture`, 'POST', { requestId: randomUUID() }, 409)
    await api(`/workspaces/${other.id}/investigation-plans/jobs/${job.id}/tool-proposal`, 'GET', undefined, 404)
    assert.deepEqual(await api(`${proposalPlans}/jobs`, 'POST', request, 202), job)
    await api(`${proposalPlans}/jobs`, 'POST', { ...request, requestId: randomUUID() }, 409)
    const stored = await api(`${proposalPlans}/jobs?bundleId=${proposalBundle.id}`)
    assert.deepEqual(stored, [job])
    proposals.push({ request, job, path, proposal, threadId: proposalBundle.payload.chatContext.threadId })
    await expectCalls(1, 1, 1, 1, kind === 'DATADOG_LOGS' ? 1 : 0)
  }
  assert.deepEqual(await api('/plugins'), connections)
  assert.deepEqual(await api(`/plugins/datadog/${workspace.id}`), datadogConnection)
  assert.deepEqual(await api(`${prefix}/answer-jobs`), beforeProposals)
  assert.deepEqual(await api(`${prefix}/generation-jobs`), [])

  await stop()
  await start()
  assert.deepEqual(await api(planPath), completed)
  assert.deepEqual(await api(cancelPath), cancelled)
  assert.deepEqual(await api(`${prefix}/answer-jobs/${answerRequest.requestId}`), answer)
  const restored = await api(`${prefix}/chat-threads/${threadId}`)
  assert.deepEqual(restored.bundles, [original, captured])
  assert(restored.jobs.some(job => job.id === answer.id && job.answer === answer.answer))
  assert.equal((await api(`${answerPath}/answer-approval`)).status, 'CONSUMED')
  assert.deepEqual(await api(`${plans}/jobs`, 'POST', startRequest, 202), completed)
  assert.deepEqual(await api(`${plans}/jobs`, 'POST', cancelRequest, 202), cancelled)
  assert.deepEqual(await api(`${planPath}/capture`, 'POST', captureRequest, 201), captured)
  assert.deepEqual(await api(`${answerPath}/answer-jobs`, 'POST', answerRequest, 202), answer)
  assert.equal((await api('/ai/providers/CODEX/connection', 'POST')).state, 'AUTHENTICATED')
  for (const { request, job, path, proposal } of proposals) {
    assert.deepEqual(await api(path), job)
    assert.deepEqual(await api(`${path}/tool-proposal`), proposal)
    assert.deepEqual(await api(`${proposalPlans}/jobs`, 'POST', request, 202), job)
    await api(`${path}/capture`, 'POST', { requestId: randomUUID() }, 409)
  }
  assert.deepEqual(await api(`${prefix}/answer-jobs`), beforeProposals)
  assert.equal((await api('/plugins')).mysqlConnected, false)
  assert.equal((await api(`/plugins/datadog/${workspace.id}`)).configured, false)
  await expectCalls(1, 1, 1, 1, 1)
  assert.equal(sha256(await readFile(jar)), jarSha256, 'JAR changed during runtime checks')
  console.log('PASS: existing fake search/capture/answer/cancellation plus two typed operational proposals and restart; 5 fake generation calls, 0 real AI calls. No operational execute or model-download endpoint called.')
  console.log(JSON.stringify({ status: 'PASS', mode: 'simulated', fixtureOnly: true,
    fixtureVersion: 'investigation-proposals-v2', executedAt: new Date().toISOString(), jarSha256,
    searchModels: { enabled: !!models, assets: models?.hashes ?? null, readiness: modelReadiness },
    scriptSha256: sha256(await readFile(fileURLToPath(import.meta.url))), fixtureSha256: sha256(await readFile(fixture)),
    fakeGenerationCalls: { search: 1, answer: 1, cancelled: 1, mysqlProposal: 1, datadogProposal: 1, total: 5 }, realAiCalls: 0,
    proposals: proposals.map(({ job, proposal }) => ({ jobId: job.id, decision: job.decision, proposal })),
    checks: ['SEARCH capture still creates immutable evidence', 'SEARCH tool-proposal rejected', 'proposal exact DTO without scope',
      'proposal capture rejected', 'cross-workspace access rejected', 'completed proposal persisted', 'same-request replay and restart do not regenerate',
      'MySQL and Datadog remain unconfigured', 'no new answer or generation jobs from proposals'],
    acceptanceUpdated: false,
    limitation: 'Explicit synthetic prompt markers drive a fake CLI, not model reasoning or semantic intent evaluation. No DB/log execute endpoint is called and no operational connection is configured. This is not an OS network sandbox or real AI acceptance evidence.' }, null, 2))
  if (hold) {
    console.log(JSON.stringify({ status: 'INVESTIGATION_SMOKE_HOLD', base, workspaceId: workspace.id, threadId,
      originalBundleId: original.id, bundleId: captured.id, planJobId: completed.id, answerJobId: answer.id, cancelledJobId: cancelled.id,
      searchReady: !!models && modelReadiness.length === 2,
      proposals: proposals.map(({ job, proposal, threadId: proposalThreadId }) => ({ workspaceId: proposal.workspaceId,
        bundleId: proposal.bundleId, jobId: job.id, threadId: proposalThreadId, kind: proposal.kind })) }))
    if (!shutdown.signal.aborted) await once(shutdown.signal, 'abort')
  }
} catch (error) {
  if (!shutdown.signal.aborted) {
    console.error(`FAIL: ${error.message}`)
    process.exitCode = 1
  }
} finally {
  try { await stop() } finally {
    await rm(directory, { recursive: true, force: true })
    process.removeListener('SIGINT', onSignal)
    process.removeListener('SIGTERM', onSignal)
  }
}
