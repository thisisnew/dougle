import assert from 'node:assert/strict'
import test from 'node:test'
import { routingBoundaryLedger } from './acceptance-boundary-results.mjs'

const checks = () => Array.from({ length: 100 }, (_, index) => ({ id: 'T' + String(index + 1).padStart(4, '0'), status: 'PASS' }))

test('100 passing boundary checks cannot be promoted to 100 full acceptance passes', () => {
  const ledger = routingBoundaryLedger(checks())
  assert.equal(ledger.length, 1000)
  assert.equal(ledger.filter(item => item.status === 'PARTIAL').length, 100)
  assert.equal(ledger.filter(item => item.status === 'NOT_RUN').length, 900)
  assert(ledger.every(item => item.fullAcceptancePassed === false))
})

test('failed boundary checks remain failures and incomplete or duplicate runs are rejected', () => {
  const rows = checks(); rows[58].status = 'FAIL'
  assert.equal(routingBoundaryLedger(rows)[58].status, 'FAIL')
  assert.throws(() => routingBoundaryLedger(rows.slice(1)))
  rows[1] = rows[0]
  assert.throws(() => routingBoundaryLedger(rows))
})
