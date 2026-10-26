import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'

export type ConversationScope = {
  jobId: string; scopeId: string; revision: number; workspaceId: string; threadId: string; parentBundleId: string
  tool: 'MYSQL_READ' | 'DATADOG_LOGS'; expiresAt: string; sessionOnly: boolean; executionAllowed: boolean
  internalResolutionRequired: boolean; questions: { topic: string; prompt: string }[]
}
type Binding = { id: string; workspaceId: string; threadId: string; parentBundleId: string; tool: ConversationScope['tool']; sessionOnly: boolean }
type Preview = Binding & { scopeId: string; scopeRevision: number; digest: string; queryPreview: string; expiresAt: string; maxRows: number; maxBytes: number; timeoutSeconds: number }
type Result = Binding & { status: string; output: string; rows: number; truncated: boolean }
type CapturePreview = { id: string; threadId: string; parentBundleId: string; digest: string; text: string; bytes: number; truncated: boolean; expiresAt: string; sessionOnly: boolean }
export type ConversationCapture = { id: string; threadId: string; parentBundleId: string; status: string; attachment: { id: string }; contentHash: string; externalCalls: number }
type Approval = { requestId: string; digest: string; approved: true }
const validExpiry = (value: string) => Number.isFinite(Date.parse(value)) && Date.parse(value) > Date.now()

