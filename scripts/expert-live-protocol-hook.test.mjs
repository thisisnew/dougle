import assert from 'node:assert/strict'
import test from 'node:test'
import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { chmod, mkdir, mkdtemp, readFile, readdir, rm, stat, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { prepareProtocolHook, collectProtocolCounters } from './expert-live-protocol-hook.mjs'

const digest = content => createHash('sha256').update(content).digest('hex')
const jarSha256 = 'a'.repeat(64)
const wire = [
  { id: 1, result: {} }, { id: 2, result: {} },
  { id: 3, result: { thread: { id: 'private-thread' } } }, { id: 4, result: { turn: { id: 'private-turn' } } },
  { method: 'item/agentMessage/delta', params: { threadId: 'private-thread', turnId: 'private-turn', delta: 'private-payload' } },
].map(value => JSON.stringify(value) + '\n').join('')

async function fixture(context) {
  const directory = await mkdtemp(join(tmpdir(), "forge-counter-hook-'"))
  context.after(() => rm(directory, { recursive: true, force: true }))
  const executable = join(directory, "bundled cli's fake")
  const program = `#!${process.execPath}\nlet input='';process.stdin.setEncoding('utf8');process.stdin.on('data',value=>input+=value);process.stdin.on('end',()=>{const args=process.argv.slice(2);process.stdout.write(args.includes('app-server')?${JSON.stringify(wire)}:JSON.stringify({args,input}));process.stderr.write('synthetic-stderr');process.exitCode=args.includes('fail')?23:0;});\n`
  await writeFile(executable, program, { mode: 0o700 })
  const profile = join(directory, 'empty-profile'); await mkdir(profile, { mode: 0o700 })
  return { executable, ownedDirectory: directory, maximumDispatches: 4, jarSha256,
    environment: { FORGE_EXPERT_PROTOCOL_COUNTER: 'approved', FORGE_EXPERT_CODEX_SHA256: digest(program),
      FORGE_EXPERT_JAR_SHA256: jarSha256, FORGE_EXPERT_READONLY_ARTIFACT_SHA256: jarSha256 }, profile }
}

function invoke(command, args, profile) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { env: { PATH: '/usr/bin:/bin', CODEX_HOME: profile }, stdio: ['pipe', 'pipe', 'pipe'] })
    let stdout = '', stderr = ''
    const deadline = setTimeout(() => { child.kill('SIGKILL'); reject(Error('Fake wrapper timed out')) }, 10000)
    child.stdout.on('data', chunk => { stdout += chunk }); child.stderr.on('data', chunk => { stderr += chunk })
    child.on('error', error => { clearTimeout(deadline); reject(error) })
    child.on('close', code => { clearTimeout(deadline); resolve({ code, stdout, stderr }) })
    child.stdin.end('synthetic-stdin\n')
  })
}

test('unset opt-in leaves defaults untouched without filesystem access', async () => {
  assert.equal(await prepareProtocolHook({ environment: {}, executable: '/not-present', ownedDirectory: '/not-present' }), null)
  assert.equal(await collectProtocolCounters(null), null)
})

for (const change of [
  { maximumDispatches: 5 }, { maximumDispatches: 0 },
  { environment: { FORGE_EXPERT_PROTOCOL_COUNTER: 'true' } },
  { environment: { FORGE_EXPERT_CODEX_SHA256: '0'.repeat(64) } },
  { environment: { FORGE_EXPERT_JAR_SHA256: 'b'.repeat(64) } },
  { environment: { FORGE_EXPERT_READONLY_ARTIFACT_SHA256: undefined } },
]) test(`rejects invalid diagnostic authorization/pins ${JSON.stringify(change)}`, async context => {
  const options = await fixture(context)
  await assert.rejects(prepareProtocolHook({ ...options, ...change, environment: { ...options.environment, ...change.environment } }))
  assert.ok(!(await readdir(options.ownedDirectory)).includes('protocol-counter'))
})

test('rejects symlink executable and requires private owned directory', async context => {
  const options = await fixture(context)
  const linked = join(options.ownedDirectory, 'linked'); await symlink(options.executable, linked)
  await assert.rejects(prepareProtocolHook({ ...options, executable: linked }))
  await chmod(options.ownedDirectory, 0o755)
  await assert.rejects(prepareProtocolHook(options))
})

test('private launcher forwards exact argv stdin stdout stderr and exit status without non-app stats', async context => {
  const options = await fixture(context)
  const hook = await prepareProtocolHook(options)
  assert.equal((await stat(hook.command)).mode & 0o777, 0o700)
  assert.equal((await stat(hook.statsDirectory)).mode & 0o777, 0o700)
  assert.equal((await stat(join(options.ownedDirectory, 'protocol-counter/pin.json'))).mode & 0o777, 0o600)
  for (const args of [['--version'], ['--config', 'cli_auth_credentials_store="file"', 'login', 'status'],
    ['--no-daemon', 'exec', '--config', 'literal="a b"', 'fail']]) {
    const result = await invoke(hook.command, args, options.profile)
    assert.equal(result.code, args.includes('fail') ? 23 : 0)
    assert.equal(result.stderr, 'synthetic-stderr')
    assert.deepEqual(JSON.parse(result.stdout), { args, input: 'synthetic-stdin\n' })
  }
  assert.deepEqual(await readdir(hook.statsDirectory), [])
  assert.equal((await collectProtocolCounters(hook)).appServerProcesses, 0)
})

test('collects only bounded numeric app-server counters, with unordered/timing caveats', async context => {
  const options = await fixture(context)
  const hook = await prepareProtocolHook(options)
  const result = await invoke(hook.command, ['--no-daemon', 'app-server', '--stdio', '--strict-config'], options.profile)
  assert.equal(result.code, 0); assert.equal(result.stdout, wire); assert.equal(result.stderr, 'synthetic-stderr')
  const collected = await collectProtocolCounters(hook)
  assert.equal(collected.appServerProcesses, 1)
  assert.equal(collected.counters[0].applicationBinding.AGENT_DELTA.MATCH, 1)
  assert.doesNotMatch(JSON.stringify(collected), /private-payload|private-thread|private-turn|synthetic-stdin|synthetic-stderr/)
  assert.match(collected.ordering, /Unordered/); assert.match(collected.timing, /overhead/)
  const [name] = await readdir(hook.statsDirectory)
  const path = join(hook.statsDirectory, name)
  assert.equal((await stat(path)).mode & 0o777, 0o600)
  const bad = JSON.parse(await readFile(path, 'utf8')); bad.raw = 'private-payload'
  await writeFile(path, JSON.stringify(bad))
  await assert.rejects(collectProtocolCounters(hook))
})

test('pin is rechecked by wrapper before fake CLI starts after mutation', async context => {
  const options = await fixture(context)
  const hook = await prepareProtocolHook(options)
  await writeFile(options.executable, '#!/bin/sh\necho MUTATED\n')
  const result = await invoke(hook.command, ['--version'], options.profile)
  assert.equal(result.code, 70); assert.equal(result.stdout, '')
  assert.match(result.stderr, /PIN_MISMATCH/)
})
