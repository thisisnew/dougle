import { execFileSync } from 'node:child_process'
import { lstatSync, readdirSync, readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const staticPrefix = 'BOOT-INF/classes/static/'

export function cleanArchivePath(name) {
  if (typeof name !== 'string' || !name || /[\\\x00-\x1f\x7f]/.test(name)
      || name.startsWith('/') || /^[A-Za-z]:/.test(name)) throw new Error('Unsafe archive path: ' + JSON.stringify(name))
  const parts = (name.endsWith('/') ? name.slice(0, -1) : name).split('/')
  if (parts.some(part => !part || part === '.' || part === '..')) throw new Error('Unsafe archive path: ' + JSON.stringify(name))
  return name
}

export function staticFileEntries(names) {
  const seen = new Set()
  const files = new Map()
  for (const name of names) {
    cleanArchivePath(name)
    if (name === staticPrefix.slice(0, -1)) throw new Error('Static root is not a directory')
    if (!name.startsWith(staticPrefix)) continue
    if (seen.has(name)) throw new Error('Duplicate static entry: ' + name)
    seen.add(name)
    if (!name.endsWith('/')) files.set(name.slice(staticPrefix.length), name)
  }
  return files
}

function distFiles(directory) {
  if (!lstatSync(directory).isDirectory()) throw new Error('dist must be a regular directory')
  const files = new Map()
  function visit(parent, prefix) {
    for (const name of readdirSync(parent).sort()) {
      const relative = prefix + name
      cleanArchivePath(relative)
      const path = join(parent, name)
      const stat = lstatSync(path)
      if (stat.isDirectory()) visit(path, relative + '/')
      else if (stat.isFile()) files.set(relative, readFileSync(path))
      else throw new Error('Non-regular dist entry: ' + relative)
    }
  }
  visit(directory, '')
  if (!files.has('index.html')) throw new Error('dist/index.html is required')
  return files
}

function archiveIdentity(path) {
  const stat = lstatSync(path, { bigint: true })
  if (!stat.isFile()) throw new Error('JAR must be a regular file')
  return [stat.dev, stat.ino, stat.size, stat.mtimeNs, stat.ctimeNs].join(':')
}

function unzip(args, maxBuffer) {
  try {
    return execFileSync('unzip', args, { maxBuffer, timeout: 30000, stdio: ['ignore', 'pipe', 'pipe'] })
  } catch {
    throw new Error('Unable to read JAR with unzip; archive may be invalid or changed')
  }
}

export function verifyFrontendBundle(jarPath, distPath) {
  const jar = resolve(jarPath)
  const directory = resolve(distPath)
  const identity = archiveIdentity(jar)
  const expected = distFiles(directory)
  const listing = unzip(['-Z1', jar], 32 * 1024 * 1024).toString('utf8')
  const names = listing.endsWith('\n') ? listing.slice(0, -1).split('\n') : listing.split('\n')
  const packaged = staticFileEntries(names)
  const missing = [...expected.keys()].filter(name => !packaged.has(name))
  const extra = [...packaged.keys()].filter(name => !expected.has(name))
  if (missing.length || extra.length) throw new Error('Static file set mismatch: ' + JSON.stringify({ missing, extra }))
  let bytes = 0
  for (const [name, content] of expected) {
    const literal = packaged.get(name).replace(/[*?\[\]]/g, '\\$&')
    const actual = unzip(['-p', jar, literal], Math.max(content.length + 1, 1024 * 1024))
    if (!actual.equals(content)) throw new Error('Static bytes differ: ' + JSON.stringify(name))
    bytes += content.length
  }
  if (archiveIdentity(jar) !== identity) throw new Error('JAR changed during verification')
  const current = distFiles(directory)
  if (current.size !== expected.size || [...expected].some(([name, content]) => !current.get(name)?.equals(content))) {
    throw new Error('dist changed during verification')
  }
  return { files: expected.size, bytes }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    if (process.argv.length !== 4) throw new Error('Usage: node scripts/verify-frontend-bundle.mjs JAR FRONTEND_DIST')
    const result = verifyFrontendBundle(process.argv[2], process.argv[3])
    console.log(`PASS: packaged frontend exactly matches dist (${result.files} files, ${result.bytes} bytes)`)
  } catch (error) {
    console.error('Frontend bundle verification failed: ' + error.message)
    process.exitCode = 1
  }
}
