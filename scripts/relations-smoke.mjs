import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { mkdtemp, rm } from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const temporary = await mkdtemp(resolve(tmpdir(), 'forge-relations-smoke-'))
const reservation = createServer().listen(0, '127.0.0.1')
await once(reservation, 'listening')
const port = reservation.address().port
await new Promise(done => reservation.close(done))
const base = `http://127.0.0.1:${port}`
const child = spawn('bash', ['scripts/run.sh'], { cwd: root, env: { ...process.env, PORT: String(port), FORGE_DATA_DIR: temporary, FORGE_DESKTOP: 'false' }, stdio: ['ignore', 'pipe', 'pipe'] })
let logs = ''
child.stdout.on('data', chunk => { logs += chunk })
child.stderr.on('data', chunk => { logs += chunk })
async function api(path, method = 'GET', body) {
  const multipart = body instanceof FormData
  const response = await fetch(`${base}/api${path}`, { method, headers: { 'X-Forge-Client': '1', ...(multipart ? {} : { 'Content-Type': 'application/json' }) }, body: multipart ? body : body ? JSON.stringify(body) : undefined })
  assert.equal(response.ok, true, `${response.status} ${await response.clone().text()}`)
  return response.json()
}
try {
  let ready = false
  for (let attempt = 0; attempt < 180; attempt++) {
    assert.equal(child.exitCode, null, logs)
    try { await api('/session'); ready = true; break } catch { await new Promise(done => setTimeout(done, 500)) }
  }
  assert.ok(ready, logs)
  const workspace = await api('/workspaces', 'POST', { name: 'Synthetic relations QA' })
  const path = `/workspaces/${workspace.id}`
  const uploaded = []
  for (const name of ['Research', 'Other']) {
    const form = new FormData()
    form.append('file', new Blob([`class ${name} { void syntheticObservation() {} }`]), `${name}.java`)
    uploaded.push(await api(`${path}/files`, 'POST', form))
  }
  const cards = []
  for (const title of ['First publication', 'Second publication']) {
    const card = await api(`${path}/knowledge`, 'POST', { title, markdown: 'Synthetic observation [E1]', citations: [{ entryId: uploaded[0].id, startLine: 1, endLine: 1 }] })
    const cardPath = `${path}/knowledge/${card.card.id}`
    await api(`${cardPath}/review`, 'POST', { expectedVersion: 1, reviewer: 'Synthetic reviewer', note: 'checked fixture', claimsSupported: true, uncertaintyRemoved: true })
    await api(`${cardPath}/publication`, 'POST', { expectedVersion: 1, expectedPublicationRevision: 0 })
    cards.push(card.card.id)
  }
  const original = await api(`${path}/relations?kind=ORIGINAL&id=${uploaded[0].id}`)
  assert.equal(original.items.length, 3)
  assert.ok(original.items.some(item => item.target.relation === 'SAME_SYMBOL'))
  const published = await api(`${path}/relations?kind=KNOWLEDGE&id=${cards[0]}&version=1`)
  assert.equal(published.items.length, 2)
  assert.ok(published.items.some(item => item.target.id === cards[1]))
  console.log(`PASS: actual JAR symbol/citation relations, published version, read-only API; no AI. Browser page: ${base}`)
  if (process.argv.includes('--browser')) {
    console.log('Holding isolated fixture for browser QA; interrupt this process to clean up.')
    await new Promise(done => { process.once('SIGINT', done); process.once('SIGTERM', done) })
  }
} finally {
  if (child.exitCode === null && child.signalCode === null) {
    const exited = once(child, 'exit')
    child.kill('SIGTERM')
    const timer = setTimeout(() => child.kill('SIGKILL'), 15000)
    await exited
    clearTimeout(timer)
  }
  await rm(temporary, { recursive: true, force: true })
}
