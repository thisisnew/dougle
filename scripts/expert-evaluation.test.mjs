import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawnSync } from 'node:child_process'
import test from 'node:test'
import { caseIds, createInventory, extractCases, validateInventory } from './expert-evaluation.mjs'

const markdown = await readFile(new URL('../docs/expert-adoption.md', import.meta.url), 'utf8')
const revision = 'a'.repeat(40)
const fresh = () => createInventory(markdown, revision, '2026-10-25T00:00:00.000Z')
function pass(inventory, id = 'E01', mode = 'real') {
  const result = inventory.cases.find(candidate => candidate.id === id)
  Object.assign(result, {
    status: 'PASS', mode, revision, executedAt: '2026-10-25T01:00:00.000Z',
    model: 'test model v1', fixtureVersion: 'synthetic v1', reviewer: 'self-test only',
    actualAnswer: 'Observed outcome for validator self-test only', expectedEvidence: ['Recorded observation'],
    evidence: [{ path: 'self-test-only/observation.json', description: 'Synthetic claim; never persisted as acceptance evidence' }],
    reason: null,
  })
  return result
}

test('extracts exactly the source cases and acceptance text', () => {
  const cases = extractCases(markdown)
  assert.deepEqual(cases.map(result => result.id), caseIds)
  assert.match(cases[46].acceptance, /ARM/)
  assert.equal(extractCases(markdown.replaceAll('\n', '\r\n')).length, 50)
})

test('explicit criterion hash binds exact acceptance text without promoting status', () => {
  const inventory = fresh()
  const result = inventory.cases.find(candidate => candidate.id === 'E05')
  result.criterionHash = createHash('sha256').update(result.acceptance).digest('hex')
  assert.equal(validateInventory(inventory, markdown).acceptancePassed, 0)
  assert.equal(result.status, 'NOT_RUN')
  result.criterionHash = '0'.repeat(64)
  assert.throws(() => validateInventory(inventory, markdown), /Criterion hash mismatch/)
})

test('source rejects duplicate, missing, extra and malformed IDs', () => {
  for (const altered of [
    markdown.replace('| E01 |', '| E02 |'),
    markdown.replace(/^\| E01 .*\n/m, ''),
    `${markdown}\n| E51 | extra | extra |`,
    markdown.replace('| E01 |', '| E1 |'),
    markdown.replace('| E01 |', '| E001 |'),
  ]) assert.throws(() => extractCases(altered))
})

test('initial inventory is 49 NOT_RUN and E47 BLOCKED, with no execution claims', () => {
  const inventory = fresh()
  const summary = validateInventory(inventory, markdown)
  assert.equal(summary.acceptancePassed, 0)
  assert.equal(summary.simulatedPassed, 0)
  assert.equal(summary.notRun, 49)
  assert.equal(summary.blocked, 1)
  assert.equal(summary.remaining, 50)
  assert.equal(summary.allAcceptancePassed, false)
  assert.equal(inventory.cases[46].status, 'BLOCKED')
  assert.ok(inventory.cases.every(result => result.executedAt === null && result.revision === null && result.evidence.length === 0))
})

test('results reject duplicate, missing and extra IDs', () => {
  for (const mutate of [
    inventory => inventory.cases.pop(),
    inventory => inventory.cases.push({ ...inventory.cases[0], id: 'E51' }),
    inventory => { inventory.cases[0].id = 'E02' },
  ]) {
    const inventory = fresh()
    mutate(inventory)
    assert.throws(() => validateInventory(inventory, markdown), /exactly E01-E50/)
  }
})

test('PASS requires nonblank evidence and complete execution metadata', () => {
  for (const change of [
    { evidence: [] }, { evidence: [{ path: ' ', description: 'claim' }] },
    { evidence: [{ path: 'record.json', description: '' }] },
    { revision: null }, { executedAt: null }, { model: null }, { reviewer: null },
    { fixtureVersion: null }, { expectedEvidence: [] }, { actualAnswer: null },
    { status: 'DONE' }, { mode: 'fixture' }, { latencyMs: -1 },
    { toolCalls: [{ name: 'tool' }] }, { fixtureOnly: true },
  ]) {
    const inventory = fresh()
    Object.assign(pass(inventory), change)
    assert.throws(() => validateInventory(inventory, markdown))
  }
})

test('simulated PASS is separate and never completes acceptance', () => {
  const inventory = fresh()
  pass(inventory, 'E01', 'simulated').fixtureOnly = true
  const summary = validateInventory(inventory, markdown)
  assert.equal(summary.simulatedPassed, 1)
  assert.equal(summary.acceptancePassed, 0)
  assert.equal(summary.remaining, 50)
  assert.equal(summary.allAcceptancePassed, false)
})

test('real and manual recorded passes count separately from simulated passes', () => {
  const inventory = fresh()
  pass(inventory, 'E01', 'real')
  pass(inventory, 'E02', 'manual')
  pass(inventory, 'E03', 'simulated')
  const summary = validateInventory(inventory, markdown)
  assert.equal(summary.acceptancePassed, 2)
  assert.equal(summary.simulatedPassed, 1)
  assert.equal(summary.remaining, 48)
})

