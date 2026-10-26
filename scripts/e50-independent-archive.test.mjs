import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { diagnostics, safeError, startupClassifier } from './e50-diagnostics.mjs'
import { safeNativeLines } from './e50-java-preflight.mjs'
import { archive, boundedPoll, catalogPath, childEnvironment, options, ownedPath, requestUrl, requireArtifact,
  sandboxProfile, semanticDetail, sha256, snapshot023Hash, unzipStored } from './e50-archive-fixtures.mjs'

const args = ['--run-approved-artifact', '--jar', '/synthetic/frozen.jar', '--sha256', snapshot023Hash,
  '--java', '/synthetic/runtime/bin/java', '--models', '/synthetic/models']
const id = '11111111-1111-4111-8111-111111111111'
const base = 'http://127.0.0.1:12345/api'

test('explicit immutable artifact options have no backend target or installed data defaults', () => {
  assert.deepEqual(options(args), { jar: args[2], hash: snapshot023Hash, java: args[6], models: args[8] })
  const future = [...args]; future[4] = 'a'.repeat(64)
  assert.equal(options(future).hash, 'a'.repeat(64))
  for (const invalid of [[], args.slice(1), [...args, '--data', '/users/private'], args.slice(0, -2),
    args.map(value => value === snapshot023Hash ? 'fc3fab' : value),
    args.map(value => value === args[2] ? 'relative.jar' : value),
    args.map(value => value === args[8] ? '/synthetic/\nmodels' : value),
    args.map(value => value === snapshot023Hash ? '675a7c59e13edc8ee849b23977699e600331555d8f53c669ef8e7f10406e3489' : value)]) {
    assert.throws(() => options(invalid))
  }
})

test('artifact mismatch is fatal and no default hash is inferred', () => {
  const bytes = Buffer.from('synthetic non-executable artifact')
  requireArtifact(bytes, sha256(bytes))
  assert.throws(() => requireArtifact(bytes, snapshot023Hash))
  assert.throws(() => requireArtifact(bytes))
})

test('independent A/B data roots cannot resolve to each other or an external DB', () => {
  const first = ownedPath('/private/tmp/synthetic', 'A', 'data')
  const second = ownedPath('/private/tmp/synthetic', 'B', 'data')
  assert.notEqual(first, second)
  for (const path of ['..', '../private.sqlite', '/Users/person/Library/database', '.']) {
    assert.throws(() => ownedPath('/private/tmp/synthetic/A', path))
  }
})

test('child environment omits ambient credentials, JVM flags and real home', () => {
  assert.deepEqual(childEnvironment('/synthetic/home', '/synthetic/data', '/synthetic/tmp'), {
    HOME: '/synthetic/home', FORGE_DATA_DIR: '/synthetic/data', TMPDIR: '/synthetic/tmp',
    PATH: '/usr/bin:/bin:/usr/sbin:/sbin', LANG: 'en_US.UTF-8',
  })
  assert.throws(() => childEnvironment('relative', '/synthetic/data', '/synthetic/tmp'))
})

test('sandbox restricts network to loopback and file contents/writes to approved roots', () => {
  const profile = sandboxProfile('/private/tmp/synthetic/A', '/synthetic/java', '/synthetic/models')
  assert(profile.includes('(deny network*)'))
  assert(profile.includes('(remote ip "localhost:*")'))
  assert(profile.includes('(deny file-read* file-write*)'))
  assert(profile.includes('(allow file-write* (subpath "/private/tmp/synthetic/A") (literal "/dev/null"))'))
  assert(!profile.includes('/private/tmp/synthetic/B'))
  assert(!profile.includes('(subpath "/Users")'))
  assert(!profile.includes('(subpath "/Applications")'))
  assert(sandboxProfile('/private/tmp/synthetic/"quoted', '/synthetic/java', '/synthetic/models').includes('\\"quoted'))
  assert.throws(() => sandboxProfile('/private/tmp/synthetic\n(allow network*)', '/synthetic/java', '/synthetic/models'))
})

test('HTTP allowlist permits only synthetic local archive, chat and index operations', () => {
  for (const [path, method] of [['/session', 'GET'], ['/workspace-archives/export/' + id, 'GET'],
    ['/workspace-archives/import-preview?workspaceName=synthetic', 'POST'], ['/workspace-archives/import-apply', 'POST'],
    ['/workspaces/' + id + '/chat-threads', 'POST'], ['/workspaces/' + id + '/attachments', 'POST'],
    ['/workspaces/' + id + '/search?query=synthetic', 'GET']]) {
    assert.equal(requestUrl(base, path, method).origin, 'http://127.0.0.1:12345')
  }
})

