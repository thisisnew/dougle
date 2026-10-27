import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { createHash, randomBytes, randomUUID } from 'node:crypto'
import { chmod, mkdir, mkdtemp, readFile, realpath, rename, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, isAbsolute, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { sandboxProfile, boundedPoll } from './e50-archive-fixtures.mjs'

export const artifactHash = 'b647ff9815037ddef3780246d712a944d4534a43060f881cdcbab8c2e6f3a612'
export const documents = {
  'patterns/Strategy.java': 'package patterns;\ninterface Strategy { int choose(); }\nclass FixedStrategy implements Strategy { public int choose() { return 7; } }\nclass Player { private final Strategy strategy; Player(Strategy strategy) { this.strategy = strategy; } int play() { return strategy.choose(); } }',
  'distractors/State.java': 'package distractors;\nclass NightState { void doClock(int hour) { if (hour >= 9 && hour < 17) System.out.println("DayState"); } }',
  '가상 업무.md': '# 가상 온실 운영\n월평균 사람이 직접 보정하는 센서 측정값은 120건입니다.\n센서 교체는 측정 보정과 다른 업무입니다. 센서 교체는 분기별 3건입니다.',
  'policy.txt': 'Synthetic badge policy. BLUE expires 72 hours after issuance. AMBER expires 24 hours after issuance. Neither badge renews automatically. AMBER renewal requires visitor-desk manual approval.',
}
export const questions = [
  { id: 'strategy-exact', question: 'Strategy 예제', expected: ['Strategy.java'], phrase: 'strategy.choose()' },
  { id: 'strategy-korean', question: '전략 패턴 예제 좀 보여줘', expected: ['Strategy.java'], phrase: 'strategy.choose()' },
  { id: 'strategy-paraphrase', question: '선택 알고리즘을 외부에서 전달하는 코드는 어디야?', expected: ['Strategy.java'], phrase: 'strategy.choose()' },
  { id: 'strategy-colloquial', question: '전략패턴 어떻게짜냐', expected: ['Strategy.java'], phrase: 'strategy.choose()' },
  { id: 'operation-exact', question: '월평균 수기 보정 건수는?', expected: ['가상 업무.md'], phrase: '120건' },
  { id: 'operation-paraphrase', question: '한 달에 사람이 직접 고치는 센서 값이 몇 건이야?', expected: ['가상 업무.md'], phrase: '120건' },
  { id: 'badge-cross-language', question: 'AMBER 배지는 언제 만료돼?', expected: ['policy.txt'], phrase: '24 hours' },
  { id: 'badge-interpretation', question: 'AMBER는 시간이 지나면 저절로 갱신되는 거야?', expected: ['policy.txt'], phrase: 'Neither badge renews automatically' },
  { id: 'negative-moon', question: '달 표면의 중력은 얼마인가?', expected: [] },
  { id: 'negative-pizza', question: '피자 만드는 방법은?', expected: [] },
]
export function score(items, question) {
  const matches = item => question.expectedPath ? (item.sourcePath ?? item.path ?? '').includes(question.expectedPath) : question.expected.includes(item.title)
  const positive = !!question.expectedPath || question.expected.length > 0
  const relevant = items.filter(matches)
  return {
    hitAt1: positive ? matches(items[0] ?? {}) : null,
    hitAt5: positive ? items.slice(0, 5).some(matches) : null,
    precisionReturned: items.length ? relevant.length / items.length : null,
    evidencePresent: question.phrase ? relevant.some(item => item.quote.includes(question.phrase)) : null,
    negativeAbstention: positive ? null : items.length === 0,
  }
}

export async function run(reportPath, notionPath) {
  assert.equal(process.platform, 'darwin')
  assert(isAbsolute(reportPath) && (!notionPath || isAbsolute(notionPath)))
  const resources = await realpath('/Applications/Knowledge Forge.app/Contents/Resources/resources')
  const sourceJar = join(resources, 'knowledge-forge.jar')
  const bytes = await readFile(sourceJar)
  assert.equal(createHash('sha256').update(bytes).digest('hex'), artifactHash)
  const directory = await realpath(await mkdtemp(join(tmpdir(), 'dougle-retrieval-')))
  await chmod(directory, 0o700)
  const home = join(directory, 'home'), data = join(directory, 'data'), scratch = join(directory, 'scratch')
  for (const path of [home, data, scratch]) await mkdir(path, { mode: 0o700 })
  const jar = join(directory, 'frozen.jar')
  await writeFile(jar, bytes, { mode: 0o400 })
  const java = await realpath(join(resources, 'runtime/bin/java'))
  const models = await realpath(join(resources, 'search-model-e5-v1'))
  const git = await realpath(join(resources, 'git'))
  const sandbox = sandboxProfile(directory, dirname(dirname(java)), models)
    .replace('(deny network*)', '(allow network*)')
    + ' (allow file-read* (subpath ' + JSON.stringify(git) + ') (subpath "/Library/Keychains"))'
  const token = randomBytes(32).toString('hex')
  const report = { version: '0.1.27', artifactHash, startedAt: new Date().toISOString(), actualAiCalls: 0,
    harnessSha256: createHash('sha256').update(await readFile(fileURLToPath(import.meta.url))).digest('hex'),
    isolation: 'Private HOME/DB, exact copied JAR; filesystem sandbox, public network enabled for fixed source URLs; no installed credentials read or copied.',
    checks: [], retrieval: [], limitations: ['Retrieval evidence checks are not AI semantic-answer evaluation.',
      'Precision uses declared fixture-file relevance, not independent passage relevance judgments.',
      'Notion snapshot import, when provided, does not validate app OAuth or live connector ingestion.'] }
  let child, base, logs = ''
  const save = () => writeFile(reportPath, JSON.stringify(report, null, 2), { mode: 0o600 })
  async function api(path, method = 'GET', body) {
    const multipart = body instanceof FormData
    const response = await fetch(base + '/api' + path, { method, redirect: 'error', signal: AbortSignal.timeout(240000),
      headers: { 'X-Forge-Session': token, 'X-Forge-Client': '1', Origin: base,
        ...(!multipart && body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
      body: multipart ? body : body === undefined ? undefined : JSON.stringify(body) })
    const text = await response.text()
    if (!response.ok) throw new Error(`HTTP ${response.status}: ${text.slice(0, 240)}`)
    return text ? JSON.parse(text) : null
  }
  async function check(id, action) {
    const started = Date.now()
    try { report.checks.push({ id, status: 'PASS', detail: await action(), elapsedMs: Date.now() - started }) }
    catch (error) { report.checks.push({ id, status: 'FAIL', error: error.message.slice(0, 500), elapsedMs: Date.now() - started }) }
    console.log(JSON.stringify(report.checks.at(-1))); await save()
  }
  async function workspace(name) { return (await api('/workspaces', 'POST', { name })).id }
  async function upload(workspaceId, name, content) {
    const form = new FormData(); form.set('file', new Blob([content]), name)
    return api(`/workspaces/${workspaceId}/files`, 'POST', form)
  }
  async function source(workspaceId, name, kind, location) {
    const registered = await api(`/workspaces/${workspaceId}/sources`, 'POST', { name, kind, location, domain: '' })
    return api(`/workspaces/${workspaceId}/sources/${registered.id}/sync`, 'POST')
  }
  async function manifest(workspaceId, parentId, prefix = '') {
    const entries = await api(`/workspaces/${workspaceId}/entries` + (parentId ? '?parentId=' + parentId : ''))
    const paths = []
    for (const entry of entries) {
      const path = prefix + entry.name
      if (entry.kind === 'FOLDER') paths.push(...await manifest(workspaceId, entry.id, path + '/'))
      else paths.push(path)
    }
    return paths
  }
  async function query(workspaceId, route, item) {
    const started = Date.now()
    const bundle = await api(`/workspaces/${workspaceId}/evidence-bundles`, 'POST', { question: item.question, domains: [], includeKnowledge: false })
    const measured = score(bundle.payload.items, item)
    const row = { route, id: item.id, question: item.question, ...measured, elapsedMs: Date.now() - started,
      hits: bundle.payload.items.map(hit => ({ title: hit.title, path: hit.sourcePath ?? hit.path, startLine: hit.startLine, endLine: hit.endLine })) }
    report.retrieval.push(row); await save(); console.log(JSON.stringify(row))
    return bundle
  }
  try {
    child = spawn('/usr/bin/sandbox-exec', ['-p', sandbox, java, '-Duser.home=' + home, '-Djava.io.tmpdir=' + scratch,
      '-jar', jar, '--server.port=0', '--server.address=127.0.0.1', '--forge.desktop=true', '--forge.data-dir=' + data,
      '--forge.search-model-directory=' + models, '--forge.git-home=' + git,
      '--forge.ai.codex-command=/usr/bin/false', '--forge.ai.claude-command=/usr/bin/false', '--forge.ai.codex-stream-home='],
    { cwd: directory, detached: true, env: { PATH: '/usr/bin:/bin:/usr/sbin:/sbin', HOME: home, TMPDIR: scratch, FORGE_DATA_DIR: data, LANG: 'en_US.UTF-8' }, stdio: ['pipe', 'pipe', 'pipe'] })
    child.on('error', error => { logs = error.message })
    for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => { logs = (logs + chunk).slice(-24000) })
    child.stdin.write(token + '\n')
    await boundedPoll(async () => {
      assert(child.exitCode === null && child.signalCode === null, 'Backend exited: ' + logs.slice(-1000))
      const port = logs.match(/^FORGE_DESKTOP_READY:(\d+)\r?$/m)?.[1]
      if (!port) return false
      base = 'http://127.0.0.1:' + port
      const state = await api('/search-model'); assert.notEqual(state.state, 'ERROR', state.error)
      return state.state === 'READY'
    })
    const files = await workspace('파일 검색 검증')
    const folders = await workspace('폴더 검색 검증')
    const original = join(directory, 'original')
    await mkdir(original)
    await check('file-and-nested-folder-ingestion', async () => {
      for (const [path, content] of Object.entries(documents)) {
        await upload(files, path.split('/').at(-1), content)
        await mkdir(dirname(join(original, path)), { recursive: true })
        await writeFile(join(original, path), content)
      }
      const synced = await source(folders, '하위 폴더', 'LOCAL_FOLDER', original)
      assert.equal(synced.status, 'READY', synced.error)
      return { status: synced.status, revision: synced.revision, sourceId: synced.id }
    })
    for (const [route, workspaceId] of [['file', files], ['folder', folders]]) {
      for (const item of questions) await check(route + ':' + item.id, async () => {
        await query(workspaceId, route, item)
        const row = report.retrieval.at(-1)
        assert(item.expected.length ? row.hitAt5 && row.evidencePresent : row.negativeAbstention, 'Expected evidence/abstention missing')
        return { hitAt1: row.hitAt1, hitAt5: row.hitAt5 }
      })
    }
    await check('folder-edit-rename-delete-resync', async () => {
      await writeFile(join(original, 'policy.txt'), 'Synthetic badge policy. AMBER expires 36 hours after issuance. Never renews automatically.')
      await rename(join(original, 'policy.txt'), join(original, 'renamed-policy.txt'))
      await rm(join(original, 'patterns/Strategy.java'))
      const registered = (await api(`/workspaces/${folders}/sources`))[0]
      const synced = await api(`/workspaces/${folders}/sources/${registered.id}/sync`, 'POST')
      assert.equal(synced.status, 'READY', synced.error)
      const bundle = await query(folders, 'folder-after-edit', { id: 'updated-policy', question: 'AMBER 배지 만료 기간', expected: ['renamed-policy.txt'], phrase: '36 hours' })
      assert(bundle.payload.items.some(item => item.title === 'renamed-policy.txt' && item.quote.includes('36 hours')))
      const removed = await query(folders, 'folder-after-edit', { id: 'deleted-strategy', question: 'Strategy', expected: [] })
      assert(!removed.payload.items.some(item => item.title === 'Strategy.java'))
      return { revision: synced.revision }
    })
    for (const repository of ['design-pattern', 'study-gof-23-design-pattern']) await check('github:' + repository, async () => {
      const workspaceId = await workspace(repository === 'design-pattern' ? 'GitHub 검증1' : 'GitHub 검증2')
      await upload(workspaceId, 'operations.txt', '월평균 수기 보정은 120건입니다. 운영 패턴을 정리했습니다.')
      const synced = await source(workspaceId, repository, 'GITHUB', 'https://github.com/thisisnew/' + repository)
      report.checks.push({ id: 'github-status:' + repository, status: 'OBSERVED', detail: { status: synced.status, error: synced.error, revision: synced.revision } })
      assert(['READY', 'PARTIAL'].includes(synced.status), synced.error)
      const paths = await manifest(workspaceId, synced.rootEntryId)
      report.checks.push({ id: 'github-manifest:' + repository, status: 'OBSERVED', detail: { files: paths.length, paths } })
      const strategy = repository === 'study-gof-23-design-pattern'
      const queries = strategy ? ['전략패턴 예제좀', '전략패턴 어떻게짜냐', '알고리즘을 교체하는 예제', 'Strategy 인터페이스의 구현체는?', '손을 선택하는 전략은 어떻게 사용해?']
        : ['팩토리 메서드 패턴 예제', '객체를 만드는 코드는 어디야?', '싱글턴 예제 보여줘']
      for (const [index, question] of queries.entries()) await check(repository + ':query-' + index, async () => {
        const expectedPath = strategy ? '/strategy/' : index === 2 ? 'SingletonPattern/' : 'FactoryMethodPattern/'
        await query(workspaceId, repository, { id: 'G-' + index, question, expected: [], expectedPath })
        assert(report.retrieval.at(-1).hitAt5, 'Expected source path missing')
        return { hitAt1: report.retrieval.at(-1).hitAt1, precisionReturned: report.retrieval.at(-1).precisionReturned }
      })
      if (strategy) {
        const filePath = 'design-patten/src/part10/strategy/WinningStrategy.java'
        const url = `https://raw.githubusercontent.com/thisisnew/${repository}/${synced.revision}/${filePath}`
        const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(20000) })
        assert(response.ok); const content = await response.text(); assert(content.length < 64000)
        report.sameSource = { url, sha256: createHash('sha256').update(content).digest('hex'), bytes: Buffer.byteLength(content) }
        const singleFile = await workspace('동일 코드 파일')
        const singleFolder = await workspace('동일 코드 폴더')
        const singleWeb = await workspace('동일 코드 URL')
        await upload(singleFile, 'WinningStrategy.java', content)
        const local = join(directory, 'same-source'); await mkdir(local); await writeFile(join(local, 'WinningStrategy.java'), content)
        assert.equal((await source(singleFolder, '동일 코드', 'LOCAL_FOLDER', local)).status, 'READY')
        assert.equal((await source(singleWeb, '동일 코드', 'URL', url)).status, 'READY')
        for (const [route, target] of [['same-file', singleFile], ['same-folder', singleFolder], ['same-url', singleWeb], ['same-github', workspaceId]]) {
          for (const [index, question] of ['WinningStrategy는 어떻게 동작해?', '직전 판에서 이겼으면 같은 손을 다시 내는 코드는?', '승패에 따라 다음 손을 선택하는 전략을 설명해줘'].entries()) {
            await check(route + ':' + index, async () => {
              await query(target, route, { id: 'SAME-' + index, question, expected: ['WinningStrategy.java'], phrase: 'won' })
              assert(report.retrieval.at(-1).evidencePresent, 'Same-source answer-bearing evidence missing')
              return { hitAt1: report.retrieval.at(-1).hitAt1 }
            })
          }
        }
      }
      return { status: synced.status, revision: synced.revision }
    })
    await check('web-public-manifesto', async () => {
      const workspaceId = await workspace('웹 본문 검증')
      const synced = await source(workspaceId, '애자일 선언', 'URL', 'https://agilemanifesto.org/iso/ko/manifesto.html')
      assert.equal(synced.status, 'READY', synced.error)
      for (const [index, question] of ['애자일 선언은 문서보다 무엇을 중요하게 생각해?', '계획을 따르는 것과 변화 대응 중 무엇을 더 중요하게 생각해?'].entries()) {
        const bundle = await query(workspaceId, 'public-web', { id: 'W-' + index, question, expected: ['page.html'] })
        assert(bundle.payload.items.some(item => /작동하는 소프트웨어|변화에 대응/.test(item.quote)), 'Missing real page body')
      }
      return { status: synced.status, revision: synced.revision }
    })
    await check('notion-without-connection', async () => {
      const workspaceId = await workspace('Notion 연결 경계')
      const synced = await source(workspaceId, 'Agile', 'URL', 'https://jagged-timer-008.notion.site/Agile-359fdb093f9380648576c536ea909b2d?source=copy_link')
      assert.notEqual(synced.status, 'READY', 'Unauthenticated Notion must not be claimed complete')
      assert(synced.error && /연결|Notion|노션/.test(synced.error))
      return { status: synced.status, error: synced.error }
    })
    if (notionPath) await check('notion-fresh-snapshot-retrieval', async () => {
      const workspaceId = await workspace('Notion 본문 검색')
      const content = await readFile(notionPath, 'utf8')
      await upload(workspaceId, 'Agile.md', content)
      const cases = [
        ['폭포수모델은 누가처음 만든거냐', '윈스턴 로이스'], ['워터폴 방식의 시초로 소개된 사람은 누구야?', '윈스턴 로이스'],
        ['칸반으로 업무가 밀리는 곳을 확인하는 이유가 뭐야?', '병목'], ['지속적으로 개선한다는 일본식 용어는?', '카이젠'],
        ['스크럼이라는 말은 어디서 왔어?', '럭비'], ['XP에서 테스트를 먼저 작성하는 방식은?', 'Test First'],
        ['빌드가 너무 오래 걸려. 자료에서는 어느 정도를 권장해?', '10분'], ['사용자 스토리와 요구사항은 어떻게 달라?', '고객에게 가치'],
        ['배포할 것을 줄이고 더 자주 내보내라는 이유는?', '아기 발걸음'], ['백로그를 얼마나 쌓아두는 게 좋다고 해?', '3개월'],
      ]
      let passed = 0
      for (const [index, [question, phrase]] of cases.entries()) {
        await query(workspaceId, 'notion-snapshot-upload', { id: 'N-' + index, question, expected: ['Agile.md'], phrase })
        if (report.retrieval.at(-1).evidencePresent) passed++
      }
      assert.equal(passed, cases.length, 'Notion answer-bearing evidence recall')
      return { found: passed, total: cases.length, snapshotSha256: createHash('sha256').update(content).digest('hex') }
    })
  } catch (error) {
    report.fatalError = error.message.slice(0, 1500)
    throw error
  } finally {
    if (child && child.exitCode === null && child.signalCode === null) {
      const ended = once(child, 'exit'); child.stdin.end()
      const timer = setTimeout(() => { try { process.kill(-child.pid, 'SIGKILL') } catch {} }, 15000)
      try { await ended } finally { clearTimeout(timer) }
    }
    report.finishedAt = new Date().toISOString()
    report.cleanup = { childExited: !child || child.exitCode !== null || child.signalCode !== null, temporaryDataRemoved: false }
    await rm(directory, { recursive: true, force: true }); report.cleanup.temporaryDataRemoved = true
    report.summary = { passed: report.checks.filter(item => item.status === 'PASS').length, failed: report.checks.filter(item => item.status === 'FAIL').length,
      queries: report.retrieval.length }
    await save()
  }
  console.log(JSON.stringify(report.summary))
  if (report.summary.failed) process.exitCode = 1
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  assert(process.argv.length === 3 || process.argv.length === 4, 'Usage: node scripts/retrieval-acceptance.mjs ABSOLUTE_REPORT [ABSOLUTE_NOTION_SNAPSHOT]')
  await run(process.argv[2], process.argv[3])
}
