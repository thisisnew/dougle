import { useEffect, useState } from 'react'
import { request } from './api'
import SourcePollingSettings from './SourcePollingSettings'
import type { Source } from './SourcePanel'

export default function SourceSettings({ workspaceId }: { workspaceId: string }) {
  const [sources, setSources] = useState<Source[]>([])
  const [revision, setRevision] = useState(0)
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    request<Source[]>(`/workspaces/${workspaceId}/sources`, { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setSources(value) })
      .catch(reason => { if (!controller.signal.aborted) setError(reason.message) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [workspaceId, revision])
  return <section className="settings-card"><h2>자동 갱신</h2>
    {error && <p role="alert" className="modal-error">{error}</p>}
    {loading ? <p role="status">불러오는 중…</p> : !sources.length ? <p>자료 탭에서 폴더나 링크를 먼저 연결하세요.</p> : sources.map(source => <div className="settings-source" key={source.id}><h3>{source.name}</h3>
      <SourcePollingSettings source={source} busy={busy} onSave={async settings => {
        setBusy(true); setError('')
        try { await request(`/workspaces/${workspaceId}/sources/${source.id}/polling`, { method: 'PATCH', body: JSON.stringify(settings) }) }
        catch (reason) { setError((reason as Error).message) }
        finally { setBusy(false); setRevision(value => value + 1) }
      }} />
    </div>)}
  </section>
}
