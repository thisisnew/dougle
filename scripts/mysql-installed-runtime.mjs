import assert from 'node:assert/strict'
import { createHash, randomBytes } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve, isAbsolute } from 'node:path'

export async function verifyInstalledMysql({ resources, expectedHash, reportPath, root, port, password, launch, stop }) {
  assert(isAbsolute(resources) && isAbsolute(reportPath))
  assert.equal(expectedHash, '97c02f213455511766c475eeb5d934cac50614b7b0d864726b6213a76d36dcbc')
  const jar = resolve(resources, 'dougle.jar')
  const hash = async path => createHash('sha256').update(await readFile(path)).digest('hex')
  assert.equal(await hash(jar), expectedHash)
  const home = resolve(root, 'app')
  await mkdir(home)
  const token = randomBytes(32).toString('hex')
  const checks = []
  const startedAt = new Date().toISOString()
  let child, base
  async function start() {
    child = launch(resolve(resources, 'runtime/bin/java'), ['-Duser.home=' + home, '-jar', jar,
      '--server.address=127.0.0.1', '--server.port=0', '--forge.desktop=true', '--forge.data-dir=' + resolve(home, 'data'),
      '--forge.ai.codex-command=' + resolve(home, 'disabled-codex'), '--forge.ai.claude-command=' + resolve(home, 'disabled-claude')], token + '\n',
      { dataDirectory: resolve(home, 'data'), keepInputOpen: true })
    base = null
    for (let attempt = 0; attempt < 300; attempt++) {
      assert(child.exitCode === null && child.signalCode === null,
        'Owned installed runtime exited: ' + child.output().replaceAll(password, '[DB_PASSWORD]').replaceAll(token, '[SESSION]').slice(-2000))
      const ready = child.output().match(/^FORGE_DESKTOP_READY:(\d+)$/m)
      if (ready) { base = 'http://127.0.0.1:' + ready[1] + '/api'; return }
      await new Promise(done => setTimeout(done, 200))
    }
    throw Error('Owned installed runtime startup timed out')
  }
  async function api(path, method = 'GET', body, expected = 200) {
    const response = await fetch(base + path, { method, headers: { 'X-Forge-Session': token, 'X-Forge-Client': '1', 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30000) })
    const text = await response.text()
    assert(!text.includes(password), 'Generated DB password leaked in API response')
    assert.equal(response.status, expected, method + ' ' + path + ' status mismatch')
    return text ? JSON.parse(text) : null
  }
  const config = { host: '127.0.0.1', port, database: 'fixture', username: 'fixture_reader', password, sslMode: 'REQUIRED' }
  const query = { table: 'items', columns: ['id', 'label'], equalities: { id: '1' }, limit: 1 }
  try {
    await start()
    assert.equal((await fetch(base + '/session')).status, 403)
    assert.equal((await api('/plugins/mysql', 'POST', config)).mysqlConnected, true)
    const owner = await api('/workspaces', 'POST', { name: 'DB owner' }, 201)
    const other = await api('/workspaces', 'POST', { name: 'DB other' }, 201)
    const prefix = '/workspaces/' + owner.id + '/mysql-investigations'
    const foreign = '/workspaces/' + other.id + '/mysql-investigations'
    const preview = await api(prefix + '/preview', 'POST', query)
    await api(foreign + '/' + preview.id, 'GET', undefined, 404)
    await api(foreign + '/' + preview.id + '/execute', 'POST', { requestId: 'synthetic-foreign', approved: true }, 404)
    await api(foreign + '/' + preview.id + '/cancel', 'POST', undefined, 404)
    await api(prefix + '/' + preview.id + '/execute', 'POST', { requestId: 'synthetic-not-approved', approved: false }, 403)
    checks.push('session-required', 'real-mysql-plugin-connect', 'foreign-preview-result-denied', 'foreign-execution-denied', 'foreign-cancel-denied', 'explicit-consent-required')
    const run = { requestId: 'synthetic-owned-read', approved: true }
    const result = await api(prefix + '/' + preview.id + '/execute', 'POST', run)
    assert.equal(result.status, 'SUCCEEDED'); assert.equal(result.rows, 1); assert(result.output.includes('synthetic healthy'))
    assert.deepEqual(await api(prefix + '/' + preview.id + '/execute', 'POST', run), result)
    await api(prefix + '/' + preview.id + '/execute', 'POST', { ...run, requestId: 'synthetic-other-request' }, 409)
    await api(foreign + '/' + preview.id, 'GET', undefined, 404)
    for (const invalid of [{ ...query, table: 'items; DELETE FROM items' }, { ...query, columns: ['password'] },
      { ...query, equalities: {} }, { ...query, sql: 'DELETE FROM items' }]) await api(prefix + '/preview', 'POST', invalid, 400)
    assert.deepEqual(await api(prefix + '/' + preview.id), result)
    await api('/plugins/mysql', 'POST', { ...config, password: password + '-wrong' }, 400)
    assert.deepEqual(await api(prefix + '/' + preview.id), result)
    const injection = await api(prefix + '/preview', 'POST', { ...query, equalities: { label: "x' OR '1'='1" } })
    const empty = await api(prefix + '/' + injection.id + '/execute', 'POST', { requestId: 'synthetic-injection', approved: true })
    assert.equal(empty.rows, 0)
    assert.equal(empty.status, 'EMPTY')
    checks.push('failed-login-preserves-existing-binding', 'predicate-injection-remains-data')
    checks.push('real-owned-row', 'same-request-reconciles', 'new-request-cannot-reuse-consent', 'populated-foreign-result-denied', 'unsafe-query-shapes-denied', 'result-unchanged-after-denials')
    const cancelled = await api(prefix + '/preview', 'POST', query)
    await api(prefix + '/' + cancelled.id + '/cancel', 'POST', undefined, 204)
    await api(prefix + '/' + cancelled.id + '/execute', 'POST', { requestId: 'synthetic-cancelled', approved: true }, 409)
    await api('/plugins/mysql', 'DELETE')
    await api(prefix + '/' + preview.id, 'GET', undefined, 409)
    await api('/plugins/mysql', 'POST', config)
    await api(prefix + '/' + preview.id, 'GET', undefined, 409)
    await api(prefix + '/' + preview.id + '/execute', 'POST', run, 409)
    checks.push('cancelled-preview-cannot-execute', 'disconnect-revokes-result', 'reconnect-does-not-revive-result', 'reconnect-does-not-revive-consent')
    await stop(child)
    await start()
    assert.equal((await api('/plugins')).mysqlConnected, true)
    await api(prefix + '/' + preview.id, 'GET', undefined, 404)
    const fresh = await api(prefix + '/preview', 'POST', query)
    const restored = await api(prefix + '/' + fresh.id + '/execute', 'POST', { requestId: 'synthetic-after-restart', approved: true })
    assert.equal(restored.status, 'SUCCEEDED'); assert.equal(restored.output, result.output)
    for (const workspace of [owner, other]) assert.deepEqual(await api('/workspaces/' + workspace.id + '/answer-jobs'), [])
    checks.push('connection-restored-on-restart', 'volatile-result-not-claimed-restored', 'fresh-consent-read-after-restart', 'no-ai-answer-dispatch')
    assert.equal(await hash(jar), expectedHash)
    await stop(child)
    await writeFile(reportPath, JSON.stringify({ fixtureVersion: 'installed-mysql-isolation-v1', jarSha256: expectedHash, startedAt,
      finishedAt: new Date().toISOString(), checks: checks.map(id => ({ id, status: 'PASS' })), externalAiCalls: 0,
      scope: 'Actual installed HTTP services and synthetic SELECT-only local MySQL; no company database or AI use. UI and full investigation acceptance are separate.' }, null, 2) + '\n', { flag: 'wx', mode: 0o600 })
    console.log('PASS installed MySQL HTTP boundaries: ' + checks.length + ' checks; no AI calls')
  } finally { if (child) await stop(child) }
}
