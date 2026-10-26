import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { zip } from './e50-archive-fixtures.mjs'
import { cleanArchivePath, staticFileEntries, verifyFrontendBundle } from './verify-frontend-bundle.mjs'

const prefix = 'BOOT-INF/classes/static/'
const script = fileURLToPath(new URL('./verify-frontend-bundle.mjs', import.meta.url))

function fixture(context, changes = entries => entries) {
  const root = mkdtempSync(join(tmpdir(), 'forge-static-guard-'))
  context.after(() => rmSync(root, { recursive: true, force: true }))
  const dist = join(root, 'frontend dist')
  const jar = join(root, 'synthetic app.jar')
  const files = new Map([
    ['index.html', Buffer.from('<html>synthetic</html>')],
    ['assets/app.js', Buffer.from('synthetic js')],
    ['public assets/guide with spaces.txt', Buffer.from('synthetic public asset')],
    ['public assets/literal[1]*?.bin', Buffer.from([0, 255, 10, 13])],
    ['empty.txt', Buffer.alloc(0)],
  ])
  for (const [name, bytes] of files) {
    mkdirSync(dirname(join(dist, name)), { recursive: true })
    writeFileSync(join(dist, name), bytes)
  }
  const entries = changes([...files].map(([name, bytes]) => [prefix + name, bytes]))
  entries.push(['BOOT-INF/classes/application.properties', Buffer.from('synthetic=true')])
  entries.push([prefix, Buffer.alloc(0)])
  entries.size = entries.length
  writeFileSync(jar, zip(entries))
  return { root, dist, jar, files }
}

test('exact paths and binary bytes pass, including spaces, literal glob characters and empty files', context => {
  const { jar, dist, files } = fixture(context)
  assert.deepEqual(verifyFrontendBundle(jar, dist), { files: files.size, bytes: [...files.values()].reduce((sum, bytes) => sum + bytes.length, 0) })
})

test('stale incremental JS is rejected', context => {
  const { jar, dist } = fixture(context, entries => [...entries, [prefix + 'assets/old.js', Buffer.from('old')]])
  assert.throws(() => verifyFrontendBundle(jar, dist), /extra.*assets\/old.js/)
})

test('missing packaged public asset is rejected', context => {
  const { jar, dist } = fixture(context, entries => entries.filter(([name]) => !name.endsWith('guide with spaces.txt')))
  assert.throws(() => verifyFrontendBundle(jar, dist), /missing.*guide with spaces.txt/)
})

test('equal length but different bytes are rejected', context => {
  const { jar, dist } = fixture(context, entries => entries.map(([name, bytes]) => [name, name.endsWith('app.js') ? Buffer.from('synthetic xx') : bytes]))
  assert.throws(() => verifyFrontendBundle(jar, dist), /Static bytes differ/)
})

test('duplicate ZIP entries are rejected even when their bytes match', context => {
  const { jar, dist } = fixture(context, entries => [...entries, entries[0]])
  assert.throws(() => verifyFrontendBundle(jar, dist), /Duplicate static entry/)
})

test('duplicate static directories are rejected', () => {
  assert.throws(() => staticFileEntries([prefix, prefix]), /Duplicate static entry/)
})

test('path traversal and ambiguous archive paths fail closed', () => {
  for (const name of ['../escape', '/absolute', 'C:/drive', 'a/../b', 'a/./b', 'a//b', 'a\\b', 'a\nb', 'a\rb', 'a\0b', '']) {
    assert.throws(() => cleanArchivePath(name), /Unsafe archive path/)
  }
  assert.throws(() => staticFileEntries([prefix + '../escape']), /Unsafe archive path/)
  assert.throws(() => staticFileEntries(['BOOT-INF/classes/static']), /not a directory/)
  assert.equal(cleanArchivePath('public assets/한글 안내.txt'), 'public assets/한글 안내.txt')
})

test('real traversal ZIP is rejected without extracting anything', context => {
  const { jar, dist, root } = fixture(context, entries => [...entries, [prefix + '../../escape.txt', Buffer.from('no')]])
  assert.throws(() => verifyFrontendBundle(jar, dist), /Unsafe archive path/)
  assert.throws(() => readFileSync(join(root, 'escape.txt')), /ENOENT/)
})

test('dist symlinks and symlinked JARs are rejected', context => {
  const { jar, dist, root } = fixture(context)
  const link = join(root, 'linked.jar')
  symlinkSync(jar, link)
  assert.throws(() => verifyFrontendBundle(link, dist), /regular file/)
  symlinkSync(join(dist, 'index.html'), join(dist, 'alias.html'))
  assert.throws(() => verifyFrontendBundle(jar, dist), /Non-regular dist entry/)
})

test('missing index and corrupt archives fail closed', context => {
  const { jar, dist } = fixture(context)
  writeFileSync(jar, 'not a zip')
  assert.throws(() => verifyFrontendBundle(jar, dist), /Unable to read JAR/)
  rmSync(join(dist, 'index.html'))
  assert.throws(() => verifyFrontendBundle(jar, dist), /index.html is required/)
})

test('CLI returns nonzero on failure and zero only on exact match', context => {
  const { jar, dist } = fixture(context)
  const valid = spawnSync(process.execPath, [script, jar, dist], { encoding: 'utf8' })
  assert.equal(valid.status, 0, valid.stderr)
  assert.match(valid.stdout, /PASS: packaged frontend exactly matches dist/)
  writeFileSync(join(dist, 'index.html'), 'changed')
  const invalid = spawnSync(process.execPath, [script, jar, dist], { encoding: 'utf8' })
  assert.equal(invalid.status, 1)
  assert.match(invalid.stderr, /Static bytes differ/)
  assert.equal(spawnSync(process.execPath, [script], { encoding: 'utf8' }).status, 1)
})

test('packaging verifies frontend before staging in every mode', () => {
  const source = readFileSync(new URL('./package-macos.sh', import.meta.url), 'utf8')
  const guard = source.indexOf('node "$repo_root/scripts/verify-frontend-bundle.mjs" "$jar" "$repo_root/frontend/dist"')
  assert.ok(guard > source.indexOf('\nfi\njar='))
  assert.ok(guard < source.indexOf('mkdir -p "$resources"'))
  assert.ok(guard < source.indexOf('staging="$(mktemp'))
})
