import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export const caseIds = Object.freeze(Array.from({ length: 50 }, (_, index) => `E${String(index + 1).padStart(2, '0')}`))
const statuses = ['PASS', 'FAIL', 'BLOCKED', 'NOT_RUN']
const modes = ['simulated', 'real', 'manual']
const root = fileURLToPath(new URL('../', import.meta.url))
const sourcePath = 'docs/expert-adoption.md'
const nonempty = value => typeof value === 'string' && value.trim().length > 0
const timestamp = value => nonempty(value) && /^\d{4}-\d{2}-\d{2}T/.test(value) && Number.isFinite(Date.parse(value))
const revision = value => typeof value === 'string' && /^[a-f0-9]{40,64}$/.test(value)
const hash = text => createHash('sha256').update(text).digest('hex')

function requireValid(condition, message) {
  if (!condition) throw new Error(message)
}

function exactIds(records, label) {
  requireValid(Array.isArray(records), `${label}: expected an array`)
  const ids = records.map(record => record?.id)
  requireValid(ids.length === 50 && new Set(ids).size === 50 && caseIds.every(id => ids.includes(id)),
    `${label}: require exactly E01-E50 with no duplicate, missing, or extra IDs`)
}

export function extractCases(markdown) {
  const records = []
  let section = ''
  for (const line of markdown.split(/\r?\n/)) {
    if (line.startsWith('## ')) section = line.slice(3).trim()
    if (!/^\s*\|\s*E\d/.test(line)) continue
    const cells = line.trim().split('|').slice(1, -1).map(cell => cell.trim())
    requireValid(cells.length === 3 && /^E\d{2}$/.test(cells[0]) && cells.every(nonempty), `Malformed case row: ${line}`)
    records.push({ id: cells[0], section, input: cells[1], acceptance: cells[2] })
  }
  exactIds(records, 'Source inventory')
  return records.sort((left, right) => left.id.localeCompare(right.id))
}

export function createInventory(markdown, appRevision, createdAt = new Date().toISOString()) {
  requireValid(revision(appRevision), 'Provide a full app commit revision')
  requireValid(timestamp(createdAt), 'Invalid creation timestamp')
  return {
    schemaVersion: 1,
    source: { path: sourcePath, sha256: hash(markdown) },
    inventoryRevision: appRevision,
    createdAt,
    policy: 'Inventory and validator only; no evaluation is executed. Simulated PASS never counts as completion. Evidence is reviewer-supplied, not independently authenticated.',
    cases: extractCases(markdown).map(definition => ({
      ...definition,
      status: definition.id === 'E47' ? 'BLOCKED' : 'NOT_RUN',
      mode: 'manual',
      revision: null,
      model: null,
      fixtureVersion: null,
      executedAt: null,
      expectedEvidence: [],
      actualAnswer: null,
      toolCalls: [],
      latencyMs: null,
      cost: { calls: null, tokens: null, amount: null, currency: null },
      evidence: [],
      fixtureOnly: false,
      hardware: null,
      reviewer: null,
      reason: definition.id === 'E47' ? 'Requires actual Apple Silicon Mac installation and execution; cross-builds, emulation, and fixtures are insufficient.' : null,
    })),
  }
}

