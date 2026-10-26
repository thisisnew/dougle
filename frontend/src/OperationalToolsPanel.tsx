import { useCallback, useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'
import MysqlReadPanel from './MysqlReadPanel'
import DatadogReadPanel from './DatadogReadPanel'
import type { QuestionAttachment } from './useQuestionAttachments'
import { checkedOperationalProposal, type OperationalToolKind, type OperationalToolOpenRequest } from './operationalToolProposal'

export type OperationalToolsPanelProps = {
  workspaceId: string | null
  disabled?: boolean
  onBusyChange?: (busy: boolean) => void
  onCaptured: (workspaceId: string, attachment: QuestionAttachment) => void
  currentBundleId?: string | null
  openRequest?: OperationalToolOpenRequest | null
  onOpenRequestHandled?: (requestId: string) => void
}
type Capabilities = { mysql: boolean; datadogRevision: string | null }

function capabilities(plugins: unknown, datadog: unknown): Capabilities {
  if (!plugins || typeof plugins !== 'object' || !('mysqlConnected' in plugins)
      || typeof plugins.mysqlConnected !== 'boolean' || !datadog || typeof datadog !== 'object'
      || !('configured' in datadog) || typeof datadog.configured !== 'boolean'
      || !('revision' in datadog) || !('site' in datadog)) throw new Error('Invalid capability metadata')
  if (datadog.configured) {
    if (typeof datadog.revision !== 'string' || !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(datadog.revision)
        || typeof datadog.site !== 'string' || !['US1', 'US3', 'US5', 'EU1', 'AP1', 'AP2', 'UK1'].includes(datadog.site)) {
      throw new Error('Invalid Datadog capability metadata')
    }
  } else if (datadog.revision !== null || datadog.site !== null) throw new Error('Inconsistent Datadog capability metadata')
  return { mysql: plugins.mysqlConnected, datadogRevision: datadog.configured ? datadog.revision as string : null }
}

export default function OperationalToolsPanel(props: OperationalToolsPanelProps) {
  const handledRequests = useRef(new Set<string>())
  return props.workspaceId ? <WorkspaceOperationalTools key={JSON.stringify([props.workspaceId, props.currentBundleId ?? null])}
    {...props} workspaceId={props.workspaceId} handledRequests={handledRequests.current} />
    : <section aria-label="운영 자료 조회"><Button variant="outline" disabled aria-expanded={false}>운영 자료 조회</Button>
      <p>Workspace를 선택한 뒤 운영 자료를 조회하세요.</p></section>
}

function WorkspaceOperationalTools({ workspaceId, disabled = false, onBusyChange, onCaptured, currentBundleId, openRequest, onOpenRequestHandled, handledRequests }:
  OperationalToolsPanelProps & { workspaceId: string; handledRequests: Set<string> }) {
  const [open, setOpen] = useState(false)
  const [metadata, setMetadata] = useState<Capabilities | null>(null)
  const [loading, setLoading] = useState(false)
  const [mysqlBusy, setMysqlBusy] = useState(false)
  const [datadogBusy, setDatadogBusy] = useState(false)
  const [error, setError] = useState('')
  const [selection, setSelection] = useState<{ kind: OperationalToolKind; requestId: string; bundleId: string } | null>(null)
  const pending = useRef<AbortController | null>(null)
  const mounted = useRef(true)
  const callbacks = useRef({ onBusyChange, onCaptured, onOpenRequestHandled })
  callbacks.current = { onBusyChange, onCaptured, onOpenRequestHandled }
  const busy = loading || mysqlBusy || datadogBusy
  useEffect(() => { callbacks.current.onBusyChange?.(busy) }, [busy])
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; pending.current?.abort(); callbacks.current.onBusyChange?.(false) }
  }, [])
  const captured = useCallback((targetWorkspace: string, attachment: QuestionAttachment) => {
    if (mounted.current && targetWorkspace === workspaceId) callbacks.current.onCaptured(targetWorkspace, attachment)
  }, [workspaceId])

  async function load() {
    if (disabled || pending.current || mysqlBusy || datadogBusy) return
    const controller = new AbortController()
    pending.current = controller
    setLoading(true); setMetadata(null); setError('')
    const timeout = setTimeout(() => {
      if (pending.current !== controller || controller.signal.aborted || !mounted.current) return
      controller.abort(); pending.current = null
      setLoading(false); setError('연결 상태 확인 시간이 초과되었습니다. 다시 확인하세요.')
    }, 10000)
    controller.signal.addEventListener('abort', () => clearTimeout(timeout), { once: true })
    try {
      const [plugins, datadog] = await Promise.all([
        request<unknown>('/plugins', { signal: controller.signal }),
        request<unknown>(`/plugins/datadog/${encodeURIComponent(workspaceId)}`, { signal: controller.signal }),
      ])
      if (!controller.signal.aborted && mounted.current) setMetadata(capabilities(plugins, datadog))
    } catch {
      if (!controller.signal.aborted && mounted.current) {
        setError('운영 자료 연결 상태를 확인하지 못했습니다. 다시 확인하세요.')
        setLoading(false)
      }
      controller.abort()
    } finally {
      clearTimeout(timeout)
      if (pending.current === controller) pending.current = null
      if (!controller.signal.aborted && mounted.current) setLoading(false)
    }
  }

  useEffect(() => {
    if (!openRequest || handledRequests.has(openRequest.requestId)) return
    handledRequests.add(openRequest.requestId)
    callbacks.current.onOpenRequestHandled?.(openRequest.requestId)
    if (disabled || loading || mysqlBusy || datadogBusy || pending.current) return
    try {
      const proposal = checkedOperationalProposal(openRequest.proposal)
      if (proposal.workspaceId !== workspaceId || proposal.bundleId !== currentBundleId) return
      setSelection({ kind: proposal.kind, requestId: openRequest.requestId, bundleId: proposal.bundleId })
      setOpen(true)
      void load()
    } catch { setError('운영 조회 제안의 대상과 형식을 확인하지 못했습니다.') }
  }, [openRequest, workspaceId, currentBundleId, disabled, loading, mysqlBusy, datadogBusy, handledRequests])

  function toggle() {
    if (disabled || mysqlBusy || datadogBusy) return
    if (open) {
      pending.current?.abort(); pending.current = null
      setOpen(false); setMetadata(null); setLoading(false); setError(''); setSelection(null)
    } else { setSelection(null); setOpen(true); void load() }
  }

  return <section className="operational-tools-panel" aria-label="운영 자료 조회">
    <Button variant="outline" aria-expanded={open} disabled={disabled || mysqlBusy || datadogBusy} onClick={toggle}>운영 자료 조회</Button>
    {open && (!selection || selection.bundleId === currentBundleId) && <div className="source-form">
      <p>열기만으로 DB·로그를 조회하거나 AI를 실행하지 않습니다. 조회 결과는 확인 후 직접 질문 자료로 추가하세요.</p>
      {selection && <p>{selection.kind === 'MYSQL_READ' ? 'MySQL 데이터' : 'Datadog 로그'} 수동 조회 제안입니다. 대상·조건은 비어 있으며 직접 지정해야 합니다.
        <Button variant="ghost" disabled={disabled || busy} onClick={() => setSelection(null)}>모든 운영 도구 보기</Button></p>}
      {loading && <p role="status">운영 자료 연결 상태 확인 중…</p>}
      {error && <><p role="alert">{error}</p><Button variant="outline" disabled={disabled || busy} onClick={() => void load()}>연결 상태 다시 확인</Button></>}
      {metadata && <>
        {(!selection || selection.kind === 'MYSQL_READ') && (metadata.mysql ? <MysqlReadPanel key={`mysql:${workspaceId}:${selection?.requestId ?? 'manual'}`} workspaceId={workspaceId}
          disabled={disabled || loading || datadogBusy} onBusyChange={setMysqlBusy} onCaptured={captured} />
          : <p>MySQL 연결은 설정의 플러그인에서 구성하세요.</p>)}
        {(!selection || selection.kind === 'DATADOG_LOGS') && (metadata.datadogRevision ? <DatadogReadPanel key={`datadog:${workspaceId}:${metadata.datadogRevision}:${selection?.requestId ?? 'manual'}`}
          workspaceId={workspaceId} revision={metadata.datadogRevision} disabled={disabled || loading || mysqlBusy}
          onBusyChange={setDatadogBusy} onCaptured={captured} />
          : <p>Datadog 연결은 설정의 플러그인에서 구성하세요.</p>)}
      </>}
    </div>}
  </section>
}
