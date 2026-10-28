import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { createHash, randomBytes, randomUUID } from 'node:crypto'
import { copyFile, mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { verifySearchModels } from './expert-live-search-models.mjs'

assert.equal(process.env.FORGE_ACCEPTANCE_LIVE, 'approved-200-synthetic')
assert(process.env.FORGE_ACCEPTANCE_PROFILE, 'Existing dedicated app profile required; credentials are never copied')
assert(process.env.FORGE_ACCEPTANCE_REPORT, 'A new private report path is required')
assert(process.env.FORGE_ACCEPTANCE_BUDGET, 'Shared budget journal required')
const suite = process.env.FORGE_ACCEPTANCE_SUITE ?? 'conversation'
assert(['conversation', 'images'].includes(suite))
if (suite === 'images') assert(process.env.FORGE_ACCEPTANCE_IMAGES, 'Owned synthetic PNG directory required')
const selectedCases = process.env.FORGE_ACCEPTANCE_CASES?.split(',')
if (selectedCases) assert(selectedCases.length > 0 && selectedCases.every(id => /^T\d{4}$/.test(id)))
const longTurns = Number(process.env.FORGE_ACCEPTANCE_LONG_TURNS ?? 20)
assert(Number.isInteger(longTurns) && longTurns >= 2 && longTurns <= 20, 'Long conversation regression must execute 2–20 turns')
const root = process.cwd()
const resources = '/Applications/Dougle.app/Contents/Resources/resources'
const directory = await mkdtemp(resolve(tmpdir(), 'dougle-acceptance-live-'))
const home = resolve(directory, 'home')
await mkdir(home)
const jar = resolve(directory, 'frozen-baseline.jar')
await copyFile(resolve(root, 'backend/target/dougle-0.1.0.jar'), jar)
const jarSha256 = createHash('sha256').update(await readFile(jar)).digest('hex')
const reportPath = resolve(process.env.FORGE_ACCEPTANCE_REPORT)
const budgetPath = resolve(process.env.FORGE_ACCEPTANCE_BUDGET)
const report = { startedAt: new Date().toISOString(), jarSha256, directory, maximumCalls: 200, dispatches: [], results: [],
  scope: 'Synthetic documents through real local HTTP/SQLite/search/answer approval/job APIs and actual AI; no operational endpoints, no user DB. No UI acceptance. Semantic review required.' }
await writeFile(reportPath, JSON.stringify(report), { flag: 'wx', mode: 0o600 })
let budget
try { budget = JSON.parse(await readFile(budgetPath, 'utf8')) }
catch (error) {
  if (error.code !== 'ENOENT') throw error
  budget = { maximum: 200, reservations: [], authorized: 'User approved at most 200 synthetic AI calls on 2026-10-27' }
  await writeFile(budgetPath, JSON.stringify(budget), { flag: 'wx', mode: 0o600 })
}
assert.equal(budget.maximum, 200)
assert(Array.isArray(budget.reservations) && budget.reservations.length <= 200)
let child, base, token, logs = ''
let setupFailure = null
const pause = duration => new Promise(done => setTimeout(done, duration))
const save = () => writeFile(reportPath, JSON.stringify(report, null, 2), { mode: 0o600 })
const shutdown = new AbortController()
const limit = setTimeout(() => shutdown.abort(), 100 * 60 * 1000)
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => shutdown.abort())

