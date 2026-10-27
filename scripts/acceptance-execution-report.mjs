import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { buildCatalog } from './acceptance-catalog.mjs'

export function executionLedger(previous, probes) {
  const catalog = buildCatalog().cases
  const ids = new Set(catalog.map(item => item.id))
  const seen = new Set()
  for (const probe of probes) {
    assert(ids.has(probe.id), `Unknown ID ${probe.id}`)
    assert(!seen.has(probe.id), `Duplicate ID ${probe.id}`)
    assert(['PASS', 'FAIL'].includes(probe.checkStatus), `Invalid check status ${probe.id}`)
    assert.equal(probe.fullAcceptancePassed, false, 'Bounded probes cannot promote full acceptance')
    assert(probe.scope && probe.startedAt && probe.remaining?.length, `Missing evidence/scope ${probe.id}`)
    seen.add(probe.id)
  }
  const before = new Map(previous.map(item => [item.id, item]))
  const current = new Map(probes.map(item => [item.id, item]))
  return catalog.map(item => {
    const probe = current.get(item.id)
    if (probe) return { ...probe, suite: item.suite, status: probe.checkStatus === 'FAIL' ? 'FAIL' : 'PARTIAL' }
    if (before.get(item.id)?.status !== 'NOT_RUN' && before.has(item.id)) return before.get(item.id)
    return { id: item.id, suite: item.suite, status: 'NOT_RUN', fullAcceptancePassed: false,
      remaining: ['No case-specific execution evidence yet; existing regression tests are not substituted for this case.'] }
  })
}

