import assert from 'node:assert/strict'
import { execFile, spawn } from 'node:child_process'
import { once } from 'node:events'
import { promisify } from 'node:util'
import { createReadStream } from 'node:fs'
import { mkdtemp, mkdir, readFile, writeFile, rm, stat, realpath } from 'node:fs/promises'
import { createHash, randomBytes } from 'node:crypto'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const execute = promisify(execFile)
const root = fileURLToPath(new URL('../', import.meta.url))
assert.equal(process.platform, 'darwin')
const previous = process.argv[2] || '0.1.38'
assert.match(previous, /^\d+\.\d+\.\d+$/)
assert.ok(previous.localeCompare('0.1.32', undefined, { numeric: true }) >= 0, 'Pre-0.1.32 app-name migration requires manual DMG installation; do not claim automatic update coverage.')
assert.equal(process.arch, 'arm64', 'Product upgrade acceptance runs on Apple Silicon Macs only')
const architecture = 'arm64'
const release = JSON.parse((await execute('gh', ['api', `repos/thisisnew/dougle/releases/tags/v${previous}-preview.1`])).stdout)
assert.equal(release.draft, false)
const archiveName = `Dougle_${previous}_${architecture}.app.tar.gz`
const asset = release.assets.find(value => value.name === archiveName)
  || release.assets.find(value => value.name === `Dougle_${previous}_${architecture}.dmg`)
