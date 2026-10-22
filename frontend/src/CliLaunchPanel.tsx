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

  return <dialog ref={dialog} className="proposal-merge" aria-label="CLI 실행 경로 설정" onCancel={event => { event.preventDefault(); onClose() }}>
    <Button variant="outline" onClick={onClose}>닫기</Button>
    <h2>CLI 실행 경로</h2>
    <p>Finder에서 CLI를 찾지 못하면 설치한 실행 파일의 절대 경로를 입력하세요. 인자·셸 명령은 넣지 않습니다. 빈칸은 앱 시작 기본값입니다.</p>
    <p>저장은 파일을 실행하지 않습니다. 앱을 완전히 종료하고 다시 열어야 적용됩니다. 로그인 정보·API 키는 저장하지 않습니다.</p>
    {error && <p role="alert">{error}</p>}
    {busy && <p role="status">설정을 처리하는 중…</p>}
    {configuration && <>
      <p>현재 실행 설정 — Codex: {configuration.activeCodexCommand} / Claude: {configuration.activeClaudeCommand}</p>
      {configuration.restartRequired && <p role="status">저장된 경로는 아직 적용되지 않았습니다. 진행 중 작업이 끝난 뒤 앱을 종료·재시작하세요. 전송 미리보기와 승인을 다시 확인하세요.</p>}
      <form className="cli-launch-form" onSubmit={event => { event.preventDefault(); void save() }}>
        <label>Codex 절대 경로<input aria-label="Codex 절대 경로" maxLength={4096} value={codexPath} disabled={busy} onChange={event => { setCodexPath(event.target.value); setAcknowledged(false) }} /></label>
        <label>Claude 절대 경로<input aria-label="Claude 절대 경로" maxLength={4096} value={claudePath} disabled={busy} onChange={event => { setClaudePath(event.target.value); setAcknowledged(false) }} /></label>
        <label><input type="checkbox" checked={acknowledged} disabled={busy} onChange={event => setAcknowledged(event.target.checked)} />실행 파일과 해당 폴더를 신뢰합니다. 다음 실행에 적용되는 것을 확인했습니다.</label>
        <Button type="submit" disabled={busy || !acknowledged}>경로 저장</Button>
      </form>
      <h3>현재 실행 설정 진단</h3>
      <label>진단 대상<select aria-label="진단 대상" value={provider} disabled={busy} onChange={event => setProvider(event.target.value)}><option value="CODEX">Codex</option><option value="CLAUDE">Claude Code</option></select></label>
      <CliStatusPanel provider={provider} disabled={busy || configuration.restartRequired} />
      <p>설치 확인은 현재 CLI의 --version을 실행합니다. 모델 요청·자료 전송·로그인을 하지 않습니다. 실행 파일 자체의 동작을 OS 차원에서 제한하는 것은 아닙니다.</p>
    </>}
    <Button variant="outline" disabled={busy} onClick={() => void load()}>설정 다시 불러오기</Button>
  </dialog>
}