export function summarize(cases) {
  const counts = { PASS: 0, PARTIAL: 0, FAIL: 0, BLOCKED: 0, NOT_RUN: 0 }
  for (const item of cases) {
    assert(Object.hasOwn(counts, item.status))
    counts[item.status]++
  }
  return counts
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  assert.equal(process.argv[2], '--write', 'Use --write after all probe runners finish')
  const root = fileURLToPath(new URL('../', import.meta.url))
  const json = relative => JSON.parse(readFileSync(new URL('../' + relative, import.meta.url), 'utf8'))
  const batches = ['file', 'folder', 'web', 'search', 'library-workflow', 'git', 'web-boundary', 'folder-failure', 'sync-progress', 'notion', 'oauth', 'mysql', 'readonly', 'sync-entry', 'update-ui', 'operational', 'dns'].map(name => ({ name,
    cases: json(`backend/target/${name}-acceptance-results.json`) }))
  assert.deepEqual(batches.map(batch => batch.cases.length), [100, 50, 50, 150, 75, 100, 45, 25, 25, 25, 25, 25, 25, 25, 25, 25, 5])
  const live = json('docs/evaluations/acceptance-live-ai-20261027.json')
  assert.equal(live.cases.length, 50)
  batches.push({ name: 'live-ai', cases: live.cases })
  const images = json('docs/evaluations/acceptance-images-20261027.json')
  assert.equal(images.cases.length, 25)
  batches.push({ name: 'images', cases: images.cases })
  const probes = batches.flatMap(batch => batch.cases.map(original => {
    const item = { ...original }
    if (item.expectedManifest) {
      item.manifestHash = createHash('sha256').update(JSON.stringify(item.expectedManifest)).digest('hex')
      item.expectedCount = Object.keys(item.expectedManifest).length
      delete item.expectedManifest
    }
    return item
  }))
  const cases = executionLedger(json('docs/evaluations/acceptance-routing-20261027.json').cases, probes)
  const summary = summarize(cases)
  const baseline = JSON.parse(execFileSync('git', ['show', '7d355ec:docs/evaluations/acceptance-remaining-20261027.json'],
    { cwd: root, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 }))
  const sourceHash = createHash('sha256')
  for (const path of execFileSync('git', ['ls-files', '-z', 'backend/src/main'], { cwd: root, encoding: 'utf8' }).split('\0').filter(Boolean).sort()) {
    sourceHash.update(path).update('\0').update(readFileSync(root + path)).update('\0')
  }
  const report = {
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    baseCommit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim(),
    productTree: execFileSync('git', ['rev-parse', 'HEAD:backend/src/main'], { cwd: root, encoding: 'utf8' }).trim(),
    productWorkingSourceSha256: sourceHash.digest('hex'),
    sourceHashMeaning: 'Current checkpoint source; mixed baseline and targeted reruns, not all cases executed against one build. Actual AI reports retain their tested JAR hashes.',
    baseline: { commit: '7d355ec', summary: baseline.summary,
      repairedProbeIds: baseline.cases.filter(item => item.status === 'FAIL' && cases.find(current => current.id === item.id)?.status === 'PARTIAL').map(item => item.id) },
    runnerHashes: Object.fromEntries([
      ...['FileAcceptanceTest', 'FolderAcceptanceTest', 'WebAcceptanceTest', 'SearchAcceptanceTest', 'LibraryWorkflowAcceptanceTest', 'GitAcceptanceTest', 'FolderFailureAcceptanceTest', 'SyncProgressAcceptanceTest']
        .map(name => `backend/src/test/java/dev/knowledgeforge/${name}.java`),
      'backend/src/test/java/dev/knowledgeforge/sources/adapter/WebBoundaryAcceptanceTest.java',
      'backend/src/test/java/dev/knowledgeforge/sources/adapter/DnsRebindingAcceptanceTest.java',
      ...['Notion', 'Oauth', 'Mysql', 'Readonly', 'Operational'].map(name =>
        `backend/src/test/java/dev/knowledgeforge/plugins/adapter/${name}AcceptanceTest.java`),
      'frontend/src/SyncEntryAcceptance.test.tsx', 'frontend/src/UpdateAcceptance.test.tsx',
      'scripts/acceptance-live-ai.mjs', 'docs/evaluations/acceptance-live-ai-20261027.json',
      'scripts/AcceptanceImages.java', 'docs/evaluations/acceptance-images-20261027.json',
      'docs/fixtures/search-acceptance.json', 'scripts/search-acceptance-fixtures.mjs',
    ].map(path => [path, createHash('sha256').update(readFileSync(new URL('../' + path, import.meta.url))).digest('hex')])),
    installedVersion: '0.1.28 installed locally; signature/JAR/HTTP/schema checked, no full installed-app acceptance',
    newCaseProbeCount: probes.length,
    newProbeSummary: summarize(cases.filter(item => probes.some(probe => probe.id === item.id))),
    summary,
    actualAi: { maximumCalls: images.maximumCalls, callsReserved: images.callsReserved,
      reports: ['docs/evaluations/acceptance-live-ai-20261027.json', 'docs/evaluations/acceptance-images-20261027.json'], manualVerdict: 'NOT_REVIEWED' },
    harnessErrors: [
      'Initial live AI startup omitted FORGE_DATA_DIR; two attempts failed before any AI dispatch. Fixed private absolute data directory; final batch completed with 75 reserved calls.',
      'MySQL JSON oracle compared serialized emoji rather than decoded fields; corrected test oracle and reran all 25 against the owned server.',
      'OAuth fixture incorrectly used AutoCloseable, then expected denial callback HTTP400 rather than HTTP200 with CANCELLED. Corrected harness; final 25 passed without weakening disconnected/token-exchange assertions.',
      'Initial search run: truncated generated JSON contained Warning; zero cases executed. Regenerated fixture artifact and reran.',
      'Initial file run: instance TempDir unavailable in BeforeAll; zero cases executed. Changed to static TempDir and reran.',
      'Existing AnswerPartialIntegrationTest/QuestionAttachmentIntegrationTest fixture workspace names exceeded 20 characters. Shortened test names without changing application validation.',
      'The shell wrapper used the reserved zsh variable status after Maven; subsequent Node execution did not start. Node was then executed separately, not counted as having run in the failed wrapper.',
      'An intermediate complete-marker oracle incorrectly rejected Korean particles after BLUE72, causing 25 harness failures. ASCII identifier boundaries and a dedicated oracle test correct this without accepting PAGE1 as PAGE1000.',
      'Initial library workflow probes expected a service-level LibraryException for duplicate names. Actual DuplicateKeyException is mapped to 409 by ApiErrors; 17 harness failures were corrected by exercising real MockMVC multipart responses, including concurrent submissions.',
    ],
    limitations: [
      'This is not completion of the requested remaining 900 cases. Full acceptance PASS is not claimed.',
      'File: real analyzer/lexical index, no upload UI/database registration. Reopen is an index reopen, not an app restart. PDF reading-order and NFC/NFD collision are not verified.',
      'Folder: real collector and manifest/hash/mtime/permission checks; no UI/SourceService/search. Symlink rejection prevents later action coverage. build is collected under current policy.',
      'Web: mocked HTTP, real URL collector/analyzer and lexical search. No real TLS/redirect/DNS/browser/semantic answer validation.',
      'Search: actual bundled model with memoized deterministic embeddings/relevance. Synthetic corpus directly indexed. Fifty garden distractors are easier/different than specified near-keyword distractors. Four ingestion routes, immutable histories, answer abstention and full evidence assembly remain unverified.',
      'Library workflow: real Spring/SQLite/lexical search, direct services rather than UI. No process restart, delayed HTTP response race or real AI. Stored answer jobs are synthetic fixtures.',
      'Git: two public repositories fetched at pinned revisions; actual collector/blob reads with mocked API and local clone transport. Not production remote registration or semantic search. LFS, submodule and encoding failures repaired and rerun.',
      'Web boundary: actual reader/collector with mocked HTTP client. Five production 25-second deadlines execute against cancellation-aware synthetic transport, not a real stalled socket. DNS5 now tests actual HTTP connection resolution with controlled answers, not a live DNS server.',
      'Folder failures: owned chmod/sparse oversized/corrupt PDF/disappearing fixtures, actual collector/analyzer/lexical index. SourceService state, UI and persisted retry are not covered.',
      'Sync progress: real SourceService/SQLite/analyzer with test latches at analyzer entry; cancellation/retry, metadata mutation rejection, concurrent sync/poll and service reconstruction. No UI navigation/focus/hover or actual process restart. Inner splitting stage sequence not sampled.',
      'Actual AI: 50 synthetic cases, 75 reserved dispatches, 45 automatic checks passed and 5 failed. These are not 45 fully accepted cases. Long conversation stops at the second generic follow-up; later requested turns never execute. Planning/UI are excluded; semantic review remains separate.',
      'Notion/OAuth: actual adapters/session/vault/callback, provider and browser transport mocked. No real account authorization or recursive Notion collection claim.',
      'MySQL: owned temporary MySQL 8.4 server with SELECT-only fixture account, real JDBC. No company DB, NL interpretation, approval UI or timezone conversion.',
      'Readonly/operational: policy boundaries and Slack mocked-provider inbox, Datadog real HTTP adapter against loopback. Five request-ID filtering failures. No company service or actual AI operational decision.',
      'Sync entry/update: real React components with mocked API/native IPC. Native updater smoke ran separately on synthetic packages; not a real application upgrade or native UI acceptance.',
      'Images: 25 actual attachment/AI cases plus targeted 5-case rerun; 36 additional reserved calls. Redaction-marker parsing failures repaired. UI intent routing, actual browser and operational investigation remain unverified.',
      'No external business writes, user credentials copy, public release or update-feed change. Dedicated existing AI profile used only by CLI; synthetic documents only. Local package preparation is separate from these source probes.',
      'Intel host only. ARM/fresh Mac and approved company services are not available to these runners. They remain unexecuted, not passed.',
    ],
    batches: batches.map(batch => ({ name: batch.name, summary: summarize(batch.cases), count: batch.cases.length })),
    cases,
  }
  writeFileSync(new URL('../docs/evaluations/acceptance-remaining-20261027.json', import.meta.url), JSON.stringify(report, null, 2) + '\n')
  process.stdout.write(JSON.stringify({ newCases: probes.length, summary }, null, 2) + '\n')
}
