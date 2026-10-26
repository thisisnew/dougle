import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { copyFile, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { allowed, artifactOptions, artifactSha256, candidateArtifactSha256, counts } from './conversation-ui-fixture.mjs'

test('candidate requires explicit paired path and digest without changing the .26 default', () => {
  assert.equal(artifactOptions([]).sha256, artifactSha256)
  const hash = candidateArtifactSha256
  assert.deepEqual(artifactOptions(['--jar', '/synthetic/candidate.jar', '--sha256', hash]), { jar: '/synthetic/candidate.jar', sha256: hash })
  assert.deepEqual(artifactOptions(['--sha256', hash, '--jar', '/synthetic/candidate.jar']), { jar: '/synthetic/candidate.jar', sha256: hash })
  assert.equal(artifactOptions(['--jar', '/synthetic/baseline.jar', '--sha256', artifactSha256]).sha256, artifactSha256)
  for (const args of [ ['--jar', '/synthetic/candidate.jar'], ['--sha256', hash],
    ['--jar', 'relative.jar', '--sha256', hash], ['--jar', '/synthetic/candidate.jar', '--sha256', 'unknown'],
    ['--jar', '/synthetic/candidate.jar', '--jar', '/other.jar'],
    ['--jar', '/synthetic/candidate.jar', '--java', '/unapproved/java'],
    ['--jar', '/synthetic/candidate.jar', '--sha256', 'a'.repeat(64)] ]) assert.throws(() => artifactOptions(args))
})

test('one fixed model handles plan and answer schemas; probes never count as generation', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'conversation-fixture-guard-'))
  try {
    const executable = join(directory, 'fake.sh'), schema = join(directory, 'schema.json'), output = join(directory, 'answer.json')
    await copyFile(new URL('./fixtures/fake-conversation-codex.sh', import.meta.url), executable)
    await writeFile(join(directory, 'conversation-fixture-only'), 'synthetic')
    const run = args => execFileSync('/bin/sh', [executable, ...args], {
      cwd: directory, input: 'Synthetic Strategy question', encoding: 'utf8', timeout: 3000,
      env: { HOME: directory, PATH: '/usr/bin:/bin' }, stdio: ['pipe', 'pipe', 'pipe'],
    })
    assert.match(run(['--version']), /conversation-fixture/)
    run(['login', 'status'])
    await assert.rejects(readFile(join(directory, 'generation.calls')), { code: 'ENOENT' })
    const args = ['--no-daemon', 'exec', '--model', 'gpt-6.1-sol', '--output-schema', schema, '--output-last-message', output]
    await writeFile(schema, JSON.stringify({ properties: { decision: { enum: ['SEARCH', 'STOP'] }, query: {}, candidate: {} } }))
    run(args)
    assert.deepEqual(JSON.parse(await readFile(output)), { decision: 'SEARCH', query: 'Strategy', candidate: null })
    await writeFile(schema, JSON.stringify({ properties: { claims: {}, evidenceIds: {} } }))
    run(args)
    assert.deepEqual(JSON.parse(await readFile(output)).claims[0].evidenceIds, ['R1'])
    assert.deepEqual(counts(await readFile(join(directory, 'generation.calls'), 'utf8')), { plan: 1, answer: 1 })
    assert.throws(() => run(args.map(value => value === 'gpt-6.1-sol' ? 'real-model' : value)))
    assert.throws(() => run(['app-server']))
    await writeFile(schema, JSON.stringify({ properties: { decision: {}, claims: {}, query: {} } }))
    assert.throws(() => run(args))
    assert.deepEqual(counts(await readFile(join(directory, 'generation.calls'), 'utf8')), { plan: 1, answer: 1 })
    await rm(join(directory, 'conversation-fixture-only'))
    assert.throws(() => run(['login', 'status']))
  } finally { await rm(directory, { recursive: true, force: true }) }
})

test('bridge permits only local conversation writes, not operational or authentication changes', () => {
  const workspace = 'synthetic-workspace'
  for (const suffix of ['chat-threads', 'investigation-plans/preview', 'investigation-plans/jobs',
    'investigation-plans/jobs/job/capture', 'evidence-bundles/bundle/answer-preview',
    'evidence-bundles/bundle/answer-approval', 'evidence-bundles/bundle/answer-jobs']) {
    assert.equal(allowed('POST', `/api/workspaces/${workspace}/${suffix}`, workspace), true)
  }
  for (const path of ['/api/ai/login', '/api/ai/launch-settings', '/api/plugins/mysql/execute',
    '/api/search-model/prepare', '/api/workspaces/other/chat-threads',
    `/api/workspaces/${workspace}/operational-captures/preview`]) {
    assert.equal(allowed('POST', path, workspace), false)
  }
  assert.equal(allowed('DELETE', `/api/workspaces/${workspace}`, workspace), false)
  assert.match(artifactSha256, /^[a-f0-9]{64}$/)
  assert.deepEqual(counts(''), { plan: 0, answer: 0 })
  assert.throws(() => counts('unknown'))
})
