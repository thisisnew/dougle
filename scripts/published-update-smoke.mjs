import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { createReadStream } from 'node:fs'
import { mkdtemp, mkdir, readFile, writeFile, rm, stat, realpath } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const execute = promisify(execFile)
const root = fileURLToPath(new URL('../', import.meta.url))
assert.equal(process.platform, 'darwin')
const previous = process.argv[2] || '0.1.6'
assert.match(previous, /^\d+\.\d+\.\d+$/)
const architecture = process.arch === 'arm64' ? 'arm64' : 'x86_64'
assert.ok(['arm64', 'x64'].includes(process.arch))
const name = `Knowledge.Forge_${previous}_${architecture}.app.tar.gz`
const archive = resolve(root, 'release', name)
const release = JSON.parse((await execute('gh', ['api', `repos/thisisnew/knowledge-forge/releases/tags/v${previous}-preview.1`])).stdout)
assert.equal(release.draft, false)
const asset = release.assets.find(value => value.name === name)
assert.ok(asset)
assert.equal(asset.size, (await stat(archive)).size)
async function digest(path) {
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(path)) hash.update(chunk)
  return hash.digest('hex')
}
assert.equal(asset.digest, 'sha256:' + await digest(archive))
const temporary = await realpath(await mkdtemp(resolve(tmpdir(), 'forge-published-update-')))
const installed = resolve(temporary, 'installed')
const app = resolve(installed, 'Knowledge Forge.app')
const marker = resolve(temporary, 'synthetic-user-data.txt')
const environment = { ...process.env, TAURI_CONFIG: '{"bundle":{"resources":[]}}', CARGO_BUILD_JOBS: '2' }
async function version() {
  return (await execute('/usr/libexec/PlistBuddy', ['-c', 'Print :CFBundleShortVersionString', resolve(app, 'Contents/Info.plist')])).stdout.trim()
}
try {
  await mkdir(installed)
  await execute('tar', ['-xzf', archive, '-C', installed])
  assert.equal(await version(), previous)
  await execute('codesign', ['--verify', '--deep', '--strict', app])
  await writeFile(marker, 'synthetic data outside app must remain', { mode: 0o600 })
  await execute('bash', ['scripts/desktop-cargo.sh', 'build', '--locked', '--example', 'published_update_smoke'],
    { cwd: root, env: environment, timeout: 300000, maxBuffer: 1024 * 1024 })
  const result = await execute(resolve(root, 'desktop/src-tauri/target/debug/examples/published_update_smoke'), [app, previous],
    { cwd: root, env: environment, timeout: 600000, maxBuffer: 1024 * 1024 })
  console.log(result.stdout.trim())
  const target = JSON.parse(await readFile(resolve(root, 'updates/preview.json'), 'utf8')).version
  assert.notEqual(target, previous)
  assert.equal(await version(), target)
  await execute('codesign', ['--verify', '--deep', '--strict', app])
  assert.equal(await digest(resolve(app, 'Contents/Resources/resources/knowledge-forge.jar')),
    await digest(resolve(root, 'backend/target/knowledge-forge-0.1.0.jar')))
  assert.equal(await readFile(marker, 'utf8'), 'synthetic data outside app must remain')
  await execute('node', ['scripts/native-host-smoke.mjs', app], { cwd: root, timeout: 300000, maxBuffer: 1024 * 1024 })
  const report = { checkedAt: new Date().toISOString(), previous, target, architecture, status: 'PASS',
    checks: ['previous public archive SHA-256 and app signature', 'public feed and real signed archive installation with default version comparison',
      'updated version, app signature and current JAR equality', 'external synthetic file preserved', 'updated native app startup, session protection, duplicate protection, shutdown and restart'],
    scope: 'Real released app copy in temporary installation; updater library harness uses previous bundle version. Not menu clicks, /Applications permissions, existing user database migration, ARM on Intel, or real AI.' }
  await writeFile(resolve(root, 'docs/published-update-report.json'), JSON.stringify(report, null, 2) + '\n')
  console.log(JSON.stringify(report, null, 2))
} finally {
  await rm(temporary, { recursive: true, force: true })
}
