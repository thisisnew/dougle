import assert from 'node:assert/strict'
import { spawn, execFileSync } from 'node:child_process'
import { once } from 'node:events'
import { chmod, copyFile, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHash, randomUUID } from 'node:crypto'

assert.notEqual(process.platform, 'win32', 'This fixture requires POSIX process groups.')
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const resources = process.env.FORGE_RECOVERY_RESOURCES
const javaHome = process.env.JAVA_HOME || (process.platform === 'darwin'
  ? execFileSync('/usr/libexec/java_home', ['-v', '21'], { encoding: 'utf8' }).trim() : '')
const java = resources ? resolve(resources, 'runtime/bin/java') : javaHome ? resolve(javaHome, 'bin/java') : 'java'
const jar = resources ? resolve(resources, 'dougle.jar') : resolve(root, 'backend/target/dougle-0.1.0.jar')
const jarSha256 = createHash('sha256').update(await readFile(jar)).digest('hex')
const directory = await mkdtemp(resolve(tmpdir(), 'forge-crash-recovery-'))
const fixture = resolve(directory, 'fake-codex.sh')
let child
let base
let logs = ''
const pause = milliseconds => new Promise(done => setTimeout(done, milliseconds))

async function request(path, method = 'GET', body) {
  const multipart = body instanceof FormData
  return fetch(`${base}/api${path}`, {
    method, headers: { 'X-Forge-Client': '1', ...(multipart ? {} : { 'Content-Type': 'application/json' }) },
    body: multipart ? body : body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(10000),
  })
}

async function api(path, method = 'GET', body) {
  const response = await request(path, method, body)
  assert.ok(response.ok, `${method} ${path}: ${response.status} ${await response.clone().text()}`)
  return response.status === 204 ? null : response.json()
}

async function start() {
  const reservation = createServer().listen(0, '127.0.0.1')
  await once(reservation, 'listening')
  const port = reservation.address().port
  await new Promise(done => reservation.close(done))
  base = `http://127.0.0.1:${port}`
  logs = ''
  child = spawn(java, [`-Djava.io.tmpdir=${directory}`, '-jar', jar, '--forge.desktop=false', '--server.address=127.0.0.1', `--server.port=${port}`,
    `--forge.data-dir=${resolve(directory, 'data')}`, `--forge.ai.codex-command=${fixture}`,
    '--forge.ai.codex-stream-home=', `--forge.ai.claude-command=${resolve(directory, 'not-installed')}`], {
    cwd: root, detached: true, stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, HOME: directory },
  })
  child.on('error', error => { logs += error.message })
  for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => { logs = (logs + chunk).slice(-32768) })
  const deadline = Date.now() + 90000
  while (Date.now() < deadline) {
    assert.equal(child.exitCode, null, logs)
    assert.equal(child.signalCode, null, logs)
    try { if ((await request('/session')).ok) return } catch {}
    await pause(250)
  }
  throw new Error(`Startup timeout: ${logs}`)
}

async function stop(signal = 'SIGTERM') {
  if (!child || child.exitCode !== null || child.signalCode !== null) return
  const owned = child
  const ended = once(owned, 'exit')
  process.kill(-owned.pid, signal)
  let forced = false
  const timeout = setTimeout(() => { forced = true; process.kill(-owned.pid, 'SIGKILL') }, 15000)
  try {
    const [, actualSignal] = await ended
    assert.equal(forced, false, 'Graceful cleanup exceeded its time limit')
    if (signal === 'SIGKILL') assert.equal(actualSignal, 'SIGKILL')
  } finally { clearTimeout(timeout) }
}

async function calls(model) {
  try { return (await readFile(resolve(directory, `${model}.calls`), 'utf8')).trim().split('\n').length }
  catch (error) { if (error.code === 'ENOENT') return 0; throw error }
}

async function until(action, predicate) {
  const deadline = Date.now() + 15000
  while (Date.now() < deadline) {
    const value = await action()
    if (predicate(value)) return value
    await pause(100)
  }
  throw new Error('Expected recovery fixture state was not reached')
}

