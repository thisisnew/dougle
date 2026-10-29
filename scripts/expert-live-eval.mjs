import assert from 'node:assert/strict'
import { execFile, spawn } from 'node:child_process'
import { promisify } from 'node:util'
import { once } from 'node:events'
import { createHash, randomBytes, randomUUID } from 'node:crypto'
import { chmod, copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { constants } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve, isAbsolute } from 'node:path'
import { fileURLToPath } from 'node:url'
import { executionDiagnostic } from './expert-live-diagnostics.mjs'
import { reservePersistentCall } from './expert-live-budget.mjs'
import { seedQuestions as lossSeedQuestions, validateLostContext, validateLostPrompt } from './expert-live-loss-fixtures.mjs'
import { requireSuccessfulAnswer, restartAndValidate, assertThreadExtends, manualCriteriaFor } from './expert-live-history.mjs'
import { holdAtRestart } from './expert-live-ui-hold.mjs'
import { proposalCases, proposalFields } from './expert-live-proposal-fixtures.mjs'
import { scheduledDispatches, createVariantBudget, validateMemoryContext, memoryAnswerCheck, requireMemoryPlan, validateMemoryPrompt } from './expert-live-schedule.mjs'
import { requireSemanticReview } from './expert-live-semantic-review.mjs'
import { verifySearchModels } from './expert-live-search-models.mjs'
import { prepareProtocolHook, collectProtocolCounters } from './expert-live-protocol-hook.mjs'
import { contextApiAllowed, knownArtifacts, validateContextConfiguration, validatePinnedRuntimeConfiguration, isolatedRuntimeSettings } from './expert-live-context-fixtures.mjs'
import { readSeedReport, seededAnswerSql, validateDiagnosticConfiguration, verifySeededContext } from './expert-live-context-diagnostic-fixtures.mjs'
const proposalOnly = process.env.FORGE_EXPERT_MODE === 'proposal'
const contextDiagnostic = process.env.FORGE_EXPERT_MODE === 'context-diagnostic'
const contextLoss = process.env.FORGE_EXPERT_MODE === 'context-loss'
const contextOnly = contextLoss || contextDiagnostic || process.env.FORGE_EXPERT_MODE === 'context'
const memoryOnly = process.env.FORGE_EXPERT_MODE === 'memory'
const isolatedRuntime = contextOnly || memoryOnly
const localOnly = contextOnly || memoryOnly || process.env.FORGE_EXPERT_MODE === 'local'
assert.ok(process.argv.slice(2).every(argument => argument === '--hold-at-restart'), 'Unknown evaluation option')
const holdForUi = process.argv.includes('--hold-at-restart')
const investigation = localOnly || process.env.FORGE_EXPERT_MODE === 'investigation'
const fixtureFile = contextLoss ? 'expert-live-loss-fixtures.mjs' : contextDiagnostic ? 'expert-live-context-diagnostic-fixtures.mjs' : contextOnly ? 'expert-live-context-fixtures.mjs' : memoryOnly ? 'expert-live-memory-fixtures.mjs' : localOnly ? 'expert-live-local-fixtures.mjs'
  : investigation ? 'expert-live-investigation-fixtures.mjs' : 'expert-live-fixtures.mjs'
