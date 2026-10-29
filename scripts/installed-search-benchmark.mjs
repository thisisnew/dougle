import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { createHash, randomBytes } from 'node:crypto'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir, cpus, arch } from 'node:os'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { performance } from 'node:perf_hooks'
import { documents, questions, score } from './retrieval-acceptance.mjs'

const resources = resolve(process.argv[2])
const destination = resolve(process.argv[3])
const distractorCount = Number(process.argv[4] ?? 100)
assert(Number.isInteger(distractorCount) && distractorCount >= 0 && distractorCount <= 5000)
const hash = bytes => createHash('sha256').update(bytes).digest('hex')
const jarBytes = await readFile(resolve(resources, 'dougle.jar'))
const jarSha256 = hash(jarBytes)
assert.equal(jarSha256, '97c02f213455511766c475eeb5d934cac50614b7b0d864726b6213a76d36dcbc')
const directory = await mkdtemp(resolve(tmpdir(), 'dougle-search-benchmark-'))
const home = resolve(directory, 'home'), project = resolve(directory, 'project')
await mkdir(home); await mkdir(project)
const jar = resolve(directory, 'frozen.jar')
await writeFile(jar, jarBytes, { mode: 0o400 })
const fixture = { ...documents }
for (let index = 0; index < distractorCount; index++) {
  fixture[`distractors/Inventory${index}.java`] = `package inventory;\nclass Inventory${index} {\n private int quantity;\n int available() { return quantity; }\n boolean canReserve(int requested) { return requested > 0 && requested <= quantity; }\n}\n`
}
for (const [path, content] of Object.entries(fixture)) {
  await mkdir(dirname(resolve(project, path)), { recursive: true })
  await writeFile(resolve(project, path), content)
}
const report = {
  startedAt: new Date().toISOString(), jarSha256,
  harnessSha256: hash(await readFile(fileURLToPath(import.meta.url))),
  fixtureSha256: hash(JSON.stringify({ fixture, questions })),
  environment: { arch: arch(), cpu: cpus()[0].model, logicalCpus: cpus().length },
  files: Object.keys(fixture).length, bytes: Object.values(fixture).reduce((sum, value) => sum + Buffer.byteLength(value), 0),
  externalAiCalls: 0, samples: [],
  limitations: ['Synthetic small corpus, not company repository scale.', 'First pass includes application warmup; OS disk caches are not cleared.',
    'HTTP search and evidence construction are measured separately; no AI generation or human investigation comparison.',
    'Relevance labels are inherited fixture-file labels, not independent passage judgments.'],
}
const token = randomBytes(32).toString('hex')
let child, base, logs = ''
const wait = ms => new Promise(done => setTimeout(done, ms))
async function api(path, method = 'GET', body) {
  const response = await fetch(base + '/api' + path, { method,
    headers: { 'X-Forge-Session': token, 'X-Forge-Client': '1', 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(120000) })
  assert(response.ok, `HTTP ${response.status}: ${await response.clone().text()}`)
  return response.json()
}
try {
  child = spawn(resolve(resources, 'runtime/bin/java'), ['-Duser.home=' + home, '-jar', jar,
    '--forge.ai.codex-command=/nonexistent', '--forge.ai.claude-command=/nonexistent',
    '--forge.search-model-directory=' + resolve(resources, 'search-model-e5-v1')], {
    cwd: home, env: { HOME: home, TMPDIR: directory, PATH: '/usr/bin:/bin',
      FORGE_DATA_DIR: resolve(directory, 'data'), FORGE_DESKTOP: 'true' }, stdio: ['pipe', 'pipe', 'pipe'],
  })
  child.stdout.on('data', chunk => { logs = (logs + chunk).slice(-32000) })
  child.stderr.on('data', chunk => { logs = (logs + chunk).slice(-32000) })
  child.stdin.write(token + '\n')
  for (let attempt = 0; attempt < 300; attempt++) {
    assert.equal(child.exitCode, null, logs)
    const ready = logs.match(/^FORGE_DESKTOP_READY:(\d+)$/m)
    if (ready) { base = `http://127.0.0.1:${ready[1]}`; break }
    await wait(200)
  }
  assert(base, 'Backend must start')
  for (let attempt = 0; ; attempt++) {
    const state = await api('/search-model')
    assert.notEqual(state.state, 'ERROR')
    if (state.state === 'READY') break
    assert(attempt < 600, 'Search model must become ready'); await wait(250)
  }
  const workspace = await api('/workspaces', 'POST', { name: 'Search timing QA' })
  const prefix = `/workspaces/${workspace.id}`
  const syncStart = performance.now()
  const source = await api(prefix + '/sources', 'POST', { name: 'Synthetic corpus', kind: 'LOCAL_FOLDER', location: project, sync: true })
  report.syncMs = performance.now() - syncStart
  assert.equal(source.status, 'READY', JSON.stringify(source))
  for (let round = 0; round < 3; round++) {
    for (const query of questions) {
      const searchStart = performance.now()
      const hits = await api(prefix + '/search?query=' + encodeURIComponent(query.question))
      const searchMs = performance.now() - searchStart
      const evidenceStart = performance.now()
      const bundle = await api(prefix + '/chat-threads', 'POST', { requestId: `timing-${round}-${query.id}`, question: query.question })
      const sample = { round, id: query.id, searchMs, evidenceMs: performance.now() - evidenceStart,
        searchHits: hits.length, titles: bundle.payload.items.map(item => item.title), ...score(bundle.payload.items, query) }
      report.samples.push(sample)
      console.log(JSON.stringify(sample))
    }
  }
  for (const [path, content] of Object.entries(fixture)) assert.equal(await readFile(resolve(project, path), 'utf8'), content)
  assert.equal(hash(await readFile(resolve(resources, 'dougle.jar'))), jarSha256)
  report.originalsUnchanged = true
  report.summary = {}
  for (const [name, samples] of [['firstPass', report.samples.filter(s => s.round === 0)], ['repeat', report.samples.filter(s => s.round > 0)]]) {
    report.summary[name] = {}
    for (const metric of ['searchMs', 'evidenceMs']) {
      const values = samples.map(s => s[metric]).sort((a, b) => a - b)
      report.summary[name][metric] = { count: values.length, median: values[Math.ceil(values.length * 0.5) - 1], p95: values[Math.ceil(values.length * 0.95) - 1], max: values.at(-1) }
    }
  }
  report.retrievalFailures = report.samples.filter(s => s.hitAt5 === false || s.evidencePresent === false || s.negativeAbstention === false).map(s => ({ round: s.round, id: s.id }))
  if (report.retrievalFailures.length) process.exitCode = 1
} catch (error) {
  report.error = error.message
  process.exitCode = 1
} finally {
  if (child && child.exitCode === null) { child.kill('SIGTERM'); await once(child, 'exit') }
  await rm(directory, { recursive: true, force: true })
  report.cleanup = true
  report.finishedAt = new Date().toISOString()
  await writeFile(destination, JSON.stringify(report, null, 2) + '\n', { flag: 'wx', mode: 0o600 })
  console.log(JSON.stringify(report.summary ?? { error: report.error }))
}
