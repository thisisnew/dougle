import { readdir, readFile, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { resolve, relative, basename } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../', import.meta.url))
const roots = ['backend/src', 'frontend/src', 'desktop/src-tauri/src', 'desktop/src-tauri/examples', 'scripts']
async function walk(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  return (await Promise.all(entries.map(entry => entry.isDirectory() ? walk(resolve(directory, entry.name)) : [resolve(directory, entry.name)]))).flat()
}
const paths = (await Promise.all(roots.map(directory => walk(resolve(root, directory))))).flat()
  .filter(path => /\.(java|ts|tsx|rs|mjs|sh|properties)$/.test(path)).sort()
const sources = await Promise.all(paths.map(async path => ({ path: relative(root, path), text: await readFile(path, 'utf8') })))
const tests = sources.filter(source => /\/test\/|\.test\.|-test\.sh$|smoke\.mjs$|\/examples\//.test(source.path))
const inventory = sources.map(source => {
  const symbol = basename(source.path).replace(/\.(java|tsx?|rs|mjs|sh|properties)$/, '')
  return { path: source.path, sha256: createHash('sha256').update(source.text).digest('hex'), lines: source.text.split('\n').length - 1,
    kind: tests.includes(source) ? 'test-or-fixture' : 'production-or-tooling',
    lexicalTestReferences: tests.filter(test => test.path !== source.path && test.text.includes(symbol)).map(test => test.path) }
})
const report = { schema: 1, scope: roots, totalFiles: inventory.length, testOrFixtureFiles: tests.length,
  limitation: 'Complete file enumeration and lexical test references; not line coverage, proof of correctness, or exhaustive manual semantic review. Rust inline tests remain in production files.', files: inventory }
await writeFile(resolve(root, 'docs/model-audit-inventory.json'), JSON.stringify(report, null, 2) + '\n')
console.log(`Indexed ${inventory.length} files, ${tests.length} test/fixture files; lexical references are not coverage.`)
