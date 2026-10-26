import { useEffect, useRef, useState } from 'react'
import { request } from './api'
import { Button } from './components/ui/button'
import './SlackReplyPanel.css'

type Reply = { id: string; workspaceId: string; inboxItemId: string; body: string; digest: string; scopeHash: string;
  scope: { workspaceId: string; inboxItemId: string; teamId: string; channelId: string; threadTimestamp: string; botUserId: string };
  status: 'PREVIEW' | 'SENDING' | 'SENT' | 'FAILED' | 'UNCERTAIN' | 'CANCELLED' | 'EXPIRED';
  dispatchCount: number; slackMessageTs: string | null; failureCode: string | null; expiresAt: string }
export type SlackReplyPanelProps = { workspaceId: string; inboxItemId: string; disabled?: boolean; onBusyChange?: (busy: boolean) => void }
const statuses: Record<Reply['status'], string> = { PREVIEW: '과거 게시 전 미리보기 · 전송 불가', SENDING: '과거 게시 시도 기록 · 현재 전송하지 않음',
  SENT: '과거 Slack 게시 확인됨', FAILED: '과거 Slack 게시 실패', UNCERTAIN: '과거 게시 여부 불확실 · 재전송 불가',
  CANCELLED: '게시 전 취소됨', EXPIRED: '미리보기 만료됨' }

export default function SlackReplyPanel(props: SlackReplyPanelProps) {
  return <ScopedSlackReplyPanel key={JSON.stringify([props.workspaceId, props.inboxItemId])} {...props} />
}
function ScopedSlackReplyPanel({ workspaceId, inboxItemId, disabled = false, onBusyChange }: SlackReplyPanelProps) {
  const [open, setOpen] = useState(false)
  const [items, setItems] = useState<Reply[]>([])
  const [selected, setSelected] = useState<Reply | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const locked = useRef(false)
  const lifetime = useRef(new AbortController())
  const callbacks = useRef(onBusyChange)
  callbacks.current = onBusyChange
  const base = `/slack/workspaces/${encodeURIComponent(workspaceId)}/replies`
  useEffect(() => {
    const controller = new AbortController(); lifetime.current = controller
    return () => { controller.abort(); callbacks.current?.(false) }
  }, [])
  useEffect(() => { callbacks.current?.(busy) }, [busy])

  function validate(value: Reply) {
    if (!value || typeof value.id !== 'string' || !value.id || value.workspaceId !== workspaceId || value.inboxItemId !== inboxItemId
      || !value.scope || value.scope.workspaceId !== workspaceId || value.scope.inboxItemId !== inboxItemId
      || !/^T[A-Z0-9]+$/.test(value.scope.teamId) || !/^[CGD][A-Z0-9]+$/.test(value.scope.channelId)
      || typeof value.scope.botUserId !== 'string' || !/^U[A-Z0-9]+$/.test(value.scope.botUserId)
      || !/^[0-9]{10,16}\.[0-9]{1,6}$/.test(value.scope.threadTimestamp)
      || typeof value.body !== 'string' || !value.body.trim() || value.body.length > 4000 || !/^[a-f0-9]{64}$/.test(value.digest)
      || !/^[a-f0-9]{64}$/.test(value.scopeHash) || !Object.hasOwn(statuses, value.status)
      || (value.dispatchCount !== 0 && value.dispatchCount !== 1)
      || (value.status === 'PREVIEW' && value.dispatchCount !== 0)
      || (value.status === 'SENT' && (value.dispatchCount !== 1 || typeof value.slackMessageTs !== 'string'
        || !/^[0-9]{10,16}\.[0-9]{1,6}$/.test(value.slackMessageTs)))
      || !Number.isFinite(Date.parse(value.expiresAt))) throw new Error('Slack 답장 영수증의 대상 또는 형식을 확인하지 못했습니다.')
    return value
  }

  async function run(action: 'list' | 'get') {
    if (locked.current || disabled || action === 'get' && !selected) return
    locked.current = true; setBusy(true); setError('')
    const parent = lifetime.current.signal
    const controller = new AbortController()
    const abort = () => controller.abort()
    parent.addEventListener('abort', abort, { once: true })
    const timeout = setTimeout(abort, 20000)
    try {
      const path = action === 'list' ? `${base}?inboxItemId=${encodeURIComponent(inboxItemId)}` : `${base}/${encodeURIComponent(selected!.id)}`
      const result = await Promise.race([
        request<Reply[] | Reply>(path, { method: 'GET', signal: controller.signal }),
        new Promise<never>((_, reject) => controller.signal.addEventListener('abort', () => reject(new Error('Slack 이력 조회 시간이 초과되었습니다. 다시 조회하세요.')), { once: true })),
      ])
      if (parent.aborted) return
      if (controller.signal.aborted) throw new Error('Slack 이력 조회 시간이 초과되었습니다. 다시 조회하세요.')
      if (action === 'list') {
        if (!Array.isArray(result) || result.length > 1000) throw new Error('Slack 답장 이력을 확인하지 못했습니다.')
        const values = result.map(validate)
        if (new Set(values.map(value => value.id)).size !== values.length) throw new Error('Slack 답장 이력에 중복 항목이 있습니다.')
        setItems(values); setSelected(previous => values.find(value => value.id === previous?.id) ?? null); return
      }
      if (Array.isArray(result)) throw new Error('Slack 답장 이력을 확인하지 못했습니다.')
      const value = validate(result)
      if (value.id !== selected!.id) throw new Error('Slack 답장 영수증이 일치하지 않습니다.')
      setSelected(value); setItems(previous => [value, ...previous.filter(item => item.id !== value.id)])
    } catch (reason) {
      if (!parent.aborted) {
        setError(reason instanceof Error ? reason.message : 'Slack 답장을 확인하지 못했습니다.')
      }
    } finally {
      clearTimeout(timeout); parent.removeEventListener('abort', abort); controller.abort()
      if (!parent.aborted) { locked.current = false; setBusy(false) }
    }
  }

  return <section aria-label="Slack 과거 답장 이력" className="slack-reply-panel">
    <Button type="button" variant="outline" disabled={disabled || busy} onClick={() => { setOpen(true); void run('list') }}>Slack 과거 영수증 조회</Button>
    {open && <>
      <p>읽기 전용 과거 기록입니다. Slack에 메시지를 게시하거나 기존 기록의 상태를 변경하지 않습니다.</p>
      {!busy && !error && items.length === 0 && <p>저장된 과거 답장 영수증이 없습니다.</p>}
      <ul aria-label="Slack 답장 영수증">{items.map(item => <li key={item.id}>
        {statuses[item.status]} <Button type="button" variant="ghost" disabled={disabled || busy} onClick={() => setSelected(item)}>과거 답장 내용 확인 {item.id}</Button>
      </li>)}</ul>
      {selected && <>
        <p>{selected.scope.teamId} / {selected.scope.channelId} / 원본 스레드 {selected.scope.threadTimestamp}</p>
        <pre aria-label="저장된 과거 답장 내용">{selected.body}</pre>
        <p role="status">{statuses[selected.status]}</p>
        {selected.failureCode && <p>결과 코드: {selected.failureCode}</p>}
        {selected.slackMessageTs && <p>Slack 메시지 ID: {selected.slackMessageTs}</p>}
        <Button type="button" variant="outline" disabled={disabled || busy} onClick={() => void run('get')}>영수증 다시 조회</Button>
      </>}
      {error && <p role="alert">{error}</p>}
    </>}
  </section>
}
