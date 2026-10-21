import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'
import type { Comparison } from './GenerationComparison'
import type { AcceptanceState } from './ProposalAcceptance'

type MergePreview = { sourceVersion: number; resultVersion: number; plan: {
  evidence: Comparison['current']['version']['evidence']; mapping: number[]; generatedMarkdown: string
} }

export default function ProposalMerge({ workspaceId, jobId, onClose }: {
  workspaceId: string; jobId: string; onClose: (saved: boolean) => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const pending = useRef<AbortController | null>(null)
  const [comparison, setComparison] = useState<Comparison | null>(null)
  const [state, setState] = useState<AcceptanceState | null>(null)
  const [draft, setDraft] = useState<{ title: string; markdown: string } | null>(null)
  const [note, setNote] = useState('')
  const [selection, setSelection] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [refresh, setRefresh] = useState(0)
  const [saved, setSaved] = useState(false)
  const [preview, setPreview] = useState<MergePreview | null>(null)
  const [planning, setPlanning] = useState(false)
  const [mappingConfirmed, setMappingConfirmed] = useState(false)
  const base = `/workspaces/${encodeURIComponent(workspaceId)}/generation-jobs/${encodeURIComponent(jobId)}`
  const dirty = !saved && !!draft && (!!note || draft.title !== comparison?.current.version.title
    || draft.markdown !== comparison?.current.version.markdown)

  useEffect(() => {
    const element = dialog.current!
    element.showModal()
    return () => { element.close(); pending.current?.abort() }
  }, [])
  useEffect(() => {
    if (!dirty) return
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])
  useEffect(() => {
    const controller = new AbortController()
    setComparison(null); setState(null); setError(''); setConfirmed(false); setSelection('')
    setPreview(null); setMappingConfirmed(false)
    Promise.all([request<Comparison>(`${base}/comparison`, { signal: controller.signal }),
      request<AcceptanceState>(`${base}/acceptance`, { signal: controller.signal })])
      .then(([documents, status]) => {
        if (controller.signal.aborted) return
        setComparison(documents); setState(status)
        setDraft(previous => previous ?? { title: documents.current.version.title, markdown: documents.current.version.markdown })
      })
      .catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
    return () => controller.abort()
  }, [base, refresh])

  const sameEvidence = !!comparison && comparison.generated.card.latestVersion === 1
    && comparison.current.version.evidence.length > 0
    && comparison.current.version.evidence.every((item, index) => {
      const generated = comparison.generated.version.evidence[index]
      return item.freshness === 'CURRENT' && generated?.freshness === 'CURRENT'
        && item.snapshotId === generated.snapshotId && item.startLine === generated.startLine && item.endLine === generated.endLine
    }) && comparison.current.version.evidence.length === comparison.generated.version.evidence.length
  const eligible = sameEvidence || !!preview

  async function planEvidence() {
    if (!comparison || saving || planning) return
    const controller = new AbortController()
    pending.current = controller
    setPlanning(true); setError(''); setConfirmed(false); setMappingConfirmed(false); setSelection('')
    try {
      const value = await request<MergePreview>(`${base}/acceptance/merge-preview`, { signal: controller.signal })
      if (controller.signal.aborted) return
      if (value.sourceVersion !== comparison.current.version.number || value.resultVersion !== comparison.generated.card.latestVersion) {
        throw new Error('문서 버전이 달라졌습니다. 최신 비교를 다시 불러오세요.')
      }
      setPreview(value)
    } catch (reason) {
      if (!controller.signal.aborted) { setError((reason as Error).message); setPreview(null) }
    } finally {
      if (!controller.signal.aborted) setPlanning(false)
    }
  }

  function close() {
    if (saving) return
    if (dirty && !window.confirm('저장하지 않은 편집 내용과 이유를 버리고 닫을까요?')) return
    onClose(!!state?.acceptance)
  }

  async function save() {
    if (!comparison || !state || !draft || state.status !== 'OPEN' || !eligible || !confirmed || saving || planning || !note.trim()
      || (preview && !mappingConfirmed)) return
    const controller = new AbortController()
    pending.current = controller
    setSaving(true); setError('')
    try {
      const value = await request<AcceptanceState>(`${base}/acceptance/merge`, {
        method: 'POST', signal: controller.signal, body: JSON.stringify({
          expectedRevision: state.decisionRevision, expectedSourceVersion: comparison.current.version.number,
          expectedResultVersion: comparison.generated.card.latestVersion, note, confirmed, ...draft,
          ...(preview ? { combineEvidence: true } : {}),
        }),
      })
      if (!controller.signal.aborted) { setState(value); setConfirmed(false); setSaved(true) }
    } catch (reason) {
      if (!controller.signal.aborted) { setError((reason as Error).message); setConfirmed(false); setMappingConfirmed(false) }
    } finally {
      if (!controller.signal.aborted) setSaving(false)
    }
  }

  return <dialog ref={dialog} className="proposal-merge" aria-label="부분 반영 편집" onCancel={event => { event.preventDefault(); close() }}>
    <h3>현재 문서를 바탕으로 부분 반영</h3>
    <p>현재 제목·본문으로 시작합니다. AI 문장에서 필요한 부분을 드래그해 추가하거나 직접 편집하세요. 자동 병합·게시·AI 전송은 하지 않습니다.</p>
    <Button variant="outline" disabled={saving} onClick={close}>편집 닫기</Button>
    <Button variant="outline" disabled={saving || planning} onClick={() => setRefresh(value => value + 1)}>최신 비교 다시 불러오기</Button>
    <p>재조회해도 작성 중인 제목·본문·이유는 유지합니다. 최신 문서와 직접 대조한 뒤 다시 확인 체크하세요. 이 화면을 닫으면 미저장 내용은 사라집니다.</p>
    {error && <p role="alert">{error} 저장 여부가 불확실하면 최신 비교를 다시 불러와 채택 기록부터 확인하세요.</p>}
    {!comparison && !error && <p role="status">비교 자료를 불러오는 중…</p>}
    {state?.acceptance && <p role="status">이미 채택 완료 · 원래 문서 v{state.acceptance.appliedVersion} · {state.acceptance.mode === 'MANUAL' ? '수동 병합' : '전체 채택'}. 게시는 별도 검토가 필요합니다.</p>}
    {comparison && <div className="comparison-grid">
      <section><h4>생성 당시 문서 v{comparison.base.version.number}</h4><pre>{comparison.base.version.markdown}</pre></section>
      <section><h4>현재 문서 v{comparison.current.version.number}</h4><strong>{comparison.current.version.title}</strong><pre>{comparison.current.version.markdown}</pre></section>
      <section><h4>{preview ? '근거 번호를 변환한 AI 결과 v1' : 'AI 최초 결과 v1'}</h4>
        <textarea aria-label="선택할 AI 원문" readOnly rows={10} value={preview?.plan.generatedMarkdown ?? comparison.generated.version.markdown}
          onSelect={event => { const element = event.currentTarget; setSelection(element.value.slice(element.selectionStart, element.selectionEnd)) }} />
        <Button variant="outline" disabled={saving || planning || !!state?.acceptance || !selection.trim() || !draft || draft.markdown.length + selection.length + 2 > 100000}
          onClick={() => { setDraft(previous => previous && { ...previous, markdown: previous.markdown + '\n\n' + selection }); setConfirmed(false); setMappingConfirmed(false) }}>선택한 AI 문장 추가</Button>
      </section>
    </div>}
    {comparison && !state?.acceptance && <Button variant="outline" disabled={saving || planning || !!preview} onClick={() => void planEvidence()}>
      {planning ? '근거 번호 확인 중…' : '근거 번호 통합 미리보기'}
    </Button>}
    {preview && <section aria-label="근거 번호 변환표">
      <h4>현재 문서 번호는 유지하고 AI 근거를 뒤에 연결합니다</h4>
      {preview.plan.mapping.map((ordinal, index) => <p key={index}>AI [E{index + 1}] → 통합 [E{ordinal}]</p>)}
      <p>AI 선택창만 번호를 변환했습니다. 이미 편집하거나 붙여넣은 본문은 자동 변환하지 않습니다. 최종 본문을 아래 통합 번호표와 직접 대조하세요.</p>
    </section>}
    {comparison && <details><summary>반영할 근거 번호 확인</summary>{(preview?.plan.evidence ?? comparison.current.version.evidence).map((item, index) =>
      <div key={`${item.snapshotId}-${index}`}><p>[E{index + 1}] {item.path} · {item.startLine}~{item.endLine}행 · {item.freshness}</p><pre>{item.quote}</pre></div>)}</details>}
    {comparison && !eligible && <p role="alert">근거 연결이 다르거나 변경·삭제됐거나 AI 결과 카드가 편집됐습니다. 근거 번호 통합 미리보기가 성공하기 전에는 반영할 수 없습니다.</p>}
    {state?.status === 'REJECTED' && <p>거절 상태입니다. 닫고 처리 기록에서 재검토로 돌린 뒤 다시 여세요.</p>}
    {draft && <div className="knowledge-form">
      <label>반영할 제목<input maxLength={120} value={draft.title} disabled={saving || planning || !!state?.acceptance}
        onChange={event => { setDraft({ ...draft, title: event.target.value }); setConfirmed(false); setMappingConfirmed(false) }} /></label>
      <label>반영할 Markdown<textarea rows={12} maxLength={100000} value={draft.markdown} readOnly={!!state?.acceptance} disabled={saving || planning}
        onChange={event => { setDraft({ ...draft, markdown: event.target.value }); setConfirmed(false); setMappingConfirmed(false) }} /></label>
      <label>부분 반영 이유<textarea maxLength={4000} value={note} disabled={saving || !!state?.acceptance} onChange={event => setNote(event.target.value)} /></label>
      <label><input type="checkbox" checked={confirmed} disabled={saving || !!state?.acceptance} onChange={event => setConfirmed(event.target.checked)} />
        최신 문서·근거와 대조했고 사람 편집을 보존할 내용을 확인했습니다. 새 미검증 초안으로 저장합니다.
      </label>
      {preview && <label><input type="checkbox" checked={mappingConfirmed} disabled={saving || !!state?.acceptance} onChange={event => setMappingConfirmed(event.target.checked)} />
        변환표를 확인했고 최종 본문의 인용을 통합 번호에 맞췄습니다.
      </label>}
      <Button disabled={saving || planning || !state || state.status !== 'OPEN' || !eligible || !confirmed || (preview && !mappingConfirmed) || !note.trim() || !draft.title.trim() || !draft.markdown.trim()}
        onClick={() => void save()}>{saving ? '부분 반영 저장 중…' : '편집한 내용을 새 초안으로 반영'}</Button>
    </div>}
  </dialog>
}
