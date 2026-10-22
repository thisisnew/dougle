import { useEffect, useState } from 'react'
import { Button } from './components/ui/button'

export type PollingSource = {
  name: string; pollingEnabled: boolean; pollIntervalSeconds: number; pollingRevision: number
  nextPollAt: number | null; consecutiveFailures: number
}

export default function SourcePollingSettings({ source, busy, onSave }: {
  source: PollingSource; busy: boolean
  onSave: (settings: { enabled: boolean; intervalSeconds: number; expectedRevision: number }) => void
}) {
  const [enabled, setEnabled] = useState(source.pollingEnabled)
  const [interval, setInterval] = useState(String(source.pollIntervalSeconds))
  useEffect(() => {
    setEnabled(source.pollingEnabled)
    setInterval(String(source.pollIntervalSeconds))
  }, [source.pollingRevision, source.pollingEnabled, source.pollIntervalSeconds])
  const seconds = Number(interval)
  const valid = Number.isInteger(seconds) && seconds >= 60 && seconds <= 86400
  return <form className="source-polling" aria-label={`${source.name} 자동 갱신 설정`} onSubmit={event => {
    event.preventDefault()
    if (valid) onSave({ enabled, intervalSeconds: seconds, expectedRevision: source.pollingRevision })
  }}>
    <label><input type="checkbox" checked={enabled} disabled={busy} onChange={event => setEnabled(event.target.checked)} />자동 갱신</label>
    <label>주기(초)<input aria-label={`${source.name} 갱신 주기`} type="number" min={60} max={86400} step={1} required value={interval} disabled={busy} onChange={event => setInterval(event.target.value)} /></label>
    <Button type="submit" size="sm" variant="outline" disabled={busy || !valid}>갱신 설정 저장</Button>
    <small>현재 {source.pollingEnabled ? 'ON' : 'OFF'} · {source.nextPollAt ? `다음 시도 ${new Date(source.nextPollAt).toLocaleString()}` : '예약 없음'}{source.consecutiveFailures > 0 ? ` · 연속 실패 ${source.consecutiveFailures}회` : ''}</small>
  </form>
}
