import { useEffect, useState } from 'react'
import { request } from './api'

type Count = { state: string; count: number }
export type SlackInboxIndicatorProps = { workspaceId?: string | null }

export default function SlackInboxIndicator({ workspaceId }: SlackInboxIndicatorProps) {
  const [result, setResult] = useState<{ workspaceId: string; value: Count } | null>(null)
  useEffect(() => {
    setResult(null)
    if (!workspaceId) return
    let controller: AbortController | null = null
    let disposed = false
    let pending = false
    const poll = async () => {
      if (pending || disposed) return
      pending = true
      const current = new AbortController()
      controller = current
      const deadline = setTimeout(() => { current.abort(); if (!disposed) setResult(null) }, 10000)
      try {
        const value = await request<Count>(`/slack/workspaces/${encodeURIComponent(workspaceId)}/inbox-count`, { signal: current.signal })
        if (!disposed && !current.signal.aborted) setResult({ workspaceId, value })
      } catch {
        if (!disposed) setResult(null)
      } finally { clearTimeout(deadline); pending = false }
    }
    void poll()
    const timer = setInterval(() => void poll(), 15000)
    return () => { disposed = true; controller?.abort(); clearInterval(timer) }
  }, [workspaceId])
  const value = result?.workspaceId === workspaceId ? result?.value : null
  if (value?.state !== 'CONNECTED' || !Number.isSafeInteger(value.count) || value.count <= 0) return null
  return <span className="slack-inbox-indicator" aria-label={`Slack 수신함 ${value.count}개`} title={`Slack 수신함 ${value.count}개`}>
    Slack {Math.min(value.count, 200)}
  </span>
}
