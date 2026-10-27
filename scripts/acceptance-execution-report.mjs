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
  const batches = ['file', 'folder', 'web', 'search', 'library-workflow'].map(name => ({ name,
    cases: json(`backend/target/${name}-acceptance-results.json`) }))
  assert.deepEqual(batches.map(batch => batch.cases.length), [100, 50, 50, 150, 75])
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
  const report = {
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    baseCommit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim(),
    productTree: execFileSync('git', ['rev-parse', 'HEAD:backend/src/main'], { cwd: root, encoding: 'utf8' }).trim(),
    runnerHashes: Object.fromEntries([
      ...['FileAcceptanceTest', 'FolderAcceptanceTest', 'WebAcceptanceTest', 'SearchAcceptanceTest', 'LibraryWorkflowAcceptanceTest']
        .map(name => `backend/src/test/java/dev/knowledgeforge/${name}.java`),
      'docs/fixtures/search-acceptance.json', 'scripts/search-acceptance-fixtures.mjs',
    ].map(path => [path, createHash('sha256').update(readFileSync(new URL('../' + path, import.meta.url))).digest('hex')])),
    installedVersion: '0.1.27 unchanged; source tests, not installed-app acceptance',
    newCaseProbeCount: probes.length,
    newProbeSummary: summarize(cases.filter(item => probes.some(probe => probe.id === item.id))),
    summary,
    harnessErrors: [
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
      'No actual AI requests, external business writes, user credentials copy, app installation, public release or update-feed change.',
      'Intel host only. ARM/fresh Mac and approved company services are not available to these runners. They remain unexecuted, not passed.',
    ],
    batches: batches.map(batch => ({ name: batch.name, summary: summarize(batch.cases), count: batch.cases.length })),
    cases,
  }
  writeFileSync(new URL('../docs/evaluations/acceptance-remaining-20261027.json', import.meta.url), JSON.stringify(report, null, 2) + '\n')
  process.stdout.write(JSON.stringify({ newCases: probes.length, summary }, null, 2) + '\n')
}