export default function ConversationReadPanel({ scope, disabled, onCaptured, onBusyChange }: {
  scope: ConversationScope; disabled: boolean; onCaptured: (receipt: ConversationCapture, signal: AbortSignal) => Promise<void>; onBusyChange: (busy: boolean) => void
}) {
  const [preview, setPreview] = useState<Preview | null>(null)
  const [result, setResult] = useState<Result | null>(null)
  const [capture, setCapture] = useState<CapturePreview | null>(null)
  const [uncertain, setUncertain] = useState(false)
  const [captureUncertain, setCaptureUncertain] = useState(false)
  const [savedReceipt, setSavedReceipt] = useState<ConversationCapture | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const locked = useRef(false)
  const lifetime = useRef(new AbortController())
  const busyCallback = useRef(onBusyChange)
  busyCallback.current = onBusyChange
  const executeApproval = useRef<Approval | null>(null)
  const captureApproval = useRef<Approval | null>(null)
  const base = `/workspaces/${encodeURIComponent(scope.workspaceId)}/conversation-operational-reads`
  useEffect(() => {
    const controller = new AbortController()
    lifetime.current = controller
    return () => { controller.abort(); busyCallback.current(false) }
  }, [])

  function checked<Value extends Binding>(value: Value): Value {
    if (!value || value.workspaceId !== scope.workspaceId || value.threadId !== scope.threadId
      || value.parentBundleId !== scope.parentBundleId || value.tool !== scope.tool || value.sessionOnly !== true
      || !value.id || (preview && value.id !== preview.id)) throw new Error('조회 대상 대화가 변경되었습니다. 다시 확인해 주세요.')
    return value
  }
  function checkedCapture(value: CapturePreview): CapturePreview {
    if (!value || value.id !== preview?.id || value.threadId !== scope.threadId || value.parentBundleId !== scope.parentBundleId
      || !value.digest || value.sessionOnly !== true || !validExpiry(value.expiresAt)) throw new Error('조회 결과의 확인 기한이 지났거나 대상이 변경되었습니다.')
    return value
  }
  async function act(action: (signal: AbortSignal) => Promise<void>) {
    if (locked.current || disabled) return
    locked.current = true; setBusy(true); setError(''); busyCallback.current(true)
    const controller = new AbortController()
    const abort = () => controller.abort()
    const lifetimeSignal = lifetime.current.signal
    lifetimeSignal.addEventListener('abort', abort, { once: true })
    const timer = setTimeout(abort, 30_000)
    let rejectAbort: () => void = () => {}
    const aborted = new Promise<never>((_, reject) => { rejectAbort = () => reject(new Error('조회 응답 기한이 지났습니다.')); controller.signal.addEventListener('abort', rejectAbort, { once: true }) })
    try { await Promise.race([action(controller.signal), aborted]) }
    catch (reason) {
      if (!lifetimeSignal.aborted) setError(controller.signal.aborted ? '응답을 확인하지 못했습니다. 다시 실행하지 않고 접수 상태를 확인해 주세요.' : (reason as Error).message)
    } finally {
      clearTimeout(timer); lifetimeSignal.removeEventListener('abort', abort); controller.signal.removeEventListener('abort', rejectAbort); locked.current = false
      if (!lifetimeSignal.aborted) { setBusy(false); busyCallback.current(false) }
    }
  }
  async function prepare(signal: AbortSignal) {
    if (scope.executionAllowed || !scope.sessionOnly || scope.internalResolutionRequired || scope.questions.length || !validExpiry(scope.expiresAt)) {
      throw new Error('조회 범위를 먼저 확인해 주세요.')
    }
    const value = checked(await request<Preview>(`${base}/preview`, { method: 'POST', signal,
      body: JSON.stringify({ scopeId: scope.scopeId, scopeRevision: scope.revision }) }))
    if (signal.aborted) return
    if (value.scopeId !== scope.scopeId || value.scopeRevision !== scope.revision || !value.digest || !validExpiry(value.expiresAt)) throw new Error('조회 범위가 변경되었습니다.')
    setPreview(value)
  }
  async function observe(signal: AbortSignal, execute: boolean) {
    if (!preview) return
    if (execute && !validExpiry(preview.expiresAt)) throw new Error('조회 동의 기한이 지났습니다. 범위를 다시 확인해 주세요.')
    executeApproval.current ??= { requestId: crypto.randomUUID(), digest: preview.digest, approved: true }
    setUncertain(true)
    const value = checked(await request<Result>(`${base}/${encodeURIComponent(preview.id)}${execute ? '/execute' : ''}`, {
      signal, ...(execute ? { method: 'POST', body: JSON.stringify(executeApproval.current) } : {}),
    }))
    if (signal.aborted) return
    if (!['PREVIEW', 'RUNNING', 'CANCELLING', 'SUCCEEDED', 'EMPTY', 'DENIED', 'FAILED', 'CANCELLED', 'TIMEOUT'].includes(value.status)) {
      throw new Error('조회 상태를 확인하지 못했습니다. 다시 실행하지 않고 상태를 확인해 주세요.')
    }
    setResult(value)
    setUncertain(['RUNNING', 'CANCELLING'].includes(value.status))
    if (value.status === 'SUCCEEDED') {
      const snapshot = await request<CapturePreview>(`${base}/${encodeURIComponent(preview.id)}/capture-preview`, { method: 'POST', signal })
      if (!signal.aborted) setCapture(checkedCapture(snapshot))
    }
  }
  async function answer(signal: AbortSignal) {
    if (!preview || !capture) return
    if (savedReceipt) { await onCaptured(savedReceipt, signal); return }
    checkedCapture(capture)
    captureApproval.current ??= { requestId: crypto.randomUUID(), digest: capture.digest, approved: true }
    setCaptureUncertain(true)
    const receipt = await request<ConversationCapture>(`${base}/${encodeURIComponent(preview.id)}/capture`, {
      method: 'POST', signal, body: JSON.stringify(captureApproval.current),
    })
    if (signal.aborted) return
    checkedReceipt(receipt)
    setSavedReceipt(receipt); setCaptureUncertain(false)
    await onCaptured(receipt, signal)
  }
  function checkedReceipt(receipt: ConversationCapture) {
    if (receipt.id !== preview?.id || receipt.threadId !== scope.threadId || receipt.parentBundleId !== scope.parentBundleId
      || !receipt.attachment?.id || receipt.status !== 'CAPTURED' || receipt.externalCalls !== 0) throw new Error('저장한 자료의 대화 연결을 확인하지 못했습니다.')
  }
  async function recoverCapture(signal: AbortSignal) {
    if (!preview) return
    const receipt = await request<ConversationCapture>(`${base}/${encodeURIComponent(preview.id)}/capture-receipt`, { signal })
    if (signal.aborted) return
    checkedReceipt(receipt)
    setSavedReceipt(receipt); setCaptureUncertain(false)
  }
  return <div aria-label="대화 자료 확인">
    {error && <p role="alert">{error}</p>}
    {busy && <p role="status">자료를 확인하고 있어요…</p>}
    {!preview && <Button variant="outline" disabled={busy || disabled} onClick={() => void act(prepare)}>조회 범위 확인</Button>}
    {preview && !result && !uncertain && <>
      <p>{scope.tool === 'MYSQL_READ' ? '연결된 업무 데이터' : '연결된 장애 기록'}를 최대 {preview.maxRows}건 읽습니다. 원본은 변경하지 않습니다.</p>
      <details><summary>조회 범위 자세히 보기</summary><pre>{preview.queryPreview}</pre></details>
      <Button variant="outline" disabled={busy || disabled} onClick={() => void act(signal => observe(signal, true))}>이 범위만 읽기</Button>
    </>}
    {(uncertain || (result?.status === 'SUCCEEDED' && !capture)) && <Button variant="outline" disabled={busy || disabled} onClick={() => void act(signal => observe(signal, false))}>조회 상태 확인</Button>}
    {result && !['SUCCEEDED', 'RUNNING', 'CANCELLING'].includes(result.status) && <p>{result.status === 'EMPTY' ? '이 범위에서 일치하는 자료를 찾지 못했습니다.' : '자료를 확인하지 못했습니다. 조회를 자동으로 반복하지 않습니다.'}</p>}
    {capture && <>
      <p>확인한 자료만 이 대화에 저장하고 선택한 AI에 보내 답변을 받습니다. 추가 조회는 하지 않습니다.</p>
      {capture.truncated && <p>결과가 많아 일부만 포함합니다.</p>}
      <details><summary>답변에 사용할 자료 확인</summary><pre>{capture.text}</pre></details>
      {captureUncertain ? <Button variant="outline" disabled={busy || disabled} onClick={() => void act(recoverCapture)}>저장 상태 확인</Button>
        : <Button variant="outline" disabled={busy || disabled} onClick={() => void act(answer)}>{savedReceipt ? '저장한 자료로 답변 받기' : '이 자료로 답변 받기'}</Button>}
    </>}
  </div>
}