function allowed(path, method) {
  if (method === 'DELETE') return /^\/workspaces\/[a-zA-Z0-9-]+\/chat-threads\/[a-zA-Z0-9-]+$/.test(path)
  if (method === 'GET') return ['/session', '/search-model'].includes(path)
    || /^\/workspaces\/[a-zA-Z0-9-]+\/(chat-threads(?:\/[a-zA-Z0-9-]+)?|answer-jobs\/[a-zA-Z0-9-]+)$/.test(path)
  return method === 'POST' && (['/workspaces', '/ai/providers/CODEX/connection'].includes(path)
    || /^\/workspaces\/[a-zA-Z0-9-]+\/(sources(?:\/[a-zA-Z0-9-]+\/sync)?|chat-threads|evidence-bundles\/[a-zA-Z0-9-]+\/(answer-preview|answer-approval|answer-jobs))$/.test(path))
}
async function api(path, method = 'GET', body) {
  assert(allowed(path, method), 'Outside synthetic local API allowlist')
  const response = await fetch(base + '/api' + path, { method, redirect: 'error',
    headers: { 'X-Forge-Session': token, 'X-Forge-Client': '1', 'Content-Type': 'application/json' },
    signal: AbortSignal.any([shutdown.signal, AbortSignal.timeout(180000)]), body: body === undefined ? undefined : JSON.stringify(body) })
  if (!response.ok) throw Error(`HTTP ${response.status} ${path}: ${(await response.text()).slice(0, 300)}`)
  return response.status === 204 ? null : response.json()
}
async function wait(path, done) {
  const until = Date.now() + 180000
  while (Date.now() < until) { const value = await api(path); if (done(value)) return value; await pause(250) }
  throw Error('Bounded runtime/job deadline exceeded')
}
async function start() {
  assert.equal(createHash('sha256').update(await readFile(jar)).digest('hex'), jarSha256)
  token = randomBytes(32).toString('hex'); base = null; logs = ''
  child = spawn(resolve(resources, 'runtime/bin/java'), [`-Duser.home=${home}`, `-Djava.io.tmpdir=${directory}`, '-jar', jar,
    '--forge.desktop=true', '--server.address=127.0.0.1', '--server.port=0', `--forge.data-dir=${directory}`,
    `--forge.search-model-directory=${report.models.directory}`, `--forge.ai.codex-command=${resolve(resources, 'ai/codex')}`,
    `--forge.ai.codex-stream-home=${resolve(process.env.FORGE_ACCEPTANCE_PROFILE)}`],
  { cwd: directory, detached: true, env: { HOME: home, TMPDIR: directory, PATH: '/usr/bin:/bin:/usr/sbin:/sbin', LANG: 'en_US.UTF-8', FORGE_DESKTOP: 'true', FORGE_DATA_DIR: directory }, stdio: ['pipe', 'pipe', 'pipe'] })
  child.on('error', error => { report.processError = error.message })
  for (const output of [child.stdout, child.stderr]) output.on('data', chunk => { logs = (logs + chunk).slice(-24000) })
  child.stdin.write(token + '\n')
  const until = Date.now() + 120000
  while (!base && Date.now() < until) {
    shutdown.signal.throwIfAborted()
    assert.equal(child.exitCode, null, 'Owned runtime exited')
    assert(!report.processError, report.processError)
    const port = logs.match(/^FORGE_DESKTOP_READY:(\d+)$/m)?.[1]
    if (port) base = `http://127.0.0.1:${port}`
    else await pause(200)
  }
  assert(base, 'Owned runtime startup deadline')
  const engine = await wait('/search-model', value => ['READY', 'ERROR'].includes(value.state))
  assert.equal(engine.state, 'READY', engine.error)
}
async function stop() {
  if (!child?.pid || child.exitCode !== null || child.signalCode !== null) return
  const ended = once(child, 'exit')
  child.stdin.end()
  const timer = setTimeout(() => { try { process.kill(-child.pid, 'SIGKILL') } catch (error) { if (error.code !== 'ESRCH') throw error } }, 15000)
  try { await ended } finally { clearTimeout(timer) }
}
async function provision(name, documents) {
  const workspace = await api('/workspaces', 'POST', { name })
  const prefix = `/workspaces/${workspace.id}`
  const folder = resolve(directory, name)
  await mkdir(folder)
  for (const [file, text] of Object.entries(documents)) await writeFile(resolve(folder, file), text)
  const source = await api(prefix + '/sources', 'POST', { name: 'Synthetic', kind: 'LOCAL_FOLDER', location: folder, domain: '' })
  const synced = await api(prefix + '/sources/' + source.id + '/sync', 'POST')
  assert.equal(synced.status, 'READY', synced.error)
  return prefix
}
async function uploadImage(prefix, fixture, variant) {
  const bytes = await readFile(resolve(process.env.FORGE_ACCEPTANCE_IMAGES, `${fixture}-${variant}.png`))
  const form = new FormData()
  form.append('file', new Blob([bytes], { type: 'image/png' }), 'synthetic-screen.png')
  const response = await fetch(base + '/api' + prefix + '/attachments', { method: 'POST', redirect: 'error',
    headers: { 'X-Forge-Session': token, 'X-Forge-Client': '1' }, body: form,
    signal: AbortSignal.any([shutdown.signal, AbortSignal.timeout(30000)]) })
  assert(response.ok, `Synthetic image upload failed: ${response.status}`)
  return { attachment: await response.json(), sha256: createHash('sha256').update(bytes).digest('hex') }
}
async function answer(prefix, question, result, previous, attachmentIds = []) {
  const bundle = await api(prefix + '/chat-threads', 'POST', { question, requestId: randomUUID(),
    threadId: previous?.threadId, parentBundleId: previous?.bundleId, attachmentIds })
  const turn = { question, bundleId: bundle.id, threadId: bundle.payload.chatContext.threadId,
    context: bundle.payload.chatContext, evidence: bundle.payload.items, status: bundle.payload.status, answer: '' }
  result.turns.push(turn)
  await save()
  if (!bundle.payload.items.length && !attachmentIds.length) return turn
  const path = prefix + '/evidence-bundles/' + bundle.id
  const preview = await api(path + '/answer-preview', 'POST', { provider: 'CODEX' })
  const approved = await api(path + '/answer-approval', 'POST', { provider: 'CODEX', requestedModel: preview.requestedModel,
    digest: preview.digest, acknowledged: true, expectedApprovalId: null })
  assert(budget.reservations.length < 200, 'Shared 200-call limit exhausted')
  const requestId = randomUUID()
  const reservation = { requestId, caseId: result.id, at: new Date().toISOString(), kind: 'answer', reportPath }
  budget.reservations.push(reservation)
  await writeFile(budgetPath, JSON.stringify(budget, null, 2), { mode: 0o600 })
  report.dispatches.push(reservation)
  await save()
  const job = await api(path + '/answer-jobs', 'POST', { requestId, approvalId: approved.approval.id, digest: preview.digest, approved: true })
  const finished = await wait(prefix + '/answer-jobs/' + job.id, value => ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(value.status))
  Object.assign(turn, { jobId: job.id, status: finished.status, answer: finished.answer ?? '', error: finished.error, model: preview.requestedModel })
  await save()
  assert.equal(finished.status, 'SUCCEEDED', finished.error)
  return turn
}

