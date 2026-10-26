import assert from 'node:assert/strict'
import test from 'node:test'
import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { chmod, mkdir, mkdtemp, readFile, readdir, rm, stat, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { setTimeout as pause } from 'node:timers/promises'
import { ProtocolCounters } from './codex-protocol-counter.mjs'

const helper = fileURLToPath(new URL('./codex-protocol-counter.mjs', import.meta.url))
const appArgs = ['--no-daemon', 'app-server', '--stdio', '--strict-config']
const line = value => Buffer.from(JSON.stringify(value) + '\n')
const response = (id, result = {}) => ({ id, result })
const event = (method, params = {}) => ({ method, params })
const binding = { threadId: 'private-thread', turnId: 'private-turn' }
const responses = [response(1), response(2), response(3, { thread: { id: binding.threadId } }), response(4, { turn: { id: binding.turnId } })]
function accepted() {
  const counters = new ProtocolCounters()
  for (const value of responses) counters.feed(line(value))
  return counters
}

test('separates ordinary metadata from wrong-binding answer completion and nested turn start', () => {
  const counters = accepted()
  for (let index = 0; index < 6; index++) counters.feed(line(event('thread/status/changed', { threadId: binding.threadId })))
  counters.feed(line(event('turn/started', { threadId: binding.threadId, turn: { id: binding.turnId } })))
  counters.feed(line(event('item/started', { ...binding, item: { type: 'agentMessage', id: 'private-item', text: 'private-content' } })))
  counters.feed(line(event('item/completed', { ...binding, turnId: 'private-wrong-turn', item: { type: 'agentMessage', text: 'private-content' } })))
  counters.feed(line(event('turn/completed', { threadId: binding.threadId, turn: { id: 'private-other', status: 'completed' } })))
  const stats = counters.snapshot()
  assert.equal(stats.applicationBinding.THREAD_STATUS.MISSING_TURN, 6)
  assert.equal(stats.applicationBinding.TURN_STARTED.MISSING_TURN, 1)
  assert.equal(stats.normalizedBinding.TURN_STARTED.MATCH, 1)
  assert.equal(stats.applicationBinding.AGENT_STARTED.MATCH, 1)
  assert.equal(stats.applicationBinding.AGENT_COMPLETED.OTHER_TURN, 1)
  assert.equal(stats.applicationBinding.TURN_COMPLETED.OTHER_TURN, 1)
  assert.equal(stats.completionStatus.APPLICATION_IGNORED.COMPLETED, 1)
  assert.doesNotMatch(JSON.stringify(stats), /private|threadId|turnId|text/)
})

test('matches completion only using nested turn id and records fixed statuses', () => {
  const counters = accepted()
  for (const status of ['completed', 'failed', 'interrupted', 'inProgress', 'private-status', '__proto__', null]) {
    counters.feed(line(event('turn/completed', { ...binding, turnId: 'wrong-direct', turn: { id: binding.turnId, status } })))
  }
  const stats = counters.snapshot()
  assert.equal(stats.applicationBinding.TURN_COMPLETED.MATCH, 7)
  assert.deepEqual(stats.completionStatus.APPLICATION_MATCH, { NONE: 1, COMPLETED: 1, FAILED: 1, INTERRUPTED: 1, IN_PROGRESS: 1, OTHER: 2 })
})

test('early notifications learn ids only from sequential matching RPC responses', () => {
  const counters = new ProtocolCounters()
  counters.feed(line(response(3, { thread: { id: 'untrusted-early-thread' } })))
  for (const value of responses.slice(0, 3)) counters.feed(line(value))
  counters.feed(line(event('item/agentMessage/delta', { ...binding, delta: 'private-reasoning' })))
  assert.equal(counters.snapshot().applicationBinding.AGENT_DELTA.UNBOUND, 1)
  counters.feed(line(responses[3]))
  assert.equal(counters.snapshot().applicationBinding.AGENT_DELTA.MATCH, 1)
  assert.equal(counters.snapshot().applicationBinding.AGENT_DELTA.UNBOUND, 0)
  assert.equal(counters.snapshot().responses.UNEXPECTED, 1)
  assert.equal(counters.snapshot().buffer.earlyPending, 0)
})

test('pre-turn-request metadata is separated and never replayed into application binding counts', () => {
  const counters = new ProtocolCounters()
  for (const value of responses.slice(0, 3)) {
    counters.feed(line(event('thread/status/changed', { threadId: binding.threadId })))
    counters.feed(line(event('item/agentMessage/delta', { ...binding, delta: 'private-prestream' })))
    counters.feed(line(value))
  }
  assert.equal(counters.snapshot().buffer.earlyPending, 0)
  counters.feed(line(event('thread/status/changed', { threadId: binding.threadId })))
  assert.equal(counters.snapshot().buffer.earlyPending, 1)
  counters.feed(line(responses[3]))
  const snapshot = counters.snapshot()
  assert.equal(snapshot.preStreamIgnored.THREAD_STATUS, 3)
  assert.equal(snapshot.preStreamIgnored.AGENT_DELTA, 3)
  assert.equal(snapshot.applicationBinding.THREAD_STATUS.MISSING_TURN, 1)
  assert.equal(Object.values(snapshot.applicationBinding.AGENT_DELTA).reduce((total, count) => total + count, 0), 0)
  assert.equal(snapshot.buffer.earlyPending, 0)
})

test('distinguishes missing invalid and mismatched binding without copying unknown keys', () => {
  const counters = accepted()
  for (const params of [{}, { threadId: 1 }, { ...binding, threadId: 'other' }, { threadId: binding.threadId },
    { ...binding, turnId: [] }, { ...binding, turnId: 'other' }, binding]) counters.feed(line(event('__proto__', params)))
  assert.deepEqual(counters.snapshot().applicationBinding.OTHER, {
    UNBOUND: 0, MISSING_THREAD: 1, INVALID_THREAD: 1, OTHER_THREAD: 1, MISSING_TURN: 1, INVALID_TURN: 1, OTHER_TURN: 1, MATCH: 1,
  })
  counters.feed(line(event('item/started', { ...binding, item: { type: '__proto__' } })))
  assert.equal(counters.snapshot().applicationBinding.OTHER_ITEM_STARTED.MATCH, 1)
})

test('buffers split UTF8/newlines and exposes no unterminated payload', () => {
  const counters = accepted()
  const bytes = line(event('item/agentMessage/delta', { ...binding, delta: 'private-😀한글' }))
  for (const byte of bytes.subarray(0, bytes.length - 1)) counters.feed(Buffer.from([byte]))
  assert.equal(counters.snapshot().applicationBinding.AGENT_DELTA.MATCH, 0)
  assert.equal(counters.snapshot().buffer.pendingLineBytes, bytes.length - 1)
  counters.feed(Buffer.from('\n'))
  assert.equal(counters.snapshot().applicationBinding.AGENT_DELTA.MATCH, 1)
})

test('caps line total early queue and fixed stats size under malformed input', () => {
  const counters = new ProtocolCounters()
  for (const value of responses.slice(0, 3)) counters.feed(line(value))
  for (let index = 0; index < 140; index++) counters.feed(line(event('private-method', { ...binding, text: 'private' })))
  assert.equal(counters.snapshot().buffer.earlyPending, 128)
  assert.equal(counters.snapshot().totals.earlyDropped, 12)
  counters.feed(Buffer.from('private\n[]\n{}\n'))
  counters.feed(Buffer.alloc(262145, 120))
  counters.feed(Buffer.from('\n'))
  assert.equal(counters.snapshot().totals.oversizedLines, 1)
  counters.feed(Buffer.alloc(1048577, 120))
  const frozen = counters.snapshot()
  counters.feed(Buffer.alloc(1048577, 120))
  assert.deepEqual(counters.snapshot(), frozen)
  assert.equal(frozen.totals.stdoutBytes, 1048576)
  assert.equal(frozen.totals.captureLimitReached, 1)
  assert.equal(frozen.buffer.pendingLineBytes, 0)
  assert.ok(JSON.stringify(frozen).length < 12000)
  assert.doesNotMatch(JSON.stringify(frozen), /private/)
})

test('malformed or server-request envelopes cannot teach response bindings', () => {
  const counters = new ProtocolCounters()
  counters.feed(line({ id: 1, method: 'private-request', result: {} }))
  counters.feed(line({ id: 1, error: { message: 'private-error' } }))
  counters.feed(line(response('1')))
  counters.feed(line(response(1)))
  counters.feed(line(response(2)))
  counters.feed(line(response(3, { thread: { id: 'x'.repeat(257) } })))
  assert.equal(counters.snapshot().responses.INVALID, 3)
  assert.equal(counters.snapshot().responses.UNEXPECTED, 1)
  assert.equal(counters.snapshot().responses.THREAD, 0)
})

async function fixture(context, source) {
  const directory = await mkdtemp(join(tmpdir(), 'codex-counter-fake-'))
  context.after(() => rm(directory, { recursive: true, force: true }))
  const executable = join(directory, 'fake-codex')
  const script = `#!${process.execPath}\n${source}\n`
  await writeFile(executable, script, { mode: 0o700 })
  const statsDirectory = join(directory, 'stats')
  await mkdir(statsDirectory, { mode: 0o700 })
  const pin = { version: 1, executable, sha256: createHash('sha256').update(script).digest('hex'), statsDirectory }
  const pinFile = join(directory, 'pin.json')
  await writeFile(pinFile, JSON.stringify(pin), { mode: 0o600 })
  return { directory, executable, pinFile, pin, statsDirectory }
}

function launch(context, fixture, args = appArgs, environment = {}) {
  const child = spawn(process.execPath, [helper, '--pin-file', fixture.pinFile, '--', ...args], {
    cwd: fixture.directory, env: { PATH: '/usr/bin:/bin', HOME: fixture.directory, LANG: 'en_US.UTF-8', ...environment }, stdio: ['pipe', 'pipe', 'pipe'],
  })
  let stdout = Buffer.alloc(0)
  let stderr = Buffer.alloc(0)
  child.stdout.on('data', bytes => { stdout = Buffer.concat([stdout, bytes]) })
  child.stderr.on('data', bytes => { stderr = Buffer.concat([stderr, bytes]) })
  const finished = new Promise(resolve => child.once('close', (code, signal) => resolve({ code, signal, stdout, stderr })))
  const watchdog = setTimeout(() => child.kill('SIGTERM'), 5000)
  context.after(async () => { clearTimeout(watchdog); if (child.exitCode === null && child.signalCode === null) child.kill('SIGTERM'); await finished })
  finished.then(() => clearTimeout(watchdog))
  return { child, finished, stdout: () => stdout }
}

async function stats(fixture) {
  const files = await readdir(fixture.statsDirectory)
  assert.equal(files.length, 1)
  assert.match(files[0], /^codex-app-server-[a-f0-9-]+\.json$/)
  const path = join(fixture.statsDirectory, files[0])
  assert.equal((await stat(path)).mode & 0o777, 0o600)
  return JSON.parse(await readFile(path, 'utf8'))
}

test('wrapper preserves app-server stdout bytes stderr stdin args and exit code', { timeout: 8000 }, async context => {
  const output = Buffer.concat([...responses.map(line), line(event('item/agentMessage/delta', { ...binding, delta: 'private-😀' })), Buffer.from([0, 255, 13])])
  const fixtureData = await fixture(context, `const fs = require('node:fs'); const assert = require('node:assert/strict');
    assert.deepEqual(process.argv.slice(2), ${JSON.stringify([...appArgs, '--config', 'private=setting'])});
    assert.equal(fs.readFileSync(0, 'utf8'), 'private-input');
    process.stdout.write(Buffer.from('${output.toString('base64')}', 'base64'));
    process.stderr.write('private-stderr'); process.exitCode = 17;`)
  const run = launch(context, fixtureData, [...appArgs, '--config', 'private=setting'])
  run.child.stdin.end('private-input')
  const result = await run.finished
  assert.equal(result.code, 17)
  assert.deepEqual(result.stdout, output)
  assert.equal(result.stderr.toString(), 'private-stderr')
  const saved = await stats(fixtureData)
  assert.equal(saved.applicationBinding.AGENT_DELTA.MATCH, 1)
  assert.doesNotMatch(JSON.stringify(saved), /private|Authorization|auth|config|threadId/)
})

test('exec and status probes passthrough without producing app-server stats', { timeout: 8000 }, async context => {
  const fixtureData = await fixture(context, `process.stdin.resume(); process.stdin.on('end', () => { process.stdout.write(JSON.stringify(process.argv.slice(2))); process.exitCode = 23; });`)
  const plannerArgs = ['--no-daemon', 'exec', '--ignore-user-config', '--ephemeral', '--skip-git-repo-check', '--sandbox', 'read-only',
    '--color', 'never', '--config', 'approval_policy="never"', '--config', 'web_search="disabled"', '--config', 'project_doc_max_bytes=0',
    '--config', 'tools.view_image=false', '--enable', 'skip_host_skill_discovery', '--output-schema', '/synthetic/schema.json',
    '--output-last-message', '/synthetic/answer.json', '--config', 'cli_auth_credentials_store="file"', '--json',
    '--disable', 'shell_tool', '--disable', 'hooks', '--model', 'synthetic-model', '-']
  for (const args of [['exec', '--model', 'synthetic'], plannerArgs, ['--version'], ['login', 'status'], ['--config', 'cli_auth_credentials_store="file"', 'login', 'status']]) {
    const run = launch(context, fixtureData, args)
    run.child.stdin.end()
    const result = await run.finished
    assert.equal(result.code, 23)
    assert.deepEqual(JSON.parse(result.stdout.toString()), args)
    assert.deepEqual(await readdir(fixtureData.statsDirectory), [])
  }
})

test('wrong pin and unsupported command fail before child launch without leaking paths', { timeout: 8000 }, async context => {
  const fixtureData = await fixture(context, `process.stdout.write('SHOULD_NOT_RUN');`)
  await writeFile(fixtureData.pinFile, JSON.stringify({ ...fixtureData.pin, sha256: '0'.repeat(64) }))
  for (const args of [appArgs, ['login'], ['app-server'], ['private-command']]) {
    const run = launch(context, fixtureData, args)
    run.child.stdin.end()
    const result = await run.finished
    assert.equal(result.code, 70)
    assert.equal(result.stdout.length, 0)
    assert.doesNotMatch(result.stderr.toString(), /private-command|SHOULD_NOT_RUN|fake-codex|pin.json/)
  }
  assert.deepEqual(await readdir(fixtureData.statsDirectory), [])
})

test('unsafe manifest permissions and symlinked binary are rejected before launch', { timeout: 8000 }, async context => {
  const fixtureData = await fixture(context, `process.stdout.write('SHOULD_NOT_RUN');`)
  await chmod(fixtureData.pinFile, 0o644)
  let run = launch(context, fixtureData)
  run.child.stdin.end()
  assert.equal((await run.finished).code, 70)
  await chmod(fixtureData.pinFile, 0o600)
  const alias = join(fixtureData.directory, 'alias')
  await symlink(fixtureData.executable, alias)
  await writeFile(fixtureData.pinFile, JSON.stringify({ ...fixtureData.pin, executable: alias }))
  run = launch(context, fixtureData)
  run.child.stdin.end()
  assert.equal((await run.finished).code, 70)
  assert.deepEqual(await readdir(fixtureData.statsDirectory), [])
})

test('stats are atomically persisted before forwarded bytes and signal cleanup preserves signal', { timeout: 8000 }, async context => {
  const output = Buffer.concat([...responses.map(line), line(event('turn/started', { threadId: binding.threadId, turn: { id: binding.turnId } }))])
  const fixtureData = await fixture(context, `process.stdout.write(Buffer.from('${output.toString('base64')}', 'base64')); setInterval(() => {}, 1000);`)
  const run = launch(context, fixtureData)
  const deadline = Date.now() + 3000
  while (run.stdout().length < output.length && Date.now() < deadline) await pause(10)
  assert.deepEqual(run.stdout(), output)
  assert.equal((await stats(fixtureData)).normalizedBinding.TURN_STARTED.MATCH, 1)
  run.child.kill('SIGTERM')
  const result = await run.finished
  assert.equal(result.signal, 'SIGTERM')
  assert.equal((await stats(fixtureData)).normalizedBinding.TURN_STARTED.MATCH, 1)
})

test('dedicated profile is passed unchanged and cannot be used as stats storage', { timeout: 8000 }, async context => {
  const fixtureData = await fixture(context, `process.stdout.write('synthetic-probe');`)
  const profile = join(fixtureData.directory, 'profile')
  await mkdir(profile, { mode: 0o700 })
  await writeFile(join(profile, 'auth.json'), 'synthetic-not-real-auth', { mode: 0o600 })
  let run = launch(context, fixtureData, ['--version'], { CODEX_HOME: profile })
  run.child.stdin.end()
  assert.equal((await run.finished).code, 0)
  assert.deepEqual(await readdir(profile), ['auth.json'])
  assert.equal(await readFile(join(profile, 'auth.json'), 'utf8'), 'synthetic-not-real-auth')
  await writeFile(fixtureData.pinFile, JSON.stringify({ ...fixtureData.pin, statsDirectory: profile }))
  run = launch(context, fixtureData, appArgs, { CODEX_HOME: profile })
  run.child.stdin.end()
  const rejected = await run.finished
  assert.equal(rejected.code, 70)
  assert.equal(rejected.stdout.length, 0)
  assert.match(rejected.stderr.toString(), /PROFILE_STATS_FORBIDDEN/)
  assert.deepEqual(await readdir(profile), ['auth.json'])
})

test('forwarded termination cleans child process group including synthetic descendant', { timeout: 8000 }, async context => {
  const fixtureData = await fixture(context, `const fs = require('node:fs'); const { spawn } = require('node:child_process');
    const leaf = spawn('/bin/sleep', ['60'], {stdio: 'ignore'});
    fs.writeFileSync('owned-pids.json', JSON.stringify([process.pid, leaf.pid]));
    process.stdout.write('${line(response(1)).toString().trim()}\\n'); setInterval(() => {}, 1000);`)
  const run = launch(context, fixtureData)
  const deadline = Date.now() + 3000
  while (run.stdout().length === 0 && Date.now() < deadline) await pause(10)
  assert.ok(run.stdout().length > 0)
  const pids = JSON.parse(await readFile(join(fixtureData.directory, 'owned-pids.json'), 'utf8'))
  context.after(() => { for (const pid of pids) { try { process.kill(pid, 'SIGKILL') } catch (error) { if (error.code !== 'ESRCH') throw error } } })
  run.child.kill('SIGTERM')
  assert.equal((await run.finished).signal, 'SIGTERM')
  for (const pid of pids) {
    const deadline = Date.now() + 1500
    let alive = true
    while (alive && Date.now() < deadline) {
      try { process.kill(pid, 0); await pause(10) } catch (error) { if (error.code !== 'ESRCH') throw error; alive = false }
    }
    assert.equal(alive, false)
  }
  assert.equal((await stats(fixtureData)).responses.INITIALIZE, 1)
})
