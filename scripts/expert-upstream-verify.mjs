import { createHash } from 'node:crypto'
import { lstat, readFile, realpath } from 'node:fs/promises'
import { isAbsolute, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const defaultRoot = fileURLToPath(new URL('../', import.meta.url))
const directory = 'third-party/expert-sources'
const manifestPath = `${directory}/manifest.json`
const text = value => typeof value === 'string' && value.trim().length > 0
const sha = (value, length) => typeof value === 'string' && new RegExp(`^[a-f0-9]{${length}}$`).test(value)
function check(condition, message) {
  if (!condition) throw new Error(message)
}

function canonicalPath(value) {
  check(text(value) && !value.includes('\\') && !/[\x00-\x1f\x7f]/.test(value) && !isAbsolute(value)
    && value.split('/').every(part => part && part !== '.' && part !== '..') && !value.includes(':'), `Invalid relative path: ${value}`)
  return value
}

async function containedFile(root, path) {
  canonicalPath(path)
  check(path.startsWith(`${directory}/`), `Path outside ${directory}: ${path}`)
  let current = root
  const parts = path.split('/')
  for (const [index, part] of parts.entries()) {
    current = resolve(current, part)
    const stat = await lstat(current)
    check(!stat.isSymbolicLink(), `Symlinks are not permitted: ${path}`)
    check(index === parts.length - 1 ? stat.isFile() : stat.isDirectory(), `Expected regular file/directory: ${path}`)
  }
  const actual = await realpath(current)
  const within = relative(resolve(root, directory), actual)
  check(within && within !== '..' && !within.startsWith(`..${sep}`) && !isAbsolute(within), `Resolved path escapes source directory: ${path}`)
  return actual
}

function repositoryName(repository) {
  check(typeof repository === 'string' && /^https:\/\/github\.com\/[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository), 'Invalid GitHub repository URL')
  const name = repository.slice('https://github.com/'.length)
  check(name.split('/').every(part => part !== '.' && part !== '..'), 'Invalid repository name')
  return name
}

export async function verifyUpstream(repoRoot = defaultRoot) {
  const root = await realpath(repoRoot)
  const manifest = JSON.parse(await readFile(await containedFile(root, manifestPath), 'utf8'))
  check(manifest?.schema_version === 1, 'Unsupported manifest schema_version')
  check(Array.isArray(manifest.sources) && manifest.sources.length > 0, 'Manifest requires nonempty sources')
  const paths = new Set([manifestPath.toLowerCase()])
  const repositories = new Set()
  let filesVerified = 0
  for (const source of manifest.sources) {
    const name = repositoryName(source?.repository)
    check(!repositories.has(name.toLowerCase()), `Duplicate repository: ${name}`)
    repositories.add(name.toLowerCase())
    check(sha(source.commit, 40), `Invalid full commit SHA: ${name}`)
    check(source.commit_url === `${source.repository}/commit/${source.commit}`, `Commit URL pin mismatch: ${name}`)
    check(text(source.license), `Missing license: ${name}`)
    check(Array.isArray(source.files) && source.files.length > 0, `Missing license files: ${name}`)
    const upstreamPaths = new Set()
    for (const file of source.files) {
      check(file && typeof file === 'object', `Invalid file entry: ${name}`)
      canonicalPath(file.upstream_path)
      canonicalPath(file.local_path)
      const key = file.local_path.toLowerCase()
      check(!paths.has(key), `Duplicate local path: ${file.local_path}`)
      paths.add(key)
      check(!upstreamPaths.has(file.upstream_path), `Duplicate upstream path: ${file.upstream_path}`)
      upstreamPaths.add(file.upstream_path)
      check(sha(file.sha256, 64), `Invalid SHA-256: ${file.local_path}`)
      check(file.source_url === `https://raw.githubusercontent.com/${name}/${source.commit}/${file.upstream_path}`, `Source URL pin mismatch: ${file.local_path}`)
      const bytes = await readFile(await containedFile(root, file.local_path))
      check(createHash('sha256').update(bytes).digest('hex') === file.sha256, `SHA-256 mismatch: ${file.local_path}`)
      filesVerified += 1
    }
  }
  if (manifest.dependency_inspection !== undefined) {
    repositoryName(manifest.dependency_inspection?.repository)
    check(sha(manifest.dependency_inspection.tag_commit, 40), 'Invalid dependency inspection commit SHA')
    check(text(manifest.dependency_inspection.version), 'Missing dependency inspection version')
  }
  return {
    status: 'PASS', sourcesVerified: repositories.size, filesVerified, offline: true,
    scope: 'Local file hashes, full commit pin syntax, URL consistency, and path containment only. No downloads or upstream execution. Does not authenticate upstream provenance, Git commit inclusion, dependency licensing, or runtime adoption.',
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  Promise.resolve().then(() => {
    check(process.argv.length === 2, 'Usage: node scripts/expert-upstream-verify.mjs')
    return verifyUpstream()
  }).then(result => console.log(JSON.stringify(result, null, 2))).catch(error => {
    console.error(error.message)
    process.exitCode = 1
  })
}