test('E47 rejects cross-builds, simulations, emulation and non-Mac hardware', () => {
  for (const hardware of [null, { arch: 'x64', platform: 'darwin', actualDevice: true },
    { arch: 'arm64', platform: 'darwin', actualDevice: false },
    { arch: 'arm64', platform: 'linux', actualDevice: true }]) {
    const inventory = fresh()
    pass(inventory, 'E47').hardware = hardware
    assert.throws(() => validateInventory(inventory, markdown), /actual Apple Silicon/)
  }
  const inventory = fresh()
  const result = pass(inventory, 'E47', 'manual')
  result.hardware = { arch: 'arm64', platform: 'darwin', actualDevice: true, description: 'Synthetic hardware attestation for validator self-test' }
  assert.equal(validateInventory(inventory, markdown).acceptancePassed, 1)
  result.mode = 'simulated'
  assert.throws(() => validateInventory(inventory, markdown), /actual Apple Silicon/)
})

test('stale source, altered criteria, unexplained blockers and failures are rejected', () => {
  assert.throws(() => validateInventory(fresh(), `${markdown}\n`), /Source hash/)
  for (const change of [{ acceptance: 'weaker' }, { status: 'BLOCKED', reason: '' },
    { status: 'FAIL', reason: null }, { executedAt: '2026-10-25T01:00:00Z' }]) {
    const inventory = fresh()
    Object.assign(inventory.cases[0], change)
    assert.throws(() => validateInventory(inventory, markdown))
  }
})

test('checked-in inventory validates without promoting self-test claims', async () => {
  const inventory = JSON.parse(await readFile(new URL('../docs/expert-evaluation-status.json', import.meta.url), 'utf8'))
  const summary = validateInventory(inventory, markdown)
  const accepted = inventory.cases.filter(result => result.status === 'PASS'
    && ['real', 'manual'].includes(result.mode) && !result.fixtureOnly).length
  assert.equal(summary.acceptancePassed, accepted)
  assert.equal(summary.simulatedPassed, inventory.cases.filter(result => result.status === 'PASS' && result.mode === 'simulated').length)
  for (const [field, status] of [['notRun', 'NOT_RUN'], ['blocked', 'BLOCKED'], ['failed', 'FAIL']]) {
    assert.equal(summary[field], inventory.cases.filter(result => result.status === status).length)
  }
  assert.equal(summary.remaining, caseIds.length - accepted)
  assert.equal(summary.allAcceptancePassed, accepted === caseIds.length)
  assert.equal(summary.allAcceptancePassed, false, 'Current inventory must not claim completed acceptance')
})

test('CLI reports honest summary and exits nonzero for invalid usage', async () => {
  const inventory = JSON.parse(await readFile(new URL('../docs/expert-evaluation-status.json', import.meta.url), 'utf8'))
  const script = new URL('./expert-evaluation.mjs', import.meta.url)
  const valid = spawnSync(process.execPath, [script.pathname, 'validate'], { encoding: 'utf8' })
  assert.equal(valid.status, 0, valid.stderr)
  assert.deepEqual(JSON.parse(valid.stdout), validateInventory(inventory, markdown))
  const invalid = spawnSync(process.execPath, [script.pathname, 'unknown'], { encoding: 'utf8' })
  assert.equal(invalid.status, 1)
  assert.match(invalid.stderr, /Usage/)
})

test('gate rejects current, initial and simulated completion but accepts all real/manual recorded passes', async () => {
  const script = new URL('./expert-evaluation.mjs', import.meta.url).pathname
  const current = spawnSync(process.execPath, [script, 'gate'], { encoding: 'utf8' })
  assert.equal(current.status, 1)
  assert.equal(JSON.parse(current.stdout).allAcceptancePassed, false)
  const directory = await mkdtemp(join(tmpdir(), 'expert-evaluation-gate-'))
  try {
    const inventory = fresh()
    const path = join(directory, 'status.json')
    await writeFile(path, JSON.stringify(inventory))
    const initial = spawnSync(process.execPath, [script, 'gate', path], { encoding: 'utf8' })
    assert.equal(initial.status, 1, initial.stderr)
    assert.equal(JSON.parse(initial.stdout).acceptancePassed, 0)
    assert.equal(JSON.parse(initial.stdout).allAcceptancePassed, false)
    for (const id of caseIds) pass(inventory, id, 'manual')
    inventory.cases[46].hardware = { arch: 'arm64', platform: 'darwin', actualDevice: true, description: 'Self-test only' }
    await writeFile(path, JSON.stringify(inventory))
    const complete = spawnSync(process.execPath, [script, 'gate', path], { encoding: 'utf8' })
    assert.equal(complete.status, 0, complete.stderr)
    assert.equal(JSON.parse(complete.stdout).allAcceptancePassed, true)
    inventory.cases[0].mode = 'simulated'
    inventory.cases[0].fixtureOnly = true
    await writeFile(path, JSON.stringify(inventory))
    const simulated = spawnSync(process.execPath, [script, 'gate', path], { encoding: 'utf8' })
    assert.equal(simulated.status, 1)
    assert.equal(JSON.parse(simulated.stdout).acceptancePassed, 49)
    assert.equal(JSON.parse(simulated.stdout).simulatedPassed, 1)
    const structural = spawnSync(process.execPath, [script, 'validate', path], { encoding: 'utf8' })
    assert.equal(structural.status, 0)
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})
