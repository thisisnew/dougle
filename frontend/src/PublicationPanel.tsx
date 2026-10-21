import { useEffect, useRef, useState, type FormEvent } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'

type State = {
  latestVersion: number
  review: { version: number; reviewer: string; note: string; reviewedAt: string } | null
  publication: { revision: number; version: number; publishedAt: string } | null
  publishedEvidence: { freshness: string }[]
}

export default function PublicationPanel({ base, version, disabled, onBusy, onOpen }: {
  base: string; version: number; disabled: boolean; onBusy: (busy: boolean) => void; onOpen: (version: number) => void
}) {
  const [state, setState] = useState<State | null>(null)
  const [error, setError] = useState('')
  const [reviewer, setReviewer] = useState('')
  const [note, setNote] = useState('')
  const [claimsSupported, setClaimsSupported] = useState(false)
  const [uncertaintyRemoved, setUncertaintyRemoved] = useState(false)
  const [busy, setBusy] = useState(false)
  const pending = useRef<AbortController | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    request<State>(`${base}/publication`, { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setState(value) })
      .catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
    return () => { controller.abort(); pending.current?.abort() }
  }, [base])

  async function submit(review: boolean, event?: FormEvent) {
    event?.preventDefault()
    if (!state || busy || disabled) return
    const controller = new AbortController()
    pending.current = controller
    setBusy(true)
    onBusy(true)
    setError('')
    try {
      const value = await request<State>(`${base}/${review ? 'review' : 'publication'}`, {
        method: 'POST', signal: controller.signal,
        body: JSON.stringify(review ? { expectedVersion: version, reviewer, note, claimsSupported, uncertaintyRemoved }
          : { expectedVersion: version, expectedPublicationRevision: state.publication?.revision ?? 0 }),
      })
      if (!controller.signal.aborted) setState(value)
    } catch (reason) { if (!controller.signal.aborted) setError(`${(reason as Error).message} 지식 새로고침 후 다시 확인하세요.`) }
    finally {
      if (!controller.signal.aborted) {
        setClaimsSupported(false); setUncertaintyRemoved(false); setBusy(false); onBusy(false)
      }
    }
  }

  const blocked = disabled || busy || !state || state.latestVersion !== version
  return <section className="draft-section" aria-label="사람 검토와 게시">
    <h3>사람 검토 → 명시적 게시</h3>
    <p>자동 사실 검증이 아닙니다. 제목과 모든 주장을 근거와 직접 대조하고, 근거 없는 추론을 제거하세요.
      본문 인용은 [E1] 형식입니다. 검토자 이름은 자기 기입이며 인증된 신원이 아닙니다.</p>
    {error && <p role="alert">{error}</p>}
    {!state && !error && <p role="status">게시 상태를 불러오는 중…</p>}
    {state && <>
      {state.publication ? <div>
        <p>현재 게시본 v{state.publication.version} · 게시 이력 #{state.publication.revision}</p>
        <Button variant="outline" disabled={disabled || busy} onClick={() => onOpen(state.publication!.version)}>게시본 열기</Button>
        {state.publishedEvidence.some(item => item.freshness !== 'CURRENT') && <p role="status">게시본 근거가 변경·삭제되었습니다. 과거 게시본은 보존되지만 최신 사실로 간주하지 마세요.</p>}
      </div> : <p>아직 게시본이 없습니다.</p>}
      {state.latestVersion !== version ? <p>이전 버전은 검토·게시할 수 없습니다. 최신 버전을 여세요.</p>
        : state.review ? <div>
          <p>사람 검토 완료 · v{state.review.version} · {state.review.reviewer} · {state.review.reviewedAt}</p>
          <pre>{state.review.note}</pre>
          <Button disabled={blocked || state.publication?.version === version} onClick={() => void submit(false)}>검토한 버전 게시</Button>
        </div> : <form className="knowledge-form" onSubmit={event => void submit(true, event)}>
          <label>검토자<input required maxLength={120} disabled={blocked} value={reviewer} onChange={event => setReviewer(event.target.value)} /></label>
          <label>검토 기록<textarea required maxLength={4000} disabled={blocked} value={note} onChange={event => setNote(event.target.value)} /></label>
          <label><input type="checkbox" checked={claimsSupported} disabled={blocked} onChange={event => setClaimsSupported(event.target.checked)} />제목·모든 주장을 근거와 직접 대조했습니다</label>
          <label><input type="checkbox" checked={uncertaintyRemoved} disabled={blocked} onChange={event => setUncertaintyRemoved(event.target.checked)} />근거 없는 추론을 제거했습니다</label>
          <Button type="submit" disabled={blocked || !claimsSupported || !uncertaintyRemoved}>사람 검토 기록 저장</Button>
        </form>}
    </>}
  </section>
}
