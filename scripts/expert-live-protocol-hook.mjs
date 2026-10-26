import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { lstat, mkdir, readFile, readdir, realpath, writeFile } from 'node:fs/promises'
import { isAbsolute, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { ProtocolCounters } from './codex-protocol-counter.mjs'

const helper = fileURLToPath(new URL('./codex-protocol-counter.mjs', import.meta.url))
const quote = value => "'" + value.replaceAll("'", "'\\''") + "'"
const hash = bytes => createHash('sha256').update(bytes).digest('hex')

export async function prepareProtocolHook({ environment, executable, ownedDirectory, maximumDispatches, jarSha256 }) {
  const enabled = environment.FORGE_EXPERT_PROTOCOL_COUNTER
  if (enabled === undefined) return null
  assert.equal(enabled, 'approved', 'Protocol counter requires explicit opt-in')
  assert.ok(Number.isSafeInteger(maximumDispatches) && maximumDispatches > 0 && maximumDispatches <= 4, 'Diagnostic batch limited to four dispatches')
  assert.match(environment.FORGE_EXPERT_CODEX_SHA256 || '', /^[a-f0-9]{64}$/, 'Exact bundled CLI pin required')
  assert.match(environment.FORGE_EXPERT_JAR_SHA256 || '', /^[a-f0-9]{64}$/, 'Exact JAR pin required')
  assert.equal(jarSha256, environment.FORGE_EXPERT_JAR_SHA256, 'Diagnostic JAR pin mismatch')
  assert.equal(environment.FORGE_EXPERT_READONLY_ARTIFACT_SHA256, jarSha256, 'Read-only artifact approval required')
  assert.ok(isAbsolute(executable), 'Bundled executable must be absolute')
  const binary = await lstat(executable)
  assert.ok(binary.isFile() && !binary.isSymbolicLink() && (binary.mode & 0o111) !== 0 && (binary.mode & 0o022) === 0, 'Unsafe bundled executable')
  assert.equal(hash(await readFile(executable)), environment.FORGE_EXPERT_CODEX_SHA256, 'Bundled CLI pin mismatch')
  const owned = await lstat(ownedDirectory)
  assert.ok(owned.isDirectory() && !owned.isSymbolicLink() && owned.uid === process.getuid() && (owned.mode & 0o077) === 0, 'Private owned directory required')
  const directory = join(await realpath(ownedDirectory), 'protocol-counter')
  await mkdir(directory, { mode: 0o700 })
  const statsDirectory = join(directory, 'stats')
  await mkdir(statsDirectory, { mode: 0o700 })
  const manifest = join(directory, 'pin.json')
  await writeFile(manifest, JSON.stringify({ version: 1, executable, sha256: environment.FORGE_EXPERT_CODEX_SHA256, statsDirectory }), { flag: 'wx', mode: 0o600 })
  const command = join(directory, 'codex-counter')
  await writeFile(command, `#!/bin/sh\nexec ${quote(process.execPath)} ${quote(helper)} --pin-file ${quote(manifest)} -- "$@"\n`, { flag: 'wx', mode: 0o700 })
  return { command, statsDirectory, metadata: { version: 1, cliSha256: environment.FORGE_EXPERT_CODEX_SHA256,
    helperSha256: hash(await readFile(helper)), hookSha256: hash(await readFile(fileURLToPath(import.meta.url))),
    scope: 'Opt-in byte-preserving wrapper; numeric protocol counters only; not provider diagnosis or acceptance evidence.',
    ordering: 'Unordered per-process counters; random filenames do not establish dispatch or answer order.',
    timing: 'Wrapper forwarding and counter persistence add overhead and may affect timing; not an uninstrumented baseline.' } }
}

function validateCounters(value, template) {
  if (typeof template === 'number') {
    assert.ok(Number.isSafeInteger(value) && value >= 0 && value <= 1048576, 'Invalid protocol counter')
    return
  }
  assert.ok(value && typeof value === 'object' && !Array.isArray(value), 'Invalid protocol stats')
  assert.deepEqual(Object.keys(value).sort(), Object.keys(template).sort(), 'Unexpected protocol stats fields')
  for (const key of Object.keys(template)) validateCounters(value[key], template[key])
}

export async function collectProtocolCounters(hook) {
  if (!hook) return null
  const names = await readdir(hook.statsDirectory)
  assert.ok(names.length <= 64, 'Too many protocol counter files')
  const counters = []
  for (const name of names.sort()) {
    assert.match(name, /^codex-app-server-[0-9a-f-]{36}\.json$/, 'Unexpected protocol counter file')
    const path = join(hook.statsDirectory, name)
    const info = await lstat(path)
    assert.ok(info.isFile() && !info.isSymbolicLink() && info.nlink === 1 && info.uid === process.getuid()
      && (info.mode & 0o077) === 0 && info.size <= 65536, 'Unsafe protocol stats file')
    const value = JSON.parse(await readFile(path, 'utf8'))
    validateCounters(value, new ProtocolCounters().snapshot())
    assert.equal(value.version, 1, 'Unknown counter version')
    counters.push(value)
  }
  return { ...hook.metadata, appServerProcesses: counters.length, counters }
}
