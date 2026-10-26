import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { executionDiagnostic } from './expert-live-diagnostics.mjs'

test('app-server timeout stages use only exact fixed public messages', () => {
  for (const [label, phase] of [['연결 초기화', 'INITIALIZE'], ['격리 설정 확인', 'CONFIG'],
    ['대화 준비', 'THREAD'], ['질문 접수', 'TURN'], ['답변 생성', 'WAIT_RESPONSE']]) {
    assert.deepEqual(executionDiagnostic('answer', 'FAILED', `Codex ${label} 단계에서 제한 시간을 초과했습니다.`),
      { transport: 'CODEX_APP_SERVER', outcome: 'FAILED', phase, deadline: 'APP_SERVER' })
  }
})

test('exec planner deadline never fabricates an app-server stage', () => {
  assert.deepEqual(executionDiagnostic('plan', 'FAILED', '검색 계획 시간 한도 90초를 초과했습니다. 새 동의가 필요합니다.'),
    { transport: 'CODEX_EXEC', outcome: 'FAILED', phase: 'UNOBSERVED', deadline: 'INVESTIGATION_90S' })
  assert.equal(executionDiagnostic('plan', 'FAILED', 'Codex 대화 준비 단계에서 제한 시간을 초과했습니다.').phase, 'UNOBSERVED')
})

test('unknown messages and status cannot leak into diagnostic output', () => {
  const diagnostic = executionDiagnostic('answer', 'synthetic-private-status', 'synthetic-secret stderr auth marker')
  assert.deepEqual(diagnostic, { transport: 'CODEX_APP_SERVER', outcome: 'UNOBSERVED', phase: 'UNOBSERVED', deadline: 'UNOBSERVED' })
  assert.equal(executionDiagnostic('answer', 'SUCCEEDED', null).phase, 'COMPLETED')
  assert.throws(() => executionDiagnostic('unsupported', 'FAILED', 'private'), TypeError)
})

test('typed enum and numeric suffix preserves exec progress without raw content', () => {
  const prefix = '검색 계획 시간 한도 90초를 초과했습니다. 새 동의가 필요합니다.'
  const suffix = ' [transport=CODEX_EXEC, phase=TURN_STARTED, stderrSignal=RATE_LIMIT, execStdinWritten=true, stdoutBytes=123, stderrBytes=80]'
  assert.deepEqual(executionDiagnostic('plan', 'FAILED', prefix + suffix), {
    transport: 'CODEX_EXEC', outcome: 'FAILED', phase: 'TURN_STARTED', deadline: 'INVESTIGATION_90S',
    stderrSignal: 'RATE_LIMIT', execStdinWritten: true, stdoutBytes: 123, stderrBytes: 80,
  })
  assert.equal(executionDiagnostic('plan', 'FAILED', prefix + suffix.replace('RATE_LIMIT', 'synthetic-secret')).phase, 'UNOBSERVED')
  assert.equal(executionDiagnostic('plan', 'FAILED', 'synthetic-secret' + suffix).phase, 'UNOBSERVED')
})

const stream = { phase: 'TEXT_DELTA', turnAccepted: true, messages: 1, deltas: 12, completedMessages: 0,
  matchedCompletions: 0, ignoredBindings: 2, otherNotifications: 3, protocolInvalid: 0, pendingLineBytes: 64, callbackActive: false }
const prefix = 'Codex 답변 생성 단계에서 제한 시간을 초과했습니다.'
function streamSuffix(value = stream) {
  return ' [transport=CODEX_APP_SERVER, phase=WAIT_RESPONSE, stderrSignal=NONE, execStdinWritten=false, stdoutBytes=24623, stderrBytes=0, '
    + `appStream=AppStream[${Object.entries(value).map(([name, content]) => `${name}=${content}`).join(', ')}]]`
}

test('optional AppStream record preserves all fixed fields and old app-server suffix', () => {
  assert.deepEqual(executionDiagnostic('answer', 'FAILED', prefix + streamSuffix()), {
    transport: 'CODEX_APP_SERVER', outcome: 'FAILED', phase: 'WAIT_RESPONSE', deadline: 'APP_SERVER',
    stderrSignal: 'NONE', execStdinWritten: false, stdoutBytes: 24623, stderrBytes: 0, appStream: stream,
  })
  const old = streamSuffix().replace(/, appStream=.*$/, ']')
  const diagnostic = executionDiagnostic('answer', 'FAILED', prefix + old)
  assert.equal(diagnostic.phase, 'WAIT_RESPONSE')
  assert.equal(diagnostic.stdoutBytes, 24623)
  assert.equal(Object.hasOwn(diagnostic, 'appStream'), false)
})

