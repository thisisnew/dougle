import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'

type Preview = { provider: string; expectedVersion: number; digest: string; prompt: string }

export default function GenerationPanel({ base, disabled, onBusy, onCreated }: {
  base: string; disabled: boolean; onBusy: (busy: boolean) => void; onCreated: (id: string) => void
}) {
  const [provider, setProvider] = useState('CODEX')
  const [preview, setPreview] = useState<Preview | null>(null)
  const [approved, setApproved] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const pending = useRef<AbortController | null>(null)
  useEffect(() => () => pending.current?.abort(), [])

  async function run(generate: boolean) {
    if (busy || disabled || (generate && (!preview || !approved))) return
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); onBusy(true); setError('')
    try {
      if (generate && preview) {
        setApproved(false)
        const result = await request<{ card: { id: string } }>(`${base}/generation`, {
          method: 'POST', signal: controller.signal,
          body: JSON.stringify({ provider, expectedVersion: preview.expectedVersion, digest: preview.digest, approved: true }),
        })
        if (!controller.signal.aborted) onCreated(result.card.id)
      } else {
        setPreview(null); setApproved(false)
        const result = await request<Preview>(`${base}/generation/preview`, {
          method: 'POST', signal: controller.signal, body: JSON.stringify({ provider }),
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
    <label>AI 제공자 <select value={provider} disabled={disabled || busy} onChange={event => {
      setProvider(event.target.value); setPreview(null); setApproved(false); setError('')
    }}><option value="CODEX">Codex (OpenAI)</option><option value="CLAUDE">Claude Code (Anthropic)</option></select></label>
    <Button variant="outline" disabled={disabled || busy} onClick={() => void run(false)}>전송 내용 미리보기</Button>
    {preview && <div className="knowledge-form">
      <pre aria-label="AI 전송 내용">{preview.prompt}</pre>
      <label><input type="checkbox" checked={approved} disabled={disabled || busy} onChange={event => setApproved(event.target.checked)} />
        {provider}에 표시된 근거와 지시문을 전송하는 데 동의합니다</label>
      <Button disabled={disabled || busy || !approved} onClick={() => void run(true)}>승인하고 별도 AI 초안 생성</Button>
    </div>}
    {busy && <p role="status">처리 중… 생성은 최대 120초입니다. 화면을 닫아도 서버 작업은 계속될 수 있습니다.</p>}
    {error && <p role="alert">{error}</p>}
    <p>기존 문서를 덮어쓰지 않습니다. 결과는 미검증 DRAFT이며 사실 검증·자동 게시 기능은 아직 없습니다.</p>
  </section>
}