assert.ok(asset, 'Previous release must have a public archive or DMG')
const archive = resolve(root, 'release', asset.name)
assert.equal(asset.size, (await stat(archive)).size)
async function digest(path) {
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(path)) hash.update(chunk)
  return hash.digest('hex')
}
assert.equal(asset.digest, 'sha256:' + await digest(archive))
const temporary = await realpath(await mkdtemp(resolve(tmpdir(), 'forge-published-update-')))
const installed = resolve(temporary, 'installed')
const app = resolve(installed, 'Dougle.app')
const marker = resolve(temporary, 'synthetic-user-data.txt')
const environment = { ...process.env, TAURI_CONFIG: '{"bundle":{"resources":[]}}', CARGO_BUILD_JOBS: '2' }
const data = resolve(temporary, 'data')
const token = randomBytes(32).toString('hex')
let backend
let base
async function startBackend() {
  const resources = resolve(app, 'Contents/Resources/resources')
  let logs = ''
  let failed = false
  backend = spawn(resolve(resources, 'runtime/bin/java'), ['-jar', resolve(resources, 'dougle.jar'),
    '--forge.desktop=true', '--server.address=127.0.0.1', '--server.port=0', `--forge.data-dir=${data}`,
    `--forge.ai.codex-command=${resolve(temporary, 'no-ai')}`, `--forge.ai.claude-command=${resolve(temporary, 'no-ai')}`],
  { cwd: temporary, env: { PATH: '/usr/bin:/bin', HOME: temporary, TMPDIR: temporary, FORGE_DATA_DIR: data, FORGE_DESKTOP: 'true' },
    stdio: ['pipe', 'pipe', 'pipe'] })
  backend.on('error', () => { failed = true })
  for (const stream of [backend.stdout, backend.stderr]) stream.on('data', chunk => { logs = (logs + chunk).slice(-16000) })
  backend.stdin.write(token + '\n')
  const deadline = Date.now() + 90000
  while (Date.now() < deadline) {
    assert.equal(failed, false, 'Packaged Java launch failed')
    assert.equal(backend.exitCode, null, 'Packaged backend exited before readiness')
    const port = logs.match(/^FORGE_DESKTOP_READY:(\d+)$/m)?.[1]
    if (port) {
      base = `http://127.0.0.1:${port}`
      assert.equal((await fetch(base + '/api/session')).status, 403)
      return
    }
    await new Promise(done => setTimeout(done, 100))
  }
  throw Error('Packaged backend readiness deadline exceeded')
}
async function stopBackend() {
  if (!backend || backend.exitCode !== null || backend.signalCode !== null) return
  const ended = once(backend, 'exit')
  const owned = backend
  let forced = false
  const timeout = setTimeout(() => { forced = true; owned.kill('SIGKILL') }, 15000)
  owned.stdin.end()
  try { await ended } finally { clearTimeout(timeout) }
  assert.equal(forced, false, 'Packaged backend must stop before app replacement')
}
async function api(path, method = 'GET', body) {
  const multipart = body instanceof FormData
  const response = await fetch(base + '/api' + path, { method, signal: AbortSignal.timeout(15000),
    headers: { 'X-Forge-Session': token, 'X-Forge-Client': '1', ...(multipart ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : multipart ? body : JSON.stringify(body) })
  assert.equal(response.ok, true, `${method} ${path}: ${response.status}`)
  return response.status === 204 ? null : response.json()
}
async function version() {
  return (await execute('/usr/libexec/PlistBuddy', ['-c', 'Print :CFBundleShortVersionString', resolve(app, 'Contents/Info.plist')])).stdout.trim()
}
try {
  await mkdir(installed)
  if (asset.name.endsWith('.dmg')) {
    const mount = resolve(temporary, 'previous-dmg')
    await mkdir(mount)
    await execute('hdiutil', ['attach', '-readonly', '-nobrowse', '-mountpoint', mount, archive])
    try { await execute('ditto', [resolve(mount, 'Dougle.app'), app]) }
    finally { await execute('hdiutil', ['detach', mount]) }
  } else await execute('tar', ['-xzf', archive, '-C', installed])
  assert.equal(await version(), previous)
  await execute('codesign', ['--verify', '--deep', '--strict', app])
  await writeFile(marker, 'synthetic data outside app must remain', { mode: 0o600 })
  await startBackend()
  const workspace = await api('/workspaces', 'POST', { name: '가상 업데이트 보존 검사' })
  const workspacePath = `/workspaces/${workspace.id}`
  const form = new FormData()
  form.append('file', new Blob(['Synthetic upgrade evidence: 21 degrees.']), 'synthetic.txt')
  const entry = await api(`${workspacePath}/files`, 'POST', form)
  const draft = await api(`${workspacePath}/knowledge`, 'POST', { title: '가상 보존 문서', markdown: '게시할 가상 메모 [E1]',
    citations: [{ entryId: entry.id, startLine: 1, endLine: 1 }] })
  const knowledgePath = `${workspacePath}/knowledge/${draft.card.id}`
  await api(`${knowledgePath}/review`, 'POST', { expectedVersion: 1, reviewer: 'Synthetic upgrade test', note: 'Fixture only',
    claimsSupported: true, uncertaintyRemoved: true })
  await api(`${knowledgePath}/publication`, 'POST', { expectedVersion: 1, expectedPublicationRevision: 0 })
  await api(`${knowledgePath}/versions`, 'POST', { expectedVersion: 1, title: '가상 사람 수정', markdown: '사람의 미게시 수정 [E1]' })
  const paths = ['/session', `${workspacePath}/entries/${entry.id}/preview`, knowledgePath,
    `${knowledgePath}/published`, `${knowledgePath}/publication`]
  const before = await Promise.all(paths.map(path => api(path)))
  await stopBackend()
  await execute('bash', ['scripts/desktop-cargo.sh', 'build', '--locked', '--example', 'published_update_smoke'],
    { cwd: root, env: environment, timeout: 300000, maxBuffer: 1024 * 1024 })
  const result = await execute(resolve(root, 'desktop/src-tauri/target/debug/examples/published_update_smoke'), [app, previous],
    { cwd: root, env: environment, timeout: 600000, maxBuffer: 1024 * 1024 })
  console.log(result.stdout.trim())
  const target = JSON.parse(await readFile(resolve(root, 'updates/preview.json'), 'utf8')).version
  assert.notEqual(target, previous)
  assert.equal(await version(), target)
  await execute('codesign', ['--verify', '--deep', '--strict', app])
  assert.equal(target, JSON.parse(await readFile(resolve(root, 'desktop/package.json'), 'utf8')).version)
  const verifiedClasses = JSON.parse(await readFile(resolve(root, `docs/evaluations/release-${target}-classes.json`), 'utf8'))
  const approvedJar = verifiedClasses.find(value => value.architecture === architecture && value.status === 'PASS')
  assert.ok(approvedJar && /^[a-f0-9]{64}$/.test(approvedJar.jarSha256))
  assert.equal(await digest(resolve(app, 'Contents/Resources/resources/dougle.jar')), approvedJar.jarSha256)
  assert.equal(await readFile(marker, 'utf8'), 'synthetic data outside app must remain')
  await startBackend()
  const after = await Promise.all(paths.map(path => api(path)))
  assert.deepEqual(after, before, 'Workspace, file, manual draft, publication and evidence must survive the real update')
  await stopBackend()
  await execute('node', ['scripts/native-host-smoke.mjs', app], { cwd: root, timeout: 300000, maxBuffer: 1024 * 1024 })
  const report = { checkedAt: new Date().toISOString(), previous, previousArtifact: asset.name, target, architecture, status: 'PASS',
    checks: ['previous public artifact SHA-256 and app signature', 'public feed and real signed archive installation with default version comparison',
      'updated version, app signature and current JAR equality', 'external synthetic file preserved',
      'same synthetic SQLite database preserves workspace, active selection, file preview, human draft, publication and evidence across versions',
      'updated native app startup, session protection, duplicate protection, shutdown and restart'],
    scope: 'Real released app copy and synthetic SQLite data in temporary installation; updater library harness uses previous bundle version. Not menu clicks, /Applications permissions, private user data inspection, company SSO, or real AI.' }
  await writeFile(resolve(root, 'docs/published-update-report.json'), JSON.stringify(report, null, 2) + '\n')
  console.log(JSON.stringify(report, null, 2))
} finally {
  try { await stopBackend() } finally { await rm(temporary, { recursive: true, force: true }) }
}
