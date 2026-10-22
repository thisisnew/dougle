import assert from 'node:assert/strict'
import { spawn, execFile } from 'node:child_process'
import { createServer } from 'node:http'
import { once } from 'node:events'
import { promisify } from 'node:util'
import { mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const execute = promisify(execFile)
const root = fileURLToPath(new URL('../', import.meta.url))
const [major, minor, patch] = JSON.parse(await readFile(resolve(root, 'desktop/package.json'), 'utf8')).version.split('.').map(Number)
const nextVersion = `${major}.${minor}.${patch + 1}`
const mismatchedVersion = `${major}.${minor}.${patch + 2}`
const temporary = await mkdtemp(resolve(tmpdir(), 'forge-updater-'))
const key = resolve(temporary, 'test.key')
const archive = resolve(temporary, 'fixture.app.tar.gz')
const invalidArchive = resolve(temporary, 'invalid.app.tar.gz')
const source = resolve(temporary, 'source/Knowledge Forge.app/Contents/MacOS')
const installed = resolve(temporary, 'installed/Knowledge Forge.app')
const executable = resolve(installed, 'Contents/MacOS/knowledge-forge-desktop')
let server
try {
  await mkdir(source, { recursive: true })
  await mkdir(resolve(installed, 'Contents/MacOS'), { recursive: true })
  await writeFile(resolve(source, 'knowledge-forge-desktop'), 'synthetic updated executable', { mode: 0o755 })
  await writeFile(executable, 'synthetic previous executable', { mode: 0o755 })
  await writeFile(resolve(installed, 'old-marker'), 'old')
  await writeFile(resolve(temporary, 'user-data.txt'), 'synthetic user data must remain')
  await execute('tar', ['-czf', archive, '-C', resolve(temporary, 'source'), 'Knowledge Forge.app'], { env: { ...process.env, COPYFILE_DISABLE: '1' } })
  await writeFile(invalidArchive, 'signed fixture with invalid archive format')
  await execute('npm', ['run', 'tauri', '--', 'signer', 'generate', '--ci', '-w', key], { cwd: resolve(root, 'desktop') })
  for (const file of [archive, invalidArchive]) {
    await execute('npm', ['run', 'tauri', '--', 'signer', 'sign', '-f', key, '--app-version', nextVersion, file], { cwd: resolve(root, 'desktop') })
  }
  const publicKey = (await readFile(key + '.pub', 'utf8')).trim()
  const signature = (await readFile(archive + '.sig', 'utf8')).trim()
  const invalidSignature = (await readFile(invalidArchive + '.sig', 'utf8')).trim()
  const bytes = await readFile(archive)
  const tampered = Buffer.from(bytes); tampered[0] ^= 1
  const invalidBytes = await readFile(invalidArchive)
  let base
  server = createServer((request, response) => {
    const path = new URL(request.url, base).pathname.slice(1)
    const payload = path === 'package' ? bytes : path === 'tampered-package' ? tampered : path === 'invalid-package' ? invalidBytes : null
    if (payload) { response.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': payload.length }); response.end(payload); return }
    const platform = { url: base + (path === 'tampered' ? '/tampered-package' : path === 'invalid-archive' ? '/invalid-package' : '/package'), signature: path === 'invalid-archive' ? invalidSignature : signature }
    const manifest = { version: path === 'older' ? '0.0.0' : path === 'wrong-version' ? mismatchedVersion : nextVersion,
      platforms: path === 'missing-target' ? {} : { 'darwin-x86_64': platform, 'darwin-aarch64': platform } }
    response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(manifest))
  }).listen(0, '127.0.0.1')
  await once(server, 'listening')
  base = `http://127.0.0.1:${server.address().port}`
  const child = spawn('bash', ['scripts/desktop-cargo.sh', 'run', '--locked', '--example', 'updater_smoke', '--', base, executable, publicKey], {
    cwd: root, env: { ...process.env, TAURI_CONFIG: '{"bundle":{"resources":[]}}', CARGO_BUILD_JOBS: '2' }, stdio: 'inherit',
  })
  const timer = setTimeout(() => child.kill('SIGKILL'), 180000)
  const [code] = await once(child, 'exit'); clearTimeout(timer)
  assert.equal(code, 0)
} finally {
  if (server) await new Promise(done => server.close(done))
  await rm(temporary, { recursive: true, force: true })
}
