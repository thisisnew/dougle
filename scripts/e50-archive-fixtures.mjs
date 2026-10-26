import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { isAbsolute, relative, resolve, sep } from 'node:path'

export const snapshot023Hash = 'fc3fab555cc948b7452b68f4f2b7fd55f108c0ae912abdba058bd79de7318119'
export const fixtureVersion = 'e50-independent-v1'
export const catalogKey = '00000000-0000-0000-0000-000000000001'
export const catalogPath = 'documents/' + catalogKey + '.bin'
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex')

export function options(args) {
  assert.equal(args.length, 9, 'Usage: --run-approved-artifact --jar ABSOLUTE_JAR --sha256 HASH --java ABSOLUTE_JAVA --models ABSOLUTE_MODELS')
  assert.equal(args[0], '--run-approved-artifact')
  assert.equal(args[1], '--jar'); assert.equal(args[3], '--sha256'); assert.equal(args[5], '--java'); assert.equal(args[7], '--models')
  assert.match(args[4], /^[a-f0-9]{64}$/)
  assert.notEqual(args[4], '675a7c59e13edc8ee849b23977699e600331555d8f53c669ef8e7f10406e3489', 'Pre-read-only .21 artifact forbidden')
  for (const path of [args[2], args[6], args[8]]) assert(isAbsolute(path) && !/[\r\n\0]/.test(path), 'Absolute clean asset paths required')
  return { jar: args[2], hash: args[4], java: args[6], models: args[8] }
}

export function requireArtifact(bytes, hash) {
  assert.match(hash, /^[a-f0-9]{64}$/)
  assert.equal(sha256(bytes), hash, 'Explicit approved artifact hash mismatch')
}

export function ownedPath(root, ...segments) {
  assert(isAbsolute(root))
  const path = resolve(root, ...segments)
  const suffix = relative(root, path)
  assert(suffix && suffix !== '..' && !suffix.startsWith('..' + sep) && !isAbsolute(suffix), 'Path must stay inside private temporary root')
  return path
}

export function childEnvironment(home, data, temporary) {
  for (const path of [home, data, temporary]) assert(isAbsolute(path))
  return { HOME: home, FORGE_DATA_DIR: data, TMPDIR: temporary, PATH: '/usr/bin:/bin:/usr/sbin:/sbin', LANG: 'en_US.UTF-8' }
}

export function sandboxProfile(temporary, javaHome, models) {
  for (const path of [temporary, javaHome, models]) assert(isAbsolute(path) && !/[\r\n\0]/.test(path))
  const quote = path => JSON.stringify(path)
  return '(version 1) (allow default) (deny network*) '
    + '(allow network-bind (local ip "localhost:*")) (allow network-inbound (local ip "localhost:*")) '
    + '(allow network-outbound (remote ip "localhost:*")) '
    + '(deny file-read* file-write*) (allow file-read-metadata) '
    + '(allow file-read* (subpath "/System") (subpath "/usr") (subpath "/bin") (subpath "/sbin") '
    + '(subpath "/dev") (subpath "/private/etc") (subpath "/private/var/db/timezone") '
    + '(subpath ' + quote(javaHome) + ') (subpath ' + quote(models) + ') (subpath ' + quote(temporary) + ')) '
    + '(allow file-write* (subpath ' + quote(temporary) + ') (literal "/dev/null"))'
}

export function requestUrl(base, path, method) {
  const origin = new URL(base)
  assert.equal(origin.protocol, 'http:')
  assert.equal(origin.hostname, '127.0.0.1')
  assert(origin.port && !origin.username && !origin.password && !origin.search && !origin.hash && origin.pathname === '/api')
  assert(typeof path === 'string' && path.startsWith('/') && !path.includes('\\') && !path.includes('#') && !/%(?:2e|2f|5c)/i.test(path))
  const endpoint = path.split('?')[0]
  const id = '[a-f0-9-]{36}'
  const get = ['/session', '/search-model', '^/workspace-archives/export/' + id + '$',
    '^/workspaces/' + id + '/(?:chat-threads(?:/' + id + ')?|answer-jobs|generation-jobs|sources|source-polling|search)$',
    '^/workspaces/' + id + '/entries/' + id + '/(?:analysis|content)$']
  const post = ['/workspaces', '/workspace-archives/import-preview', '/workspace-archives/import-apply',
    '^/workspaces/' + id + '/(?:files|folders|chat-threads|attachments)$']
  const allowed = method === 'GET' ? get : method === 'POST' ? post : []
  assert(allowed.some(rule => rule.startsWith('^') ? new RegExp(rule).test(endpoint) : rule === endpoint), 'Endpoint outside local E50 allowlist')
  const target = new URL(base + path)
  assert.equal(target.origin, origin.origin)
  assert.equal(target.pathname, '/api' + endpoint)
  return target
}

export async function boundedPoll(probe, { timeoutMs = 120000, now = Date.now,
  sleep = milliseconds => new Promise(done => setTimeout(done, milliseconds)) } = {}) {
  const deadline = now() + timeoutMs
  while (now() < deadline) {
    const value = await probe()
    if (value) return value
    await sleep(250)
  }
  throw new Error('E50 bounded poll timed out')
}

