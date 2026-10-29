import assert from 'node:assert/strict'
import test from 'node:test'
import { spawnSync } from 'node:child_process'
import { artifactSha256, knownArtifacts, contextApiAllowed, documents, scenarios, validateContextConfiguration, validatePinnedRuntimeConfiguration, isolatedRuntimeSettings } from './expert-live-context-fixtures.mjs'
import { scheduledDispatches } from './expert-live-schedule.mjs'

test('four purposeful turns fit eight dispatches and restart after correction/followup', () => {
  assert.equal(scheduledDispatches(scenarios, 0, 1, 2), 8)
  assert.equal(scenarios[0].restartBeforeTurn, 3)
  assert(scenarios[0].questions[0][1].includes('BLUE가 아니라 AMBER'))
  assert(scenarios[0].questions[0][2].includes('그 배지'))
  assert(!scenarios[0].questions[0][2].includes('AMBER'))
  assert(Object.values(documents)[0].includes('현재 선택한 대상은 문서가 아니라 대화'))
})

test('context batch refuses unpinned resources, changed budget or protocol instrumentation', () => {
  const environment = { FORGE_EXPERT_JAR_SHA256: artifactSha256, FORGE_EXPERT_READONLY_ARTIFACT_SHA256: artifactSha256,
    FORGE_EXPERT_RESOURCES: '/synthetic/bundle', FORGE_EXPERT_JAR: '/synthetic/app.jar', FORGE_EXPERT_SEARCH_MODELS: '/synthetic/models', FORGE_EXPERT_PROFILE: '/synthetic/profile' }
  validateContextConfiguration(environment, 8, 1, 0)
  for (const change of [{ FORGE_EXPERT_JAR_SHA256: 'old' }, { FORGE_EXPERT_PROFILE: '' }, { FORGE_EXPERT_RESOURCES: '../private' }, { FORGE_EXPERT_PROTOCOL_COUNTER: 'approved' }]) {
    assert.throws(() => validateContextConfiguration({ ...environment, ...change }, 8, 1, 0))
  }
  assert.throws(() => validateContextConfiguration(environment, 10, 1, 0))
})

test('context route allowlist excludes operational providers, external URLs and execution', () => {
  for (const path of ['/workspaces/synthetic/mysql-investigations/preview', '/workspaces/synthetic/datadog-investigations/job/execute',
    '/workspaces/synthetic/browser-investigations/jobs', '/plugins/mysql', '/slack/connection',
    '/workspaces/synthetic/operational-captures/preview', '//evil.invalid/path', '/workspaces/synthetic/investigation-plans/jobs/id/capture']) {
    for (const method of ['GET', 'POST', 'PUT', 'DELETE']) assert.equal(contextApiAllowed(path, method), false)
  }
  assert(contextApiAllowed('/workspaces/synthetic/investigation-plans/jobs?bundleId=bundle', 'GET'))
  assert(contextApiAllowed('/workspaces/synthetic/evidence-bundles/bundle/answer-jobs', 'POST'))
})

test('artifact allowlist accepts only explicitly verified releases with matching approval and unchanged finite guards', () => {
  assert.deepEqual(Object.values(knownArtifacts), ['0.1.25', '0.1.26', '0.1.38'])
  assert(Object.isFrozen(knownArtifacts))
  const environment = { FORGE_EXPERT_RESOURCES: '/synthetic/bundle', FORGE_EXPERT_JAR: '/synthetic/app.jar',
    FORGE_EXPERT_SEARCH_MODELS: '/synthetic/models', FORGE_EXPERT_PROFILE: '/synthetic/profile' }
  for (const pin of Object.keys(knownArtifacts)) {
    const approved = { ...environment, FORGE_EXPERT_JAR_SHA256: pin, FORGE_EXPERT_READONLY_ARTIFACT_SHA256: pin }
    validateContextConfiguration(approved, 8, 1, 0)
    assert.throws(() => validateContextConfiguration({ ...approved, FORGE_EXPERT_READONLY_ARTIFACT_SHA256: 'a'.repeat(64) }, 8, 1, 0))
    assert.throws(() => validateContextConfiguration({ ...approved, FORGE_EXPERT_PROTOCOL_COUNTER: 'approved' }, 8, 1, 0))
    for (const schedule of [[10, 1, 0], [8, 2, 0], [8, 1, 1]]) assert.throws(() => validateContextConfiguration(approved, ...schedule))
  }
  for (const pin of ['a'.repeat(64), 'toString', '__proto__', '', undefined]) {
    assert.throws(() => validateContextConfiguration({ ...environment, FORGE_EXPERT_JAR_SHA256: pin,
      FORGE_EXPERT_READONLY_ARTIFACT_SHA256: pin }, 8, 1, 0))
  }
})

