import assert from 'node:assert/strict'
import { spawn, execFile } from 'node:child_process'
import { once } from 'node:events'
import { chmod, copyFile, mkdir, mkdtemp, readFile, realpath, rm } from 'node:fs/promises'
import { randomBytes, randomUUID } from 'node:crypto'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'
import { diagnostics, safeError, startupClassifier } from './e50-diagnostics.mjs'
import { archive, boundedPoll, catalogPath, childEnvironment, fixtureVersion,
  options, ownedPath, requestUrl, requireArtifact, sandboxProfile, semanticDetail, sha256, unzipStored, zip } from './e50-archive-fixtures.mjs'

const execute = promisify(execFile)
const modelNames = ['model.onnx', 'tokenizer.json', 'reranker.onnx', 'reranker-tokenizer.json']
const identifier = value => { assert.match(value, /^[a-f0-9-]{36}$/); return value }

export async function run(config) {
  const diagnostic = diagnostics()
  try { return await runChecked(config, diagnostic) }
  catch (error) {
    console.error(JSON.stringify({ status: 'FAIL', diagnostics: diagnostic.state, error: safeError(error) }))
    throw error
  }
}

async function runChecked(config, diagnostic) {
  const mark = name => diagnostic.mark(name)
  mark('preflight-platform')
  assert.equal(process.platform, 'darwin', 'macOS sandbox-exec required; no unsandboxed fallback')
  mark('preflight-jar-path')
  const jar = await realpath(config.jar)
  mark('preflight-java-path')
  const java = await realpath(config.java)
  mark('preflight-model-path')
  const models = await realpath(config.models)
  mark('preflight-jar-hash')
  const original = await readFile(jar)
  requireArtifact(original, config.hash)
  mark('preflight-java-hash')
  const javaHash = sha256(await readFile(java))
  mark('preflight-model-hashes')
  const modelHashes = Object.fromEntries(await Promise.all(modelNames.map(async name => [name, sha256(await readFile(join(models, name)))])))
  mark('preflight-private-root')
  const temporary = await realpath(await mkdtemp(join(tmpdir(), 'dougle-e50-independent-')))
  await chmod(temporary, 0o700)
  const copy = ownedPath(temporary, 'pinned-artifact.jar')
  const instances = []
  const checks = []
  let phase = 'initialization', interrupted = false, calls = 0
  const report = { caseIds: ['E50'], mode: 'simulated', fixtureOnly: true, fixtureVersion, jarSha256: config.hash,
    javaSha256: javaHash, modelHashes, checks, acceptanceUpdated: false, aiCalls: 0,
    limitation: 'Synthetic independent data roots on one Intel host, not different OS accounts or historical application packages. No full attachment transfer, semantic ranking evaluation, or E50 acceptance promotion.' }
  const record = name => { checks.push(name); phase = name }
  const interrupt = () => { interrupted = true; for (const instance of instances) instance.kill('SIGTERM') }
  process.on('SIGINT', interrupt); process.on('SIGTERM', interrupt)
  const overall = setTimeout(interrupt, 15 * 60 * 1000)
  const active = () => { assert(!interrupted, 'Run interrupted or total deadline reached') }

  async function instance(label) {
    mark('instance-directories-' + label.toLowerCase())
    const directory = ownedPath(temporary, label)
    const home = ownedPath(directory, 'home'), data = ownedPath(directory, 'data'), scratch = ownedPath(directory, 'tmp')
    for (const path of [home, data, scratch]) await mkdir(path, { recursive: true, mode: 0o700 })
    let child, base, token, startup = '', spawnFailure = false
    const alive = () => child?.pid && child.exitCode === null && child.signalCode === null
    const target = {
      label, data, isAlive: () => Boolean(alive()),
      kill(signal) { if (alive()) { try { process.kill(-child.pid, signal) } catch (error) { if (error.code !== 'ESRCH') throw error } } },
      async stop() {
        if (!alive()) return
        const exited = once(child, 'exit')
        target.kill('SIGTERM')
        const force = setTimeout(() => target.kill('SIGKILL'), 10000)
        let timer
        try { await Promise.race([exited, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Owned backend did not exit')), 15000) })]) }
        finally { clearTimeout(force); clearTimeout(timer) }
        assert(!alive())
      },
      async start() {
        mark('start-state-' + label.toLowerCase())
        active(); assert(!alive())
        mark('start-artifact-hashes')
        requireArtifact(await readFile(copy), config.hash); requireArtifact(await readFile(jar), config.hash)
        mark('start-java-hash')
        assert.equal(sha256(await readFile(java)), javaHash, 'Installed Java changed; coordinate installation before retry')
        token = randomBytes(32).toString('hex'); base = undefined; startup = ''; spawnFailure = false
        mark('start-sandbox-profile')
        const profile = sandboxProfile(directory, dirname(dirname(java)), models)
          + ' (allow file-read* (literal ' + JSON.stringify(copy) + '))'
        const classifier = startupClassifier()
        const childState = { instance: label, spawned: false, ready: false, exitCode: null, signal: null, spawnError: null, stdinError: null, categories: [] }
        diagnostic.state.child = childState
        mark('start-sandbox-spawn')
        child = spawn('/usr/bin/sandbox-exec', ['-p', profile, java,
          '-Djava.net.preferIPv4Stack=true', '-Duser.home=' + home, '-Djava.io.tmpdir=' + scratch, '-jar', copy,
          '--server.port=0', '--server.address=127.0.0.1', '--forge.desktop=true', '--forge.data-dir=' + data,
          '--forge.search-model-directory=' + models, '--forge.ai.codex-command=/usr/bin/false',
          '--forge.ai.claude-command=/usr/bin/false', '--forge.ai.codex-stream-home=' + ownedPath(directory, 'codex-profile')],
        { cwd: directory, env: childEnvironment(home, data, scratch), detached: true, stdio: ['pipe', 'pipe', 'pipe'] })
        child.on('spawn', () => { childState.spawned = true })
        child.on('exit', (code, signal) => {
          childState.exitCode = Number.isInteger(code) ? code : null
          childState.signal = ['SIGTERM', 'SIGKILL', 'SIGABRT', 'SIGSEGV'].includes(signal) ? signal : null
        })
        child.on('error', error => { spawnFailure = true; childState.spawnError = safeError(error) })
        child.stdin.on('error', error => { spawnFailure = true; childState.stdinError = safeError(error) })
        child.stderr.on('data', chunk => { classifier.accept(chunk); childState.categories = classifier.snapshot() })
        child.stdout.on('data', chunk => {
          classifier.accept(chunk); childState.categories = classifier.snapshot()
          startup = (startup + chunk).slice(-16000)
          const match = startup.match(/^FORGE_DESKTOP_READY:(\d+)\r?\n/m)
          if (match && Number(match[1]) > 0 && Number(match[1]) <= 65535) {
            base = 'http://127.0.0.1:' + match[1] + '/api'; childState.ready = true
          }
        })
        mark('start-session-input')
        child.stdin.write(token + '\n')
        mark('start-ready-wait')
        await boundedPoll(async () => {
          active(); assert(!spawnFailure && alive(), 'Sandboxed backend startup failed; raw logs withheld')
          if (!base) return false
          try { await target.api('/session'); return true } catch (error) {
            childState.sessionError = safeError(error); return false
          }
        }, { timeoutMs: 90000 })
      },
      async api(path, method = 'GET', body, expected = 200) {
        mark('http-allowlist')
        active(); assert(++calls <= 3000, 'Request budget exhausted')
        const url = requestUrl(base, path, method)
        const raw = Buffer.isBuffer(body), form = body instanceof FormData
        const headers = { 'X-Forge-Session': token, 'X-Forge-Client': '1', Origin: url.origin,
          ...(body === undefined || form ? {} : { 'Content-Type': raw ? 'application/zip' : 'application/json' }) }
        diagnostic.state.http = { method, expected, status: null }
        mark('http-request')
        const response = await fetch(url, { method, headers, redirect: 'error', signal: AbortSignal.timeout(20000),
          body: body === undefined ? undefined : raw || form ? body : JSON.stringify(body) })
        diagnostic.state.http.status = response.status
        mark('http-bounded-response')
        const reader = response.body?.getReader(), chunks = []
        let size = 0
        if (reader) try {
          while (true) {
            const next = await reader.read(); if (next.done) break
            size += next.value.length
            assert(size <= 10 * 1024 * 1024, 'Response exceeds E50 bound')
            chunks.push(next.value)
          }
        } finally { await reader.cancel().catch(() => {}) }
        mark('http-status')
        assert.equal(response.status, expected, 'Unexpected HTTP status in phase ' + phase)
        const bytes = Buffer.concat(chunks)
        mark('http-decode')
        return response.headers.get('content-type')?.includes('application/zip') || path.endsWith('/content')
          ? bytes : bytes.length ? JSON.parse(bytes.toString()) : null
      },
      async sql(query) {
        assert(query.startsWith('SELECT ') && !/[;\r\n]/.test(query))
        const { stdout } = await execute('/usr/bin/sqlite3', ['-readonly', ownedPath(data, 'knowledge-forge.sqlite'), query],
          { timeout: 3000, maxBuffer: 65536, env: childEnvironment(home, data, scratch) })
        return stdout.trim()
      },
      async search(workspace, entry, textHash, quote) {
        await boundedPoll(async () => {
          const model = await target.api('/search-model')
          assert(['STARTING', 'LOADING', 'INDEXING', 'READY'].includes(model.state), 'Search model failed')
          if (model.state !== 'READY' || await target.sql('SELECT count(*) FROM search_outbox') !== '0') return false
          const hits = await target.api('/workspaces/' + identifier(workspace) + '/search?query=E50_SYNTHETIC_ONLY')
          assert(Array.isArray(hits)); if (!hits.length) return false
          for (const hit of hits) {
            assert.equal(hit.entryId, entry); assert.equal(hit.kind, 'ORIGINAL')
            assert(hit.sourceId === null || hit.sourceId === '')
            assert.equal(hit.textHash, textHash); assert(hit.snippet.includes(quote))
          }
          return true
        })
      },
    }
    instances.push(target)
    return target
  }

  const exportArchive = (target, workspace) => target.api('/workspace-archives/export/' + identifier(workspace))
  async function importArchive(target, bytes, name) {
    const before = await target.api('/session')
    const preview = await target.api('/workspace-archives/import-preview?workspaceName=' + encodeURIComponent(name), 'POST', bytes)
    assert.deepEqual(await target.api('/session'), before)
    const request = { requestId: randomUUID(), previewId: preview.previewId, digest: preview.digest }
    const result = await target.api('/workspace-archives/import-apply', 'POST', request)
    assert.deepEqual(await target.api('/workspace-archives/import-apply', 'POST', request), result)
    assert.equal((await target.api('/session')).activeWorkspaceId, before.activeWorkspaceId)
    assert.equal(result.threadIds.length, 1)
    return { ...result, request }
  }
  const detail = (target, result) => target.api('/workspaces/' + identifier(result.workspaceId) + '/chat-threads/' + identifier(result.threadIds[0]))
  const fileEntry = value => value.bundles[0].payload.items[0].entryId
  async function idle(target, result) {
    const prefix = '/workspaces/' + identifier(result.workspaceId)
    assert.deepEqual(await target.api(prefix + '/generation-jobs'), [])
    assert.deepEqual(await target.api(prefix + '/sources'), [])
    assert.equal((await target.api(prefix + '/source-polling')).enabled, false)
    const jobs = await target.api(prefix + '/answer-jobs')
    assert.equal(jobs.length, 2)
    assert(jobs.every(job => job.status === 'SUCCEEDED' && job.requestedModel === ''))
    assert.equal(await target.sql('SELECT count(*) FROM answer_approval'), '0')
  }

  try {
    mark('copy-artifact')
    await copyFile(jar, copy)
    mark('protect-artifact-copy')
    await chmod(copy, 0o400)
    mark('verify-artifact-copy')
    requireArtifact(await readFile(copy), config.hash)
    const first = await instance('A'), second = await instance('B')
    mark('verify-independent-roots')
    assert.notEqual(first.data, second.data)
    await first.start()
    const initialSession = await first.api('/session')
    mark('verify-a-empty-workspaces')
    assert.deepEqual(initialSession.workspaces, [])
    phase = 'synthetic source fixture'
    const workspace = await first.api('/workspaces', 'POST', { name: 'E50 source files' }, 201)
    const content = Buffer.from('E50_SYNTHETIC_ONLY: retry budget is three.\n')
    const form = new FormData(); form.append('file', new Blob([content]), '합성.txt')
    const entry = await first.api('/workspaces/' + workspace.id + '/files', 'POST', form, 201)
    const analysis = await first.api('/workspaces/' + workspace.id + '/entries/' + entry.id + '/analysis')
    const exported = unzipStored(await exportArchive(first, workspace.id))
    const catalog = JSON.parse(exported.get(catalogPath))
    const material = catalog.materials.find(value => value.kind === 'FILE')
    assert(material)
    const when = '2026-01-01T00:00:00Z', question = 'Synthetic retry budget?', answer = 'Synthetic historical answer: three retries.'
    const root = randomUUID()
    const evidence = { materialKey: material.key, contentHash: sha256(content), textHash: sha256(analysis.text),
      startLine: 1, endLine: 1, quote: analysis.text.split('\n')[0] }
    catalog.threads = [{ turns: [
      { key: root, createdAt: when, question, retrievalQuery: 'retry budget', evidence: [evidence], history: [],
        answers: ['CODEX', 'CLAUDE'].map(provider => ({ provider, model: '', text: answer, createdAt: when, updatedAt: when })) },
      { key: randomUUID(), createdAt: when, question: 'Synthetic followup?', retrievalQuery: 'retry', evidence: [evidence],
        history: [{ turnKey: root, question, answer }], answers: [] },
    ] }]
    const source = await importArchive(first, archive(catalog, new Map([[material.key, content]])), 'E50 source history')
    await first.search(source.workspaceId, fileEntry(await detail(first, source)), evidence.textHash, evidence.quote)
    let current = await detail(first, source)
    for (let index = 0; index < 7; index++) {
      await first.api('/workspaces/' + source.workspaceId + '/chat-threads', 'POST', { requestId: randomUUID(), threadId: source.threadIds[0],
        parentBundleId: current.bundles.at(-1).id, question: 'E50_SYNTHETIC_ONLY followup ' + index, attachmentIds: [] }, 201)
      current = await detail(first, source)
    }
    assert.equal(current.bundles.length, 9)
    assert(current.bundles.at(-1).payload.chatContext.extractive)
    const canonical = semanticDetail(current, fileEntry(current))
    const sourceZip = await exportArchive(first, source.workspaceId)
    assert.equal(JSON.parse(unzipStored(sourceZip).get(catalogPath)).version, 2)
    await idle(first, source)
    record('A: default-model historical answers and nine-turn v2 context; no AI approvals')
    await first.stop()

    await second.start(); assert.deepEqual((await second.api('/session')).workspaces, [])
    const imported = await importArchive(second, sourceZip, 'E50 target B')
    const restored = await detail(second, imported), restoredEntry = fileEntry(restored)
    assert.notEqual(restoredEntry, fileEntry(current))
    assert.deepEqual(semanticDetail(restored, restoredEntry), canonical)
    assert.deepEqual(await second.api('/workspaces/' + imported.workspaceId + '/entries/' + restoredEntry + '/content'), content)
    await second.search(imported.workspaceId, restoredEntry, evidence.textHash, evidence.quote)
    await idle(second, imported)
    const backZip = await exportArchive(second, imported.workspaceId)
    record('Independent B: bytes, answers, context remapping and fresh scoped index verified')
    await second.stop(); await second.start()
    assert.deepEqual(await second.api('/workspace-archives/import-apply', 'POST', imported.request),
      { workspaceId: imported.workspaceId, threadIds: imported.threadIds, materials: imported.materials, indexStatus: imported.indexStatus })
    assert.deepEqual(await detail(second, imported), restored)
    await second.search(imported.workspaceId, restoredEntry, evidence.textHash, evidence.quote)
    await idle(second, imported)
    record('B restart: durable receipt and exact history, index rebuilt without reexecution')

    const stable = await second.api('/session'), documents = unzipStored(backZip)
    for (const mutate of [
      value => { value.version = 999 },
      value => { value.materials[0].name = '../escape' },
      value => { value.threads[0].turns[1].history[0].turnKey = randomUUID() },
      value => { value.threads[0].turns.at(-1).extractive.questions[0].bundleDigest = 'f'.repeat(64) },
    ]) {
      const malformed = JSON.parse(documents.get(catalogPath)); mutate(malformed)
      const files = new Map([...documents].filter(([path]) => path.startsWith('documents/') && path !== catalogPath)
        .map(([path, bytes]) => [path.slice(10, -4), bytes]))
      await second.api('/workspace-archives/import-preview?workspaceName=E50%20invalid', 'POST', archive(malformed, files), 400)
    }
    const corrupt = new Map(documents)
    const contentPath = [...corrupt.keys()].find(path => path.startsWith('documents/') && path !== catalogPath)
    corrupt.set(contentPath, Buffer.from('damaged synthetic content'))
    await second.api('/workspace-archives/import-preview?workspaceName=E50%20invalid', 'POST', zip(corrupt), 400)
    await second.api('/workspace-archives/import-preview?workspaceName=E50%20invalid', 'POST', backZip.subarray(0, 20), 400)
    assert.deepEqual(await second.api('/session'), stable)
    assert.deepEqual(await detail(second, imported), restored)
    record('B: corrupt archive, path, version and context references rejected without history loss')

    const attachedText = Buffer.from('Synthetic operational evidence only\nservice=example\nresult=healthy\n')
    const attachedForm = new FormData(); attachedForm.append('file', new Blob([attachedText]), 'datadog-synthetic.txt')
    const attachment = await second.api('/workspaces/' + imported.workspaceId + '/attachments', 'POST', attachedForm, 201)
    const bound = await second.api('/workspaces/' + imported.workspaceId + '/chat-threads', 'POST', { requestId: randomUUID(),
      threadId: imported.threadIds[0], parentBundleId: restored.bundles.at(-1).id, question: 'Explain synthetic operational evidence', attachmentIds: [attachment.id] }, 201)
    const beforeRejection = await detail(second, imported)
    await second.api('/workspace-archives/export/' + imported.workspaceId, 'GET', undefined, 409)
    assert.deepEqual(await detail(second, imported), beforeRejection)
    assert.equal(await second.sql("SELECT count(*) FROM question_attachment WHERE id='" + identifier(attachment.id)
      + "' AND bundle_id='" + identifier(bound.id) + "' AND content_hash='" + sha256(attachedText) + "'"), '1')
    await idle(second, imported)
    record('B: attached operational text explicitly blocks export while preserving bound evidence')
    await second.stop()

    await first.start()
    assert.deepEqual(await detail(first, source), current)
    const roundtrip = await importArchive(first, backZip, 'E50 returned A')
    const returned = await detail(first, roundtrip), returnedEntry = fileEntry(returned)
    assert.notEqual(returnedEntry, restoredEntry); assert.notEqual(returnedEntry, fileEntry(current))
    assert.deepEqual(semanticDetail(returned, returnedEntry), canonical)
    await first.search(roundtrip.workspaceId, returnedEntry, evidence.textHash, evidence.quote)
    assert.deepEqual(await first.api('/workspaces/' + roundtrip.workspaceId + '/entries/' + returnedEntry + '/content'), content)
    await idle(first, roundtrip)
    record('A restart and B-to-A roundtrip preserve source and fresh-ID context/index')
    report.status = 'PASS'
  } catch (error) {
    report.status = 'FAIL'; report.failedPhase = phase; report.errorType = safeError(error).type
    report.diagnostics = structuredClone(diagnostic.state); report.error = safeError(error)
    process.exitCode = 1
  } finally {
    clearTimeout(overall)
    try {
      for (const instance of instances) await instance.stop()
      requireArtifact(await readFile(jar), config.hash); requireArtifact(await readFile(copy), config.hash)
      assert.equal(sha256(await readFile(java)), javaHash)
      for (const name of modelNames) assert.equal(sha256(await readFile(join(models, name))), modelHashes[name])
      report.assetsUnchanged = true
    } catch (error) { report.status = 'FAIL'; report.cleanupError = safeError(error); process.exitCode = 1 }
    finally {
      process.off('SIGINT', interrupt); process.off('SIGTERM', interrupt)
      if (instances.every(instance => !instance.isAlive())) {
        await rm(temporary, { recursive: true, force: true })
        report.temporaryRemoved = true
      }
    }
    report.executedAt = new Date().toISOString()
    report.scriptSha256 = sha256(await readFile(fileURLToPath(import.meta.url)))
    report.fixturesSha256 = sha256(await readFile(new URL('./e50-archive-fixtures.mjs', import.meta.url)))
    report.diagnosticsSha256 = sha256(await readFile(new URL('./e50-diagnostics.mjs', import.meta.url)))
    console.log(JSON.stringify(report, null, 2))
  }
  return report
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  run(options(process.argv.slice(2))).catch(() => {
    console.error('E50 preflight failed; no unpinned or unsandboxed fallback permitted')
    process.exitCode = 1
  })
}
