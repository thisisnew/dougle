import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { randomBytes } from 'node:crypto'
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises'
import { resolve, join } from 'node:path'
import { tmpdir } from 'node:os'

const resources = resolve(process.argv[2])
const automatic = process.argv.includes('--auto-discovery')
const temporary = await mkdtemp(join(tmpdir(), 'dougle-fresh-install-'))
const home = join(temporary, 'home'), data = join(temporary, 'data')
await mkdir(home)
const cli = join(home, automatic ? '.nvm/versions/node/v22/bin' : '.local/bin')
await mkdir(cli, { recursive: true })
await writeFile(join(cli, 'company-codex'), '#!/bin/sh\n[ "$*" = "login status" ] || exit 99\necho "Logged in using ChatGPT"\n', { mode: 0o700 })
await writeFile(join(cli, 'company-claude'), '#!/bin/sh\ncase "$*" in\n"auth --help") printf "  status   show status\\n";;\n"auth status") printf \'{"loggedIn":true}\\n\';;\n*) exit 99;;\nesac\n', { mode: 0o700 })
const pause = () => new Promise(done => setTimeout(done, 150))
let child, base, token, logs = ''
const checks = []
async function until(check, label, milliseconds = 180000) {
  const deadline = Date.now() + milliseconds
  while (Date.now() < deadline) {
    if (child?.exitCode != null) throw Error('Backend exited before ' + label + '\n' + logs.slice(-6000))
    if (await check()) return
    await pause()
  }
  throw Error('Timeout: ' + label + '\n' + logs.slice(-6000))
}
async function start() {
  token = randomBytes(32).toString('hex'); logs = ''
  child = spawn(join(resources, 'runtime/bin/java'), ['-Duser.home=' + home, '-jar', join(resources, 'dougle.jar'),
    '--forge.desktop=true', ...(!automatic ? ['--forge.ai.codex-command=' + join(temporary, 'missing-codex'),
    '--forge.ai.claude-command=' + join(temporary, 'missing-claude')] : []), '--forge.git-home=' + join(resources, 'git')], {
    cwd: temporary, env: { HOME: home, PATH: '/usr/bin:/bin', TMPDIR: temporary, LANG: 'en_US.UTF-8', FORGE_DATA_DIR: data, FORGE_DESKTOP: 'true' },
    stdio: ['pipe', 'pipe', 'pipe'],
  })
  child.stdout.on('data', value => { logs = (logs + value).slice(-40000) })
  child.stderr.on('data', value => { logs = (logs + value).slice(-40000) })
  child.stdin.write(token + '\n')
  await until(() => {
    const port = logs.match(/^FORGE_DESKTOP_READY:(\d+)\r?$/m)?.[1]
    if (port) base = 'http://127.0.0.1:' + port
    return !!port
  }, 'clean HOME backend readiness')
  assert.equal((await fetch(base + '/api/session')).status, 403)
}
async function stop() {
  if (child && child.exitCode === null) {
    const ended = once(child, 'exit')
    child.stdin.end()
    const timer = setTimeout(() => child.kill('SIGKILL'), 15000)
    await ended.finally(() => clearTimeout(timer))
  }
}
async function api(path, method = 'GET', body) {
  const form = body instanceof FormData
  const response = await fetch(base + '/api' + path, { method, signal: AbortSignal.timeout(30000),
    headers: { 'X-Forge-Session': token, 'X-Forge-Client': '1', ...(!form && body ? { 'Content-Type': 'application/json' } : {}) },
    body: form ? body : body ? JSON.stringify(body) : undefined,
  })
  assert.equal(response.ok, true, 'API ' + path + ' HTTP ' + response.status + ': ' + await response.clone().text())
  return response.json()
}
try {
  await start(); checks.push('clean-home-bundled-runtime-and-session-protection')
  console.log('PASS clean HOME startup and session protection')
  if (automatic) {
    const settings = await api('/ai/launch-settings')
    const selected = {}
    for (const provider of ['CODEX', 'CLAUDE']) {
      const discovered = await api('/ai/launch-settings/discovered/' + provider)
      const candidate = discovered.find(item => item.name === 'company-' + provider.toLowerCase())
      assert(candidate, provider + ' company tool must be discovered')
      selected[provider === 'CODEX' ? 'codexPath' : 'claudePath'] = candidate.path
    }
    if (settings.activeCodexCommand !== selected.codexPath || settings.activeClaudeCommand !== selected.claudePath) {
      await api('/ai/launch-settings', 'PUT', { ...selected, expectedRevision: settings.saved.revision, acknowledged: true })
      await stop(); await start()
      checks.push('multiple-installed-tools-selected-from-discovery-without-typing-paths')
    }
  }
  for (const provider of ['CODEX', 'CLAUDE']) {
    assert.equal((await api('/ai/providers/' + provider + '/connection', 'POST')).state, automatic ? 'AUTHENTICATED' : 'TOOL_UNAVAILABLE')
    if (automatic) assert((await api('/ai/launch-settings/discovered/' + provider)).some(item => item.path === join(cli, 'company-' + provider.toLowerCase())))
  }
  checks.push(automatic ? 'both-company-tools-discovered-under-minimal-finder-path' : 'missing-both-ai-tools-does-not-block-library')
  let previousModelState = null
  await until(async () => {
    const model = await api('/search-model')
    if (model.state !== previousModelState) console.log('Bundled model:', JSON.stringify(model))
    previousModelState = model.state
    assert.notEqual(model.state, 'ERROR', JSON.stringify(model))
    return model.state === 'READY'
  }, 'bundled tokenizer and embedding/reranker native libraries')
  checks.push('trimmed-native-libraries-load-real-bundled-models')
  const workspace = await api('/workspaces', 'POST', { name: '가상 새 Mac' })
  const form = new FormData()
  form.append('file', new Blob(['전략 패턴 Strategy 예제: Player는 Strategy.choose()에 선택 알고리즘을 위임합니다.']), 'strategy.txt')
  const file = await api('/workspaces/' + workspace.id + '/files', 'POST', form)
  await until(async () => (await api('/workspaces/' + workspace.id + '/search?query=' + encodeURIComponent('전략 패턴'))).some(hit => hit.entryId === file.id), 'upload-to-semantic-search')
  checks.push('upload-and-korean-concept-search-without-inference')
  const settings = await api('/ai/launch-settings')
  if (automatic) {
    assert(['', join(cli, 'company-codex')].includes(settings.saved.codexPath))
    assert(['', join(cli, 'company-claude')].includes(settings.saved.claudePath))
    assert.equal(settings.activeCodexCommand, join(cli, 'company-codex'))
    assert.equal(settings.activeClaudeCommand, join(cli, 'company-claude'))
  } else {
    const saved = await api('/ai/launch-settings', 'PUT', { codexPath: 'company-codex', claudePath: 'company-claude', acknowledged: true, expectedRevision: settings.saved.revision })
    assert.equal(saved.saved.codexPath, join(cli, 'company-codex'))
    assert.equal(saved.saved.claudePath, join(cli, 'company-claude'))
    assert.equal(saved.restartRequired, true)
  }
  await stop(); await start()
  for (const provider of ['CODEX', 'CLAUDE']) assert.equal((await api('/ai/providers/' + provider + '/connection', 'POST')).state, 'AUTHENTICATED')
  assert((await api('/session')).workspaces.some(item => item.id === workspace.id))
  checks.push('custom-company-command-names-survive-restart-and-authenticate-synthetic-tools')
  console.log(JSON.stringify({ checks, actualAiCalls: 0, companyAuthenticationVerified: false, resources }, null, 2))
} catch (error) {
  console.error(logs.slice(-6000))
  throw error
} finally {
  await stop()
  await rm(temporary, { recursive: true, force: true })
}
