import { useEffect, useRef, useState } from 'react'
import { ApiError, request } from './api'
import { Button } from './components/ui/button'
import OperationalCapturePanel from './OperationalCapturePanel'
import type { QuestionAttachment } from './useQuestionAttachments'

type Preview = { id: string; workspaceId: string; connection: string; database: string; expiresAt: string; maxBytes: number; timeoutSeconds: number }
type Result = { id: string; status: string; output: string; rows: number; truncated: boolean; capturedAt: string | null }
const messages: Record<string, string> = {
  EMPTY: '조건에 맞는 데이터가 없습니다.', TIMEOUT: '조회 시간이 초과되었습니다. 조건을 좁혀 다시 확인하세요.',
  FAILED: 'DB 조회를 완료하지 못했습니다. 연결과 테이블·열의 조회 권한을 확인하세요.', CANCELLED: '조회를 취소했습니다.',
  RUNNING: 'DB 조회 중…', PREVIEW: '조회 실행 전입니다.',
}

export default function MysqlReadPanel({ workspaceId, disabled: externalDisabled = false, onBusyChange, onCaptured }: {
  workspaceId: string; disabled?: boolean; onBusyChange?: (busy: boolean) => void
  onCaptured?: (workspaceId: string, attachment: QuestionAttachment) => void
}) {
  const [captureBusy, setCaptureBusy] = useState(false)
  const disabled = externalDisabled || captureBusy
  const [open, setOpen] = useState(false)
  const [table, setTable] = useState('')
  const [columns, setColumns] = useState('')
  const [column, setColumn] = useState('')
  const [value, setValue] = useState('')
  const [preview, setPreview] = useState<Preview | null>(null)
  const [result, setResult] = useState<Result | null>(null)
  const [busy, setBusy] = useState(false)
  const [uncertain, setUncertain] = useState(false)
  const [error, setError] = useState('')
  const submission = useRef<string | null>(null)
  const locked = useRef(false)
  const lifetime = useRef(new AbortController())
  const base = `/workspaces/${encodeURIComponent(workspaceId)}/mysql-investigations`
  useEffect(() => { onBusyChange?.(busy || uncertain || captureBusy); return () => onBusyChange?.(false) }, [busy, uncertain, captureBusy, onBusyChange])
  useEffect(() => {
    const controller = new AbortController(); lifetime.current = controller
    return () => controller.abort()
  }, [])

  async function run(action: 'preview' | 'execute' | 'result') {
    if (locked.current || (disabled && action !== 'result')) return
    if (action === 'execute' && preview && Date.now() >= Date.parse(preview.expiresAt)) {
      change(() => {}); setError('조회가 만료되었습니다. 새 미리보기가 필요합니다.'); return
    }
    locked.current = true; setBusy(true); setError('')
    const signal = lifetime.current.signal
    try {
      if (action === 'preview') {
        const planned = await request<Preview>(`${base}/preview`, { method: 'POST', signal,
          body: JSON.stringify({ table: table.trim(), columns: columns.split(',').map(item => item.trim()).filter(Boolean), equalities: { [column.trim()]: value }, limit: 20 }) })
        if (planned.workspaceId !== workspaceId) throw new Error('조회 대상 Workspace가 다릅니다.')
        if (!signal.aborted) { setPreview(planned); setResult(null); setUncertain(false); submission.current = null }
      } else if (preview) {
        submission.current ??= crypto.randomUUID()
        const response = await request<Result>(`${base}/${encodeURIComponent(preview.id)}${action === 'execute' ? '/execute' : ''}`, action === 'execute'
          ? { method: 'POST', signal, body: JSON.stringify({ requestId: submission.current, approved: true }) } : { signal })
        if (response.id !== preview.id) throw new Error('조회 결과가 일치하지 않습니다.')
        if (!signal.aborted) { setResult(response); setUncertain(['RUNNING', 'PREVIEW'].includes(response.status)) }
      }
    } catch (reason) {
      if (!signal.aborted) {
        setError((reason as Error).message)
        if (action !== 'preview') {
          const terminal = reason instanceof ApiError && ([400, 403, 404].includes(reason.status)
            || (action === 'result' && reason.status === 409))
          if (terminal) { setPreview(null); setResult(null); submission.current = null }
          setUncertain(!terminal)
        }
      }
    } finally { locked.current = false; if (!signal.aborted) setBusy(false) }
  }

  async function cancel() {
    if (!preview) return
    try {
      await request(`${base}/${encodeURIComponent(preview.id)}/cancel`, { method: 'POST', signal: lifetime.current.signal })
      if (!lifetime.current.signal.aborted) { setUncertain(true); setError('취소를 요청했습니다. 결과를 확인하세요.') }
    } catch (reason) { if (!lifetime.current.signal.aborted) setError((reason as Error).message) }
  }

  function change(update: () => void) { update(); setPreview(null); setResult(null); setError(''); submission.current = null }
  const controlsLocked = disabled || busy || uncertain
  return <section className="mysql-read-panel" aria-label="MySQL 데이터 조회">
    <Button variant="outline" disabled={controlsLocked} onClick={() => setOpen(!open)}>DB 데이터 조회</Button>
    {open && <div className="source-form">
      <p>지정한 조건으로 최대 20행만 조회합니다. 결과는 AI에 자동 전송하지 않습니다.</p>
      <fieldset disabled={controlsLocked}>
        <label>테이블<input value={table} maxLength={64} onChange={event => change(() => setTable(event.target.value))} /></label>
        <label>조회할 열 (쉼표로 구분)<input value={columns} maxLength={1024} onChange={event => change(() => setColumns(event.target.value))} /></label>
        <label>조건 열<input value={column} maxLength={64} onChange={event => change(() => setColumn(event.target.value))} /></label>
        <label>일치하는 값<input value={value} maxLength={1024} onChange={event => change(() => setValue(event.target.value))} /></label>
      </fieldset>
      {error && <p role="alert">{error}</p>}
      {!preview && <Button disabled={controlsLocked || !table.trim() || !columns.trim() || !column.trim()} onClick={() => void run('preview')}>조회 범위 확인</Button>}
      {preview && <><p>대상: {preview.connection} / {preview.database} · 최대 {preview.timeoutSeconds}초</p>
        {!result && !uncertain && <Button disabled={disabled || busy} onClick={() => void run('execute')}>이 DB에서 조회</Button>}
        {(busy || uncertain) && <Button variant="outline" onClick={() => void cancel()}>조회 취소</Button>}
        {uncertain && <Button disabled={busy} onClick={() => void run('result')}>결과 다시 확인</Button>}
      </>}
      {busy && <p role="status">조회 처리 중…</p>}
      {result && <div role="status"><p>{result.status === 'SUCCEEDED' ? `${result.rows}행 조회 완료` : messages[result.status] || '조회 상태를 확인하세요.'}</p>
        {result.truncated && <p>일부 결과만 표시합니다. 조건을 좁혀 조회하세요.</p>}
        {result.output && <pre>{result.output}</pre>}
      </div>}
      {onCaptured && result?.status === 'SUCCEEDED' && result.output && <OperationalCapturePanel key={result.id}
        workspaceId={workspaceId} provider="MYSQL" resultId={result.id} disabled={externalDisabled || busy || uncertain}
        onBusyChange={setCaptureBusy} onCaptured={onCaptured} />}
      {preview && !busy && !uncertain && <Button variant="ghost" disabled={disabled} onClick={() => change(() => {})}>새 조회</Button>}
    </div>}
  </section>
}
