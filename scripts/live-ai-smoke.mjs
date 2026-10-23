import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { createHash, randomBytes, randomUUID } from 'node:crypto'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const provider = process.env.FORGE_LIVE_CLI
assert.ok(['CODEX', 'CLAUDE'].includes(provider), 'Explicit FORGE_LIVE_CLI=CODEX or CLAUDE approval is required; real account usage applies.')
const profile = process.env.FORGE_LIVE_CODEX_PROFILE
if (profile) assert.equal(provider, 'CODEX')
const root = fileURLToPath(new URL('../', import.meta.url))
const resources = resolve(process.env.FORGE_LIVE_RESOURCES || resolve(root, 'desktop/src-tauri/target/release/bundle/macos/Knowledge Forge.app/Contents/Resources/resources'))
const jar = resolve(process.env.FORGE_LIVE_JAR || resolve(resources, 'knowledge-forge.jar'))
const directory = await mkdtemp(resolve(tmpdir(), 'forge-live-api-'))
const token = randomBytes(32).toString('hex')
const report = { provider, checkedAt: new Date().toISOString(), status: 'RUNNING', checks: [], scope: 'Packaged Intel Java/JAR HTTP API, synthetic data only; not GUI login, ARM, or token-by-token Codex streaming.' }
let child
let base
let logs = ''
const pause = milliseconds => new Promise(done => setTimeout(done, milliseconds))
async function api(path, method = 'GET', body) {
  const multipart = body instanceof FormData
  const response = await fetch(base + '/api' + path, { method, signal: AbortSignal.timeout(75000),
    headers: { 'X-Forge-Session': token, 'X-Forge-Client': '1', ...(multipart ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : multipart ? body : JSON.stringify(body) })
  assert.equal(response.ok, true, `HTTP ${response.status}: ${path}`)
  return response.json()
}
async function waitJob(path, predicate) {
  const deadline = Date.now() + 150000
  while (Date.now() < deadline) {
    const job = await api(path)
    if (predicate(job)) return job
    await pause(100)
  }
  throw Error('Job deadline exceeded')
}
try {
  report.jarSha256 = createHash('sha256').update(await readFile(jar)).digest('hex')
  report.jarOverride = Boolean(process.env.FORGE_LIVE_JAR)
  child = spawn(resolve(resources, 'runtime/bin/java'), ['-jar', jar,
    '--forge.desktop=true', '--server.address=127.0.0.1', '--server.port=0', `--forge.data-dir=${directory}`,
    `--forge.ai.codex-command=${resolve(resources, 'ai/codex')}`,
    `--forge.ai.claude-command=${process.env.FORGE_LIVE_CLAUDE_COMMAND || 'claude'}`,
    ...(profile ? [`--forge.ai.codex-stream-home=${resolve(profile)}`] : [])],
  { cwd: directory, env: { ...process.env, FORGE_DATA_DIR: directory, FORGE_DESKTOP: 'true', JAVA_TOOL_OPTIONS: '', JDK_JAVA_OPTIONS: '', _JAVA_OPTIONS: '' }, stdio: ['pipe', 'pipe', 'pipe'] })
  child.on('error', () => { report.launchFailed = true })
  for (const pipe of [child.stdout, child.stderr]) pipe.on('data', chunk => { logs = (logs + chunk).slice(-16000) })
  child.stdin.write(token + '\n')
  const deadline = Date.now() + 90000
  while (!base && Date.now() < deadline) {
    assert.equal(child.exitCode, null, 'Packaged backend exited before ready')
    assert.ok(!report.launchFailed, 'Packaged backend could not start')
    const port = logs.match(/^FORGE_DESKTOP_READY:(\d+)$/m)?.[1]
    if (port) base = `http://127.0.0.1:${port}`
    else await pause(200)
  }
  assert.ok(base, 'Backend readiness deadline exceeded')
  assert.equal((await fetch(base + '/api/session')).status, 403)
  let connection = await api(`/ai/providers/${provider}/connection`, 'POST')
  if (profile && process.env.FORGE_LIVE_LOGIN === 'true' && connection.state === 'LOGIN_REQUIRED') {
    console.error('Complete the provider browser login. Credentials remain in the explicitly selected dedicated profile.')
    const login = await api('/ai/logins', 'POST', { provider, acknowledged: true })
    const finished = await waitJob(`/ai/logins/${login.id}`, value => value.state !== 'PENDING')
    report.loginState = finished.state
    assert.equal(finished.state, 'AUTHENTICATED', 'Browser login did not finish')
    connection = await api(`/ai/providers/${provider}/connection`, 'POST')
  }
  report.connection = connection
  assert.equal(connection.state, 'AUTHENTICATED', 'Provider must already be authenticated; no automatic login or fallback')
  report.checks.push('existing CLI authentication and protected packaged API')
  const workspace = await api('/workspaces', 'POST', { name: 'Synthetic live AI check' })
  const workspacePath = `/workspaces/${workspace.id}`
  const form = new FormData()
  form.append('file', new Blob(['가상 관측 기록: 실험실 센서의 측정 온도는 21도이다.']), 'synthetic.txt')
  const entry = await api(`${workspacePath}/files`, 'POST', form)
  const draft = await api(`${workspacePath}/knowledge`, 'POST', { title: '가상 초안', markdown: '사람 메모', citations: [{ entryId: entry.id, startLine: 1, endLine: 1 }] })
  const cardPath = `${workspacePath}/knowledge/${draft.card.id}`
  const preview = await api(`${cardPath}/generation/preview`, 'POST', { provider })
  const generated = await api(`${cardPath}/generation/jobs`, 'POST', { provider, requestId: randomUUID(), requestedModel: preview.requestedModel, expectedVersion: preview.expectedVersion, digest: preview.digest, approved: true })
  const finalDraft = await waitJob(`${workspacePath}/generation-jobs/${generated.id}`, job => ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(job.status))
  assert.equal(finalDraft.status, 'SUCCEEDED', finalDraft.error)
  const result = await api(`${workspacePath}/knowledge/${finalDraft.resultCardId}`)
  assert.equal(result.version.status, 'DRAFT')
  assert.ok(result.version.markdown.includes('[E1]'))
  assert.equal((await api(cardPath)).version.markdown, '사람 메모')
  report.checks.push('real AI separate unverified draft; human text preserved')
  const bundle = await api(`${workspacePath}/evidence-bundles`, 'POST', { question: '센서 측정 온도', domains: [], includeKnowledge: false })
  const bundlePath = `${workspacePath}/evidence-bundles/${bundle.id}`
  const answerPreview = await api(`${bundlePath}/answer-preview`, 'POST', { provider })
  async function approve(expectedApprovalId) {
    return api(`${bundlePath}/answer-approval`, 'POST', { provider, requestedModel: answerPreview.requestedModel, digest: answerPreview.digest, acknowledged: true, expectedApprovalId })
  }
  async function submit(approval) {
    return api(`${bundlePath}/answer-jobs`, 'POST', { requestId: randomUUID(), approvalId: approval.approval.id, digest: answerPreview.digest, approved: true })
  }
  const approval = await approve(null)
  const answer = await submit(approval)
  const answerPath = `${workspacePath}/answer-jobs/${answer.id}`
  const events = await fetch(base + '/api' + answerPath + '/events', { headers: { 'X-Forge-Session': token }, signal: AbortSignal.timeout(150000) })
  assert.equal(events.status, 200)
  const stream = await events.text()
  const finalAnswer = await api(answerPath)
  assert.equal(finalAnswer.status, 'SUCCEEDED', finalAnswer.error)
  assert.ok(finalAnswer.answer.includes('[R1]'))
  assert.ok(stream.includes('event:evidence') && stream.includes('SUCCEEDED'))
  assert.ok(stream.indexOf('event:evidence') < stream.indexOf('SUCCEEDED'))
  report.partialObserved = stream.includes('event:partial')
  if (profile) {
    assert.equal(report.partialObserved, true, 'Dedicated profile must produce real partial text')
    report.scope = 'Packaged Java/JAR HTTP API with explicit dedicated Codex profile and real partial SSE; not GUI login or ARM.'
  }
  report.checks.push('real grounded answer; evidence precedes final SSE event')
  const cancellation = await submit(await approve(approval.approval.id))
  const cancelPath = `${workspacePath}/answer-jobs/${cancellation.id}`
  const running = await waitJob(cancelPath, job => job.status !== 'QUEUED')
  assert.equal(running.status, 'RUNNING', 'Cancellation must reach a running job')
  await api(cancelPath + '/cancel', 'POST')
  const cancelled = await waitJob(cancelPath, job => ['CANCELLED', 'FAILED', 'SUCCEEDED'].includes(job.status))
  assert.equal(cancelled.status, 'CANCELLED')
  assert.ok(!cancelled.answer)
  report.checks.push('running answer cancellation without saved answer; may precede model dispatch')
  report.status = 'PASS'
} catch (error) {
  report.status = 'FAIL'
  report.error = error.message
  process.exitCode = 1
} finally {
  if (child?.pid && child.exitCode === null && child.signalCode === null) {
    const ended = once(child, 'exit')
    child.stdin.end()
    const timer = setTimeout(() => child.kill('SIGKILL'), 15000)
    await ended
    clearTimeout(timer)
  }
  await rm(directory, { recursive: true, force: true })
  console.log(JSON.stringify(report, null, 2))
}
