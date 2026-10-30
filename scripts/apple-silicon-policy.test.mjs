import assert from 'node:assert/strict'
import { test } from 'node:test'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { mkdtemp, mkdir, copyFile, writeFile, chmod, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
const exec = promisify(execFile)
const root = fileURLToPath(new URL('../', import.meta.url))
const cases = [
  ['build defaults to ARM', ['build'], ['build', '--target', 'aarch64-apple-darwin']],
  ['dev defaults to ARM', ['dev'], ['dev', '--target', 'aarch64-apple-darwin']],
  ['explicit ARM is not duplicated', ['build', '--target', 'aarch64-apple-darwin'], ['build', '--target', 'aarch64-apple-darwin']],
  ['equals Intel target rejected', ['build', '--target=x86_64-apple-darwin'], null],
  ['split Intel target rejected', ['build', '--target', 'x86_64-apple-darwin'], null],
  ['universal target rejected', ['build', '-t', 'universal-apple-darwin'], null],
  ['host signing utility retained', ['signer', 'sign', '--help'], ['signer', 'sign', '--help']],
]
for (const [name, args, expected] of cases) test(name, async () => {
  const dir = await mkdtemp(resolve(tmpdir(), 'dougle-platform-policy-'))
  try {
    const desktop = resolve(dir, 'desktop'), cli = resolve(desktop, 'node_modules/.bin/tauri')
    await mkdir(resolve(desktop, 'node_modules/.bin'), { recursive: true })
    await copyFile(resolve(root, 'desktop/tauri.sh'), resolve(desktop, 'tauri.sh'))
    await writeFile(cli, '#!/bin/bash\nprintf "%s\\n" "$@"\n')
    await chmod(cli, 0o755)
    const command = ['bash', [resolve(desktop, 'tauri.sh'), ...args]]
    if (expected) assert.deepEqual((await exec(...command)).stdout.trim().split('\n'), expected)
    else await assert.rejects(exec(...command), error => error.code === 2 && /Apple Silicon/.test(error.stderr))
  } finally { await rm(dir, { recursive: true, force: true }) }
})
test('Intel package refused before resource staging', { skip: process.platform !== 'darwin' }, async () => {
  await assert.rejects(exec('bash', [resolve(root, 'scripts/package-macos.sh'), '--stage-only'], {
    cwd: root, env: { ...process.env, FORGE_MACOS_TARGET: 'x86_64-apple-darwin' },
  }), error => error.code === 2 && /Apple Silicon/.test(error.stderr))
})
