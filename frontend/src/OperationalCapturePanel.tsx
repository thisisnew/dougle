import { useEffect, useRef, useState } from 'react'
import { ApiError, request } from './api'
import { Button } from './components/ui/button'
import type { QuestionAttachment } from './useQuestionAttachments'

type Preview = { id: string; digest: string; text: string; bytes: number; truncated: boolean; expiresAt: string; sessionOnly: true }
type Receipt = { id: string; status: 'CAPTURED' | 'FAILED' | 'RUNNING' | 'PREVIEW'; attachment: QuestionAttachment | null; contentHash: string | null; externalCalls: 0 }
export type OperationalCapturePanelProps = {
  workspaceId: string
  provider: 'MYSQL' | 'DATADOG'
  resultId: string
  disabled?: boolean
  onBusyChange?: (busy: boolean) => void
  onCaptured: (workspaceId: string, attachment: QuestionAttachment) => void
}

export default function OperationalCapturePanel(props: OperationalCapturePanelProps) {
  return <ScopedOperationalCapturePanel key={JSON.stringify([props.workspaceId, props.provider, props.resultId])} {...props} />
}

function ScopedOperationalCapturePanel({ workspaceId, provider, resultId, disabled = false, onBusyChange, onCaptured }: OperationalCapturePanelProps) {
  const [preview, setPreview] = useState<Preview | null>(null)
  const [receipt, setReceipt] = useState<Receipt | null>(null)
  const [approved, setApproved] = useState(false)
  const [busy, setBusy] = useState(false)
  const [uncertain, setUncertain] = useState(false)
  const [error, setError] = useState('')
  const locked = useRef(false)
  const submission = useRef<string | null>(null)
  const lifetime = useRef(new AbortController())
  const base = `/workspaces/${encodeURIComponent(workspaceId)}/operational-captures`

  useEffect(() => {
    const controller = new AbortController()
    lifetime.current = controller
    return () => controller.abort()
  }, [])
  useEffect(() => { onBusyChange?.(busy || uncertain); return () => onBusyChange?.(false) }, [busy, uncertain, onBusyChange])

  function clearPreview() { setPreview(null); setApproved(false); setUncertain(false); submission.current = null }

  async function run(action: 'preview' | 'capture' | 'recover') {
    if (locked.current || receipt || (disabled && action !== 'recover') || (uncertain && action !== 'recover')) return
    if (action === 'capture' && (!preview || !approved)) return
    if (action === 'recover' && (!preview || !uncertain)) return
    if (action === 'capture' && preview && Date.now() >= Date.parse(preview.expiresAt)) {
      clearPreview(); setError('미리보기가 만료되었습니다. 새 미리보기를 확인하세요.'); return
    }
    locked.current = true; setBusy(true); setError('')
    if (action === 'preview') { setPreview(null); setApproved(false) }
    const signal = lifetime.current.signal
    const operation = new AbortController()
    const abort = () => operation.abort()
    signal.addEventListener('abort', abort, { once: true })
    const deadline = setTimeout(abort, 15000)
    const interrupted = new Promise<never>((_, reject) => {
      operation.signal.addEventListener('abort', () => reject(new Error('캡처 요청 시간이 초과되었습니다.')), { once: true })
    })
    let captured: QuestionAttachment | null = null
    try {
      if (action === 'preview') {
        const next = await Promise.race([
          request<Preview>(`${base}/preview`, { method: 'POST', signal: operation.signal, body: JSON.stringify({ provider, resultId }) }),
          interrupted,
        ])
        if (signal.aborted) return
        if (operation.signal.aborted) throw new Error('캡처 요청 시간이 초과되었습니다.')
        if (!next.id || !/^[a-f0-9]{64}$/.test(next.digest) || typeof next.text !== 'string' || !Number.isSafeInteger(next.bytes)
          || next.bytes <= 0 || next.bytes !== new TextEncoder().encode(next.text).length || typeof next.truncated !== 'boolean'
          || next.sessionOnly !== true || !Number.isFinite(Date.parse(next.expiresAt))) {
          throw new Error('캡처 미리보기 형식을 확인하지 못했습니다.')
        }
        setPreview(next); setApproved(false); submission.current = null
      } else if (preview) {
        submission.current ??= crypto.randomUUID()
        const next = await Promise.race([
          request<Receipt>(`${base}/${encodeURIComponent(preview.id)}${action === 'capture' ? '/capture' : ''}`,
            action === 'capture' ? { method: 'POST', signal: operation.signal, body: JSON.stringify({ requestId: submission.current, digest: preview.digest, approved: true }) } : { signal: operation.signal }),
          interrupted,
        ])
        if (signal.aborted) return
        if (operation.signal.aborted) throw new Error('캡처 요청 시간이 초과되었습니다.')
        if (next.id !== preview.id || next.externalCalls !== 0) {
          throw new Error('저장 영수증을 확인하지 못했습니다. 결과 조회로 확인하세요.')
        }
        if (next.status === 'FAILED') {
          clearPreview(); setReceipt(next); setError('저장에 실패했습니다. 운영 조회를 새로 실행한 뒤 새 결과를 확인하세요.'); return
        }
        if (next.status === 'RUNNING' || next.status === 'PREVIEW') { setUncertain(true); return }
        if (next.status !== 'CAPTURED' || !next.attachment?.id || !next.attachment.name || next.attachment.mediaType !== 'text/plain'
          || !Number.isSafeInteger(next.attachment.size) || next.attachment.size !== preview.bytes
          || !Number.isSafeInteger(next.attachment.characters) || next.attachment.characters !== preview.text.length
          || next.attachment.truncated !== false || !next.contentHash || !/^[a-f0-9]{64}$/.test(next.contentHash)) {
          throw new Error('저장 첨부 형식 또는 크기가 일치하지 않습니다. 결과 조회로 확인하세요.')
        }
        setReceipt(next); setUncertain(false); setApproved(false); captured = next.attachment
      }
    } catch (reason) {
      if (!signal.aborted) {
        const terminal = reason instanceof ApiError && [400, 403, 404, 409, 410].includes(reason.status)
        if (terminal) {
          clearPreview()
          setError('미리보기 또는 저장 권한이 만료되거나 변경되었습니다. 새 미리보기가 필요합니다.')
        } else {
          setError(reason instanceof Error ? reason.message : '캡처 요청을 완료하지 못했습니다.')
          if (action !== 'preview') setUncertain(true)
        }
      }
    } finally {
      clearTimeout(deadline); signal.removeEventListener('abort', abort); operation.abort()
      if (!signal.aborted) { locked.current = false; setBusy(false) }
    }
    if (captured && !signal.aborted) onCaptured(workspaceId, captured)
  }

  return <section className="operational-capture-panel" aria-label="운영 조회 결과 첨부">
    <p>조회 결과를 확인하고 새 질문에 첨부할 수 있습니다. 저장만으로 AI나 외부 서비스를 호출하지 않습니다.</p>
    {!receipt && <Button type="button" variant="outline" disabled={disabled || busy || uncertain} onClick={() => void run('preview')}>첨부 내용 미리보기</Button>}
    {preview && <>
      <p>{preview.bytes} bytes · 미리보기 만료: {preview.expiresAt} · 미리보기는 현재 앱 세션에서만 유효합니다.</p>
      {preview.truncated && <p role="status">조회 결과가 잘렸습니다. 아래 표시된 내용만 저장합니다.</p>}
      <pre aria-label="저장할 정확한 내용">{preview.text}</pre>
      {!receipt && !uncertain && <>
        <label><input type="checkbox" checked={approved} disabled={disabled || busy} onChange={event => setApproved(event.target.checked)} />표시된 내용에 비밀정보가 없는지 확인했으며 새 질문 첨부로 저장합니다.</label>
        <Button type="button" disabled={disabled || busy || !approved} onClick={() => void run('capture')}>저장하고 새 질문에 첨부</Button>
      </>}
    </>}
    {uncertain && <>
      <p role="status">저장 접수 여부를 확인하지 못했습니다. 다시 저장하지 않고 결과만 조회하세요.</p>
      <Button type="button" disabled={busy} onClick={() => void run('recover')}>저장 결과 확인</Button>
    </>}
    {receipt?.status === 'CAPTURED' && receipt.attachment && <p role="status">새 질문 첨부로 저장했습니다: {receipt.attachment.name} · 외부 호출 0회</p>}
    {error && <p role="alert">{error}</p>}
  </section>
}
