import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { mkdtemp, mkdir, writeFile, symlink, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { once } from 'node:events'

const resources = resolve(process.argv[2] || 'desktop/src-tauri/resources')
const directory = await mkdtemp(resolve(tmpdir(), 'dougle-source-recovery-'))
const home = resolve(directory, 'home')
const project = resolve(directory, 'project')
const token = randomBytes(32).toString('hex')
await mkdir(home)
await mkdir(project)
await writeFile(resolve(project, 'Main.java'), 'class Telescope { int syntheticObservation() { return 42; } }')
await writeFile(resolve(directory, 'outside.txt'), 'OUTSIDE_SECRET_SENTINEL')
await symlink(resolve(directory, 'outside.txt'), resolve(project, 'external'))
await symlink(project, resolve(project, 'cycle'))
await mkdir(resolve(project, 'level/'.repeat(17)), { recursive: true })
await writeFile(resolve(project, 'level/'.repeat(17), 'deep.txt'), 'DEEP_SENTINEL')
let logs = ''
let base
const child = spawn(resolve(resources, 'runtime/bin/java'), ['-Duser.home=' + home, '-jar', resolve(resources, 'dougle.jar'), '--forge.ai.codex-command=/nonexistent', '--forge.ai.claude-command=/nonexistent'], {
  cwd: home, env: { PATH: '/usr/bin:/bin', HOME: home, FORGE_DATA_DIR: resolve(directory, 'data'), FORGE_DESKTOP: 'true' }, stdio: ['pipe', 'pipe', 'pipe'],
})
child.stdout.on('data', chunk => { logs += chunk })
child.stderr.on('data', chunk => { logs += chunk })
child.stdin.write(token + '\n')
async function api(path, method = 'GET', body, expected = 200) {
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
  assert.deepEqual(await api(prefix + '/search?query=OUTSIDE_SECRET_SENTINEL'), [])
  await rm(resolve(project, 'external'))
  await rm(resolve(project, 'cycle'))
  await rm(resolve(project, 'level'), { recursive: true })
  const retried = await api(prefix + `/sources/${source.id}/sync`, 'POST')
  assert.equal(retried.status, 'READY')
  assert.equal(retried.error, null)
  assert.equal(await readFile(resolve(project, 'Main.java'), 'utf8'), 'class Telescope { int syntheticObservation() { return 42; } }')
  for (const location of ['https://github.com/org/repo', 'https://GITHUB.COM./org/repo', 'https://raw.githubusercontent.com/org/repo/main/file']) {
    await api(prefix + '/sources', 'POST', { name: 'Blocked', kind: 'URL', location, sync: true }, 400)
  }
  const confluence = await api(prefix + '/sources', 'POST', { name: 'Synthetic Confluence', kind: 'URL', location: 'https://synthetic.atlassian.net/wiki/spaces/TEAM/pages/123', sync: true }, 201)
  assert.equal(confluence.status, 'ERROR')
  assert.match(confluence.error, /연결이 필요/)
  console.log('PASS: packaged runtime, isolated HOME, no AI, healthy source search, unsafe entries skipped, retry recovery, original unchanged, GitHub blocked, Confluence connection required')
} finally {
  if (child.exitCode === null) { child.kill('SIGTERM'); await once(child, 'exit') }
  await rm(directory, { recursive: true, force: true })
}
