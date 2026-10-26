const appServerTimeouts = new Map([
  ['연결 초기화', 'INITIALIZE'], ['격리 설정 확인', 'CONFIG'], ['대화 준비', 'THREAD'],
  ['질문 접수', 'TURN'], ['답변 생성', 'WAIT_RESPONSE'],
].map(([label, phase]) => [`Codex ${label} 단계에서 제한 시간을 초과했습니다.`, phase]))

const streamPhases = ['NONE', 'TURN_ACCEPTED', 'MESSAGE_STARTED', 'TEXT_DELTA', 'MESSAGE_COMPLETED',
  'TURN_COMPLETION_RECEIVED', 'COMPLETED', 'PROTOCOL_INVALID']
const streamCounts = ['messages', 'deltas', 'completedMessages', 'matchedCompletions', 'ignoredBindings', 'otherNotifications', 'protocolInvalid']
const ignoredCounts = ['missingThread', 'foreignThread', 'missingTurn', 'foreignTurn', 'answerLifecycle', 'other']
const ignoredPattern = '(?:, ignoredEvents=(?:null|IgnoredEvents\\['
  + ignoredCounts.map(name => `${name}=(?<ignored_${name}>[0-9]{1,7})`).join(', ') + '\\]))?'
const streamPattern = `, appStream=AppStream\\[phase=(?<streamPhase>${streamPhases.join('|')}), turnAccepted=(?<turnAccepted>true|false)`
  + streamCounts.map(name => `, ${name}=(?<${name}>[0-9]{1,7})`).join('')
  + ', pendingLineBytes=(?<pendingLineBytes>[0-9]{1,6}), callbackActive=(?<callbackActive>true|false)' + ignoredPattern + '\\]'
const suffixPattern = new RegExp(' \\[transport=(?<transport>CODEX_EXEC|CLI_EXEC|CODEX_APP_SERVER), '
  + 'phase=(?<phase>PROCESS_STARTED|PROMPT_WRITTEN|THREAD_STARTED|TURN_STARTED|TURN_COMPLETED|INITIALIZE|CONFIG|THREAD|TURN|WAIT_RESPONSE), '
  + 'stderrSignal=(?<stderrSignal>NONE|RATE_LIMIT|NETWORK_RECONNECT|PROVIDER_UNAVAILABLE), '
  + 'execStdinWritten=(?<execStdinWritten>true|false), stdoutBytes=(?<stdoutBytes>[0-9]{1,10}), stderrBytes=(?<stderrBytes>[0-9]{1,10})'
  + `(?:${streamPattern})?\\]$`)

function safeSuffix(error, transport) {
  if (typeof error !== 'string' || error.length > 2048) return null
  const match = error.match(suffixPattern)
  if (!match || match.index + match[0].length !== error.length || match.groups.transport !== transport) return null
  const fields = match.groups
  if (Number(fields.stdoutBytes) > 2147483647 || Number(fields.stderrBytes) > 2147483647) return null
  let appStream
  if (fields.streamPhase !== undefined) {
    if (transport !== 'CODEX_APP_SERVER' || Number(fields.pendingLineBytes) > 262144
      || streamCounts.some(name => Number(fields[name]) > 1048576)
      || ignoredCounts.some(name => Number(fields[`ignored_${name}`]) > 1048576)) return null
    appStream = { phase: fields.streamPhase, turnAccepted: fields.turnAccepted === 'true',
      ...Object.fromEntries(streamCounts.map(name => [name, Number(fields[name])])),
      pendingLineBytes: Number(fields.pendingLineBytes), callbackActive: fields.callbackActive === 'true' }
    if (fields.ignored_missingThread !== undefined) {
      appStream.ignoredEvents = Object.fromEntries(ignoredCounts.map(name => [name, Number(fields[`ignored_${name}`])]))
    }
  }
  return { message: error.slice(0, match.index), phase: fields.phase, stderrSignal: fields.stderrSignal,
    execStdinWritten: fields.execStdinWritten === 'true', stdoutBytes: Number(fields.stdoutBytes), stderrBytes: Number(fields.stderrBytes),
    ...(appStream ? { appStream } : {}) }
}

export function executionDiagnostic(kind, status, error) {
  if (!['plan', 'answer'].includes(kind)) throw new TypeError('Unsupported execution kind')
  const diagnostic = { transport: kind === 'plan' ? 'CODEX_EXEC' : 'CODEX_APP_SERVER',
    outcome: ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(status) ? status : 'UNOBSERVED',
    phase: status === 'SUCCEEDED' ? 'COMPLETED' : 'UNOBSERVED', deadline: 'UNOBSERVED' }
  if (status !== 'FAILED') return diagnostic
  const suffix = safeSuffix(error, diagnostic.transport)
  if (suffix) error = suffix.message
  if (kind === 'plan' && error === '검색 계획 시간 한도 90초를 초과했습니다. 새 동의가 필요합니다.') {
    diagnostic.deadline = 'INVESTIGATION_90S'
  } else if (kind === 'plan' && ['AI CLI 응답 제한 시간을 초과했습니다.',
    'AI CLI 응답 제한 시간을 초과했습니다. 기존 지식은 유지됩니다.'].includes(error)) {
    diagnostic.deadline = 'CLI'
  } else if (kind === 'answer' && appServerTimeouts.has(error)) {
    diagnostic.deadline = 'APP_SERVER'
    diagnostic.phase = appServerTimeouts.get(error)
  } else if (kind === 'answer' && error === 'Codex 스트리밍 제한 시간을 초과했습니다.') {
    diagnostic.deadline = 'APP_SERVER'
  }
  const knownNonTimeout = kind === 'answer' && ['Codex 스트리밍이 중단되었습니다.',
    'Codex 스트리밍 응답을 검증하지 못했습니다. 자동 재전송하지 않습니다.'].includes(error)
  if (suffix && (diagnostic.deadline !== 'UNOBSERVED' || knownNonTimeout)) {
    const validPhases = kind === 'plan' ? ['PROCESS_STARTED', 'PROMPT_WRITTEN', 'THREAD_STARTED', 'TURN_STARTED', 'TURN_COMPLETED']
      : ['INITIALIZE', 'CONFIG', 'THREAD', 'TURN', 'WAIT_RESPONSE']
    if (validPhases.includes(suffix.phase)) {
      const { message, ...safe } = suffix
      Object.assign(diagnostic, safe)
    }
  }
  return diagnostic
}
