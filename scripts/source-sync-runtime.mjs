import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createHash, randomBytes } from 'node:crypto'
import { mkdtemp, mkdir, writeFile, symlink, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { once } from 'node:events'

const resources = resolve(process.argv[2] || 'desktop/src-tauri/resources')
const jarSha256 = createHash('sha256').update(await readFile(resolve(resources, 'dougle.jar'))).digest('hex')
if (process.env.FORGE_RECOVERY_JAR_SHA256) assert.equal(jarSha256, process.env.FORGE_RECOVERY_JAR_SHA256)
const asyncJobs = []
const startedAt = new Date().toISOString()
const checks = []
const directory = await mkdtemp(resolve(tmpdir(), 'dougle-source-recovery-'))
const home = resolve(directory, 'home')
const project = resolve(directory, 'project')
const token = randomBytes(32).toString('hex')
await mkdir(home)
await mkdir(project)
await writeFile(resolve(project, 'Main.java'), 'class Telescope { int syntheticObservation() { return 42; } }')
await writeFile(resolve(project, 'build.gradle'), "plugins { id 'java' }\n// SYNTHETIC_GRADLE_EVIDENCE")
await writeFile(resolve(project, 'empty.txt'), '')
await mkdir(resolve(project, 'module/build/reports/tests/test/classes'), { recursive: true })
await writeFile(resolve(project, 'module/build/reports/tests/test/classes', 'GeneratedTest'.repeat(12) + '.html'), 'GENERATED_REPORT_SHOULD_NOT_BE_COLLECTED')
await writeFile(resolve(directory, 'outside.txt'), 'OUTSIDE_SECRET_SENTINEL')
await symlink(resolve(directory, 'outside.txt'), resolve(project, 'external'))
await symlink(project, resolve(project, 'cycle'))
await mkdir(resolve(project, 'level/'.repeat(17)), { recursive: true })
await writeFile(resolve(project, 'level/'.repeat(17), 'deep.txt'), 'DEEP_SENTINEL')
const fixtureFiles = Number(process.env.FORGE_SOURCE_FIXTURE_FILES || 0)
assert(Number.isInteger(fixtureFiles) && fixtureFiles >= 0 && fixtureFiles <= 5000)
let maxStatusPollMillis = 0
let featureSearchMillis = null
if (fixtureFiles > 0) {
  for (let number = 0; number < fixtureFiles; number++) {
    await writeFile(resolve(project, `Synthetic${number}.java`),
      `class Synthetic${number} { int compute${number}(int value) { return value + ${number}; } }`)
  }
  await writeFile(resolve(project, 'FeatureFlagClient.java'),
    'class FeatureFlagClient { String provider = "flipt"; boolean enabled(String flag) { return flipt.evaluate(flag); } }')
}
let logs = ''
let base
const child = spawn(resolve(resources, 'runtime/bin/java'), ['-Duser.home=' + home, '-jar', resolve(resources, 'dougle.jar'), '--forge.ai.codex-command=/nonexistent', '--forge.ai.claude-command=/nonexistent'], {
  cwd: home, env: { PATH: '/usr/bin:/bin', HOME: home, FORGE_DATA_DIR: resolve(directory, 'data'), FORGE_DESKTOP: 'true' }, stdio: ['pipe', 'pipe', 'pipe'],
})
child.stdout.on('data', chunk => { logs += chunk })
child.stderr.on('data', chunk => { logs += chunk })
child.stdin.write(token + '\n')
async function api(path, method = 'GET', body, expected = 200) {
  if (method === 'POST' && path.endsWith('/sources') && body?.sync && expected === 201 && body.kind === 'LOCAL_FOLDER') {
    const created = await api(path, method, { ...body, sync: false }, expected)
    return api(path + '/' + created.id + '/sync', 'POST')
  }
  if (method === 'POST' && path.endsWith('/sync') && !path.endsWith('/files/sync')) {
    let job = await api(path + '-jobs', 'POST', undefined, 202)
    assert.ok(job.id)
    const jobPath = path + '-jobs/' + job.id
    asyncJobs.push({ path: jobPath, id: job.id })
    for (let retry = 0; job.state === 'RUNNING'; retry++) {
      assert(retry < 1200)
      await new Promise(done => setTimeout(done, 100))
      const started = performance.now()
      job = await api(jobPath)
      maxStatusPollMillis = Math.max(maxStatusPollMillis, performance.now() - started)
      assert(maxStatusPollMillis < 5000, 'Job status must not wait for the publish transaction or indexing')
    }
    assert.equal(job.state, 'COMPLETED', JSON.stringify(job))
    return job.source
  }

  const response = await fetch(base + '/api' + path, { method, headers: { 'X-Forge-Session': token, 'X-Forge-Client': '1', 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(120000) })
  assert.equal(response.status, expected, `${method} ${path}: ${await response.clone().text()}`)
  return response.status === 204 ? null : response.json()
}
try {
  for (let attempt = 0; attempt < 300; attempt++) {
    assert.equal(child.exitCode, null, logs)
    const ready = logs.match(/^FORGE_DESKTOP_READY:(\d+)$/m)
    if (ready) { base = `http://127.0.0.1:${ready[1]}`; break }
    await new Promise(done => setTimeout(done, 200))
  }
  assert.ok(base, 'Packaged runtime must start')
  assert.equal((await fetch(base + '/api/session')).status, 403)
  let modelReady = false
  for (let attempt = 0; attempt < 600; attempt++) {
    const model = await api('/search-model')
    assert.notEqual(model.state, 'ERROR', JSON.stringify(model))
    if (model.state === 'READY') { modelReady = true; break }
    await new Promise(done => setTimeout(done, 250))
  }
  assert.ok(modelReady, 'Bundled search engine must finish initialization')
  const workspace = await api('/workspaces', 'POST', { name: 'Synthetic source QA' }, 201)
  const prefix = `/workspaces/${workspace.id}`
  const source = await api(prefix + '/sources', 'POST', { name: 'Synthetic project', kind: 'LOCAL_FOLDER', location: project, sync: true }, 201)
  assert.equal(source.status, 'PARTIAL')
  assert.match(source.error, /심볼릭|깊이/)
  const hits = await api(prefix + '/search?query=syntheticObservation')
  assert.ok(hits.some(hit => hit.name === 'Main.java'), JSON.stringify({ source, hits }))
  if (fixtureFiles > 0) {
    const started = performance.now()
    const feature = await api(prefix + '/chat-threads', 'POST', {
      requestId: 'feature-flag-fixture', question: 'FeatureFlagClient flipt 구현 의존성 설정',
    }, 201)
    featureSearchMillis = performance.now() - started
    assert(feature.payload.items.some(item => item.quote.includes('flipt')), 'Feature flag evidence must be found')
    assert(featureSearchMillis < 10000, 'Feature flag evidence retrieval must finish within ten seconds')
    checks.push('feature-flag-retrieval-with-code-corpus')
  }
  assert.deepEqual(await api(prefix + '/search?query=OUTSIDE_SECRET_SENTINEL'), [])
  assert.deepEqual(await api(prefix + '/search?query=GENERATED_REPORT_SHOULD_NOT_BE_COLLECTED'), [])
  await rm(resolve(project, 'external'))
  await rm(resolve(project, 'cycle'))
  await rm(resolve(project, 'level'), { recursive: true })
  const retried = await api(prefix + `/sources/${source.id}/sync`, 'POST')
  assert.equal(retried.status, 'READY')
  assert.match(retried.error, /검색에서 제외한 파일 1개/)
  const gradleHits = await api(prefix + '/search?query=SYNTHETIC_GRADLE_EVIDENCE')
  assert.ok(gradleHits.some(hit => hit.name === 'build.gradle'), JSON.stringify(gradleHits))
  assert.deepEqual(await api(prefix + '/analysis-failures'), [])
  assert.equal(await readFile(resolve(project, 'Main.java'), 'utf8'), 'class Telescope { int syntheticObservation() { return 42; } }')
  checks.push('partial-source-recovery', 'healthy-sibling-search', 'excluded-path-isolation', 'original-unchanged')
  const otherProject = resolve(directory, 'other-project')
  await mkdir(otherProject)
  await writeFile(resolve(otherProject, 'Main.java'), 'class Telescope { int syntheticObservation() { return 987654; } }')
  const other = await api('/workspaces', 'POST', { name: 'Isolation QA' }, 201)
  const otherPrefix = `/workspaces/${other.id}`
  const otherSource = await api(otherPrefix + '/sources', 'POST', { name: 'Other project', kind: 'LOCAL_FOLDER', location: otherProject, sync: true }, 201)
  await api(otherPrefix + '/sources/' + otherSource.id + '/sync-jobs/' + asyncJobs[0].id, 'GET', undefined, 404)
  const bundle = await api(prefix + '/chat-threads', 'POST', { requestId: 'boundary-first', question: 'syntheticObservation' }, 201)
  assert(bundle.payload.items.length > 0)
  assert(bundle.payload.items.every(item => item.sourceId === source.id))
  assert(!JSON.stringify(bundle.payload.items).includes('987654'))
  const previewBody = { provider: 'CODEX', requestedModel: 'preview-only-no-dispatch' }
  await api(prefix + `/evidence-bundles/${bundle.id}/answer-preview`, 'POST', previewBody)
  await api(otherPrefix + `/chat-threads/${bundle.payload.chatContext.threadId}`, 'GET', undefined, 404)
  await api(otherPrefix + `/evidence-bundles/${bundle.id}`, 'GET', undefined, 404)
  await api(otherPrefix + `/evidence-bundles/${bundle.id}/answer-preview`, 'POST', previewBody, 404)
  await api(otherPrefix + '/chat-threads', 'POST', { requestId: 'foreign-parent',
    threadId: bundle.payload.chatContext.threadId, parentBundleId: bundle.id, question: 'syntheticObservation' }, 404)
  checks.push('same-name-workspace-evidence-isolation', 'foreign-thread-read-denied', 'foreign-bundle-read-denied',
    'foreign-preview-denied', 'foreign-parent-injection-denied')
  await writeFile(resolve(project, 'Main.java'), 'class Telescope { int syntheticObservation() { return 84; } }')
  await api(prefix + `/sources/${source.id}/sync`, 'POST')
  assert.equal((await api(prefix + `/evidence-bundles/${bundle.id}/validation`)).eligible, false)
  await api(prefix + `/evidence-bundles/${bundle.id}/answer-preview`, 'POST', previewBody, 409)
  assert.deepEqual(await api(prefix + `/evidence-bundles/${bundle.id}`), bundle)
  const fresh = await api(prefix + '/chat-threads', 'POST', { requestId: 'boundary-fresh', question: 'syntheticObservation' }, 201)
  assert(fresh.payload.items.some(item => item.quote.includes('84')))
  await api(prefix + `/evidence-bundles/${fresh.id}/answer-preview`, 'POST', previewBody)
  checks.push('changed-source-revalidated', 'stale-preview-denied', 'historical-evidence-preserved', 'fresh-source-searchable')
  await api(prefix + `/sources/${source.id}`, 'DELETE', undefined, 204)
  assert.equal((await api(prefix + `/evidence-bundles/${fresh.id}/validation`)).eligible, false)
  await api(prefix + `/evidence-bundles/${fresh.id}/answer-preview`, 'POST', previewBody, 409)
  assert.deepEqual(await api(prefix + `/evidence-bundles/${fresh.id}`), fresh)
  assert.deepEqual(await api(prefix + '/search?query=syntheticObservation'), [])
  assert((await api(otherPrefix + '/search?query=syntheticObservation')).some(hit => hit.name === 'Main.java'))
  assert.deepEqual(await api(prefix + '/answer-jobs'), [])
  assert.deepEqual(await api(otherPrefix + '/answer-jobs'), [])
  assert.equal(await readFile(resolve(project, 'Main.java'), 'utf8'), 'class Telescope { int syntheticObservation() { return 84; } }')
  checks.push('deleted-source-preview-denied', 'deleted-source-removed-from-search', 'other-workspace-preserved', 'no-answer-dispatch')
  for (const location of ['https://github.com/org/repo', 'https://GITHUB.COM./org/repo', 'https://raw.githubusercontent.com/org/repo/main/file']) {
    await api(prefix + '/sources', 'POST', { name: 'Blocked', kind: 'URL', location, sync: true }, 400)
  }
  const confluence = await api(prefix + '/sources', 'POST', { name: 'Synthetic Confluence', kind: 'URL', location: 'https://synthetic.atlassian.net/wiki/spaces/TEAM/pages/123', sync: true }, 201)
  assert.equal(confluence.status, 'ERROR')
  assert.match(confluence.error, /연결이 필요/)
  checks.push('github-url-denied', 'confluence-login-required')
  assert.equal(createHash('sha256').update(await readFile(resolve(resources, 'dougle.jar'))).digest('hex'), jarSha256)
  if (process.env.FORGE_RECOVERY_REPORT) await writeFile(resolve(process.env.FORGE_RECOVERY_REPORT), JSON.stringify({
    fixtureVersion: 'source-sync-jobs-v2', fixtureFiles, maxStatusPollMillis, featureSearchMillis, jarSha256, startedAt, finishedAt: new Date().toISOString(),
    checks: checks.map(id => ({ id, status: 'PASS' })), asyncJobs: asyncJobs.length, foreignJobDenied: true, buildReportExcluded: true, externalAiCalls: 0,
    scope: 'Packaged runtime with isolated synthetic local sources; partial E37/E40 evidence, not browser/tool isolation, real SSO, or semantic AI acceptance',
  }, null, 2) + '\n', { flag: 'wx', mode: 0o600 })
  console.log('PASS: packaged runtime, isolated HOME, no AI, healthy source search, unsafe entries skipped, retry recovery, original unchanged, GitHub blocked, Confluence connection required')
} finally {
  if (child.exitCode === null) { child.kill('SIGTERM'); await once(child, 'exit') }
  await rm(directory, { recursive: true, force: true })
}
