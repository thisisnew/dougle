import assert from 'node:assert/strict'
import test from 'node:test'
import { proposalCases, proposalFields } from './expert-live-proposal-fixtures.mjs'

test('six unique synthetic questions require three proposals of each kind', () => {
  assert.equal(proposalCases.length, 6)
  assert.equal(new Set(proposalCases.map(fixture => fixture.question)).size, 6)
  for (const kind of ['MYSQL_READ', 'DATADOG_LOGS']) assert.equal(proposalCases.filter(fixture => fixture.kind === kind).length, 3)
  for (const fixture of proposalCases) {
    assert.match(fixture.question, /가상|합성/)
    assert.match(fixture.question, /수동/)
    assert.ok(fixture.question.length <= 256)
    if (fixture.kind === 'MYSQL_READ') assert.match(fixture.question, /현재/)
    else assert.match(fixture.question, /Datadog.*synthetic.*receipt-demo.*2026-10-25T/s)
  }
})

test('proposal field names are fixed; no inferred parameter values', () => {
  assert.deepEqual(proposalFields('MYSQL_READ'), ['table', 'columns', 'equalities', 'limit'])
  assert.deepEqual(proposalFields('DATADOG_LOGS'), ['environment', 'service', 'indexes', 'from', 'to', 'limit'])
  assert.throws(() => proposalFields('UNKNOWN'))
})
