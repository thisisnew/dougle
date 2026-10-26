import assert from 'node:assert/strict'
import test from 'node:test'
import { mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { requireSemanticReview } from './expert-live-semantic-review.mjs'

async function fixture(run) {
  const ownedDirectory = await mkdtemp(join(tmpdir(), 'semantic-review-test-'))
  try { await run(ownedDirectory) }
  finally { await rm(ownedDirectory, { recursive: true, force: true }) }
}

test('semantic review binds exact raw result, privately and without advancing before acknowledgment', async () => fixture(async ownedDirectory => {
  let observed = false
  const review = await requireSemanticReview({ ownedDirectory, result: { answer: 'synthetic VEGA production', turn: 31 },
    timeoutMs: 2000, pollMs: 1, notify: async ({ controlPath }) => {
      assert.equal((await stat(controlPath)).mode & 0o777, 0o600)
      const checkpoint = JSON.parse(await readFile(controlPath, 'utf8'))
      assert.equal(checkpoint.result.turn, 31)
      observed = true
      await writeFile(checkpoint.acknowledgmentPath, JSON.stringify({ checkpointId: checkpoint.checkpointId,
        resultSha256: checkpoint.resultSha256, verdict: 'PASS', reviewer: 'AGENT', note: 'Synthetic answer and evidence reviewed.' }), { mode: 0o600 })
    } })
  assert.equal(observed, true)
  assert.equal(review.reviewer, 'AGENT')
  assert.deepEqual(await readdir(ownedDirectory), [])
}))

test('failed, uncertain, stale and unsafe semantic acknowledgments stop and clean controls', async () => fixture(async ownedDirectory => {
  for (const mode of ['FAIL', 'UNCERTAIN', 'STALE', 'UNSAFE']) {
    let advanced = false
    await assert.rejects((async () => {
      await requireSemanticReview({ ownedDirectory, result: { answer: 'synthetic' }, timeoutMs: 2000, pollMs: 1,
        notify: async ({ controlPath }) => {
          const checkpoint = JSON.parse(await readFile(controlPath, 'utf8'))
          await writeFile(checkpoint.acknowledgmentPath, JSON.stringify({ checkpointId: checkpoint.checkpointId,
            resultSha256: mode === 'STALE' ? 'old' : checkpoint.resultSha256,
            verdict: ['FAIL', 'UNCERTAIN'].includes(mode) ? mode : 'PASS', reviewer: 'AGENT', note: 'Review.' }),
          { mode: mode === 'UNSAFE' ? 0o644 : 0o600 })
        } })
      advanced = true
    })())
    assert.equal(advanced, false)
    assert.deepEqual(await readdir(ownedDirectory), [])
  }
}))

test('semantic notification/error/expiry and cancellation never grant another dispatch', async () => fixture(async ownedDirectory => {
  for (const mode of ['timeout', 'notify-error', 'hung-notify', 'abort']) {
    const controller = new AbortController()
    await assert.rejects(requireSemanticReview({ ownedDirectory, result: {}, timeoutMs: 30, pollMs: 1,
      signal: controller.signal, notify: () => {
        if (mode === 'notify-error') throw Error('synthetic notifier error')
        if (mode === 'hung-notify') return new Promise(() => {})
        if (mode === 'abort') controller.abort()
      } }))
    assert.deepEqual(await readdir(ownedDirectory), [])
  }
}))
