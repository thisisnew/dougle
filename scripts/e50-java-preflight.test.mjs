import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { versionProbeDiff } from './e50-java-preflight.mjs'

test('verified candidate grants read-data of root directory only, not its descendants', () => {
  assert.equal(versionProbeDiff('--system-root-directory'), '(allow file-read-data (literal "/"))')
  for (const option of ['/', '--allow-users', '--apple-private-frameworks', '--system-loader-directories', '__proto__']) {
    assert.throws(() => versionProbeDiff(option))
  }
})

test('version-only probe retains private environment, bounded execution and unchanged network profile', async () => {
  const source = await readFile(new URL('./e50-java-preflight.mjs', import.meta.url), 'utf8')
  assert(source.includes("'-version'"))
  assert(!source.includes("'-jar'"))
  assert(source.includes('sandboxProfile(directory, dirname(dirname(java)), directory)'))
  assert(source.includes('env: childEnvironment(home, directory, scratch)'))
  assert(source.includes("timeout: 10000, killSignal: 'SIGKILL', maxBuffer: 65536"))
  assert(source.includes('await rm(root, { recursive: true, force: true })'))
  assert(!source.includes('(subpath "/")'))
  assert(!source.includes('(subpath "/Users")'))
  assert(!source.includes('(subpath "/private/var")'))
})
