import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { randomBytes, randomUUID } from 'node:crypto'
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises'
import { resolve, join } from 'node:path'
import { tmpdir } from 'node:os'

const resources = resolve(process.argv[2])
const profileAlias = process.argv.includes('--profile-alias')
const liveSelection = process.argv.includes('--live-selection')
assert(!liveSelection || profileAlias, '--live-selection requires --profile-alias')
const automatic = profileAlias || process.argv.includes('--auto-discovery')
const temporary = await mkdtemp(join(tmpdir(), 'dougle-fresh-install-'))
const home = join(temporary, 'home'), data = join(temporary, 'data')
await mkdir(home)
const cli = join(home, automatic ? '.nvm/versions/node/v22/bin' : '.local/bin')
await mkdir(cli, { recursive: true })
await writeFile(join(cli, 'company-codex'), '#!/bin/sh\n[ "$*" = "login status" ] || exit 99\necho "Logged in using ChatGPT"\n', { mode: 0o700 })
await writeFile(join(cli, 'company-claude'), '#!/bin/sh\ncase "$*" in\n"auth --help") printf "  status   show status\\n";;\n"auth status") printf \'{"loggedIn":true}\\n\';;\n*) exit 99;;\nesac\n', { mode: 0o700 })
if (liveSelection) {
  await writeFile(join(cli, 'company-codex'), '#!/bin/sh\nif [ "$*" = "login status" ]; then echo "Logged in using ChatGPT"; exit 0; fi\ncat >/dev/null\necho \'{"title":"Synthetic","claims":[{"text":"Synthetic selected profile answer.","evidenceIds":["R1"]}]}\' > answer.json\n', { mode: 0o700 })
}
if (profileAlias) {
  await rm(join(cli, 'company-claude'))
  await writeFile(join(cli, 'claude'), '#!/bin/sh\n[ "$CLAUDE_CONFIG_DIR" = "$HOME/Company Profile" ] || exit 41\ncase "$*" in\n"auth --help") printf "  status show status\\n";;\n"auth status") printf \'{"loggedIn":true}\\n\';;\n*) ' + (liveSelection ? 'cat >/dev/null; echo \'{"type":"result","subtype":"success","is_error":false,"structured_output":{"title":"Synthetic","claims":[{"text":"Synthetic selected profile answer.","evidenceIds":["R1"]}]}}\'' : 'exit 99') + ';;\nesac\n', { mode: 0o700 })
  await writeFile(join(home, '.zshrc'), 'alias company-claude=\'CLAUDE_CONFIG_DIR="$HOME/Company Profile" claude\'\n')
}
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
    '--forge.desktop=true', ...(!automatic || liveSelection ? ['--forge.ai.codex-command=' + join(temporary, 'missing-codex'),
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
async function api(path, method = 'GET', body, expectedStatus) {
  const form = body instanceof FormData
  const response = await fetch(base + '/api' + path, { method, signal: AbortSignal.timeout(30000),
    headers: { 'X-Forge-Session': token, 'X-Forge-Client': '1', ...(!form && body ? { 'Content-Type': 'application/json' } : {}) },
    body: form ? body : body ? JSON.stringify(body) : undefined,
  })
  assert.equal(expectedStatus === undefined ? response.ok : response.status === expectedStatus, true, 'API ' + path + ' HTTP ' + response.status + ': ' + await response.clone().text())
  return response.json()
}
try {
  await start(); checks.push('clean-home-bundled-runtime-and-session-protection')
  console.log('PASS clean HOME startup and session protection')
  if (automatic) {
    const settings = await api('/ai/launch-settings')
    if (profileAlias && !liveSelection) assert(settings.activeClaudeCommand.startsWith('dougle-alias-'), 'profile alias must be selected at startup')
    if (liveSelection) {
      for (const provider of ['CODEX', 'CLAUDE']) assert.equal((await api('/ai/providers/' + provider + '/connection', 'POST')).state, 'TOOL_UNAVAILABLE')
      const discovered = await api('/ai/launch-settings/discovered/CLAUDE')
      const native = discovered.find(item => item.name === 'claude')
      assert(native)
      const failed = await api('/ai/launch-settings/connect/CLAUDE', 'POST', { path: native.path, expectedRevision: settings.saved.revision })
      assert.equal(failed.state, 'UNSUPPORTED')
      assert.deepEqual(await api('/ai/launch-settings'), settings)
      checks.push('wrong-native-profile-rejected-without-saving-or-activation')
    }
    const selected = {}
    for (const provider of ['CODEX', 'CLAUDE']) {
      const discovered = await api('/ai/launch-settings/discovered/' + provider)
      const candidate = discovered.find(item => item.name === 'company-' + provider.toLowerCase())
      assert(candidate, provider + ' company tool must be discovered')
      selected[provider === 'CODEX' ? 'codexPath' : 'claudePath'] = candidate.path
    }
    for (const provider of ['CODEX', 'CLAUDE']) {
      const before = await api('/ai/launch-settings')
      const path = selected[provider === 'CODEX' ? 'codexPath' : 'claudePath']
      assert.equal((await api('/ai/launch-settings/connect/' + provider, 'POST', { path, expectedRevision: before.saved.revision })).state, 'AUTHENTICATED')
      const active = await api('/ai/launch-settings')
      assert.equal(active[provider === 'CODEX' ? 'activeCodexCommand' : 'activeClaudeCommand'], path)
      assert.equal(active.restartRequired, false)
      assert.equal(active.saved.revision, before.saved.revision + 1)
    }
    checks.push('both-providers-verified-and-applied-without-process-restart')
  }
  for (const provider of ['CODEX', 'CLAUDE']) {
    assert.equal((await api('/ai/providers/' + provider + '/connection', 'POST')).state, automatic ? 'AUTHENTICATED' : 'TOOL_UNAVAILABLE')
    if (automatic) assert((await api('/ai/launch-settings/discovered/' + provider)).some(item => item.name === 'company-' + provider.toLowerCase()))
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
  async function answerWithSelectedProfiles(label) {
    await until(async () => {
      const model = await api('/search-model')
      assert.notEqual(model.state, 'ERROR', JSON.stringify(model))
      return model.state === 'READY'
    }, label + ' search readiness before answering')
    for (const provider of ['CODEX', 'CLAUDE']) {
      const bundle = await api(`/workspaces/${workspace.id}/evidence-bundles`, 'POST', { question: 'Strategy', domains: [], includeKnowledge: false })
      assert.equal(bundle.payload.status, 'READY')
      const path = `/workspaces/${workspace.id}/evidence-bundles/${bundle.id}`
      const preview = await api(path + '/answer-preview', 'POST', { provider, requestedModel: '' })
      const approved = await api(path + '/answer-approval', 'POST', { provider, requestedModel: '', digest: preview.digest, acknowledged: true, expectedApprovalId: null })
      const job = await api(path + '/answer-jobs', 'POST', { requestId: randomUUID(), approvalId: approved.approval.id, digest: preview.digest, approved: true })
      let result
      await until(async () => {
        result = await api(`/workspaces/${workspace.id}/answer-jobs/${job.id}`)
        return !['QUEUED', 'RUNNING', 'CANCELLING'].includes(result.status)
      }, label + ' ' + provider + ' synthetic answer', 15000)
      assert.equal(result.status, 'SUCCEEDED', JSON.stringify(result))
      assert.equal(result.answer, 'Synthetic selected profile answer. [R1]')
    }
    checks.push(label + '-both-selected-profiles-produce-synthetic-answers')
  }
  if (liveSelection) {
    await answerWithSelectedProfiles('without-restart')
    const bundle = await api(`/workspaces/${workspace.id}/evidence-bundles`, 'POST', { question: 'Strategy', domains: [], includeKnowledge: false })
    const path = `/workspaces/${workspace.id}/evidence-bundles/${bundle.id}`
    const preview = await api(path + '/answer-preview', 'POST', { provider: 'CLAUDE', requestedModel: '' })
    const approval = await api(path + '/answer-approval', 'POST', { provider: 'CLAUDE', requestedModel: '', digest: preview.digest, acknowledged: true, expectedApprovalId: null })
    await writeFile(join(home, '.zshrc'), 'alias company-claude=\'CLAUDE_CONFIG_DIR="$HOME/Company Profile" claude\'\nalias company-review=\'CLAUDE_CONFIG_DIR="$HOME/Company Profile" claude\'\n')
    const candidates = await api('/ai/launch-settings/discovered/CLAUDE')
    const alternative = candidates.find(item => item.name === 'company-review')
    const original = candidates.find(item => item.name === 'company-claude')
    assert(alternative && original && alternative.path !== original.path)
    const before = await api('/ai/launch-settings')
    assert.equal((await api('/ai/launch-settings/connect/CLAUDE', 'POST', { path: alternative.path, expectedRevision: before.saved.revision })).state, 'AUTHENTICATED')
    await api(path + '/answer-jobs', 'POST', { requestId: randomUUID(), approvalId: approval.approval.id, digest: preview.digest, approved: true }, 409)
    const changed = await api('/ai/launch-settings')
    assert.equal((await api('/ai/launch-settings/connect/CLAUDE', 'POST', { path: original.path, expectedRevision: changed.saved.revision })).state, 'AUTHENTICATED')
    checks.push('hot-switch-invalidates-old-answer-approval-before-model-execution')
  }
  const settings = await api('/ai/launch-settings')
  if (automatic) {
    const claudeCommand = profileAlias
      ? (await api('/ai/launch-settings/discovered/CLAUDE')).find(item => item.name === 'company-claude')?.path
      : join(cli, 'company-claude')
    assert(claudeCommand)
    assert(['', join(cli, 'company-codex')].includes(settings.saved.codexPath))
    assert(['', claudeCommand].includes(settings.saved.claudePath))
    assert.equal(settings.activeCodexCommand, join(cli, 'company-codex'))
    assert.equal(settings.activeClaudeCommand, claudeCommand)
  } else {
    const saved = await api('/ai/launch-settings', 'PUT', { codexPath: 'company-codex', claudePath: 'company-claude', acknowledged: true, expectedRevision: settings.saved.revision })
    assert.equal(saved.saved.codexPath, join(cli, 'company-codex'))
    assert.equal(saved.saved.claudePath, join(cli, 'company-claude'))
    assert.equal(saved.restartRequired, true)
  }
  await stop(); await start()
  for (const provider of ['CODEX', 'CLAUDE']) assert.equal((await api('/ai/providers/' + provider + '/connection', 'POST')).state, 'AUTHENTICATED')
  assert((await api('/session')).workspaces.some(item => item.id === workspace.id))
  if (liveSelection) await answerWithSelectedProfiles('after-process-restart')
  checks.push('custom-company-command-names-survive-restart-and-authenticate-synthetic-tools')
  console.log(JSON.stringify({ checks, actualAiCalls: 0, companyAuthenticationVerified: false, resources }, null, 2))
} catch (error) {
  console.error(logs.slice(-6000))
  throw error
} finally {
  await stop()
  await rm(temporary, { recursive: true, force: true })
}
