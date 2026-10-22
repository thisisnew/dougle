import { useEffect, useRef, useState } from 'react'
import { preferredProvider } from './providerPreference'
import { request } from './api'
import { Button } from './components/ui/button'
import AnswerApprovalPanel from './AnswerApprovalPanel'

type Validation = { bundleId: string; digest: string; checkedAt: string; eligible: boolean; status: string; items: {
  label: string; status: string; currentEntryId: string | null; currentSourceRevision: string | null; supports: { label: string; status: string }[]
}[] }
type Preview = { bundleId: string; bundleDigest: string; provider: string; requestedModel: string; digest: string; prompt: string; characters: number; maxCharacters: number; validation: Validation; contextRequestId?: string | null }
const statuses: Record<string, string> = {
  CURRENT: '현재 수집본과 일치', NO_EVIDENCE: '사용 가능한 근거 없음', BLOCKED: '변경된 근거가 있어 준비 불가',
  MISSING: '자료 삭제·조회 불가', ORIGIN_CHANGED: '원본 이름·연결 변경', ANALYSIS_UNAVAILABLE: '추출 결과 사용 불가',
  CONTENT_CHANGED: '원본 내용 변경', TEXT_CHANGED: '추출 본문·게시 내용 변경', SCOPE_CHANGED: '도메인 범위 변경',
  PUBLICATION_CHANGED: '게시 버전 변경', SUPPORT_CHANGED: '게시 근거 변경', STALE: '근거 원본 변경', INVALID_BUNDLE: '묶음 구성 불일치',
}

export default function EvidenceAnswerPreparation({ workspaceId, bundleId, bundleDigest }: {
  workspaceId: string; bundleId: string; bundleDigest: string
}) {
  const [provider, setProvider] = useState<string>(preferredProvider)
  const [mode, setMode] = useState('server')
  const [model, setModel] = useState('')
  const [validation, setValidation] = useState<Validation | null>(null)
  const [preview, setPreview] = useState<Preview | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const pending = useRef<AbortController | null>(null)
  const base = `/workspaces/${encodeURIComponent(workspaceId)}/evidence-bundles/${encodeURIComponent(bundleId)}`
  useEffect(() => () => pending.current?.abort(), [])

  function clear() { setValidation(null); setPreview(null); setError('') }

  async function run(action: 'validate' | 'preview') {
    if (busy) return
    clear()
    if (action === 'preview' && mode === 'custom' && !model.trim()) { setError('모델 ID를 입력하세요.'); return }
    const controller = new AbortController()
    pending.current = controller
    setBusy(true)
    try {
      if (action === 'validate') {
        const value = await request<Validation>(`${base}/validation`, { signal: controller.signal })
        if (controller.signal.aborted) return
        if (value.bundleId !== bundleId || value.digest !== bundleDigest) throw new Error('묶음 식별 정보가 다릅니다. 다시 열어 확인하세요.')
        setValidation(value)
      } else {
        const value = await request<Preview>(`${base}/answer-preview`, { method: 'POST', signal: controller.signal,
          body: JSON.stringify({ provider, requestedModel: mode === 'server' ? null : mode === 'cli' ? '' : model }) })
        if (controller.signal.aborted) return
        if (value.bundleId !== bundleId || value.bundleDigest !== bundleDigest || value.provider !== provider) throw new Error('미리보기 조건이 다릅니다. 다시 확인하세요.')
        setValidation(value.validation); setPreview(value)
      }
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }

  return <section aria-label="근거 재검증과 요청 미리보기" className="answer-preparation">
    <h3>근거 재검증 · 요청 미리보기</h3>
    <p>검사와 요청 구성만 합니다. CLI를 실행하거나 외부 AI에 전송하지 않습니다. 현재 코딩 대화를 사용하는 기능도 아닙니다.</p>
    <Button variant="outline" disabled={busy} onClick={() => void run('validate')}>근거 상태 다시 확인</Button>
    <label>답변 제공자<select value={provider} disabled={busy} onChange={event => { clear(); setProvider(event.target.value) }}><option value="CODEX">Codex CLI</option><option value="CLAUDE">Claude Code CLI</option></select></label>
    <label>답변 모델 선택<select value={mode} disabled={busy} onChange={event => { clear(); setMode(event.target.value) }}><option value="server">앱 설정값 사용</option><option value="cli">CLI 기본값 사용</option><option value="custom">직접 입력</option></select></label>
    {mode === 'custom' && <label>답변 모델 ID<input value={model} maxLength={128} disabled={busy} onChange={event => { clear(); setModel(event.target.value) }} /></label>}
    <Button disabled={busy} onClick={() => void run('preview')}>요청 미리보기 만들기 (전송 안 함)</Button>
    {busy && <p role="status">현재 자료를 확인하는 중…</p>}
    {error && <p role="alert">{error}</p>}
    {validation && <section aria-label="근거 재검증 결과">
      <p role="status">{statuses[validation.status] || validation.status}</p>
      <p>확인 시각: {new Date(validation.checkedAt).toLocaleString('ko-KR')} · 이 시점의 수집본만 확인했습니다. 답변의 충분성·사실성·원격 최신성 보장은 아닙니다.</p>
      <ul>{validation.items.map(item => <li key={item.label}>[{item.label}] {statuses[item.status] || item.status}
        {item.currentSourceRevision && <small> · 현재 Source 버전 {item.currentSourceRevision}</small>}
        {item.supports.map(support => <span key={support.label}> · [{item.label}/{support.label}] {statuses[support.status] || support.status}</span>)}
      </li>)}</ul>
    </section>}
    {preview && <section aria-label="답변 요청 미리보기">
      {preview.contextRequestId && <p>선택한 이전 문맥이 고정된 요청입니다. 요청 전문의 이전 질문·답변까지 확인하고 승인하세요.</p>}
      <p>{preview.provider} · 요청 모델: {preview.requestedModel || 'CLI 기본값 (실행 모델 미확인)'}</p>
      <p>전체 요청 {preview.characters}/{preview.maxCharacters} UTF-16자 · 토큰 수가 아닙니다.</p>
      <p>아래에서 이 요청만 승인·철회할 수 있습니다. 승인 저장은 실제 전송이 아닙니다.</p>
      <details><summary>실제 요청 전문·해시 보기</summary><pre>{preview.prompt}</pre><pre>{preview.digest}</pre></details>
    </section>}
    <AnswerApprovalPanel key={preview?.digest || 'no-preview'} workspaceId={workspaceId} bundleId={bundleId} preview={preview} />
  </section>
}