export function semanticDetail(detail, expectedEntryId) {
  assert(Array.isArray(detail.bundles) && detail.bundles.length > 0)
  const positions = new Map(detail.bundles.map((bundle, index) => [bundle.id, index]))
  assert.equal(positions.size, detail.bundles.length)
  const source = (id, digest) => {
    assert(positions.has(id), 'Missing context source')
    const value = detail.bundles[positions.get(id)]
    if (digest !== undefined) assert.equal(value.digest, digest, 'Context digest mismatch')
    return positions.get(id)
  }
  return detail.bundles.map((bundle, index) => {
    const context = bundle.payload.chatContext
    assert.equal(context.threadId, detail.id)
    assert.equal(context.parentBundleId, index ? detail.bundles[index - 1].id : null)
    const evidence = bundle.payload.items.map(item => {
      assert.equal(item.kind, 'ORIGINAL')
      assert.equal(item.entryId, expectedEntryId)
      assert(item.sourceId === null && item.supports.length === 0)
      return { kind: item.kind, title: item.title, contentHash: item.contentHash, textHash: item.textHash,
        startLine: item.startLine, endLine: item.endLine, quote: item.quote }
    })
    const history = context.history.map(turn => {
      const position = source(turn.bundleId, turn.bundleDigest)
      assert(position < index)
      assert.equal(turn.question, detail.bundles[position].payload.question)
      return { position, question: turn.question, answer: turn.answer }
    })
    const extractive = context.extractive ? { ...context.extractive,
      questions: context.extractive.questions.map(question => {
        const position = source(question.bundleId, question.bundleDigest)
        assert(position < index)
        assert.equal(question.question, detail.bundles[position].payload.question)
        return { position, question: question.question, legacyUnclassified: question.legacyUnclassified }
      }),
      truncatedAnswerBundleIds: context.extractive.truncatedAnswerBundleIds.map(id => source(id)),
    } : null
    const answers = detail.jobs.filter(job => job.bundleId === bundle.id).map(job => {
      assert.equal(job.status, 'SUCCEEDED')
      return { provider: job.provider, requestedModel: job.requestedModel, answer: job.answer,
        createdAt: job.createdAt, updatedAt: job.updatedAt }
    }).sort((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right)))
    return { question: bundle.payload.question, createdAt: bundle.createdAt, retrievalQuery: context.retrievalQuery, evidence, history, extractive, answers }
  })
}

export function crc32(bytes) {
  let crc = 0xffffffff
  for (const byte of bytes) {
    crc ^= byte
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0)
  }
  return (crc ^ 0xffffffff) >>> 0
}

export function zip(entries) {
  const local = []
  const central = []
  let offset = 0
  for (const [path, content] of entries) {
    const name = Buffer.from(path)
    const header = Buffer.alloc(30)
    header.writeUInt32LE(0x04034b50)
    header.writeUInt16LE(20, 4)
    header.writeUInt16LE(33, 12)
    header.writeUInt32LE(crc32(content), 14)
    header.writeUInt32LE(content.length, 18)
    header.writeUInt32LE(content.length, 22)
    header.writeUInt16LE(name.length, 26)
    const directory = Buffer.alloc(46)
    directory.writeUInt32LE(0x02014b50)
    directory.writeUInt16LE(20, 4)
    header.copy(directory, 6, 4, 28)
    directory.writeUInt32LE(offset, 42)
    local.push(header, name, content)
    central.push(directory, name)
    offset += header.length + name.length + content.length
  }
  const directory = Buffer.concat(central)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50)
  end.writeUInt16LE(entries.size, 8)
  end.writeUInt16LE(entries.size, 10)
  end.writeUInt32LE(directory.length, 12)
  end.writeUInt32LE(offset, 16)
  return Buffer.concat([...local, directory, end])
}

export function unzipStored(bytes) {
  assert(Buffer.isBuffer(bytes) && bytes.length <= 9 * 1024 * 1024)
  const entries = new Map()
  let offset = 0
  while (bytes.readUInt32LE(offset) === 0x04034b50) {
    assert.equal(bytes.readUInt16LE(offset + 6) & 8, 0)
    assert.equal(bytes.readUInt16LE(offset + 8), 0, 'Backend export must use STORED ZIP entries')
    const size = bytes.readUInt32LE(offset + 18)
    const nameLength = bytes.readUInt16LE(offset + 26)
    const extraLength = bytes.readUInt16LE(offset + 28)
    const path = bytes.subarray(offset + 30, offset + 30 + nameLength).toString()
    const start = offset + 30 + nameLength + extraLength
    const content = bytes.subarray(start, start + size)
    assert.equal(content.length, size)
    assert.equal(crc32(content), bytes.readUInt32LE(offset + 14))
    assert(!entries.has(path))
    entries.set(path, content)
    offset = start + size
  }
  assert.equal(bytes.readUInt32LE(offset), 0x02014b50)
  return entries
}

export function archive(catalog, files) {
  const documents = new Map(files)
  documents.set(catalogKey, Buffer.from(JSON.stringify(catalog)))
  const manifest = Buffer.alloc(12 + documents.size * 52)
  manifest.writeUInt32BE(0x4b465441)
  manifest.writeUInt32BE(1, 4)
  manifest.writeUInt32BE(documents.size, 8)
  let offset = 12
  for (const [id, bytes] of documents) {
    Buffer.from(id.replaceAll('-', ''), 'hex').copy(manifest, offset)
    manifest.writeUInt32BE(bytes.length, offset + 16)
    Buffer.from(sha256(bytes), 'hex').copy(manifest, offset + 20)
    offset += 52
  }
  return zip(new Map([['manifest.bin', manifest], ...[...documents].map(([id, bytes]) => ['documents/' + id + '.bin', bytes])]))
}