test('HTTP allowlist rejects AI, Slack, operational tools, model download, redirects and encoded path escapes', () => {
  for (const path of ['/slack/connection', '/slack/workspaces/' + id + '/replies/preview',
    '/workspaces/' + id + '/answer-jobs', '/workspaces/' + id + '/answer-approval',
    '/workspaces/' + id + '/mysql-investigations/execute', '/search-model/prepare',
    '//external.invalid', '/workspaces/' + id + '/chat-threads/../answer-jobs',
    '/workspaces/' + id + '/chat-threads%2f..', '/session#fragment', 'https://external.invalid/']) {
    assert.throws(() => requestUrl(base, path, 'POST'))
  }
  for (const invalid of ['https://127.0.0.1:12345/api', 'http://localhost:12345/api',
    'http://external.invalid:12345/api', 'http://secret@127.0.0.1:12345/api', 'http://127.0.0.1:12345/other']) {
    assert.throws(() => requestUrl(invalid, '/session', 'GET'))
  }
  assert.throws(() => requestUrl(base, '/workspaces/' + id, 'DELETE'))
})

test('polling is bounded and fails immediately on model errors without sleeping', async () => {
  let time = 0, attempts = 0
  const timing = { timeoutMs: 500, now: () => time, sleep: async () => { time += 250 } }
  assert.equal(await boundedPoll(async () => ++attempts === 2, timing), true)
  time = 0
  await assert.rejects(boundedPoll(async () => false, timing), /timed out/)
  await assert.rejects(boundedPoll(async () => { throw new Error('model failed') }, timing), /model failed/)
})

test('archive fixtures preserve blank model and reject byte corruption without touching a DB', () => {
  const catalog = { version: 1, workspaceName: 'Synthetic', materials: [], threads: [
    { turns: [{ answers: [{ provider: 'CODEX', model: '', text: 'Synthetic' }] }] },
  ] }
  const bytes = archive(catalog, new Map())
  assert.deepEqual(JSON.parse(unzipStored(bytes).get(catalogPath)), catalog)
  const damaged = Buffer.from(bytes)
  damaged[damaged.indexOf(Buffer.from('Synthetic'))] ^= 1
  assert.throws(() => unzipStored(damaged))
  assert.throws(() => unzipStored(bytes.subarray(0, 20)))
})

function history() {
  const root = { id: 'root', digest: 'root-digest', createdAt: '2026-01-01T00:00:00Z', payload: {
    question: 'Synthetic root', items: [], chatContext: { threadId: 'thread', parentBundleId: null, retrievalQuery: 'root', history: [] },
  } }
  const next = { id: 'next', digest: 'next-digest', createdAt: '2026-01-01T00:00:01Z', payload: {
    question: 'Synthetic next', items: [], chatContext: { threadId: 'thread', parentBundleId: 'root', retrievalQuery: 'next',
      history: [{ bundleId: 'root', bundleDigest: 'root-digest', question: 'Synthetic root', answer: 'Synthetic answer' }],
      extractive: { questions: [{ bundleId: 'root', bundleDigest: 'root-digest', question: 'Synthetic root', legacyUnclassified: true }], truncatedAnswerBundleIds: [] },
    },
  } }
  return { id: 'thread', bundles: [root, next], jobs: [{ bundleId: 'root', provider: 'CODEX', requestedModel: '',
    status: 'SUCCEEDED', answer: 'Synthetic answer', createdAt: root.createdAt, updatedAt: root.createdAt }] }
}

test('comparison preserves default-model answers and validates parent/context bindings before normalization', () => {
  const detail = history(), normalized = semanticDetail(detail, 'entry')
  assert.equal(normalized[0].answers[0].requestedModel, '')
  assert.equal(normalized[1].extractive.questions[0].position, 0)
  for (const mutate of [
    value => { value.bundles[1].payload.chatContext.parentBundleId = 'missing' },
    value => { value.bundles[1].payload.chatContext.history[0].bundleDigest = 'forged' },
    value => { value.bundles[1].payload.chatContext.extractive.questions[0].bundleDigest = 'forged' },
    value => { value.bundles[1].payload.chatContext.extractive.questions[0].bundleId = 'next' },
    value => { value.jobs[0].status = 'RUNNING' },
  ]) {
    const changed = structuredClone(detail); mutate(changed)
    assert.throws(() => semanticDetail(changed, 'entry'))
  }
  const changed = structuredClone(detail); changed.jobs[0].requestedModel = 'invented-model'
  assert.notDeepEqual(semanticDetail(changed, 'entry'), normalized)
})