try {
  await copyFile(resolve(root, 'scripts/fixtures/fake-codex-recovery.sh'), fixture)
  await chmod(fixture, 0o700)
  await start()
  const workspace = await api('/workspaces', 'POST', { name: '강제 종료 가상 서재' })
  const path = `/workspaces/${workspace.id}`
  const content = 'Synthetic telescope recovery evidence. 가상 자료입니다.\n'
  const form = new FormData()
  form.append('file', new Blob([content]), '복구 가상 자료.txt')
  const entry = await api(`${path}/files`, 'POST', form)
  const draft = await api(`${path}/knowledge`, 'POST', {
    title: '수동 지식', markdown: 'Synthetic telescope evidence [E1]', citations: [{ entryId: entry.id, startLine: 1, endLine: 1 }],
  })
  const cardPath = `${path}/knowledge/${draft.card.id}`
  await api(`${cardPath}/review`, 'POST', { expectedVersion: 1, reviewer: 'Fixture', note: 'Synthetic only', claimsSupported: true, uncertaintyRemoved: true })
  const published = await api(`${cardPath}/publication`, 'POST', { expectedVersion: 1, expectedPublicationRevision: 0 })
  const edited = await api(`${cardPath}/versions`, 'POST', { expectedVersion: 1, title: '사람 편집', markdown: 'Human edit retained [E1]' })
  const generationPreview = await api(`${cardPath}/generation/preview`, 'POST', { provider: 'CODEX', requestedModel: 'recovery-generation' })
  const generation = { requestId: randomUUID(), provider: 'CODEX', requestedModel: 'recovery-generation', expectedVersion: 2, digest: generationPreview.digest, approved: true }
  await api(`${cardPath}/generation/jobs`, 'POST', generation)
  const generationPath = `${path}/generation-jobs/${generation.requestId}`
  const bundle = await api(`${path}/evidence-bundles`, 'POST', { question: 'telescope', domains: [], includeKnowledge: false })
  assert.equal(bundle.payload.status, 'READY')
  const bundlePath = `${path}/evidence-bundles/${bundle.id}`
  const preview = await api(`${bundlePath}/answer-preview`, 'POST', { provider: 'CODEX', requestedModel: 'recovery-answer' })
  const approve = expectedApprovalId => api(`${bundlePath}/answer-approval`, 'POST', {
    provider: 'CODEX', requestedModel: 'recovery-answer', digest: preview.digest, acknowledged: true, expectedApprovalId,
  })
  const approval = await approve(null)
  const answer = { requestId: randomUUID(), approvalId: approval.approval.id, digest: preview.digest, approved: true }
  await api(`${bundlePath}/answer-jobs`, 'POST', answer)
  const answerPath = `${path}/answer-jobs/${answer.requestId}`
  for (const model of ['recovery-generation', 'recovery-answer']) await until(() => calls(model), count => count === 1)
  assert.equal((await api(generationPath)).status, 'RUNNING')
  assert.equal((await api(answerPath)).status, 'RUNNING')
  await stop('SIGKILL')
  await start()
  const interruptedGeneration = await api(generationPath)
  const interruptedAnswer = await api(answerPath)
  assert.equal(interruptedGeneration.status, 'INTERRUPTED')
  assert.equal(interruptedGeneration.resultCardId, null)
  assert.equal(interruptedAnswer.status, 'INTERRUPTED')
  assert.equal(interruptedAnswer.answer, null)
  const eventResponse = await request(`${answerPath}/events`)
  assert.equal(eventResponse.status, 200)
  assert.ok(eventResponse.headers.get('content-type').startsWith('text/event-stream'))
  const events = (await eventResponse.text()).trim().split(/\r?\n\r?\n/).map(block => {
    const lines = block.split(/\r?\n/)
    return { name: lines.find(line => line.startsWith('event:')).slice(6).trim(),
      data: JSON.parse(lines.filter(line => line.startsWith('data:')).map(line => line.slice(5)).join('\n')) }
  })
  assert.deepEqual(events.map(event => event.name), ['evidence', 'job', 'done'])
  assert.deepEqual(events[0].data, bundle)
  assert.deepEqual(events[1].data, interruptedAnswer)
  assert.equal(events[2].data.reason, 'terminal')
  assert.equal((await api('/session')).activeWorkspaceId, workspace.id)
  assert.equal(await (await request(`${path}/entries/${entry.id}/content`)).text(), content)
  assert.deepEqual((await api(cardPath)).version, edited.version)
  assert.deepEqual((await api(`${cardPath}/versions/1`)).version, draft.version)
  assert.deepEqual((await api(`${cardPath}/publication`)).publication, published.publication)
  assert.equal((await api(`${path}/knowledge`)).length, 1)
  assert.ok((await api(`${path}/search?query=telescope`)).some(hit => hit.entryId === entry.id))
  assert.deepEqual(await api(bundlePath), bundle)
  assert.equal((await api(`${bundlePath}/answer-approval`)).status, 'CONSUMED')
  assert.deepEqual(await api(`${cardPath}/generation/jobs`, 'POST', generation), interruptedGeneration)
  assert.deepEqual(await api(`${bundlePath}/answer-jobs`, 'POST', answer), interruptedAnswer)
  assert.equal((await request(`${bundlePath}/answer-jobs`, 'POST', { ...answer, requestId: randomUUID() })).status, 409)
  await stop()
  await start()
  assert.deepEqual(await api(generationPath), interruptedGeneration)
  assert.deepEqual(await api(answerPath), interruptedAnswer)
  await pause(1200)
  assert.equal(await calls('recovery-generation'), 1)
  assert.equal(await calls('recovery-answer'), 1)
  await writeFile(resolve(directory, 'release'), '')
  const newApproval = await approve(approval.approval.id)
  const newAnswer = await api(`${bundlePath}/answer-jobs`, 'POST', { ...answer, requestId: randomUUID(), approvalId: newApproval.approval.id })
  const completed = await until(() => api(`${path}/answer-jobs/${newAnswer.id}`), job => !['QUEUED', 'RUNNING'].includes(job.status))
  assert.equal(completed.status, 'SUCCEEDED', completed.error)
  assert.equal(completed.answer, 'Synthetic recovery evidence. [R1]')
  assert.equal(await calls('recovery-answer'), 2)
  assert.equal(await calls('recovery-generation'), 1)
  const newGeneration = await api(`${cardPath}/generation/jobs`, 'POST', { ...generation, requestId: randomUUID() })
  const generated = await until(() => api(`${path}/generation-jobs/${newGeneration.id}`), job => !['QUEUED', 'RUNNING'].includes(job.status))
  assert.equal(generated.status, 'SUCCEEDED', generated.error)
  assert.notEqual(generated.resultCardId, draft.card.id)
  assert.equal((await api(`${path}/knowledge`)).length, 2)
  assert.deepEqual((await api(cardPath)).version, edited.version)
  assert.deepEqual((await api(`${cardPath}/publication`)).publication, published.publication)
  assert.equal(await calls('recovery-generation'), 2)
  await stop()
  console.log(JSON.stringify({ status: 'PASS', jarSha256, checkedAt: new Date().toISOString(),
    checks: ['SIGKILL while two fake CLI requests are RUNNING', 'interrupted jobs recovered without results',
      'reconnected SSE sends evidence then interrupted job then terminal, without partial answer',
      'bytes, human versions, publication, evidence and search preserved', 'consumed approval cannot dispatch again',
      'idempotent replay and second restart do not rerun requests', 'new explicit approval permits a new fake answer',
      'new approved generation creates a separate draft without changing human edits or publication'],
    scope: 'Real JAR and HTTP API; isolated data and fake CLI only. Not power-loss, UI, native supervisor, or real AI validation.' }, null, 2))
} finally {
  await stop()
  await rm(directory, { recursive: true, force: true })
}