export function validateInventory(inventory, markdown) {
  requireValid(inventory?.schemaVersion === 1, 'Unsupported schemaVersion')
  requireValid(inventory.source?.path === sourcePath && inventory.source.sha256 === hash(markdown), 'Source hash/path mismatch: review changed acceptance criteria')
  requireValid(revision(inventory.inventoryRevision) && timestamp(inventory.createdAt), 'Invalid inventory revision/timestamp')
  const definitions = extractCases(markdown)
  exactIds(inventory.cases, 'Results')
  for (const result of inventory.cases) {
    const check = (condition, message) => requireValid(condition, `${result.id}: ${message}`)
    const definition = definitions.find(candidate => candidate.id === result.id)
    for (const field of ['section', 'input', 'acceptance']) check(result[field] === definition[field], `Source ${field} mismatch`)
    if (Object.hasOwn(result, 'criterionHash')) check(result.criterionHash === hash(definition.acceptance), 'Criterion hash mismatch')
    check(statuses.includes(result.status), 'Invalid status')
    check(modes.includes(result.mode), 'Invalid mode')
    check(typeof result.fixtureOnly === 'boolean', 'fixtureOnly must be explicit')
    check(Array.isArray(result.evidence) && result.evidence.every(item => nonempty(item?.path) && nonempty(item?.description)), 'Evidence requires path and description')
    check(Array.isArray(result.expectedEvidence) && result.expectedEvidence.every(nonempty), 'Invalid expectedEvidence')
    check(Array.isArray(result.toolCalls) && result.toolCalls.every(call => nonempty(call?.name) && Object.hasOwn(call, 'arguments') && Object.hasOwn(call, 'result')), 'Invalid toolCalls')
    check(result.revision === null || revision(result.revision), 'Invalid app revision')
    check(result.executedAt === null || timestamp(result.executedAt), 'Invalid execution timestamp')
    for (const field of ['model', 'fixtureVersion', 'actualAnswer', 'reviewer', 'reason']) check(result[field] === null || nonempty(result[field]), `Invalid ${field}`)
    check(result.latencyMs === null || (Number.isFinite(result.latencyMs) && result.latencyMs >= 0), 'Invalid latencyMs')
    check(result.cost && ['calls', 'tokens', 'amount'].every(field => result.cost[field] === null || (Number.isFinite(result.cost[field]) && result.cost[field] >= 0)), 'Invalid cost')
    check(['calls', 'tokens'].every(field => result.cost[field] === null || Number.isInteger(result.cost[field])), 'Calls/tokens must be integers')
    check(result.cost.currency === null || nonempty(result.cost.currency), 'Invalid currency')
    if (result.status === 'NOT_RUN') check(result.executedAt === null && result.evidence.length === 0, 'NOT_RUN cannot contain execution evidence')
    if (['FAIL', 'BLOCKED'].includes(result.status)) check(nonempty(result.reason), 'Failure/blocker reason required')
    if (['PASS', 'FAIL'].includes(result.status)) {
      check(revision(result.revision) && timestamp(result.executedAt), 'Executed results require revision and timestamp')
      check(nonempty(result.model) && nonempty(result.fixtureVersion) && nonempty(result.reviewer), 'Record model/version, fixture version (or explicit not-applicable), and reviewer')
      check(nonempty(result.actualAnswer) && result.expectedEvidence.length > 0, 'Record actual outcome and expected evidence')
    }
    if (result.status === 'PASS') {
      check(result.evidence.length > 0, 'PASS requires evidence')
      check(result.reason === null, 'PASS cannot retain failure/blocker reason')
      check(!result.fixtureOnly || result.mode === 'simulated', 'Fixture-only PASS must use simulated mode')
      if (result.id === 'E47') check(result.mode !== 'simulated' && !result.fixtureOnly && result.hardware?.arch === 'arm64' && result.hardware?.platform === 'darwin' && result.hardware?.actualDevice === true && nonempty(result.hardware?.description), 'E47 requires actual Apple Silicon Mac execution evidence')
    }
  }
  const byMode = Object.fromEntries(modes.map(mode => [mode, Object.fromEntries(statuses.map(status => [status, inventory.cases.filter(result => result.mode === mode && result.status === status).length]))]))
  const completed = byMode.real.PASS + byMode.manual.PASS
  return {
    total: 50,
    acceptancePassed: completed,
    simulatedPassed: byMode.simulated.PASS,
    failed: inventory.cases.filter(result => result.status === 'FAIL').length,
    blocked: inventory.cases.filter(result => result.status === 'BLOCKED').length,
    notRun: inventory.cases.filter(result => result.status === 'NOT_RUN').length,
    remaining: 50 - completed,
    allAcceptancePassed: completed === 50,
    byMode,
    limitation: 'Validates recorded claims, not evidence authenticity or semantic correctness. Self-tests and simulated passes do not complete acceptance cases.',
  }
}

async function main() {
  const [command = 'validate', argument, ...extra] = process.argv.slice(2)
  requireValid(extra.length === 0 && ['validate', 'gate', 'inventory'].includes(command), 'Usage: node scripts/expert-evaluation.mjs validate|gate [status.json] | inventory <full-app-revision> (prints JSON; does not overwrite files)')
  const markdown = await readFile(resolve(root, sourcePath), 'utf8')
  const result = command === 'inventory'
    ? createInventory(markdown, argument)
    : validateInventory(JSON.parse(await readFile(resolve(root, argument || 'docs/expert-evaluation-status.json'), 'utf8')), markdown)
  console.log(JSON.stringify(result, null, 2))
  if (command === 'gate' && !result.allAcceptancePassed) process.exitCode = 1
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => {
    console.error(error.message)
    process.exitCode = 1
  })
}
