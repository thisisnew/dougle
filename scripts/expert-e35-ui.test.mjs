import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { once } from 'node:events'
import test from 'node:test'
import { allowedRequest, artifactSha256, createBridge, fixtures, observations, validateAcknowledgment } from './expert-e35-ui.mjs'

const workspaceIds = ['synthetic-a', 'synthetic-b']
const url = path => new URL(path, 'http://127.0.0.1:1234')

test('synthetic legacy fixture has distinct exact bodies, no credentials, and escaped SQL', () => {
  const fixture = fixtures(workspaceIds[0])
  assert.deepEqual(fixture.receipts.map(value => value.status), ['PREVIEW', 'SENT', 'UNCERTAIN'])
  assert.equal(new Set(fixture.receipts.map(value => value.body)).size, 3)
  assert(fixture.sql.includes("''quoted''"))
  assert(fixture.sql.includes('INSERT INTO slack_inbox_item'))
  assert.equal(fixture.item.sent, null)
  assert.throws(() => fixtures("bad';DROP TABLE workspace;"))
})

test('receipt-only routing rejects business mutation, arbitrary workspace, URL/query escape', () => {
  for (const method of ['POST', 'PUT', 'PATCH', 'DELETE']) {
    for (const path of ['/api/slack/connection', '/api/slack/workspaces/synthetic-a/replies/preview',
      '/api/slack/workspaces/synthetic-a/replies/synthetic-legacy-sent/execute',
      '/api/slack/workspaces/synthetic-a/replies/synthetic-legacy-uncertain/acknowledge-uncertain',
      '/api/workspaces/synthetic-a/chat-threads', '/api/plugins/mysql']) {
      assert.equal(allowedRequest(method, url(path), workspaceIds), 'DENY')
    }
  }
  for (const path of ['/api/workspaces/real-user/active', '/api/session?url=https://slack.com',
    '/api/slack/workspaces/synthetic-a/replies?inboxItemId=other', '/api/../private', '/assets/../../private']) {
    assert.equal(allowedRequest('GET', url(path), workspaceIds), 'DENY')
  }
  assert.equal(allowedRequest('PUT', url('/api/workspaces/synthetic-a/active'), workspaceIds), 'LOCAL_SWITCH')
  assert.equal(allowedRequest('POST', url('/api/ai/providers/CODEX/connection'), workspaceIds), 'BLOCK_CONNECTION')
  assert.equal(allowedRequest('GET', url('/api/slack/workspaces/synthetic-a/replies?inboxItemId=synthetic-inbox'), workspaceIds), 'READ')
})

test('manual acknowledgment requires exact independent complete unexpired checkpoint', () => {
  const checkpoint = { checkpointId: 'checkpoint', artifactSha256, phase: 'restart', expiresAt: 100 }
  const ack = { checkpointId: 'checkpoint', artifactSha256, phase: 'restart', verdict: 'PASS', reviewer: 'PARENT', observed: observations, screenshotPath: 'docs/screenshots/e35-restart.png' }
  validateAcknowledgment(ack, checkpoint, 99)
  for (const change of [{ checkpointId: 'old' }, { artifactSha256: 'f'.repeat(64) }, { phase: 'initial' }, { verdict: 'FAIL' },
    { reviewer: 'AUTOMATION' }, { observed: [] }, { screenshotPath: '../private.png' }, { extra: true }]) {
    assert.throws(() => validateAcknowledgment({ ...ack, ...change }, checkpoint, 99))
  }
  assert.throws(() => validateAcknowledgment(ack, checkpoint, 100))
})

test('bridge forwards only narrow reads and bodyless local switch, blocks AI check without forwarding', async () => {
  const received = []
  const upstream = createServer((request, response) => {
    received.push({ method: request.method, path: request.url, session: request.headers['x-forge-session'] })
    response.writeHead(200, { 'Content-Type': 'application/json' }); response.end('{}')
  })
  upstream.listen(0, '127.0.0.1'); await once(upstream, 'listening')
  const session = 'a'.repeat(64)
  const bridge = await createBridge({ backend: `http://127.0.0.1:${upstream.address().port}`, session, workspaceIds })
  try {
    const response = await fetch(bridge.origin + '/api/session')
    assert.equal(response.status, 200)
    assert(response.headers.get('content-security-policy').includes("connect-src 'self'"))
    assert.equal(received[0].session, session)
    assert(!JSON.stringify(bridge.trace).includes(session))
    assert.equal((await fetch(bridge.origin + '/api/ai/providers/CODEX/connection', { method: 'POST' })).status, 409)
    assert.equal(received.length, 1)
    bridge.check()
    assert.throws(() => bridge.verify())
    assert.equal((await fetch(bridge.origin + '/api/session', { headers: { Origin: 'https://evil.invalid' } })).status, 403)
    for (const id of workspaceIds) {
      await fetch(bridge.origin + `/api/workspaces/${id}/active`, { method: 'PUT' })
      await fetch(bridge.origin + `/api/slack/workspaces/${id}/inbox`)
    }
    for (const status of ['preview', 'sent', 'uncertain']) await fetch(bridge.origin + `/api/slack/workspaces/synthetic-a/replies/synthetic-legacy-${status}`)
    bridge.verify()
    const before = received.length
    assert.equal((await fetch(bridge.origin + '/api/workspaces/synthetic-a/active', { method: 'PUT', body: '{}' })).status, 403)
    assert.equal((await fetch(bridge.origin + '/api/slack/workspaces/synthetic-a/replies/preview', { method: 'POST' })).status, 403)
    assert.equal(received.length, before)
    assert.throws(() => bridge.check())
  } finally {
    await bridge.close(); upstream.closeAllConnections(); await new Promise(done => upstream.close(done))
  }
})

test('bridge cannot target external or ambiguous destinations', async () => {
  for (const backend of ['https://slack.com', 'http://localhost:1234', 'http://secret@127.0.0.1:1234', 'http://127.0.0.1:1234/?url=external']) {
    await assert.rejects(createBridge({ backend, session: 'a'.repeat(64), workspaceIds }))
  }
})
