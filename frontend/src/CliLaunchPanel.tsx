import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'
import CliStatusPanel from './CliStatusPanel'

type Configuration = { saved: { codexPath: string; claudePath: string; revision: number }; activeCodexCommand: string; activeClaudeCommand: string; restartRequired: boolean }

export default function CliLaunchPanel({ onClose }: { onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const pending = useRef<AbortController | null>(null)
  const [configuration, setConfiguration] = useState<Configuration | null>(null)
  const [codexPath, setCodexPath] = useState('')
  const [claudePath, setClaudePath] = useState('')
  const [acknowledged, setAcknowledged] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [provider, setProvider] = useState('CODEX')

  function apply(value: Configuration) {
    setConfiguration(value); setCodexPath(value.saved.codexPath); setClaudePath(value.saved.claudePath); setAcknowledged(false)
  }

  async function load() {
    pending.current?.abort()
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setError('')
    try {
      const value = await request<Configuration>('/ai/launch-settings', { signal: controller.signal })
      if (!controller.signal.aborted) apply(value)
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }

  useEffect(() => {
    const element = dialog.current!
    element.showModal()
    void load()
    return () => { pending.current?.abort(); element.close() }
  }, [])

  async function save() {
    if (!configuration || !acknowledged || busy) return
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setError('')
    try {
      const value = await request<Configuration>('/ai/launch-settings', { method: 'PUT', signal: controller.signal,
        body: JSON.stringify({ codexPath, claudePath, expectedRevision: configuration.saved.revision, acknowledged }) })
      if (!controller.signal.aborted) apply(value)
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }

  return <dialog ref={dialog} className="proposal-merge cli-path-dialog" aria-label="CLI 실행 경로 설정" onCancel={event => { event.preventDefault(); onClose() }}>
    <div className="provider-connection-header"><h2>실행 경로</h2><Button variant="ghost" onClick={onClose}>닫기</Button></div>
    <p>빈칸은 기본 경로를 사용합니다. 변경 후 앱을 다시 열어주세요.</p>
    {error && <p role="alert">{error}</p>}
    {busy && <p role="status">설정을 처리하는 중…</p>}
    {configuration && <>
      {configuration.restartRequired && <p role="status">저장된 경로는 아직 적용되지 않았습니다. 앱을 다시 열어주세요.</p>}
      <form className="cli-launch-form" onSubmit={event => { event.preventDefault(); void save() }}>
        <label>Codex 절대 경로<input aria-label="Codex 절대 경로" maxLength={4096} value={codexPath} disabled={busy} onChange={event => { setCodexPath(event.target.value); setAcknowledged(false) }} /></label>
        <label>Claude 절대 경로<input aria-label="Claude 절대 경로" maxLength={4096} value={claudePath} disabled={busy} onChange={event => { setClaudePath(event.target.value); setAcknowledged(false) }} /></label>
        <label><input type="checkbox" checked={acknowledged} disabled={busy} onChange={event => setAcknowledged(event.target.checked)} />이 실행 파일과 폴더를 신뢰합니다.</label>
        <Button type="submit" disabled={busy || !acknowledged}>경로 저장</Button>
      </form>
      <details><summary>실행 도구 확인</summary>
      <label>진단 대상<select aria-label="진단 대상" value={provider} disabled={busy} onChange={event => setProvider(event.target.value)}><option value="CODEX">Codex</option><option value="CLAUDE">Claude Code</option></select></label>
      <CliStatusPanel provider={provider} disabled={busy || configuration.restartRequired} />
      </details>
    </>}
    <Button variant="outline" disabled={busy} onClick={() => void load()}>설정 다시 불러오기</Button>
  </dialog>
}
