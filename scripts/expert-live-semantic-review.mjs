import assert from 'node:assert/strict'
import { createHash, randomUUID } from 'node:crypto'
import { constants } from 'node:fs'
import { chmod, mkdtemp, open, rm, writeFile } from 'node:fs/promises'
import { join } from 'node:path'

export async function requireSemanticReview({ ownedDirectory, result, signal, timeoutMs = 900000, notify = () => {}, pollMs = 200 }) {
  assert.ok(Number.isSafeInteger(timeoutMs) && timeoutMs > 0 && timeoutMs <= 900000)
  const directory = await mkdtemp(join(ownedDirectory, 'semantic-review-'))
  const checkpointId = randomUUID()
  const resultSha256 = createHash('sha256').update(JSON.stringify(result)).digest('hex')
  const expiresAt = Date.now() + timeoutMs
  const acknowledgmentPath = join(directory, 'ack.json')
  try {
    await chmod(directory, 0o700)
    const controlPath = join(directory, 'review.json')
    await writeFile(controlPath, JSON.stringify({ checkpointId, resultSha256, expiresAt, acknowledgmentPath, result }), { mode: 0o600, flag: 'wx' })
    let timer
    try {
      await Promise.race([Promise.resolve().then(() => notify({ controlPath, checkpointId, expiresAt })),
        new Promise((resolve, reject) => { timer = setTimeout(() => reject(Error('Semantic notification expired')), Math.max(0, expiresAt - Date.now())) })])
    } finally { clearTimeout(timer) }
    while (Date.now() < expiresAt) {
      signal?.throwIfAborted()
      let file
      try { file = await open(acknowledgmentPath, constants.O_RDONLY | constants.O_NOFOLLOW) }
      catch (error) { if (error.code !== 'ENOENT') throw Error('Unsafe semantic acknowledgment') }
      if (file) {
        let review
        try {
          const stat = await file.stat()
          assert.ok(stat.isFile() && stat.uid === process.getuid() && (stat.mode & 0o077) === 0 && stat.size <= 4096)
          const buffer = Buffer.alloc(4097)
          const { bytesRead } = await file.read(buffer, 0, buffer.length, 0)
          assert.ok(bytesRead <= 4096)
          review = JSON.parse(buffer.subarray(0, bytesRead).toString('utf8'))
        } finally { await file.close() }
        assert.deepEqual(Object.keys(review).sort(), ['checkpointId', 'resultSha256', 'verdict', 'reviewer', 'note'].sort())
        assert.equal(review.checkpointId, checkpointId)
        assert.equal(review.resultSha256, resultSha256)
        assert.ok(Date.now() < expiresAt)
        assert.ok(['AGENT', 'HUMAN'].includes(review.reviewer))
        assert.ok(typeof review.note === 'string' && review.note.trim().length > 0 && review.note.length <= 1000)
        assert.equal(review.verdict, 'PASS', 'Semantic review failed or uncertain; no next dispatch')
        return review
      }
      await new Promise(resolve => setTimeout(resolve, Math.min(pollMs, Math.max(0, expiresAt - Date.now()))))
    }
    throw Error('Semantic review expired; no next dispatch')
  } finally { await rm(directory, { recursive: true, force: true }) }
}
