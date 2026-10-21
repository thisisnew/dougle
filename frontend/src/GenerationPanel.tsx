import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'
import type { GenerationJob } from './GenerationJobsPanel'
import CliStatusPanel from './CliStatusPanel'

type Preview = { provider: string; expectedVersion: number; digest: string; prompt: string; requestedModel: string }
type ModelChoice = { mode: 'server' | 'cli' | 'custom'; model: string }
const preferenceKey = (provider: string) => `forge.ai.model.${provider}`
function loadChoice(provider: string): ModelChoice {
  try {
    const choice = JSON.parse(localStorage.getItem(preferenceKey(provider)) || 'null')
    if (choice && ['server', 'cli', 'custom'].includes(choice.mode) && typeof choice.model === 'string' && choice.model.length <= 128) return choice
  } catch { return { mode: 'server', model: '' } }
  return { mode: 'server', model: '' }
}

export default function GenerationPanel({ base, disabled, onBusy, onQueued }: {
  base: string; disabled: boolean; onBusy: (busy: boolean) => void; onQueued: (id: string) => void
}) {
  const [provider, setProvider] = useState('CODEX')
  const [choice, setChoice] = useState<ModelChoice>(() => loadChoice('CODEX'))
  const [saved, setSaved] = useState(false)
  const [preview, setPreview] = useState<Preview | null>(null)
  const [approved, setApproved] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [submission, setSubmission] = useState<{ requestId: string; provider: string; requestedModel: string; expectedVersion: number; digest: string; approved: true } | null>(null)
  const pending = useRef<AbortController | null>(null)
  useEffect(() => () => pending.current?.abort(), [])

  function changeChoice(value: ModelChoice) {
    setChoice(value); setPreview(null); setApproved(false); setError(''); setSaved(false)
  }

  function saveChoice() {
    try { localStorage.setItem(preferenceKey(provider), JSON.stringify(choice)); setSaved(true) }
    catch { setError('이 브라우저에 모델 선택을 저장할 수 없습니다.') }
  }

  async function run(generate: boolean) {
    if (busy || disabled || (generate && !submission && (!preview || !approved))) return
    if (!generate && choice.mode === 'custom' && !choice.model.trim()) { setError('모델 ID를 입력하세요.'); return }
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); onBusy(true); setError('')
    try {
      if (generate && (submission || preview)) {
        setApproved(false)
        const payload = submission ?? { requestId: crypto.randomUUID(), provider, requestedModel: preview!.requestedModel,
          expectedVersion: preview!.expectedVersion, digest: preview!.digest, approved: true as const }
        setSubmission(payload)
        const result = await request<GenerationJob>(`${base}/generation/jobs`, {
          method: 'POST', signal: controller.signal,
          body: JSON.stringify(payload),
        })
        if (!controller.signal.aborted) { setSubmission(null); setPreview(null); onQueued(result.id) }
      } else {
        setPreview(null); setApproved(false)
        const result = await request<Preview>(`${base}/generation/preview`, {
          method: 'POST', signal: controller.signal, body: JSON.stringify({ provider,
            requestedModel: choice.mode === 'server' ? null : choice.mode === 'cli' ? '' : choice.model }),
        })
        if (!controller.signal.aborted) setPreview(result)
      }
    } catch (reason) {
      if (!controller.signal.aborted) { setError((reason as Error).message); setPreview(null); setApproved(false) }
    } finally {
      if (!controller.signal.aborted) { setBusy(false); onBusy(false) }
    }
  }

  return <section className="draft-section" aria-label="로그인된 CLI로 AI 초안 생성">
    <h3>로그인된 AI로 별도 초안 생성</h3>
    <p>이 PC의 Codex / Claude Code CLI를 설치하고 터미널에서 로그인하세요. 현재 대화가 아닌 새 요청입니다.
      Ollama는 필요 없습니다. 선택한 근거는 제공자 서버로 전송되며 계정 한도·요금·조직 정책이 적용됩니다.</p>
    <label>AI 제공자 <select value={provider} disabled={disabled || busy || !!submission} onChange={event => {
      setProvider(event.target.value); changeChoice(loadChoice(event.target.value))
    }}><option value="CODEX">Codex (OpenAI)</option><option value="CLAUDE">Claude Code (Anthropic)</option></select></label>
    <label>모델 선택 방식 <select value={choice.mode} disabled={disabled || busy || !!submission}
      onChange={event => changeChoice({ ...choice, mode: event.target.value as ModelChoice['mode'] })}>
      <option value="server">서버 설정 사용</option><option value="cli">CLI 기본 선택</option><option value="custom">모델 ID 직접 지정</option>
    </select></label>
    {choice.mode === 'custom' && <label>모델 ID <input value={choice.model} maxLength={128} disabled={disabled || busy || !!submission}
      onChange={event => changeChoice({ ...choice, model: event.target.value })} /></label>}
    <p>이 계정에서 이용 가능한 모델 ID를 입력하세요. 모델 목록·이용 가능 여부는 자동 조회하지 않습니다.</p>
    <Button variant="outline" disabled={disabled || busy || !!submission} onClick={saveChoice}>이 브라우저에 모델 선택 저장</Button>
    {saved && <p role="status">선택을 저장했습니다. 전송 승인은 저장하지 않습니다.</p>}
    <CliStatusPanel provider={provider} disabled={disabled || busy} />
    <Button variant="outline" disabled={disabled || busy || !!submission} onClick={() => void run(false)}>전송 내용 미리보기</Button>
    {preview && <div className="knowledge-form">
      <p aria-label="요청 모델">요청 모델: {preview.requestedModel || 'CLI 기본값'} · 실제 실행 모델은 아직 확인하지 않습니다.</p>
      <p>표시된 요청 모델로 이번 작업을 고정합니다. 모델 변경 후에는 새 미리보기와 승인이 필요합니다.</p>
      <pre aria-label="AI 전송 내용">{preview.prompt}</pre>
      <label><input type="checkbox" checked={approved} disabled={disabled || busy} onChange={event => setApproved(event.target.checked)} />
        {provider}에 표시된 근거와 지시문을 전송하는 데 동의합니다</label>
      <Button disabled={disabled || busy || !approved} onClick={() => void run(true)}>승인하고 별도 AI 초안 생성</Button>
    </div>}
    {busy && <p role="status">요청 처리 중…</p>}
    {submission && !busy && <div>
      <p>접수 여부를 확인하지 못했습니다. 작업 목록을 새로고침하거나 같은 요청 ID로 다시 확인하세요: {submission.requestId}</p>
      <Button disabled={disabled} onClick={() => void run(true)}>같은 요청 ID로 재전송</Button>
      <Button variant="outline" disabled={disabled} onClick={() => { setSubmission(null); setPreview(null); setApproved(false) }}>새 승인 준비</Button>
    </div>}
    {error && <p role="alert">{error}</p>}
    <p>기존 문서를 덮어쓰지 않습니다. 결과는 미검증 DRAFT이며 사실 검증·자동 게시 기능은 아직 없습니다.</p>
  </section>
}