test('runtime wiring has bounded cleanup, no implicit target JAR, and no unsandboxed launch path', async () => {
  const source = await readFile(new URL('./e50-independent-archive.mjs', import.meta.url), 'utf8')
  assert(source.includes("spawn('/usr/bin/sandbox-exec'"))
  assert(source.includes("'-Djava.net.preferIPv4Stack=true'"))
  assert(source.includes("redirect: 'error'"))
  assert(source.includes('AbortSignal.timeout(20000)'))
  assert(source.includes('instance.isAlive()'))
  assert(source.includes('process.kill(-child.pid, signal)'))
  assert(source.includes('--forge.ai.codex-command=/usr/bin/false'))
  assert(source.includes('--forge.ai.claude-command=/usr/bin/false'))
  assert(!source.includes('backend/target/'))
  assert(!source.includes('scripts/run.sh'))
  assert(!source.includes('scripts/maven.sh'))
})

test('diagnostic errors never expose assertion values, messages, paths or arbitrary metadata', () => {
  const error = new Error('secret body and session token')
  error.name = 'AssertionError'; error.operator = 'deepStrictEqual'; error.code = 'ERR_ASSERTION'
  error.actual = { token: 'private' }; error.expected = 'private'
  error.stack = 'AssertionError: secret\n    at run ('
    + new URL('./e50-independent-archive.mjs', import.meta.url).href + ':123:9)'
    + '\n    at foreign (file:///Users/private/secret.mjs:22:3)'
    + '\nsecret ' + new URL('./e50-archive-fixtures.mjs', import.meta.url).href + ':44:1'
  assert.deepEqual(safeError(error), { type: 'AssertionError', operator: 'deepStrictEqual', code: 'ERR_ASSERTION',
    frames: [{ file: 'e50-independent-archive.mjs', line: 123 }] })
  error.name = 'secret'; error.operator = 'secret'; error.code = 'secret'; error.stack = 'secret'
  assert.deepEqual(safeError(error), { type: 'OtherError', operator: null, code: null, frames: [] })
  assert(!JSON.stringify(safeError(error)).includes('secret'))
})

test('checkpoints are bounded static identifiers, separate from completed checks', () => {
  const diagnostic = diagnostics()
  diagnostic.mark('start-ready-wait'); diagnostic.mark('start-ready-wait')
  assert.deepEqual(diagnostic.state.steps, ['start-ready-wait'])
  for (const bad of ['https://private', '/Users/private', 'token=secret', 'a'.repeat(65)]) {
    assert.throws(() => diagnostic.mark(bad))
  }
  for (let index = 0; index < 200; index++) diagnostic.mark('step-' + index)
  assert.equal(diagnostic.state.steps.length, 128)
  assert.equal(diagnostic.state.checkpoint, 'step-199')
})

test('startup classifier emits fixed categories only, including chunk-split diagnostics', () => {
  const classifier = startupClassifier()
  classifier.accept('secret /Users/private sandbox-')
  classifier.accept('exec: Operation not permitted secret-token')
  assert.deepEqual(classifier.snapshot(), ['PERMISSION_DENIED', 'SANDBOX_DIAGNOSTIC'])
  classifier.accept('x'.repeat(20000) + 'APPLICATION FAILED TO START')
  assert.deepEqual(classifier.snapshot(), ['APPLICATION_START_FAILED', 'PERMISSION_DENIED', 'SANDBOX_DIAGNOSTIC'])
  assert(!JSON.stringify(classifier.snapshot()).includes('secret'))
})

test('version-only diagnostic filtering excludes environment/profile lines and redacts native paths', () => {
  assert.deepEqual(safeNativeLines('TOKEN=secret\nHOME=/Users/private\nprofile secret\nopenjdk version "21.0.10"'), ['openjdk version "21.0.10"'])
  const lines = safeNativeLines('dyld[12345]: Library not loaded: /Users/private/runtime/lib.dylib\nlibc++abi: terminating at 0x123abc')
  assert.equal(lines.length, 2)
  assert(!JSON.stringify(lines).includes('/Users'))
  assert(!JSON.stringify(lines).includes('123abc'))
  assert.deepEqual(safeNativeLines(''), [])
})