test('AppStream parser accepts exact Java record enums and inclusive numeric bounds only', () => {
  for (const phase of ['NONE', 'TURN_ACCEPTED', 'MESSAGE_STARTED', 'TEXT_DELTA', 'MESSAGE_COMPLETED',
    'TURN_COMPLETION_RECEIVED', 'COMPLETED', 'PROTOCOL_INVALID']) {
    const value = { ...stream, phase, turnAccepted: false, messages: 1048576, deltas: 1048576,
      completedMessages: 1048576, matchedCompletions: 1048576, ignoredBindings: 1048576,
      otherNotifications: 1048576, protocolInvalid: 1048576, pendingLineBytes: 262144, callbackActive: true }
    const suffix = streamSuffix(value).replace('stdoutBytes=24623', 'stdoutBytes=2147483647').replace('stderrBytes=0', 'stderrBytes=2147483647')
    assert.deepEqual(executionDiagnostic('answer', 'FAILED', prefix + suffix).appStream, value)
  }
})

test('malformed, unbounded or arbitrary stream content is rejected without leaking text', () => {
  const invalid = [
    ...['messages', 'deltas', 'completedMessages', 'matchedCompletions', 'ignoredBindings', 'otherNotifications', 'protocolInvalid']
      .flatMap(name => [streamSuffix({ ...stream, [name]: 1048577 }), streamSuffix({ ...stream, [name]: -1 })]),
    streamSuffix({ ...stream, pendingLineBytes: 262145 }), streamSuffix({ ...stream, phase: 'synthetic-secret' }),
    streamSuffix({ ...stream, callbackActive: 'TRUE' }), streamSuffix({ ...stream, turnAccepted: '1' }),
    streamSuffix({ ...stream, deltas: '1e3' }), streamSuffix({ ...stream, messages: '0.5' }),
    streamSuffix({ ...stream, extra: 'synthetic-secret' }), streamSuffix({ ...stream, callbackActive: undefined }),
    streamSuffix().replace('stdoutBytes=24623', 'stdoutBytes=2147483648'),
    streamSuffix().replace('stderrBytes=0', 'stderrBytes=-1'),
    streamSuffix().replace('AppStream[', 'AppStream[' + 'x'.repeat(3000)),
    streamSuffix() + '\n', streamSuffix() + 'synthetic-secret', streamSuffix().slice(0, -1),
    streamSuffix().replace(/, appStream=.*$/, ', appStream=null]'),
  ]
  for (const suffix of invalid) {
    const diagnostic = executionDiagnostic('answer', 'FAILED', prefix + suffix)
    assert.equal(diagnostic.deadline, 'UNOBSERVED')
    assert.equal(diagnostic.phase, 'UNOBSERVED')
    assert.equal(Object.hasOwn(diagnostic, 'appStream'), false)
    assert.ok(!JSON.stringify(diagnostic).includes('synthetic-secret'))
  }
  assert.equal(executionDiagnostic('answer', 'FAILED', 'synthetic-secret' + streamSuffix()).phase, 'UNOBSERVED')
  assert.equal(executionDiagnostic('plan', 'FAILED', 'AI CLI 응답 제한 시간을 초과했습니다.'
    + streamSuffix().replace('CODEX_APP_SERVER', 'CODEX_EXEC').replace('phase=WAIT_RESPONSE', 'phase=TURN_STARTED')).phase, 'UNOBSERVED')
})

test('fixed response-validation and interruption errors retain counters without inventing a timeout', () => {
  for (const message of ['Codex 스트리밍이 중단되었습니다.', 'Codex 스트리밍 응답을 검증하지 못했습니다. 자동 재전송하지 않습니다.']) {
    const diagnostic = executionDiagnostic('answer', 'FAILED', message + streamSuffix({ ...stream, phase: 'PROTOCOL_INVALID', protocolInvalid: 1 }))
    assert.equal(diagnostic.phase, 'WAIT_RESPONSE')
    assert.equal(diagnostic.appStream.phase, 'PROTOCOL_INVALID')
    assert.equal(diagnostic.deadline, 'UNOBSERVED')
  }
})

test('parser field order and StreamPhase allowlist match current Java record contract', async () => {
  const source = await readFile(new URL('../backend/src/main/java/dev/knowledgeforge/knowledge/application/ProviderExecutionException.java', import.meta.url), 'utf8')
  const declaration = source.match(/public record AppStream\(([^)]*)\)/)[1]
  const fields = declaration.split(',').map(field => field.trim().split(/\s+/).at(-1))
  assert.deepEqual(fields, Object.keys(stream))
  const phases = source.match(/public enum StreamPhase \{([^}]+)\}/)[1].split(',').map(phase => phase.trim())
  assert.deepEqual(phases, ['NONE', 'TURN_ACCEPTED', 'MESSAGE_STARTED', 'TEXT_DELTA', 'MESSAGE_COMPLETED',
    'TURN_COMPLETION_RECEIVED', 'COMPLETED', 'PROTOCOL_INVALID'])
  assert.match(source, /count > 1048576/)
  assert.match(source, /pendingLineBytes > 262144/)
  assert.match(source, /appStream == null \? "" : ", appStream=" \+ appStream/)
})
