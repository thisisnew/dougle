import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { observeAnswerJob } from './answerJobEvents'
import { FakeEventSource } from './test/FakeEventSource'

const evidence = { id: 'bundle', workspaceId: 'workspace', digest: 'hash' }
const job = { id: 'job', workspaceId: 'workspace', bundleId: 'bundle', approvalId: 'approval', provider: 'CODEX', requestedModel: '', status: 'RUNNING', answer: null, error: null }
let stop: (() => void) | undefined
beforeEach(() => { vi.useFakeTimers(); FakeEventSource.instances = []; vi.stubGlobal('EventSource', FakeEventSource) })
afterEach(() => { stop?.(); vi.useRealTimers(); vi.unstubAllGlobals() })
function start() {
  const callbacks = { job: vi.fn(), state: vi.fn(), error: vi.fn(), partial: vi.fn() }
  stop = observeAnswerJob('workspace', 'bundle', 'job', callbacks)
  return { callbacks, source: FakeEventSource.instances[0] }
}

it('근거 이후만 답변을 받고 완료 즉시 연결을 닫는다', () => {
  const { callbacks, source } = start()
  expect(source.url).toBe('/api/workspaces/workspace/answer-jobs/job/events')
  source.emit('evidence', evidence)
  source.emit('job', { ...job, status: 'SUCCEEDED', answer: '답변 [R1]' })
  expect(callbacks.job).toHaveBeenCalledOnce()
  expect(source.closed).toBe(true)
  source.onerror?.(); source.emit('job', job)
  vi.advanceTimersByTime(70000)
  expect(FakeEventSource.instances).toHaveLength(1)
  expect(callbacks.job).toHaveBeenCalledOnce()
})

it.each([
  ['job', job],
  ['evidence', { ...evidence, workspaceId: 'other' }],
  ['evidence', { ...evidence, id: 'other' }],
  ['evidence', null],
])('잘못된 선행 이벤트 %s를 차단한다', (name, value) => {
  const { callbacks, source } = start()
  source.emit(name as string, value)
  expect(callbacks.error).toHaveBeenCalledOnce()
  expect(callbacks.job).not.toHaveBeenCalled()
  expect(source.closed).toBe(true)
})

it.each([{ ...job, id: 'other' }, { ...job, workspaceId: 'other' }, { ...job, status: 'UNKNOWN' }, { ...job, answer: '검사 전 답변' }])('잘못된 작업을 표시하지 않는다: %j', value => {
  const { callbacks, source } = start()
  source.emit('evidence', evidence); source.emit('job', value)
  expect(callbacks.job).not.toHaveBeenCalled()
  expect(callbacks.error).toHaveBeenCalledOnce()
})

it('오류 재연결은 1/2/4초 최대 세 번이고 오래된 이벤트를 무시한다', () => {
  const { callbacks, source } = start()
  for (const delay of [1000, 2000, 4000]) {
    const current = FakeEventSource.instances.at(-1)!
    current.onerror?.()
    expect(current.closed).toBe(true)
    vi.advanceTimersByTime(delay)
  }
  FakeEventSource.instances.at(-1)!.onerror?.()
  vi.advanceTimersByTime(70000)
  expect(FakeEventSource.instances).toHaveLength(4)
  expect(callbacks.error).toHaveBeenCalledWith(expect.stringContaining('3회'))
  source.emit('evidence', evidence); source.emit('job', job)
  expect(callbacks.job).not.toHaveBeenCalled()
})

it('정상 연결 갱신 후에도 근거부터 다시 확인한다', () => {
  const { callbacks, source } = start()
  source.emit('evidence', evidence); source.emit('job', job); source.emit('done', { reason: 'reconnect' })
  expect(source.closed).toBe(true)
  vi.advanceTimersByTime(1000)
  FakeEventSource.instances[1].emit('job', job)
  expect(callbacks.job).toHaveBeenCalledOnce()
  expect(callbacks.error).toHaveBeenCalledOnce()
})

it('삭제/조회 불가 응답은 자동 재연결하지 않는다', () => {
  const { callbacks, source } = start()
  source.emit('unavailable', { message: '내부 상세를 표시하지 않음' })
  source.onerror?.(); vi.advanceTimersByTime(70000)
  expect(callbacks.error).toHaveBeenCalledWith(expect.stringContaining('삭제'))
  expect(FakeEventSource.instances).toHaveLength(1)
})

it('정지 시 연결과 재연결 타이머를 정리한다', () => {
  const { callbacks, source } = start()
  source.onerror?.(); stop?.(); vi.advanceTimersByTime(70000)
  expect(source.closed).toBe(true)
  expect(FakeEventSource.instances).toHaveLength(1)
  expect(callbacks.job).not.toHaveBeenCalled()
})

it('응답이 멎으면 65초 watchdog으로 제한된 재연결을 한다', () => {
  const { source } = start()
  vi.advanceTimersByTime(65000)
  expect(source.closed).toBe(true)
  vi.advanceTimersByTime(1000)
  expect(FakeEventSource.instances).toHaveLength(2)
})

it('미검증 스냅샷을 교체하고 재연결/완료 시 폐기한다', () => {
  const { callbacks, source } = start()
  source.emit('evidence', evidence); source.emit('job', job)
  const partial = { workspaceId: 'workspace', bundleId: 'bundle', jobId: 'job', text: '임시', unverified: true }
  source.emit('partial', partial); source.emit('partial', { ...partial, text: '임시 출력' })
  expect(callbacks.partial).toHaveBeenLastCalledWith('임시 출력')
  source.onerror?.()
  expect(callbacks.partial).toHaveBeenLastCalledWith('')
  vi.advanceTimersByTime(1000)
  const next = FakeEventSource.instances[1]
  next.emit('evidence', evidence); next.emit('job', job); next.emit('partial', partial)
  next.emit('job', { ...job, status: 'FAILED' })
  expect(callbacks.partial).toHaveBeenLastCalledWith('')
})

it.each([
  { workspaceId: 'other', bundleId: 'bundle', jobId: 'job', text: '임시', unverified: true },
  { workspaceId: 'workspace', bundleId: 'bundle', jobId: 'job', text: '임시', unverified: false },
  { workspaceId: 'workspace', bundleId: 'bundle', jobId: 'job', text: 'x'.repeat(20001), unverified: true },
])('잘못된 임시 출력은 표시하지 않는다', partial => {
  const { callbacks, source } = start()
  source.emit('evidence', evidence); source.emit('job', job); source.emit('partial', partial)
  expect(callbacks.error).toHaveBeenCalledOnce()
  expect(callbacks.partial).toHaveBeenLastCalledWith('')
})
