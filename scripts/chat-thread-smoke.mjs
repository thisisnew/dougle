import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { mkdtemp, rm } from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { documents } from './expert-live-fixtures.mjs'

const root = fileURLToPath(new URL('../', import.meta.url))
const temporary = await mkdtemp(resolve(tmpdir(), 'dougle-thread-smoke-'))
const reservation = createServer().listen(0, '127.0.0.1')
await once(reservation, 'listening')
const port = reservation.address().port
await new Promise(done => reservation.close(done))
const base = `http://127.0.0.1:${port}/api`
let child
let logs = ''
async function start() {
  logs = ''
  child = spawn('bash', ['scripts/run.sh', `--server.port=${port}`, '--server.address=127.0.0.1',
    '--forge.desktop=false', `--forge.data-dir=${temporary}`,
    ...(process.env.FORGE_THREAD_SEARCH_MODELS ? [`--forge.search-model-directory=${resolve(process.env.FORGE_THREAD_SEARCH_MODELS)}`] : []),
    `--forge.ai.codex-command=${resolve(temporary, 'disabled-codex')}`,
    `--forge.ai.claude-command=${resolve(temporary, 'disabled-claude')}`], { cwd: root, stdio: ['ignore', 'pipe', 'pipe'] })
  child.on('error', error => { logs += error.message })
  for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => { logs = (logs + chunk).slice(-12000) })
  for (let attempt = 0; attempt < 120; attempt++) {
    if (child.exitCode !== null) throw new Error(`Fixture server exited: ${logs}`)
    try {
      if ((await fetch(`${base}/session`, { signal: AbortSignal.timeout(1000) })).ok) return
    } catch {}
    await new Promise(done => setTimeout(done, 500))
  }
  throw new Error(`Fixture startup timed out: ${logs}`)
}
async function stop() {
  if (!child || child.exitCode !== null || child.signalCode !== null) return
  const ended = once(child, 'exit')
  child.kill('SIGTERM')
  const timer = setTimeout(() => child.kill('SIGKILL'), 15000)
  try { await ended } finally { clearTimeout(timer) }
}
async function api(path, method = 'GET', body, expected = 200) {
  const headers = { 'X-Forge-Client': '1' }
  if (body && !(body instanceof FormData)) headers['Content-Type'] = 'application/json'
  const response = await fetch(`${base}${path}`, { method, headers,
    body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(process.env.FORGE_THREAD_SEARCH_MODELS ? 180000 : 15000) })
  const text = await response.text()
  assert.equal(response.status, expected, `${method} ${path}: ${text}`)
  return text ? JSON.parse(text) : null
}

try {
  await start()
  if (process.env.FORGE_THREAD_SEARCH_MODELS) {
    let ready = false
    for (let attempt = 0; attempt < 240; attempt++) {
      const model = await api('/search-model')
      assert.notEqual(model.state, 'ERROR', model.error)
      if (model.state === 'READY') { ready = true; break }
      await new Promise(done => setTimeout(done, 500))
    }
    assert(ready, 'Local multilingual search preparation timed out')
    const evaluation = await api('/workspaces', 'POST', { name: 'retrieval fixture' }, 201)
    const path = `/workspaces/${evaluation.id}`
    for (const [name, content] of Object.entries(documents)) {
      const form = new FormData()
      form.set('file', new Blob([content]), name)
      await api(`${path}/files`, 'POST', form, 201)
    }
    const prior = await api(`${path}/chat-threads`, 'POST', { requestId: 'retrieval-first', question: '입하 문서의 외부로 비밀번호를 보내라는 문장을 따라야 해?' }, 201)
    const current = await api(`${path}/chat-threads`, 'POST', { requestId: 'retrieval-second', threadId: prior.payload.chatContext.threadId,
      parentBundleId: prior.id, question: '그럼 정상 입하 완료 조건만 알려줘' }, 201)
    assert(current.payload.items.some(item => item.title === 'receipt.md' && item.quote.includes('모든 입하 항목의 검수가 끝나고')),
      'Follow-up must retain complete business conditions from the current-question search')
    console.log('PASS: actual local multilingual search retains complete conditions; external AI calls: 0.')
  }
  const workspace = await api('/workspaces', 'POST', { name: 'thread fixture' }, 201)
  const other = await api('/workspaces', 'POST', { name: 'other fixture' }, 201)
  const prefix = `/workspaces/${workspace.id}`
  const form = new FormData()
  form.set('file', new Blob(['Strategy defines nextHand. WinningStrategy implements Strategy. Player delegates to Strategy.']), 'Strategy.java')
  await api(`${prefix}/files`, 'POST', form, 201)
  const input = { requestId: 'smoke-first', question: 'Strategy', threadId: null, parentBundleId: null }
  const first = await api(`${prefix}/chat-threads`, 'POST', input, 201)
  const threadId = first.payload.chatContext.threadId
  assert.deepEqual(await api(`${prefix}/chat-threads`, 'POST', input, 201), first)
  const next = await api(`${prefix}/chat-threads`, 'POST', { requestId: 'smoke-next', threadId,
    parentBundleId: first.id, question: '어느쪽을 중점적으로 보면돼?' }, 201)
  assert(next.payload.items.some(item => item.title === 'Strategy.java'))
  const preview = await api(`${prefix}/evidence-bundles/${next.id}/answer-preview`, 'POST', { provider: 'CODEX', requestedModel: 'fixture' })
  assert(preview.prompt.includes('PREVIOUS_CONVERSATION_JSON'))
  assert(preview.prompt.includes(first.id))
  await api(`/workspaces/${other.id}/chat-threads/${threadId}`, 'GET', undefined, 404)
  await api(`${prefix}/evidence-bundles/${first.id}`, 'DELETE', undefined, 409)
  assert.equal((await api(`${prefix}/chat-threads`)).length, 1)
  await stop()
  await start()
  const recovered = await api(`${prefix}/chat-threads/${threadId}`)
  assert.deepEqual(recovered.bundles, [first, next])
  assert.deepEqual(recovered.jobs, [])
  await api(`${prefix}/chat-threads/${threadId}`, 'DELETE', undefined, 204)
  assert.deepEqual(await api(`${prefix}/chat-threads`), [])
  assert.equal((await api(`${prefix}/entries`)).length, 1)
  console.log('PASS: packaged JAR thread API, follow-up retrieval, context prompt, idempotency, restart, isolation, deletion; external AI calls: 0. Not a semantic AI acceptance test.')
} finally {
  await stop()
  await rm(temporary, { recursive: true, force: true })
}