const notes = [
  ['로이스는 반복 없이 만들라고 한 거야?', '로이스의 모델은 피드백과 반복을 강조한다. 순차 단계만 수행하고 피드백을 생략하면 위험하다.', /피드백|반복/],
  ['배포를 크게 하는 게 항상 효율적이야?', '작은 배포는 실패 시 복구 낭비를 줄인다. 적절한 배포 크기는 팀 상황에 따라 다르며 항상 큰 배포가 효율적인 것은 아니다.', /작|복구|상황/],
  ['백로그를 많이 쌓으면 좋은 거 아냐?', '백로그가 작을수록 우선순위를 정하기 쉽다. 약 3개월 이내의 일감을 권고한다. 오래된 일감을 무조건 삭제하라는 규칙은 아니다.', /우선순위|3개월|삼 개월/],
  ['모든 팀에 한 방법론을 강제해야 돼?', '모두를 만족시키는 하나의 방법론은 없다. 팀의 상황과 피드백에 맞게 방법을 조정한다.', /상황|조정|없|다르/],
  ['우리 실패율과 원인은 뭐야?', '이것은 일반 애자일 원칙 노트다. 팀의 운영 실패율, 장애 원인, 운영 통계는 수록하지 않았다.', /없|알 수|확인|정보|자료|제공/],
]
const conversationDocuments = {
  'strategy.md': 'Strategy는 선택 알고리즘을 교체한다. Strategy 인터페이스를 먼저 보고 Player가 strategy.choose()에 위임하는 부분과 WinningStrategy 구현을 중점적으로 본다. State나 Singleton 예제는 별개다.',
  'inbound.md': '입하 정보는 inbound_receipt 테이블에 저장한다. 저장 시점과 트랜잭션 시점은 이 문서에 없다.',
  'correction.md': '입하 수기 보정은 월평균 120건이다. 장비 교체는 분기 3건이다. 수기 보정과 장비 교체는 다른 업무다.',
  'badges.md': 'BLUE 배지는 72시간, AMBER 배지는 24시간 유효하다. AMBER는 자동 갱신되지 않으며 담당자의 수동 승인이 필요하다.',
  'singleton.md': 'Singleton은 인스턴스를 하나만 제공한다. private 생성자와 정적 getInstance()를 중점적으로 본다.',
}
const conversations = [
  ['전략 패턴 예제 설명해 줘', '어느 쪽을 중점적으로 보면 돼?', /Strategy|전략|Player/],
  ['입하 정보가 들어가는 테이블은?', '거기에 저장되는 시점은?', /시점|언제|없|확인/],
  ['입하 수기 보정은 몇 건이고 장비 교체와 어떻게 달라?', '아니 교체 말고 수기 보정이야', /120/],
  ['BLUE와 AMBER의 유효 기간을 순서대로 설명해 줘', '두 번째 것은 자동 갱신돼?', /AMBER|앰버/],
  ['전략 패턴 설명해 줘', '이제 싱글턴 이야기하자', /Singleton|싱글턴|getInstance/],
]