const fixtures = await import('./' + fixtureFile)
const { documents } = fixtures
const availableScenarios = memoryOnly ? [fixtures.scenario] : fixtures.scenarios
const selectedIds = process.env.FORGE_EXPERT_SCENARIOS?.split(',')
const scenarios = selectedIds ? selectedIds.map(id => availableScenarios.find(scenario => scenario.id === id)) : availableScenarios
assert.ok(scenarios.length && scenarios.every(Boolean), 'Unknown scenario selection')
assert.equal(new Set(scenarios.map(scenario => scenario.id)).size, scenarios.length, 'Duplicate scenario selection')
const maximumDispatches = Number(process.env.FORGE_EXPERT_MAX_CALLS || (investigation ? 8 : 30))
const variants = Number(process.env.FORGE_EXPERT_VARIANTS || (investigation ? 1 : 3))
const variantStart = Number(process.env.FORGE_EXPERT_VARIANT_START || 0)
assert.ok(Number.isSafeInteger(maximumDispatches) && maximumDispatches > 0, 'Positive bounded batch size required')
assert.ok(Number.isSafeInteger(variantStart) && variantStart >= 0, 'Invalid starting variant')
assert.ok(Number.isSafeInteger(variants) && variants > 0 && scenarios.every(scenario => scenario.questions.length >= variantStart + variants), 'Invalid variant count')
assert.ok((proposalOnly ? proposalCases.length : scheduledDispatches(scenarios, variantStart, variants, investigation ? 2 : 1)) <= maximumDispatches, 'Select scenarios fitting the bounded batch')
if (contextOnly) {
  if (contextLoss) {
    assert.equal(maximumDispatches, 2)
    validateContextConfiguration(process.env, 8, variants, variantStart)
    assert(isAbsolute(process.env.FORGE_EXPERT_BUDGET_JOURNAL || ''), 'Explicit persistent budget journal required')
    assert.match(process.env.FORGE_EXPERT_BUDGET_APPROVAL_ID || '', /^[A-Za-z0-9_-]{1,120}$/)
    assert(Number.isSafeInteger(Number(process.env.FORGE_EXPERT_BUDGET_LIMIT))
      && Number(process.env.FORGE_EXPERT_BUDGET_LIMIT) > 0, 'Explicit approved persistent limit required')
  } else if (contextDiagnostic) validateDiagnosticConfiguration(process.env, maximumDispatches, variants, variantStart)
  else validateContextConfiguration(process.env, maximumDispatches, variants, variantStart)
  assert.equal(holdForUi, false)
}
if (memoryOnly) {
  assert.equal(variants, 1, 'Only one separately authorized memory variant per invocation')
  assert.equal(maximumDispatches, 64, 'Memory variant requires an explicit 64-dispatch cap')
  assert.equal(process.env.FORGE_EXPERT_MEMORY_VARIANT_APPROVED, String(variantStart), 'Separate finite variant authorization required')
  validatePinnedRuntimeConfiguration(process.env)
  assert(isAbsolute(process.env.FORGE_EXPERT_BUDGET_JOURNAL || ''), 'Explicit persistent budget journal required')
  assert.match(process.env.FORGE_EXPERT_BUDGET_APPROVAL_ID || '', /^[A-Za-z0-9_-]{1,120}$/)
  assert(Number.isSafeInteger(Number(process.env.FORGE_EXPERT_BUDGET_LIMIT))
    && Number(process.env.FORGE_EXPERT_BUDGET_LIMIT) > 0, 'Explicit approved persistent limit required')
  assert.match(process.env.FORGE_EXPERT_READONLY_ARTIFACT_SHA256 || '', /^[a-f0-9]{64}$/)
  assert.equal(process.env.FORGE_EXPERT_READONLY_ARTIFACT_SHA256, process.env.FORGE_EXPERT_JAR_SHA256)
  assert.notEqual(process.env.FORGE_EXPERT_JAR_SHA256, '675a7c59e13edc8ee849b23977699e600331555d8f53c669ef8e7f10406e3489')
}
if (holdForUi) {
  assert.ok(localOnly && scenarios.every(scenario => scenario.restartBeforeTurn === 1), 'UI hold requires restart scenarios')
  assert.match(process.env.FORGE_EXPERT_READONLY_ARTIFACT_SHA256 || '', /^[a-f0-9]{64}$/)
  assert.equal(process.env.FORGE_EXPERT_READONLY_ARTIFACT_SHA256, process.env.FORGE_EXPERT_JAR_SHA256, 'Explicit future read-only artifact pin required')
  assert.notEqual(process.env.FORGE_EXPERT_JAR_SHA256, '675a7c59e13edc8ee849b23977699e600331555d8f53c669ef8e7f10406e3489', 'Old outbound-capable artifact is not approved for UI hold')
}

assert.equal(process.env.FORGE_EXPERT_LIVE, 'approved', 'Explicit synthetic external AI evaluation approval required')
assert.ok(process.env.FORGE_EXPERT_PROFILE, 'Dedicated authenticated profile required; never read or copy credentials')
assert.ok(process.env.FORGE_EXPERT_REPORT, 'Choose a new private report path')
const root = fileURLToPath(new URL('../', import.meta.url))
const resources = isolatedRuntime ? resolve(process.env.FORGE_EXPERT_RESOURCES) : '/Applications/Dougle.app/Contents/Resources/resources'
const sourceJar = isolatedRuntime ? resolve(process.env.FORGE_EXPERT_JAR) : resolve(root, 'backend/target/dougle-0.1.0.jar')
const reportPath = resolve(process.env.FORGE_EXPERT_REPORT)
const directory = await mkdtemp(resolve(tmpdir(), 'dougle-expert-live-'))
const jar = isolatedRuntime ? resolve(directory, `frozen-context-${knownArtifacts[process.env.FORGE_EXPERT_JAR_SHA256]}.jar`) : sourceJar
const privateHome = resolve(directory, 'home')
let token = randomBytes(32).toString('hex')
const report = { fixtureVersion: investigation ? 'synthetic-investigation-v3' : 'synthetic-expert-v1', startedAt: new Date().toISOString(), maximumDispatches,
  scenarios: scenarios.map(scenario => scenario.id), variants, variantStart,
  dispatched: 0, planDispatches: 0, answerDispatches: 0, dispatches: [], results: [], observedModelId: null,
  scope: 'Real model; isolated synthetic documents; no operational tools or user database. Dispatch counts include uncertain acceptance, not proven provider invocations. Automatic checks require manual review.' }
