import { useEffect, useRef, useState } from 'react'
import { request } from './api'

type Settings = { enabled: boolean; revision: number; mixed: boolean; intervalSeconds: number }

const intervals = [300, 900, 1800, 3600, 10800, 21600, 86400]
const intervalLabel = (seconds: number) => seconds < 3600 ? `${seconds / 60}분` : `${seconds / 3600}시간`

export default function WorkspacePollingToggle({ workspaceId, name }: { workspaceId: string; name: string }) {
  const [settings, setSettings] = useState<Settings | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const pending = useRef<AbortController | null>(null)
  const path = `/workspaces/${encodeURIComponent(workspaceId)}/source-polling`
  useEffect(() => {
    const controller = new AbortController()
    pending.current = controller
    setSettings(null)
    setBusy(false)
    setError('')
    request<Settings>(path, { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setSettings(value) })
      .catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
    return () => { controller.abort(); pending.current?.abort() }
  }, [path])
  async function update(intervalSeconds?: number) {
    if (busy) return
    const controller = new AbortController()
    pending.current = controller
    setBusy(true); setError('')
    try {
      const value = await request<Settings>(path, settings
        ? { method: 'PATCH', signal: controller.signal, body: JSON.stringify({ enabled: intervalSeconds === undefined ? !settings.enabled : settings.enabled, expectedRevision: settings.revision, ...(intervalSeconds === undefined ? {} : { intervalSeconds }) }) }
        : { signal: controller.signal })
      if (!controller.signal.aborted) setSettings(value)
    } catch (reason) {
      if (!controller.signal.aborted) {
        setError((reason as Error).message)
        try {
          const latest = await request<Settings>(path, { signal: controller.signal })
          if (!controller.signal.aborted) setSettings(latest)
        }
        catch { if (!controller.signal.aborted) setSettings(null) }
      }
    } finally { if (!controller.signal.aborted) setBusy(false) }
  }
  return <div className="workspace-polling">
    <span>자동 갱신</span>
    <select aria-label={`${name} 갱신 주기`} value={settings?.intervalSeconds ?? 3600}
      disabled={busy || !settings} onChange={event => void update(Number(event.target.value))}>
      {settings && settings.intervalSeconds && !intervals.includes(settings.intervalSeconds) &&
        <option value={settings.intervalSeconds}>{intervalLabel(settings.intervalSeconds)}</option>}
      {intervals.map(seconds => <option key={seconds} value={seconds}>{intervalLabel(seconds)}</option>)}
    </select>
    <button type="button" role="switch" className="polling-toggle" aria-label={`${name} 자동 갱신`}
      aria-checked={settings?.enabled ?? false} data-mixed={settings?.mixed}
      title={settings?.mixed ? '일부 자료만 자동 갱신 중' : '자동 갱신'}
      disabled={busy || (!settings && !error)} onClick={() => void update()}><i /></button>
    {error && <small role="alert">{error}</small>}
  </div>
}