try {
  report.models = await verifySearchModels(root, resolve(resources, 'search-model-e5-v1'))
  await start()
  const connection = await api('/ai/providers/CODEX/connection', 'POST')
  report.connection = connection.state
  assert.equal(connection.state, 'AUTHENTICATED', 'Existing app login unavailable; no credentials copied or login launched')
} catch (error) { setupFailure = String(error); report.setupFailure = setupFailure; report.startupDiagnostic = logs.replaceAll(token ?? 'unused-token', '[REDACTED]') }
try {
  for (const baseId of suite === 'images' ? [926] : [751, 776]) for (let fixture = 0; fixture < 5; fixture++) for (let action = 0; action < 5; action++) {
    const id = `T${String(baseId + fixture * 5 + action).padStart(4, '0')}`
    if (selectedCases && !selectedCases.includes(id)) continue
    const result = { id, startedAt: new Date().toISOString(), scope: 'ACTUAL_AI_LOCAL_APP_HTTP_SYNTHETIC_DOCUMENTS', fullAcceptancePassed: false,
      remaining: ['Human semantic and claim-by-claim citation review', 'Native UI tab/reopen flows', 'No planning-model dispatch; actual answer pipeline tested'], turns: [], manualVerdict: 'NOT_REVIEWED' }
    report.results.push(result)
    if (setupFailure) { result.status = 'BLOCKED'; result.checkStatus = 'BLOCKED'; result.reason = setupFailure; await save(); continue }
    try {
      shutdown.signal.throwIfAborted()
      if (baseId === 926) {
        result.scope = 'ACTUAL_AI_ATTACHMENT_HTTP_SYNTHETIC_SCREENSHOTS_NO_BROWSER_OR_OPERATIONAL_PLANNING'
        result.remaining.push('Actual screenshot intent router, browser navigation and operational tool selection',
          'Image-only uses the explicit confirm-image question, not empty UI submission', 'Semantic/OCR accuracy needs manual review')
        const workspace = await api('/workspaces', 'POST', { name: id })
        const prefix = `/workspaces/${workspace.id}`
        const uploaded = await uploadImage(prefix, fixture, action === 3 ? 1 : 0)
        result.imageSha256 = uploaded.sha256
        result.attachmentId = uploaded.attachment.id
        const question = action === 0 ? '첨부한 이미지를 확인해 주세요.'
          : action === 2 ? '이 캡처의 오류 원인을 조사하려면 무엇을 확인해야 해? 보이는 사실과 가설을 구분해 줘.'
          : '이 화면의 용어와 동작을 설명해 줘. 보이지 않는 값은 추정하지 말아 줘.'
        const turn = await answer(prefix, question, result, undefined, [uploaded.attachment.id])
        assert.equal(turn.status, 'SUCCEEDED')
        assert(turn.answer.length > 0)
        assert(!/삭제했습니다|전송했습니다|게시했습니다/.test(turn.answer), 'Must not claim external writes')
        if (action === 4) {
          await api(prefix + '/chat-threads/' + turn.threadId, 'DELETE')
          const threads = await api(prefix + '/chat-threads')
          assert(!JSON.stringify(threads).includes(turn.threadId), 'Deleted thread remains in history')
          const replacement = await uploadImage(prefix, (fixture + 1) % 5, 0)
          assert.notEqual(replacement.attachment.id, uploaded.attachment.id)
          const next = await answer(prefix, '새로 첨부한 화면만 설명해 줘.', result, undefined, [replacement.attachment.id])
          assert.equal(next.status, 'SUCCEEDED')
          assert.notEqual(next.threadId, turn.threadId)
          assert.equal(next.context.history.length, 0)
        }
      } else if (baseId === 751) {
        const [question, text, expected] = notes[fixture]
        let documents = { 'primary.md': text, 'context.md': '가상 평가용 애자일 일반 노트다. 실제 회사 데이터는 포함하지 않는다.' }
        if (action === 1) documents = Object.fromEntries(Object.entries(documents).reverse())
        if (action === 2) documents['different-team.md'] = '다른 가상 팀 OLD의 오래된 정책은 큰 배포와 백로그 12개월을 권장했다. 이는 현재 문서의 정책이 아니다.'
        if (action === 3) documents = { 'garden.md': '온실 GREEN의 물 주기 간격은 12시간이다. 애자일 노트는 이 자료에 포함되어 있지 않다.' }
        const prefix = await provision(id, documents)
        const turn = await answer(prefix, question, result)
        if (action === 3) assert(turn.status === 'NO_EVIDENCE' || /없|제공|확인|부족|알 수/.test(turn.answer), 'Removed evidence must not produce unsupported facts')
        else { assert.equal(turn.status, 'SUCCEEDED'); assert(expected.test(turn.answer), 'Automatic content check failed; inspect meaning and evidence') }
        const cited = [...turn.answer.matchAll(/\[(R\d+)\]/g)].map(match => match[1])
        assert(cited.every(label => turn.evidence.some(item => item.label.replace(/[\[\]]/g, '') === label)), 'Unknown citation label')
      } else {
        const prefix = await provision(id, conversationDocuments)
        const [first, follow, expected] = conversations[fixture]
        let previous = await answer(prefix, first, result)
        assert.equal(previous.status, 'SUCCEEDED', 'Initial answer failed; no substitute seeded answer')
        if (action === 1 || action === 2) {
          const before = await api(prefix + '/chat-threads/' + previous.threadId)
          if (action === 2) { await stop(); await start() }
          assert.deepEqual(await api(prefix + '/chat-threads/' + previous.threadId), before, 'Persisted thread changed on reopen')
        }
        if (action === 3) await answer(prefix, '온실과 무관한 싱글턴 정의를 알려줘', result)
        if (action === 4) {
          result.requestedLongTurns = longTurns
          result.completedLongTurns = 0
          if (longTurns < 20) result.remaining.push(`Short regression only: ${longTurns} of the specified 20 generic turns; full long-conversation acceptance not completed`)
          for (let number = 0; number < longTurns; number++) {
            previous = await answer(prefix, `앞서 설명한 대상을 유지하고 핵심만 한 문장으로 정리해 줘. 확인 ${number + 1}`, result, previous)
            assert.equal(previous.status, 'SUCCEEDED', 'Long conversation turn failed')
            result.completedLongTurns++
          }
        }
        const final = await answer(prefix, follow, result, previous)
        assert.equal(final.threadId, previous.threadId)
        assert.equal(final.status, 'SUCCEEDED')
        assert(expected.test(final.answer), 'Follow-up lost intended subject or facts')
        assert(final.context.history.length > 0, 'Follow-up context is empty')
        if (fixture === 3) assert(/없|않|수동/.test(final.answer), 'AMBER must not gain automatic renewal')
      }
      result.status = 'PARTIAL'; result.checkStatus = 'PASS'
    } catch (error) {
      result.status = 'FAIL'; result.checkStatus = 'FAIL'; result.failure = String(error)
      if (shutdown.signal.aborted || budget.reservations.length >= 200) setupFailure = 'Runtime deadline or authorized global budget exhausted'
    }
    result.elapsedMillis = Date.now() - Date.parse(result.startedAt)
    await save()
    console.log(JSON.stringify({ id, checkStatus: result.checkStatus, callsReserved: budget.reservations.length }))
  }
} finally {
  await stop(); clearTimeout(limit)
  report.completedAt = new Date().toISOString(); report.callsReserved = budget.reservations.length
  await save()
}
