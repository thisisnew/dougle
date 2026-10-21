export type AnswerJob = { id: string; bundleId: string; approvalId: string; status: string; provider: string; requestedModel: string; answer: string | null; error: string | null }
export const activeAnswerJob = (job: AnswerJob | null) => !!job && ['QUEUED', 'RUNNING', 'CANCELLING'].includes(job.status)
const statuses = ['QUEUED', 'RUNNING', 'CANCELLING', 'CANCELLED', 'SUCCEEDED', 'FAILED', 'INTERRUPTED']

export function observeAnswerJob(workspaceId: string, bundleId: string, jobId: string, callbacks: {
  job: (job: AnswerJob) => void; state: (message: string) => void; error: (message: string) => void
}) {
  let stopped = false
  let failures = 0
  let disconnect = () => {}
  let retry: ReturnType<typeof setTimeout> | undefined

  function stop() { stopped = true; disconnect(); clearTimeout(retry) }
  function fail(message: string) { stop(); callbacks.state('SSE 관측 중지'); callbacks.error(message) }
  function connect() {
    if (stopped) return
    callbacks.state('SSE 연결 중 — 근거 확인 대기')
    let source: EventSource
    try { source = new EventSource(`/api/workspaces/${encodeURIComponent(workspaceId)}/answer-jobs/${encodeURIComponent(jobId)}/events`) }
    catch { fail('SSE 연결을 시작하지 못했습니다. 작업 목록을 다시 조회하세요.'); return }
    let closed = false
    let evidence = false
    let receivedJob = false
    const watchdog = setTimeout(() => reconnect(false), 65000)
    disconnect = () => { closed = true; clearTimeout(watchdog); source.close() }
    function reconnect(rotation: boolean) {
      if (stopped || closed) return
      disconnect()
      if (!rotation && failures >= 3) { fail('SSE 재연결 3회에 실패했습니다. 작업 목록을 다시 조회하세요.'); return }
      const delay = rotation ? 1000 : 1000 * 2 ** failures++
      callbacks.state(rotation ? 'SSE 연결 갱신 대기' : `SSE 재연결 대기 (${failures}/3)`)
      retry = setTimeout(connect, delay)
    }
    function listen(name: string, handler: (value: Record<string, unknown>) => void) {
      source.addEventListener(name, event => {
        if (stopped || closed) return
        try {
          const value = JSON.parse((event as MessageEvent).data)
          if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error()
          handler(value)
        } catch { fail('SSE 응답의 순서 또는 식별 정보가 올바르지 않습니다. 작업 목록을 다시 조회하세요.') }
      })
    }
    listen('evidence', value => {
      if (evidence || value.id !== bundleId || value.workspaceId !== workspaceId || typeof value.digest !== 'string') throw new Error()
      evidence = true
      callbacks.state('SSE 근거 확인 완료 — 상태 수신 중 (당시 자료, 최신성 보장 아님)')
    })
    listen('job', value => {
      if (!evidence || value.id !== jobId || value.bundleId !== bundleId || value.workspaceId !== workspaceId
        || !statuses.includes(value.status as string)
        || !['approvalId', 'provider', 'requestedModel'].every(key => typeof value[key] === 'string')
        || !['answer', 'error'].every(key => value[key] === null || typeof value[key] === 'string')
        || (value.status !== 'SUCCEEDED' && value.answer !== null)) throw new Error()
      receivedJob = true
      const job = value as AnswerJob
      callbacks.job(job)
      if (!activeAnswerJob(job)) { stop(); callbacks.state('SSE 관측 완료') }
    })
    listen('done', value => {
      if (!receivedJob || value.reason !== 'reconnect') throw new Error()
      reconnect(true)
    })
    listen('unavailable', () => fail('작업이 삭제되었거나 조회할 수 없습니다. 작업 목록을 다시 확인하세요.'))
    source.onerror = () => reconnect(false)
  }
  connect()
  return stop
}
