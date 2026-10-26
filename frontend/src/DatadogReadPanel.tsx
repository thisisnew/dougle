import { useEffect, useRef, useState } from 'react'
import { ApiError, request } from './api'
import { Button } from './components/ui/button'
import OperationalCapturePanel from './OperationalCapturePanel'
import type { QuestionAttachment } from './useQuestionAttachments'

type Query = { connectionRevision: string; environment: string; service: string; indexes: string[]; from: string; to: string; limit: number }
type Preview = { id: string; workspaceId: string; site: string; query: Query; expiresAt: string; maxBytes: number; timeoutSeconds: number; sessionOnly: boolean }
type Result = { id: string; status: 'OK' | 'EMPTY' | 'DENIED' | 'FAILED' | 'TIMEOUT' | 'CANCELLED' | 'RUNNING' | 'PREVIEW'; output: string; rows: number; truncated: boolean }
const tag = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/

export default function DatadogReadPanel({ workspaceId, revision, disabled: externalDisabled, onBusyChange, onCaptured }: {
  workspaceId: string; revision: string; disabled: boolean; onBusyChange: (busy: boolean) => void
  onCaptured?: (workspaceId: string, attachment: QuestionAttachment) => void
}) {
  const [captureBusy, setCaptureBusy] = useState(false)
  const disabled = externalDisabled || captureBusy
  const [environment, setEnvironment] = useState('')
  const [service, setService] = useState('')
  const [indexes, setIndexes] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [limit, setLimit] = useState(20)
  const [preview, setPreview] = useState<Preview | null>(null)
  const [confirmed, setConfirmed] = useState(false)
  const [attempted, setAttempted] = useState(false)
  const [result, setResult] = useState<Result | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [cancelling, setCancelling] = useState(false)
  const lifetime = useRef<AbortController | null>(null)
  const locked = useRef(false)
  const active = useRef<string | null>(null)
  const base = `/workspaces/${encodeURIComponent(workspaceId)}/datadog-investigations`
  const unresolved = attempted && (!result || result.status === 'RUNNING' || result.status === 'PREVIEW')

  useEffect(() => {
    const controller = new AbortController(); lifetime.current = controller
    return () => {
      controller.abort()
      if (active.current) void request(`${base}/${encodeURIComponent(active.current)}/cancel`, { method: 'POST' }).catch(() => {})
    }
  }, [base])
  useEffect(() => { onBusyChange(busy || unresolved || captureBusy); return () => onBusyChange(false) }, [busy, unresolved, captureBusy, onBusyChange])

  function reset() { setPreview(null); setConfirmed(false); setAttempted(false); setResult(null); setError(''); active.current = null }
  function change(update: () => void) {
    if (disabled || busy || attempted || locked.current) return
    reset(); update()
  }
  const start = Date.parse(from ? `${from}Z` : '')
  const end = Date.parse(to ? `${to}Z` : '')
  const indexList = indexes.split(',').map(value => value.trim())
  const valid = tag.test(environment) && tag.test(service) && indexList.length <= 8 && indexList.every(value => tag.test(value))
    && new Set(indexList).size === indexList.length && Number.isFinite(start) && Number.isFinite(end)
    && start < end && end - start <= 3600000 && start >= Date.now() - 7 * 86400000 && end <= Date.now()
    && Number.isInteger(limit) && limit >= 1 && limit <= 50

  async function prepare() {
    if (disabled || busy || locked.current || attempted || !valid) return
    locked.current = true; setBusy(true); reset()
    const signal = lifetime.current!.signal
    try {
      const value = await request<Preview>(`${base}/preview`, { method: 'POST', signal, body: JSON.stringify({
        connectionRevision: revision, environment, service, indexes: indexList,
        from: new Date(start).toISOString(), to: new Date(end).toISOString(), limit,
      }) })
      if (!signal.aborted) setPreview(value)
    } catch { if (!signal.aborted) setError('조회 미리보기를 만들지 못했습니다. 연결과 범위를 확인하세요.') }
    finally { locked.current = false; if (!signal.aborted) setBusy(false) }
  }

  async function executeOrRecover(recover: boolean) {
    if (!preview || busy || locked.current || (disabled && !recover)) return
    if (!recover && (!confirmed || attempted)) return
    if (!recover && Date.now() >= Date.parse(preview.expiresAt)) { reset(); setError('미리보기가 만료되었습니다. 다시 준비하세요.'); return }
    locked.current = true; setBusy(true); setError('')
    const signal = lifetime.current!.signal
    if (!recover) { setAttempted(true); active.current = preview.id }
    try {
      const value = await request<Result>(`${base}/${encodeURIComponent(preview.id)}${recover ? '' : '/execute'}`, recover
        ? { signal } : { method: 'POST', signal, body: JSON.stringify({ requestId: crypto.randomUUID(), approved: true }) })
      if (!signal.aborted) {
        setResult(value)
        if (value.status !== 'RUNNING' && value.status !== 'PREVIEW') active.current = null
      }
    } catch (failure) {
      if (!signal.aborted) {
        if (recover && failure instanceof ApiError && [404, 409].includes(failure.status)) {
          reset(); setError('조회가 만료되었거나 연결/앱 세션이 변경되었습니다. 새 미리보기가 필요합니다.')
        } else setError('결과를 확인하지 못했습니다. 자동 재실행하지 않습니다. 조회 상태를 확인하세요.')
      }
    } finally { locked.current = false; if (!signal.aborted) setBusy(false) }
  }

  async function cancel() {
    if (!preview || cancelling) return
    setCancelling(true)
    const signal = lifetime.current!.signal
    try {
      await request(`${base}/${encodeURIComponent(preview.id)}/cancel`, { method: 'POST', signal })
      if (!signal.aborted) setError('취소를 요청했습니다. 조회 상태 확인으로 종료를 확인하세요.')
    } catch { if (!signal.aborted) setError('취소 확인에 실패했습니다. 조회 상태를 확인하세요.') }
    finally { if (!signal.aborted) setCancelling(false) }
  }

  return <section className="datadog-read-panel" aria-label="Datadog 로그 조회">
    <h3>로그 조회</h3>
    <p>직접 지정하고 승인한 환경·서비스·인덱스만 조회합니다. AI에 자동 전송하지 않습니다. 최근 7일 내 최대 1시간, 50개 로그입니다.</p>
    <p>미리보기와 결과는 이 앱 세션에서만 10분간 보관됩니다. 재시작 시 새 승인이 필요합니다. 비밀정보 마스킹은 완전한 보장을 하지 않습니다.</p>
    <fieldset disabled={disabled || busy || attempted}>
      <label>환경<input maxLength={64} value={environment} onChange={event => change(() => setEnvironment(event.target.value))} /></label>
      <label>서비스<input maxLength={64} value={service} onChange={event => change(() => setService(event.target.value))} /></label>
      <label>인덱스 (쉼표 구분)<input maxLength={519} value={indexes} onChange={event => change(() => setIndexes(event.target.value))} /></label>
      <label>조회 시작 (UTC)<input type="datetime-local" value={from} onChange={event => change(() => setFrom(event.target.value))} /></label>
      <label>조회 종료 (UTC)<input type="datetime-local" value={to} onChange={event => change(() => setTo(event.target.value))} /></label>
      <label>최대 로그 수<input type="number" min={1} max={50} value={limit} onChange={event => change(() => setLimit(Number(event.target.value)))} /></label>
      <Button disabled={!valid} onClick={() => void prepare()}>조회 미리보기</Button>
    </fieldset>
    {preview && <div aria-label="승인할 조회 범위">
      <p>{preview.site} · {preview.query.environment} / {preview.query.service} · {preview.query.indexes.join(', ')}</p>
      <p>{preview.query.from} ~ {preview.query.to} · 최대 {preview.query.limit}개 · {preview.maxBytes} bytes · {preview.timeoutSeconds}초</p>
      <p>승인 만료: {preview.expiresAt}</p>
      <label><input type="checkbox" checked={confirmed} disabled={disabled || busy || attempted} onChange={event => setConfirmed(event.target.checked)} />표시한 범위의 일회 로그 조회를 승인합니다.</label>
      <Button disabled={disabled || busy || attempted || !confirmed} onClick={() => void executeOrRecover(false)}>승인한 로그 조회</Button>
    </div>}
    {unresolved && <>
      <Button disabled={busy} onClick={() => void executeOrRecover(true)}>조회 상태 확인</Button>
      <Button disabled={cancelling} variant="outline" onClick={() => void cancel()}>로그 조회 취소</Button>
    </>}
    {attempted && !unresolved && !busy && <Button disabled={disabled} variant="ghost" onClick={reset}>새 조회 준비</Button>}
    {busy && <p role="status">Datadog 요청 처리 중…</p>}
    {error && <p role="alert">{error}</p>}
    {result && <div role="status"><p>{result.status === 'OK' ? `${result.rows}개 로그 조회 완료` : resultMessage[result.status]}</p>
      {result.truncated && <p>제한으로 일부 로그만 표시합니다. 시간 범위를 좁혀 확인하세요.</p>}
      {result.output && <pre>{result.output}</pre>}
    </div>}
    {onCaptured && result?.status === 'OK' && result.output && <OperationalCapturePanel key={result.id}
      workspaceId={workspaceId} provider="DATADOG" resultId={result.id} disabled={externalDisabled || busy || unresolved}
      onBusyChange={setCaptureBusy} onCaptured={onCaptured} />}
  </section>
}

const resultMessage = {
  DENIED: '연결 또는 승인된 조회 권한이 변경되었습니다. 새 승인이 필요합니다.',
  EMPTY: '조건에 맞는 로그가 없습니다.', FAILED: '로그 조회 실패. 연결과 읽기 권한을 확인하세요.',
  TIMEOUT: '조회 시간이 초과되었습니다.', CANCELLED: '로그 조회가 취소되었습니다.',
  RUNNING: '조회 진행 중입니다. 상태를 다시 확인하세요.', PREVIEW: '실행 도착 여부가 아직 확정되지 않았습니다. 취소하거나 상태를 다시 확인하세요.',
}