test('pinned memory runtime requires every explicit path without implicit HOME or build defaults', () => {
  const pin = '97c02f213455511766c475eeb5d934cac50614b7b0d864726b6213a76d36dcbc'
  const paths = { FORGE_EXPERT_RESOURCES: '/synthetic/bundle', FORGE_EXPERT_JAR: '/synthetic/app.jar',
    FORGE_EXPERT_SEARCH_MODELS: '/synthetic/models', FORGE_EXPERT_PROFILE: '/synthetic/profile' }
  const environment = { ...paths, FORGE_EXPERT_JAR_SHA256: pin, FORGE_EXPERT_READONLY_ARTIFACT_SHA256: pin }
  validatePinnedRuntimeConfiguration(environment)
  for (const key of Object.keys(paths)) {
    for (const value of [undefined, '', 'relative/path', '~/profile', '/synthetic/../private', '/bad\0path']) {
      assert.throws(() => validatePinnedRuntimeConfiguration({ ...environment, [key]: value }), /Explicit resource path/)
    }
  }
  assert.throws(() => validatePinnedRuntimeConfiguration({ ...environment, FORGE_EXPERT_JAR_SHA256: 'a'.repeat(64) }), /Explicit known artifact/)
  assert.throws(() => validatePinnedRuntimeConfiguration({ ...environment, FORGE_EXPERT_READONLY_ARTIFACT_SHA256: 'b'.repeat(64) }))
})

test('isolated runtime settings reject unsafe paths and preserve Unicode and quoting as literal arguments', () => {
  const home = "/synthetic/새 Mac's home 😀"
  const directory = '/synthetic/private data'
  const settings = isolatedRuntimeSettings(home, directory)
  assert.deepEqual(settings.args, [`-Duser.home=${home}`, `-Djava.io.tmpdir=${directory}`])
  assert.deepEqual(Object.keys(settings.env).sort(), ['FORGE_DATA_DIR', 'FORGE_DESKTOP', 'HOME', 'LANG', 'PATH', 'TMPDIR'])
  for (const invalid of ['', '../parent', '/tmp/../user', '/bad\0path', undefined]) {
    assert.throws(() => isolatedRuntimeSettings(invalid, directory))
    assert.throws(() => isolatedRuntimeSettings(home, invalid))
  }
})

test('actual child process receives only isolated environment despite poisoned parent credentials and runtime flags', () => {
  const helper = new URL('./expert-live-context-fixtures.mjs', import.meta.url).href
  const program = `
    import { spawnSync } from 'node:child_process';
    import { isolatedRuntimeSettings } from ${JSON.stringify(helper)};
    const settings = isolatedRuntimeSettings("/synthetic/새 Mac's home 😀", '/synthetic/private data');
    const child = spawnSync(process.execPath, ['-e', 'process.stdout.write(JSON.stringify({env:process.env,args:process.argv.slice(1)}))', '--', ...settings.args], {env:settings.env,encoding:'utf8'});
    if(child.status !== 0) process.exit(1);
    process.stdout.write(child.stdout);
  `
  const child = spawnSync(process.execPath, ['--input-type=module', '-e', program], { encoding: 'utf8', timeout: 10000,
    env: { ...process.env, NODE_OPTIONS: '', HOME: '/synthetic/personal-home', FORGE_DATA_DIR: '/synthetic/personal-data',
      AWS_ACCESS_KEY_ID: 'SYNTHETIC_SECRET', ANTHROPIC_API_KEY: 'SYNTHETIC_SECRET', OPENAI_API_KEY: 'SYNTHETIC_SECRET',
      JAVA_TOOL_OPTIONS: '-javaagent:/synthetic/agent.jar', CODEX_HOME: '/synthetic/personal-profile', HTTPS_PROXY: 'https://synthetic.invalid' } })
  assert.equal(child.status, 0, child.stderr)
  const observed = JSON.parse(child.stdout)
  const { __CF_USER_TEXT_ENCODING: platformEncoding, ...environment } = observed.env
  if (platformEncoding !== undefined) {
    assert.equal(process.platform, 'darwin')
    assert.match(platformEncoding, /^0x[0-9A-Fa-f]+:0x[0-9A-Fa-f]+:0x[0-9A-Fa-f]+$/)
  }
  assert.deepEqual({ ...observed, env: environment }, { env: isolatedRuntimeSettings("/synthetic/새 Mac's home 😀", '/synthetic/private data').env,
    args: ["-Duser.home=/synthetic/새 Mac's home 😀", '-Djava.io.tmpdir=/synthetic/private data'] })
  assert.doesNotMatch(child.stdout, /SYNTHETIC_SECRET|personal-home|personal-data|personal-profile|javaagent|synthetic.invalid/)
})
