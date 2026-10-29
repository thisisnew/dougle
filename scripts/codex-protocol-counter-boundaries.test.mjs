import assert from 'node:assert/strict'
import test from 'node:test'
import { ProtocolCounters } from './codex-protocol-counter.mjs'

const encode = value => Buffer.from(JSON.stringify(value) + '\n')
const threadId = 'private-thread-한글😀'
const turnId = 'private-turn-한글😀'
const bindings = [
  ['missing-thread', undefined, turnId, 'MISSING_THREAD'],
  ['invalid-thread', 7, turnId, 'INVALID_THREAD'],
  ['other-thread', 'private-other', turnId, 'OTHER_THREAD'],
  ['missing-turn', threadId, undefined, 'MISSING_TURN'],
  ['null-turn', threadId, null, 'MISSING_TURN'],
  ['invalid-turn', threadId, [], 'INVALID_TURN'],
  ['other-turn', threadId, 'private-other', 'OTHER_TURN'],
  ['matching', threadId, turnId, 'MATCH'],
]
const statuses = [
  [undefined, 'NONE'], ['completed', 'COMPLETED'], ['failed', 'FAILED'],
  ['interrupted', 'INTERRUPTED'], ['inProgress', 'IN_PROGRESS'], ['__proto__', 'OTHER'],
]

for (const [label, eventThread, eventTurn, expectedBinding] of bindings) {
  for (const [status, expectedStatus] of statuses) {
    for (const early of [false, true]) {
      for (let chunkSize = 1; chunkSize <= 56; chunkSize++) {
        test(`completion ${label}/${expectedStatus}/${early ? 'early' : 'bound'}/chunk-${chunkSize}`, () => {
          const counters = new ProtocolCounters()
          const feed = bytes => {
            for (let offset = 0; offset < bytes.length; offset += chunkSize) {
              counters.feed(bytes.subarray(offset, offset + chunkSize))
            }
          }
          for (const response of [
            { id: 1, result: {} }, { id: 2, result: {} },
            { id: 3, result: { thread: { id: threadId } } },
          ]) feed(encode(response))
          const turn = encode({ id: 4, result: { turn: { id: turnId } } })
          if (!early) feed(turn)
          const completion = encode({ method: 'turn/completed', params: {
            threadId: eventThread, turnId, turn: { id: eventTurn, status }, text: 'private-answer-한글😀',
          } })
          feed(completion.subarray(0, -1))
          const pending = counters.snapshot()
          assert.equal(pending.buffer.pendingLineBytes, completion.length - 1)
          assert.equal(Object.values(pending.applicationBinding.TURN_COMPLETED).reduce((sum, count) => sum + count, 0), 0)
          feed(completion.subarray(-1))
          if (early) {
            assert.equal(counters.snapshot().applicationBinding.TURN_COMPLETED.UNBOUND, 1)
            assert.equal(counters.snapshot().buffer.earlyPending, 1)
            feed(turn)
          }
          const snapshot = counters.snapshot()
          for (const view of ['applicationBinding', 'normalizedBinding']) {
            for (const [binding, count] of Object.entries(snapshot[view].TURN_COMPLETED)) {
              assert.equal(count, binding === expectedBinding ? 1 : 0)
            }
          }
          for (const prefix of ['APPLICATION', 'NORMALIZED']) {
            for (const disposition of ['MATCH', 'IGNORED']) {
              for (const [category, count] of Object.entries(snapshot.completionStatus[`${prefix}_${disposition}`])) {
                assert.equal(count, category === expectedStatus && disposition === (expectedBinding === 'MATCH' ? 'MATCH' : 'IGNORED') ? 1 : 0)
              }
            }
          }
          assert.deepEqual(snapshot.buffer, { pendingLineBytes: 0, earlyPending: 0 })
          assert.deepEqual(snapshot.responses, { INITIALIZE: 1, CONFIG: 1, THREAD: 1, TURN: 1, UNEXPECTED: 0, INVALID: 0 })
          assert.equal(snapshot.totals.lines, 5)
          assert.equal(snapshot.totals.invalidJson, 0)
          assert.doesNotMatch(JSON.stringify(snapshot), /private|한글|😀|__proto__/)
        })
      }
    }
  }
}