if (proposalOnly) {
  report.fixtureVersion = 'synthetic-manual-proposals-v1'
  report.scenarios = ['manual-mysql-proposals', 'manual-datadog-proposals']
  report.apiTrace = []
}
report.apiTrace = []
report.restartChecks = []
report.runtimes = []
if (localOnly) report.fixtureVersion = 'synthetic-local-restart-glossary-v1'
if (memoryOnly) { report.fixtureVersion = 'synthetic-memory-v1'; report.fullE05Pass = false; report.memoryChecks = [] }
if (isolatedRuntime) {
  report.artifactVersion = knownArtifacts[process.env.FORGE_EXPERT_JAR_SHA256]
  report.privateDirectory = directory
  report.runtimeIsolation = 'private HOME/temp, explicit allowlisted environment, frozen JAR, pinned Java/CLI resources'
}
if (contextOnly) {
  report.fixtureVersion = 'synthetic-short-context-correction-v1'
  report.contextBundles = []; report.fullE04Pass = false; report.fullE05Pass = false
}
let seededTurns
if (contextLoss) report.fixtureVersion = 'synthetic-seeded-loss-awareness-v1'
if (contextDiagnostic) {
  report.fixtureVersion = 'synthetic-seeded-b1-implicit-diagnostic-v1'
  report.seededHistoryLimitation = 'Exact prior questions/answers seeded into fresh local bundles and terminal jobs; new IDs/digests; not uninterrupted third turn or identical full serialized prompt.'
}
let variantBudget = null
let child
let base
let logs = ''
let reportCreated = false
let uiHoldActive = false
let protocolHook = null
const pause = milliseconds => new Promise(done => setTimeout(done, milliseconds))
const shutdown = new AbortController()
const onSignal = () => shutdown.abort()
const batchTimer = contextOnly ? setTimeout(onSignal, 30 * 60 * 1000) : null
process.on('SIGINT', onSignal)
process.on('SIGTERM', onSignal)
async function save() { await writeFile(reportPath, JSON.stringify(report, null, 2), { mode: 0o600 }) }
async function dispatch(kind, requestId, context) {
  assert.ok(report.dispatched < maximumDispatches, 'Bounded batch exhausted')
  const verified = await verifySearchModels(root, report.searchModels.directory)
  assert.equal(verified.directory, report.searchModels.directory, 'Model directory changed')
  report.lastModelVerificationAt = verified.verifiedAt
  variantBudget?.reserve(kind, requestId)
  if (memoryOnly || contextLoss) {
    const grant = { approvalId: process.env.FORGE_EXPERT_BUDGET_APPROVAL_ID,
      jarSha256: process.env.FORGE_EXPERT_JAR_SHA256, limit: Number(process.env.FORGE_EXPERT_BUDGET_LIMIT) }
    const sequence = await reservePersistentCall(process.env.FORGE_EXPERT_BUDGET_JOURNAL, grant, { kind, requestId })
    report.persistentBudget = { ...grant, reservedThrough: sequence }
  }
  report.dispatched++
  report[kind + 'Dispatches']++
  report.dispatches.push({ kind, requestId, at: new Date().toISOString(), ...context })
  await save()
}
async function api(path, method = 'GET', body) {
  if (isolatedRuntime) assert(contextApiAllowed(path, method), 'Context batch route outside synthetic local/AI allowlist')
  assert.ok(!uiHoldActive || method === 'GET', 'No POST while waiting for UI acknowledgment')
  if (method === 'POST') variantBudget?.beforePost(path, body)
  if (proposalOnly) {
    assert.ok(method === 'GET' || method === 'POST' && (path === '/workspaces'
      || path === '/ai/providers/CODEX/connection'
      || /^\/workspaces\/[^/]+\/(sources(?:\/[^/]+\/sync)?|chat-threads|investigation-plans\/(preview|jobs))$/.test(path)),
    'Plan-only evaluation must not execute operational tools or request answers')
  }
  if (localOnly) assert.ok(!/\/(mysql|datadog|browser|slack|operational)(?:\/|$)/.test(path), 'Local-only batch forbids operational routes')
  report.apiTrace.push({ method, path })
  const response = await fetch(base + '/api' + path, { method, redirect: 'error', signal: AbortSignal.any([shutdown.signal, AbortSignal.timeout(180000)]),
    headers: { 'X-Forge-Session': token, 'X-Forge-Client': '1', 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body) })
  if (!response.ok) throw Error(`HTTP ${response.status}: ${path}: ${(await response.text()).slice(0, 250)}`)
  return response.status === 204 ? null : response.json()
}
async function waitFor(path, complete) {
  const deadline = Date.now() + 180000
  while (Date.now() < deadline) {
    const value = await api(path)
    if (complete(value)) return value
    await pause(300)
  }
  throw Error('Evaluation deadline exceeded')
}
async function startRuntime() {
  assert.equal(createHash('sha256').update(await readFile(jar)).digest('hex'), report.jarSha256, 'JAR changed before restart')
  const models = await verifySearchModels(root, process.env.FORGE_EXPERT_SEARCH_MODELS)
  if (report.searchModels) assert.equal(models.directory, report.searchModels.directory, 'Model path changed before restart')
  report.searchModels = models
  await save()
  token = randomBytes(32).toString('hex')
  base = null
  logs = ''
  report.launchFailed = false
  if (isolatedRuntime) {
    for (const [name, expected] of Object.entries(report.assetHashes)) {
      assert.equal(createHash('sha256').update(await readFile(resolve(resources, name))).digest('hex'), expected, 'Frozen resource changed')
    }
  }
  const isolation = isolatedRuntime ? isolatedRuntimeSettings(privateHome, directory) : null
  child = spawn(resolve(resources, 'runtime/bin/java'), [...(isolation?.args ?? []), '-jar', jar,
    '--forge.desktop=true', '--server.address=127.0.0.1', '--server.port=0', `--forge.data-dir=${directory}`,
    `--forge.search-model-directory=${models.directory}`,
    `--forge.ai.codex-command=${protocolHook?.command ?? resolve(resources, 'ai/codex')}`,
    `--forge.ai.codex-stream-home=${resolve(process.env.FORGE_EXPERT_PROFILE)}`],
  { cwd: directory, detached: true, env: isolation?.env
    ?? { ...process.env, FORGE_DATA_DIR: directory, FORGE_DESKTOP: 'true', JAVA_TOOL_OPTIONS: '', JDK_JAVA_OPTIONS: '', _JAVA_OPTIONS: '' }, stdio: ['pipe', 'pipe', 'pipe'] })
  child.on('error', () => { report.launchFailed = true })
  for (const pipe of [child.stdout, child.stderr]) pipe.on('data', chunk => { logs = (logs + chunk).slice(-16000) })
  child.stdin.write(token + '\n')
  const deadline = Date.now() + 120000
  while (!base && Date.now() < deadline) {
    shutdown.signal.throwIfAborted()
    assert.equal(child.exitCode, null, 'Evaluation backend exited')
    assert.ok(!report.launchFailed, 'Evaluation process failed to start')
    const port = logs.match(/^FORGE_DESKTOP_READY:(\d+)$/m)?.[1]
    if (port) base = `http://127.0.0.1:${port}`
    else await pause(200)
  }
  assert.ok(base, 'Evaluation startup timed out')
  const engine = await waitFor('/search-model', value => ['READY', 'ERROR'].includes(value.state))
  assert.equal(engine.state, 'READY', engine.error)
  report.runtimes.push({ pid: child.pid, port: Number(new URL(base).port), startedAt: new Date().toISOString() })
}
async function stopRuntime() {
  if (!child?.pid) return
  let forceTimer
  let deadlineTimer
  try {
    if (child.exitCode === null && child.signalCode === null) {
      const ended = once(child, 'exit')
      child.stdin.end()
      forceTimer = setTimeout(() => { try { process.kill(-child.pid, 'SIGKILL') } catch (error) { if (error.code !== 'ESRCH') throw error } }, 15000)
      await Promise.race([ended, new Promise((resolve, reject) => {
        deadlineTimer = setTimeout(() => reject(Error('Owned runtime shutdown deadline exceeded')), 20000)
      })])
    }
  } finally {
    clearTimeout(forceTimer)
    clearTimeout(deadlineTimer)
    try { process.kill(-child.pid, 'SIGKILL') } catch (error) { if (error.code !== 'ESRCH') throw error }
  }
  const runtime = report.runtimes.find(entry => entry.pid === child.pid)
  if (runtime) runtime.stoppedAt = new Date().toISOString()
}
async function historySnapshot(prefix, threadId) {
  const detail = await api(prefix + '/chat-threads/' + threadId)
  const history = await api(prefix + '/chat-threads')
  const evidence = []
  const plans = []
  for (const bundle of detail.bundles) {
    evidence.push(await api(prefix + '/evidence-bundles/' + bundle.id))
    plans.push(await api(prefix + '/investigation-plans/jobs?bundleId=' + bundle.id))
  }
  return { detail, history, evidence, plans, answerJobs: await api(prefix + '/answer-jobs'),
    generationJobs: await api(prefix + '/generation-jobs') }
}
try {
  await writeFile(reportPath, '{}', { flag: 'wx', mode: 0o600 })
  reportCreated = true
  if (isolatedRuntime) {
    assert.equal(createHash('sha256').update(await readFile(sourceJar)).digest('hex'), process.env.FORGE_EXPERT_JAR_SHA256)
    await copyFile(sourceJar, jar, constants.COPYFILE_EXCL); await chmod(jar, 0o400)
    await mkdir(privateHome, { mode: 0o700 })
    report.assetHashes = {}
    for (const name of ['runtime/bin/java', 'ai/codex']) report.assetHashes[name] = createHash('sha256').update(await readFile(resolve(resources, name))).digest('hex')
    report.resourceDirectory = resources
  }
  if (contextDiagnostic) {
    const bytes = await readFile(resolve(root, 'docs/evaluations/context-live-025-b1-20261026.json'))
    seededTurns = readSeedReport(bytes)
    report.seedReportSha256 = createHash('sha256').update(bytes).digest('hex')
    report.seededTurns = seededTurns
  }
  report.harnessSha256 = createHash('sha256').update(await readFile(fileURLToPath(import.meta.url))).digest('hex')
  report.fixtureSha256 = createHash('sha256').update(await readFile(resolve(root, 'scripts', fixtureFile))).digest('hex')
  report.historyHelperSha256 = createHash('sha256').update(await readFile(resolve(root, 'scripts/expert-live-history.mjs'))).digest('hex')
  report.uiHoldHelperSha256 = createHash('sha256').update(await readFile(resolve(root, 'scripts/expert-live-ui-hold.mjs'))).digest('hex')
  for (const helper of ['schedule', 'semantic-review', 'search-models']) report[helper + 'HelperSha256'] = createHash('sha256')
    .update(await readFile(resolve(root, `scripts/expert-live-${helper}.mjs`))).digest('hex')
  if (proposalOnly) report.proposalFixtureSha256 = createHash('sha256').update(await readFile(resolve(root, 'scripts/expert-live-proposal-fixtures.mjs'))).digest('hex')
  report.jarSha256 = createHash('sha256').update(await readFile(jar)).digest('hex')
  if (process.env.FORGE_EXPERT_JAR_SHA256) assert.equal(report.jarSha256, process.env.FORGE_EXPERT_JAR_SHA256, 'JAR changed; do not run a different artifact')
  protocolHook = await prepareProtocolHook({ environment: process.env, executable: resolve(resources, 'ai/codex'),
    ownedDirectory: directory, maximumDispatches, jarSha256: report.jarSha256 })
  if (protocolHook) report.protocolCounter = protocolHook.metadata
  await startRuntime()
  const connection = await api('/ai/providers/CODEX/connection', 'POST')
  report.connection = connection
  await save()
  assert.equal(connection.state, 'AUTHENTICATED', 'Existing app login unavailable; no fallback or automatic login')
  console.log('Isolated backend, search engine and existing app authentication ready')
  const workspace = await api('/workspaces', 'POST', { name: '가상 전문가 평가' })
  const defaultPrefix = `/workspaces/${workspace.id}`
  const sourcePath = resolve(directory, 'synthetic-documents')
  await mkdir(sourcePath)
  for (const [name, content] of Object.entries(documents)) await writeFile(resolve(sourcePath, name), content)
  async function importSyntheticSource(prefix) {
    const source = await api(`${prefix}/sources`, 'POST', { name: '가상 자료', kind: 'LOCAL_FOLDER', location: sourcePath, domain: '' })
    const synced = await api(`${prefix}/sources/${source.id}/sync`, 'POST')
    assert.equal(synced.status, 'READY', synced.error)
    return source.id
  }
  const syntheticSourceId = await importSyntheticSource(defaultPrefix)
  report.syntheticSourceId = syntheticSourceId
  if (proposalOnly) {
    assert.deepEqual(await api(`${defaultPrefix}/answer-jobs`), [])
    assert.deepEqual(await api(`${defaultPrefix}/generation-jobs`), [])
    for (const [variant, fixture] of proposalCases.entries()) {
      const started = Date.now()
      const bundle = await api(`${defaultPrefix}/chat-threads`, 'POST', { requestId: randomUUID(), question: fixture.question })
      const plans = `${defaultPrefix}/investigation-plans`
      const preview = await api(plans + '/preview', 'POST', { bundleId: bundle.id, provider: 'CODEX' })
      const request = { requestId: randomUUID(), bundleId: bundle.id, provider: preview.provider,
        requestedModel: preview.requestedModel, digest: preview.digest, approved: true }
      await dispatch('plan', request.requestId, { scenario: fixture.kind, variant, question: fixture.question, request })
      const job = await api(plans + '/jobs', 'POST', request)
      const plan = await waitFor(plans + '/jobs/' + job.id, value => ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(value.status))
      report.dispatches.at(-1).result = plan
      report.dispatches.at(-1).diagnostic = executionDiagnostic('plan', plan.status, plan.error)
      await save()
      assert.equal(plan.status, 'SUCCEEDED', plan.error)
      assert.equal(plan.decision, `PROPOSE_${fixture.kind}`, 'Operational proposal misrouting; stop without retry')
      for (const field of ['query', 'output', 'toolStatus']) assert.equal(plan[field], null)
      assert.equal(plan.truncated, false)
      const proposal = await api(plans + '/jobs/' + job.id + '/tool-proposal')
      assert.deepEqual(proposal, { jobId: job.id, workspaceId: workspace.id, bundleId: bundle.id,
        kind: fixture.kind, requiredFields: proposalFields(fixture.kind), executionAllowed: false })
      assert.equal((await api(`${defaultPrefix}/evidence-bundles/${bundle.id}/answer-approval`)).status, 'NONE')
      assert.deepEqual(await api(`${defaultPrefix}/answer-jobs`), [])
      assert.deepEqual(await api(`${defaultPrefix}/generation-jobs`), [])
      report.results.push({ variant, question: fixture.question, expectedDecision: `PROPOSE_${fixture.kind}`,
        plan, proposal, originalEvidence: bundle.payload.items.map(item => ({ label: item.label, title: item.title, quote: item.quote })),
        elapsedMs: Date.now() - started, answer: null, manualVerdict: 'NOT_REVIEWED' })
      await save()
      console.log(JSON.stringify({ variant, decision: plan.decision, dispatched: report.dispatched }))
    }
  }
  if (!proposalOnly) for (let variant = variantStart; variant < variantStart + variants; variant++) {
    variantBudget = memoryOnly ? createVariantBudget(64) : null
    for (const scenario of scenarios) {
      const prefix = scenario.noInitialEvidence
        ? `/workspaces/${(await api('/workspaces', 'POST', { name: `가상 빈 근거 ${variant}` })).id}` : defaultPrefix
      let threadId = null
      let parentBundleId = null
      let expectedBundles = 0
      let restartedSnapshot = null
      const priorMemoryTurns = []
      if (contextLoss) {
        report.lossSeedBundles = []
        for (const question of lossSeedQuestions) {
          const seeded = await api(prefix + '/chat-threads', 'POST', { requestId: randomUUID(), threadId, parentBundleId, question })
          threadId = seeded.payload.chatContext.threadId
          parentBundleId = seeded.id
          expectedBundles++
          report.lossSeedBundles.push({ id: seeded.id, digest: seeded.digest, question })
        }
        assert.equal(report.dispatched, 0)
        assert.deepEqual(await api(prefix + '/answer-jobs'), [])
        await save()
      }
      if (contextDiagnostic) {
        for (const seeded of seededTurns) {
          const bundle = await api(prefix + '/chat-threads', 'POST', { requestId: randomUUID(), threadId, parentBundleId, question: seeded.question })
          threadId = bundle.payload.chatContext.threadId; parentBundleId = bundle.id; expectedBundles++
          await stopRuntime()
          assert(child.exitCode !== null || child.signalCode !== null, 'Seed only after owned backend exits')
          await promisify(execFile)('/usr/bin/sqlite3', ['-batch', '-bail', resolve(directory, 'dougle.sqlite'), seededAnswerSql(bundle, seeded.answer, randomUUID())],
            { timeout: 5000, maxBuffer: 16384 })
          await startRuntime()
          const restored = await api(prefix + '/chat-threads/' + threadId)
          assert(restored.jobs.some(job => job.bundleId === bundle.id && job.status === 'SUCCEEDED' && job.answer === seeded.answer))
        }
        report.seededHistory = await historySnapshot(prefix, threadId)
        assert.equal(report.dispatched, 0, 'Seeding must not dispatch AI')
        await save()
      }
      for (let turn = 0; turn < scenario.questions[variant].length; turn++) {
        if (scenario.restartBeforeTurn === turn) {
          const before = await historySnapshot(prefix, threadId)
          const check = { scenario: scenario.id, variant, before, status: 'STARTED', uiReopenObserved: false }
          report.restartChecks.push(check)
          await save()
          restartedSnapshot = await restartAndValidate({ stop: stopRuntime, start: startRuntime,
            snapshot: () => historySnapshot(prefix, threadId) }, before)
          check.after = restartedSnapshot
          check.status = 'RESTORED'
          await save()
          if (holdForUi) {
            check.status = 'WAIT_UI_REOPEN'
            await save()
            uiHoldActive = true
            try {
              check.uiCheckpoint = await holdAtRestart({ ownedDirectory: directory, backend: base, session: token,
                binding: { artifactSha256: report.jarSha256, workspaceId: workspace.id, threadId, variant,
                  bundleIds: before.detail.bundles.map(bundle => bundle.id), bundleDigests: before.detail.bundles.map(bundle => bundle.digest) },
                signal: shutdown.signal, snapshot: () => historySnapshot(prefix, threadId), before: restartedSnapshot,
                notify: details => console.log(JSON.stringify({ event: 'WAIT_UI_REOPEN', ...details })) })
              check.uiReopenObserved = true
              check.status = 'UI_REOPEN_VERIFIED'
              await save()
            } finally { uiHoldActive = false }
          }
        }
        const started = Date.now()
        const question = scenario.questions[variant][turn]
        let bundle = await api(`${prefix}/chat-threads`, 'POST', { requestId: randomUUID(), threadId, parentBundleId, question })
        if (contextDiagnostic) verifySeededContext(bundle, seededTurns)
        if (contextLoss) report.lossContext = validateLostContext(bundle)
        if (contextOnly) { report.contextBundles.push({ turn, bundle }); await save() }
        if (memoryOnly) {
          report.memoryChecks.push({ variant, turn, bundle, validation: 'PENDING' })
          await save()
          const check = report.memoryChecks.at(-1)
          check.metric = validateMemoryContext(bundle, priorMemoryTurns, scenario, turn)
          assert.ok(bundle.payload.items.length > 0, 'Memory fixture lacks source evidence')
          assert.ok(bundle.payload.items.every(item => item.sourceId === syntheticSourceId
            && (item.supports ?? []).every(support => support.sourceId === syntheticSourceId)), 'Unexpected evidence source ID')
          check.validation = 'VALID'
          await save()
        }
        const originalBundleId = bundle.id
        const originalStatus = bundle.payload.status
        const originalEvidence = bundle.payload.items.map(item => ({ label: item.label, title: item.title, quote: item.quote }))
        if (scenario.noInitialEvidence && turn === 0) {
          assert.equal(originalStatus, 'NO_EVIDENCE')
          assert.equal(originalEvidence.length, 0)
          assert.equal((await api(`${prefix}/evidence-bundles/${bundle.id}/answer-approval`)).status, 'NONE')
          await importSyntheticSource(prefix)
        }
        expectedBundles++
        threadId = bundle.payload.chatContext.threadId
        parentBundleId = bundle.id
        let plan = null
        if (investigation) {
          const plans = `${prefix}/investigation-plans`
          const preview = await api(plans + '/preview', 'POST', { bundleId: bundle.id, provider: 'CODEX' })
          if (contextLoss) validateLostPrompt(preview.prompt)
          assert.equal(preview.maxCalls, 1)
          assert.equal(preview.maxToolCalls, 1)
          if (memoryOnly) {
            report.memoryChecks.at(-1).planPromptContext = validateMemoryPrompt(preview.prompt, bundle.payload.chatContext, scenario.maximumContextCharacters)
            report.memoryChecks.at(-1).planPromptSha256 = createHash('sha256').update(preview.prompt).digest('hex')
            await save()
          }
          const request = { requestId: randomUUID(), bundleId: bundle.id, provider: preview.provider,
            requestedModel: preview.requestedModel, digest: preview.digest, approved: true }
          await dispatch('plan', request.requestId, { scenario: scenario.id, variant, turn, question })
          const startedPlan = await api(plans + '/jobs', 'POST', request)
          plan = await waitFor(plans + '/jobs/' + startedPlan.id, value => ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(value.status))
          report.dispatches.at(-1).result = plan
          report.dispatches.at(-1).diagnostic = executionDiagnostic('plan', plan.status, plan.error)
          await save()
          assert.equal(plan.status, 'SUCCEEDED', plan.error)
          if (contextOnly) assert.equal(plan.decision, 'STOP', 'Routine synthetic definition must not propose or execute tools')
          if (memoryOnly) requireMemoryPlan(plan)
          else assert.deepEqual(await api(plans + '/jobs', 'POST', request), plan, 'Plan replay must reconcile without new generation')
          if (plan.decision === 'SEARCH') {
            assert.equal(plan.toolStatus, 'OK')
            const original = bundle
            const captureRequest = { requestId: randomUUID() }
            bundle = await api(plans + '/jobs/' + plan.id + '/capture', 'POST', captureRequest)
            assert.deepEqual(await api(plans + '/jobs/' + plan.id + '/capture', 'POST', captureRequest), bundle)
            assert.notEqual(bundle.id, original.id)
            assert.equal(bundle.payload.chatContext.threadId, threadId)
            assert.equal(bundle.payload.chatContext.parentBundleId, original.id)
            assert.equal((await api(`${prefix}/evidence-bundles/${bundle.id}/answer-approval`)).status, 'NONE')
            expectedBundles++
            parentBundleId = bundle.id
          } else assert.equal(plan.decision, 'STOP')
          if (scenario.noInitialEvidence && turn === 0) {
            assert.equal(plan.decision, 'SEARCH', 'No-evidence recovery must exercise a real SEARCH')
            assert.ok(bundle.payload.items.some(item => item.title === 'Strategy.java'), 'Recovery must capture target source')
            const restored = await api(`${prefix}/chat-threads/${threadId}`)
            const original = restored.bundles.find(value => value.id === originalBundleId)
            assert.equal(original.payload.status, 'NO_EVIDENCE', 'Original bundle must stay immutable')
            assert.equal(original.payload.items.length, 0)
          }
        }
        let answer = ''
        let status = 'NO_EVIDENCE'
        let model = null
        if (bundle.payload.items.length) {
          const path = `${prefix}/evidence-bundles/${bundle.id}`
          const preview = await api(path + '/answer-preview', 'POST', { provider: 'CODEX' })
          if (contextLoss) validateLostPrompt(preview.prompt)
          if (memoryOnly) {
            report.memoryChecks.at(-1).answerPromptContext = validateMemoryPrompt(preview.prompt, bundle.payload.chatContext, scenario.maximumContextCharacters)
            report.memoryChecks.at(-1).answerPromptSha256 = createHash('sha256').update(preview.prompt).digest('hex')
            await save()
          }
          if (plan) assert.notEqual(preview.digest, plan.digest, 'Answer approval must not reuse planning digest')
          model = preview.requestedModel
          const approval = await api(path + '/answer-approval', 'POST', { provider: 'CODEX', requestedModel: model,
            digest: preview.digest, acknowledged: true, expectedApprovalId: null })
          const answerRequest = { requestId: randomUUID(), approvalId: approval.approval.id, digest: preview.digest, approved: true }
          await dispatch('answer', answerRequest.requestId, { scenario: scenario.id, variant, turn, question })
          const job = await api(path + '/answer-jobs', 'POST', answerRequest)
          const finished = await waitFor(`${prefix}/answer-jobs/${job.id}`, value => ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(value.status))
          if (finished.status === 'SUCCEEDED' && !memoryOnly) assert.deepEqual(await api(path + '/answer-jobs', 'POST', answerRequest), finished, 'Answer replay must reconcile without new generation')
          if (memoryOnly) report.memoryChecks.at(-1).answerJob = finished
          report.dispatches.at(-1).status = finished.status
          report.dispatches.at(-1).diagnostic = executionDiagnostic('answer', finished.status, finished.error)
          status = finished.status
          answer = finished.answer || finished.error || ''
        }
        const automaticPass = status === 'SUCCEEDED' && (memoryOnly ? memoryAnswerCheck(answer, scenario, turn, variant)
          : scenario.expected[turn].test(answer) && !(scenario.forbidden[turn]?.test(answer)))
        report.results.push({ scenario: scenario.id, variant, turn, question, status, model, answer, automaticPass,
          plan, expectedDecision: scenario.expectedDecisions?.[turn], decisionMatchesExpectation: plan ? plan.decision === scenario.expectedDecisions?.[turn] : null,
          manualCriteria: manualCriteriaFor(scenario, holdForUi), manualVerdict: 'NOT_REVIEWED',
          originalEvidence,
          originalBundleId, originalStatus, answerBundleId: bundle.id, threadId,
          elapsedMs: Date.now() - started, retrievalQuery: bundle.payload.chatContext.retrievalQuery,
          historyTurns: bundle.payload.chatContext.history.length,
          evidence: bundle.payload.items.map(item => ({ label: item.label, title: item.title, quote: item.quote })) })
        await save()
        console.log(JSON.stringify({ scenario: scenario.id, variant, turn, status, automaticPass, dispatched: report.dispatched }))
        requireSuccessfulAnswer(report.results.at(-1))
        if (contextOnly || memoryOnly || protocolHook) {
          const result = report.results.at(-1)
          result.semanticReview = await requireSemanticReview({ ownedDirectory: directory,
            result: { ...result, contextBundle: contextOnly ? bundle : undefined, memoryCheck: memoryOnly ? report.memoryChecks.at(-1) : undefined, artifactSha256: report.jarSha256 }, signal: shutdown.signal,
            notify: details => console.log(JSON.stringify({ event: 'WAIT_SEMANTIC_REVIEW', variant, turn, ...details })) })
          result.manualVerdict = 'PASS'
          if (memoryOnly) priorMemoryTurns.push({ bundle, answer })
          await save()
        }
      }
      const finalDetail = await api(prefix + '/chat-threads/' + threadId)
      assert.equal(finalDetail.bundles.length, expectedBundles)
      if (memoryOnly) {
        assert.equal(variantBudget.count, 64)
        report.memoryFinalSnapshot = await historySnapshot(prefix, threadId)
        report.memoryScope = report.memoryChecks.some(check => check.metric?.actualCompactionObserved)
          ? 'EXTRACTIVE_COMPACTION_OBSERVED_REQUIRES_ACCEPTANCE_REVIEW' : 'WINDOW_ONLY_NO_COMPACTION_OBSERVED'
        await save()
      }
      if (restartedSnapshot) {
        assertThreadExtends(restartedSnapshot.detail, finalDetail)
        const finalSnapshot = await historySnapshot(prefix, threadId)
        assert.deepEqual(await historySnapshot(prefix, threadId), finalSnapshot, 'GET reopening created or changed jobs')
        assert.equal(finalSnapshot.history.filter(row => row.id === threadId).length, 1)
        report.restartChecks.at(-1).final = finalSnapshot
        report.restartChecks.at(-1).status = 'FOLLOWUP_VERIFIED'
        await save()
      }
    }
  }
  report.completed = true
} catch (error) {
  report.error = error.message
  if (error.code === 'FIXTURE_INVALID') {
    report.outcome = 'FIXTURE_INVALID_NOT_MODEL_WRONG'
    if (report.memoryChecks?.length) report.memoryChecks.at(-1).validation = 'FIXTURE_INVALID'
  }
  process.exitCode = 1
} finally {
  await stopRuntime()
  if (batchTimer) clearTimeout(batchTimer)
  if (isolatedRuntime) {
    report.sourceJarUnchanged = createHash('sha256').update(await readFile(sourceJar)).digest('hex') === process.env.FORGE_EXPERT_JAR_SHA256
    report.snapshotJarUnchanged = createHash('sha256').update(await readFile(jar)).digest('hex') === process.env.FORGE_EXPERT_JAR_SHA256
    report.assetsUnchanged = {}
    for (const [name, expected] of Object.entries(report.assetHashes ?? {})) report.assetsUnchanged[name] = createHash('sha256').update(await readFile(resolve(resources, name))).digest('hex') === expected
  }
  if (protocolHook) {
    try { report.protocolCounter = await collectProtocolCounters(protocolHook) }
    catch {
      report.protocolCounter = { ...protocolHook.metadata, collectionFailed: true }
      report.error = report.error || 'Protocol counter collection failed; no raw stats retained'
      report.completed = false
      process.exitCode = 1
    }
  }
  await rm(directory, { recursive: true, force: true })
  process.removeListener('SIGINT', onSignal)
  process.removeListener('SIGTERM', onSignal)
  report.finishedAt = new Date().toISOString()
  if (reportCreated) await save()
  console.log(JSON.stringify({ dispatched: report.dispatched, evaluated: report.results.length, completed: !!report.completed, error: report.error, reportPath }))
}
