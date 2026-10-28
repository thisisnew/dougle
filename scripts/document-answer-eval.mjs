import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { randomBytes, randomUUID } from 'node:crypto'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

assert.equal(process.env.FORGE_EVALUATION_LIVE, 'approved', 'Explicit approval is required for real AI usage')
assert.ok(process.env.FORGE_DOCUMENT_EVALUATION, 'Provide the explicitly approved document evaluation directory')
assert.ok(process.env.FORGE_EVALUATION_CODEX_PROFILE, 'Provide an existing dedicated authenticated profile; credentials are never read or copied')
const root = fileURLToPath(new URL('../', import.meta.url))
const evaluation = resolve(process.env.FORGE_DOCUMENT_EVALUATION)
const resources = resolve(process.env.FORGE_EVALUATION_RESOURCES || '/Applications/Dougle.app/Contents/Resources/resources')
const jar = resolve(process.env.FORGE_EVALUATION_JAR || resolve(root, 'backend/target/dougle-0.1.0.jar'))
const models = resolve(root, 'desktop/src-tauri/resources/search-model-e5-v1')
const directory = await mkdtemp(resolve(tmpdir(), 'dougle-document-eval-'))
const token = randomBytes(32).toString('hex')
const results = []
const reportPath = resolve(evaluation, process.env.FORGE_EVALUATION_REPORT || 'answers.json')
let child
let base
let logs = ''
let dispatched = 0
const pause = milliseconds => new Promise(done => setTimeout(done, milliseconds))
async function api(path, method = 'GET', body) {
  const response = await fetch(base + '/api' + path, { method, signal: AbortSignal.timeout(180000),
    headers: { 'X-Forge-Session': token, 'X-Forge-Client': '1', 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body) })
  if (!response.ok) throw Error(`HTTP ${response.status}: ${path}: ${(await response.text()).slice(0,300)}`)
  return response.status === 204 ? null : response.json()
}
async function waitFor(path, complete, timeout = 180000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    const value = await api(path)
    if (complete(value)) return value
    await pause(300)
  }
  throw Error(`Deadline exceeded: ${path}`)
}
async function save() {
  await writeFile(reportPath, JSON.stringify({ checkedAt: new Date().toISOString(), dispatched, results }, null, 2), { mode: 0o600 })
}
try {
  child = spawn(resolve(resources, 'runtime/bin/java'), ['-jar', jar,
    '--forge.desktop=true', '--server.address=127.0.0.1', '--server.port=0', `--forge.data-dir=${directory}`,
    ...(process.env.FORGE_EVALUATION_DEFAULT_BUNDLE === 'true' ? [] : [`--forge.search-model-directory=${models}`]),
    `--forge.ai.codex-command=${resolve(resources, 'ai/codex')}`,
    `--forge.ai.codex-stream-home=${resolve(process.env.FORGE_EVALUATION_CODEX_PROFILE)}`],
  { cwd: directory, env: { ...process.env, FORGE_DATA_DIR: directory, FORGE_DESKTOP: 'true', JAVA_TOOL_OPTIONS: '', JDK_JAVA_OPTIONS: '', _JAVA_OPTIONS: '' }, stdio: ['pipe', 'pipe', 'pipe'] })
  for (const pipe of [child.stdout, child.stderr]) pipe.on('data', chunk => { logs = (logs + chunk).slice(-16000) })
  child.stdin.write(token + '\n')
  const deadline = Date.now() + 120000
  while (!base && Date.now() < deadline) {
    assert.equal(child.exitCode, null, 'Evaluation backend exited before ready')
    const port = logs.match(/^FORGE_DESKTOP_READY:(\d+)$/m)?.[1]
    if (port) base = `http://127.0.0.1:${port}`
    else await pause(200)
  }
  assert.ok(base, 'Backend startup deadline exceeded')
  console.log('Evaluation backend ready')
  const engine = await waitFor('/search-model', value => ['READY', 'ERROR'].includes(value.state))
  assert.equal(engine.state, 'READY', engine.error)
  console.log('Bundled search engine ready')
  const connection = await api('/ai/providers/CODEX/connection', 'POST')
  assert.equal(connection.state, 'AUTHENTICATED', 'Existing dedicated CLI login is required; no automatic login')
  console.log('Existing AI connection verified')
  const workspace = await api('/workspaces', 'POST', { name: '문서 정확도 검사' })
  const workspacePath = `/workspaces/${workspace.id}`
  const sourceDirectory = resolve(directory, 'document')
  await mkdir(sourceDirectory)
  const name = (await readFile(resolve(evaluation, 'imported-name.txt'), 'utf8')).trim()
  assert.ok(name && !name.includes('/') && !name.includes('\\'))
  await copyFile(resolve(evaluation, 'imported.txt'), resolve(sourceDirectory, name))
  const source = await api(`${workspacePath}/sources`, 'POST', { name: 'Notion 검증 문서', kind: 'LOCAL_FOLDER', location: sourceDirectory, domain: '' })
  const synced = await api(`${workspacePath}/sources/${source.id}/sync`, 'POST')
  assert.equal(synced.status, 'READY', synced.error)
  console.log('Approved Notion snapshot imported and indexed')
  const chosen = process.env.FORGE_EVALUATION_QUESTION_IDS?.split(',')
  const cases = JSON.parse(await readFile(resolve(evaluation, 'cases.json'), 'utf8')).filter(item => !chosen || chosen.includes(item.id))
  assert.ok(cases.length > 0 && cases.length <= 50)
  for (const item of cases) {
    const started = Date.now()
    const bundle = await api(`${workspacePath}/evidence-bundles`, 'POST', { question: item.question, domains: [], includeKnowledge: false })
    let answer = ''
    let status = 'NO_EVIDENCE'
    let model = null
    if (bundle.payload.items.length) {
      const path = `${workspacePath}/evidence-bundles/${bundle.id}`
      const preview = await api(path + '/answer-preview', 'POST', { provider: 'CODEX' })
      model = preview.requestedModel
      const approval = await api(path + '/answer-approval', 'POST', { provider: 'CODEX', requestedModel: model, digest: preview.digest, acknowledged: true, expectedApprovalId: null })
      const job = await api(path + '/answer-jobs', 'POST', { requestId: randomUUID(), approvalId: approval.approval.id, digest: preview.digest, approved: true })
      dispatched++
      const finished = await waitFor(`${workspacePath}/answer-jobs/${job.id}`, value => ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(value.status))
      status = finished.status
      answer = finished.answer || finished.error || ''
    }
    const evidenceText = bundle.payload.items.map(value => value.quote).join('\n')
    const evidenceMatched = !item.evidence.length || item.evidence.some(value => evidenceText.includes(value))
    const abstained = status === 'NO_EVIDENCE' || /없|명시.{0,15}않|확인.{0,15}못|알.{0,8}없|제공.{0,15}않/.test(answer)
    const automaticPass = item.answer === null ? abstained && !['FAILED','CANCELLED'].includes(status)
      : status === 'SUCCEEDED' && new RegExp(item.answer, 'i').test(answer) && /\[R\d+\]/.test(answer) && evidenceMatched
    results.push({ id: item.id, question: item.question, status, model, answer, evidenceMatched, automaticPass,
      elapsedMs: Date.now() - started, evidence: bundle.payload.items.map(value => ({label:value.label,title:value.title,startLine:value.startLine,endLine:value.endLine,quote:value.quote})) })
    await save()
    console.log(JSON.stringify({ id: item.id, status, evidenceMatched, automaticPass, elapsedMs: Date.now() - started }))
    if (['FAILED','CANCELLED'].includes(status)) throw Error('Actual AI execution failed; stop before spending further account usage')
  }
  console.log(JSON.stringify({ evaluated: results.length, dispatched, automaticPasses: results.filter(value => value.automaticPass).length, manualReviewRequired: true }))
} finally {
  if (child?.pid && child.exitCode === null && child.signalCode === null) {
    const ended = once(child, 'exit')
    child.stdin.end()
    const timer = setTimeout(() => child.kill('SIGKILL'), 15000)
    await ended
    clearTimeout(timer)
  }
  await rm(directory, { recursive: true, force: true })
  await save()
}
