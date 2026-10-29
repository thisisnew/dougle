import assert from 'node:assert/strict'
import { constants } from 'node:fs'
import { open, mkdir, rmdir } from 'node:fs/promises'
import { isAbsolute } from 'node:path'

function validateGrant(grant) {
  assert.match(grant.approvalId, /^[A-Za-z0-9_-]{1,120}$/)
  assert.match(grant.jarSha256, /^[a-f0-9]{64}$/)
  assert(Number.isSafeInteger(grant.limit) && grant.limit > 0)
}

export async function initializeBudget(path, grant) {
  validateGrant(grant)
  assert(isAbsolute(path))
  const file = await open(path, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL, 0o600)
  try {
    await file.writeFile(JSON.stringify({ version: 1, ...grant }) + '\n')
    await file.sync()
  } finally { await file.close() }
}

export async function reservePersistentCall(path, grant, call) {
  validateGrant(grant)
  assert(isAbsolute(path))
  assert(['plan', 'answer'].includes(call.kind))
  assert.match(call.requestId, /^[A-Za-z0-9_-]{1,120}$/)
  const lock = path + '.lock'
  await mkdir(lock, { mode: 0o700 })
  try {
    const file = await open(path, constants.O_RDWR | constants.O_APPEND | constants.O_NOFOLLOW)
    try {
      const stat = await file.stat()
      assert(stat.isFile() && stat.size < 1024 * 1024, 'Invalid budget journal')
      const content = await file.readFile('utf8')
      assert(content.endsWith('\n'), 'Incomplete budget reservation; reconcile manually')
      const [header, ...entries] = content.trimEnd().split('\n').map(line => JSON.parse(line))
      assert.deepEqual(header, { version: 1, ...grant }, 'Approval or artifact mismatch')
      const ids = new Set()
      for (const [index, entry] of entries.entries()) {
        assert.equal(entry.sequence, index + 1)
        assert(['plan', 'answer'].includes(entry.kind))
        assert.match(entry.requestId, /^[A-Za-z0-9_-]{1,120}$/)
        assert(!ids.has(entry.requestId), 'Duplicate journal entry')
        ids.add(entry.requestId)
      }
      assert(entries.length < grant.limit, 'Approved persistent budget exhausted')
      assert(!ids.has(call.requestId), 'Previously reserved call; no automatic retry')
      const entry = { sequence: entries.length + 1, kind: call.kind, requestId: call.requestId }
      await file.writeFile(JSON.stringify(entry) + '\n')
      await file.sync()
      return entry.sequence
    } finally { await file.close() }
  } finally { await rmdir(lock) }
}
