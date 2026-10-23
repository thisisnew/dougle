import { readFile, writeFile } from 'node:fs/promises'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { createHash } from 'node:crypto'
import { resolve, basename } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../', import.meta.url))
const roots = ['backend', 'frontend', 'desktop', 'scripts']
const { stdout } = await promisify(execFile)('git', ['ls-files', '--cached', '--others', '--exclude-standard', '-z'], { cwd: root })
const paths = [...new Set(stdout.split('\0').filter(path => roots.some(directory => path.startsWith(directory + '/'))
  && /\.(java|ts|tsx|rs|mjs|sh|properties|json|toml|xml|html|css)$/.test(path)))].sort()
const sources = await Promise.all(paths.map(async path => ({ path, text: await readFile(resolve(root, path), 'utf8') })))
const tests = sources.filter(source => /\/test\/|\.test\.|-test\.sh$|(?:smoke|e2e)\.mjs$|\/examples\//.test(source.path))
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
