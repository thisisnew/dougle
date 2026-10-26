import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError, request } from './api'
import { Button } from './components/ui/button'
import SlackReplyPanel from './SlackReplyPanel'

type Status = { state: string; teamId: string | null; inboxCount: number; ignoredEvents: number; sessionOnly: boolean }
type Route = { teamId: string; channelId: string; workspaceId: string }
type Sent = { threadId: string; bundleId: string }
type Item = { id: string; workspaceId: string; receivedAt: string; sentQuestion: string | null; sent: Sent | null;
  event: { eventId: string; teamId: string; channelId: string; userId: string; timestamp: string; text: string;
    threadTimestamp?: string; revisionTimestamp?: string; deleted?: boolean } }
export type SlackPanelProps = {
  workspaceId?: string | null
  onBusyChange?: (busy: boolean) => void
  onConversationSaved?: (workspaceId: string, threadId: string, bundleId: string) => void
}
const stateNames: Record<string, string> = {
  NOT_CONFIGURED: '연결 설정 필요', CONNECTING: 'Slack 연결 확인 중', CONNECTED: 'Slack 수신 연결됨',
  DISCONNECTED: '연결 해제됨', CONNECTION_FAILED: 'Slack 인증 또는 연결 실패',
  RECONNECTING: 'Slack 재연결 중 · 누락 메시지가 있을 수 있습니다.',
  AUTH_REQUIRED: 'Slack 인증이 만료되거나 철회되었습니다. 다시 인증해 주세요.',
  PERMISSION_DENIED: 'Slack 접근 권한이 없어 수신을 중지했습니다. 관리자 확인이 필요합니다.',
  RECONNECT_REQUIRED: 'Slack 연결이 종료되었습니다. 토큰으로 다시 연결해 주세요.',
  INBOX_FULL: '수신함 또는 중복 방지 보관 한도에 도달했습니다. 수신을 중지했습니다.',
}

export default function SlackPanel(props: SlackPanelProps) {
  return <ScopedSlackPanel key={props.workspaceId ?? 'no-workspace'} {...props} />
}

function ScopedSlackPanel({ workspaceId, onBusyChange, onConversationSaved }: SlackPanelProps) {
  const [status, setStatus] = useState<Status | null>(null)
  const [routes, setRoutes] = useState<Route[]>([])
  const [items, setItems] = useState<Item[]>([])
  const [appToken, setAppToken] = useState('')
  const [botToken, setBotToken] = useState('')
  const [acknowledged, setAcknowledged] = useState(false)
  const [channel, setChannel] = useState('')
  const [questions, setQuestions] = useState<Record<string, string>>({})
  const [missingThreads, setMissingThreads] = useState<Record<string, string>>({})
  const [confirmedRecovery, setConfirmedRecovery] = useState<Record<string, boolean>>({})
  const [localBusy, setBusy] = useState(false)
  const [replyBusy, setReplyBusy] = useState<Record<string, boolean>>({})
  const replyInProgress = Object.values(replyBusy).some(Boolean)
  const busy = localBusy || replyInProgress
  const [error, setError] = useState('')
  const [readError, setReadError] = useState(false)
  const [message, setMessage] = useState('')
  const locked = useRef(false)
  const pending = useRef<AbortController | null>(null)
  const refreshVersion = useRef(0)
  const inboxPath = workspaceId ? `/slack/workspaces/${encodeURIComponent(workspaceId)}/inbox` : null
  const connected = !readError && status?.state === 'CONNECTED'

  async function refresh(signal: AbortSignal) {
    const version = ++refreshVersion.current
    const readController = new AbortController()
    const abort = () => readController.abort()
    signal.addEventListener('abort', abort, { once: true })
    if (signal.aborted) abort()
    const deadline = setTimeout(abort, 10000)
    try {
      const [nextStatus, nextRoutes, nextItems] = await Promise.all([
        request<Status>('/slack/status', { signal: readController.signal }),
        request<Route[]>('/slack/routes', { signal: readController.signal }),
        inboxPath ? request<Item[]>(inboxPath, { signal: readController.signal }) : Promise.resolve([]),
      ])
      if (signal.aborted || version !== refreshVersion.current) return
      if (readController.signal.aborted) throw new Error('Slack 조회 시간이 초과되었습니다.')
      if (nextItems.some(item => item.workspaceId !== workspaceId)) throw new Error('Slack 수신 항목의 Workspace가 다릅니다.')
      setStatus(nextStatus); setRoutes(nextRoutes); setItems(nextItems); setReadError(false)
    } catch (reason) {
      if (!signal.aborted && version === refreshVersion.current) setReadError(true)
      throw reason
    } finally {
      clearTimeout(deadline)
      signal.removeEventListener('abort', abort)
      readController.abort()
    }
  }

  useEffect(() => {
    const controller = new AbortController()
    let polling = false
    const update = () => {
      if (locked.current || polling) return
      polling = true
      void refresh(controller.signal).catch(() => {}).finally(() => { polling = false })
    }
    update()
    const timer = setInterval(update, 5000)
    return () => { controller.abort(); pending.current?.abort(); clearInterval(timer) }
  }, [inboxPath])
  useEffect(() => { onBusyChange?.(busy); return () => onBusyChange?.(false) }, [busy, onBusyChange])

  async function run(action: (signal: AbortSignal) => Promise<void>) {
    if (locked.current || replyInProgress) return
    const controller = new AbortController()
    pending.current = controller
    locked.current = true; refreshVersion.current++; setBusy(true); setError(''); setMessage('')
    try {
      await action(controller.signal)
      if (!controller.signal.aborted) await refresh(controller.signal)
    } catch (reason) {
      if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : 'Slack 요청을 완료하지 못했습니다.')
    } finally {
      if (!controller.signal.aborted) { locked.current = false; setBusy(false) }
    }
  }

  function connect(event: FormEvent) {
    event.preventDefault()
    if (!acknowledged || !appToken || !botToken) return
    void run(async signal => {
      try {
        await request('/slack/connection', { method: 'POST', signal,
          body: JSON.stringify({ appToken, botToken, acknowledged: true }) })
      } finally {
        if (!signal.aborted) { setAppToken(''); setBotToken(''); setAcknowledged(false) }
      }
    })
  }

  function question(item: Item) { return questions[item.id] ?? (item.event.text.length <= 256 ? item.event.text : '') }

  function send(item: Item, startNewConversation = false) {
    if (!inboxPath || !workspaceId || readError || !question(item).trim()) return
    const expectedThreadId = missingThreads[item.id]
    if (startNewConversation && (!expectedThreadId || !confirmedRecovery[item.id])) return
    void run(async signal => {
      let sent: Sent
      try {
        sent = await request<Sent>(`${inboxPath}/${encodeURIComponent(item.id)}/conversation`, {
          method: 'POST', signal, body: JSON.stringify({ question: question(item).trim(),
            ...(startNewConversation ? { startNewConversation: true, expectedThreadId } : {}) }),
        })
      } catch (reason) {
        if (!signal.aborted && reason instanceof ApiError && reason.status === 409
          && reason.code === 'SLACK_THREAD_MISSING' && reason.expectedThreadId) {
          setMissingThreads(previous => ({ ...previous, [item.id]: reason.expectedThreadId! }))
        }
        if (!signal.aborted) setConfirmedRecovery(previous => ({ ...previous, [item.id]: false }))
        throw reason
      }
      if (signal.aborted) return
      setMissingThreads(previous => { const next = { ...previous }; delete next[item.id]; return next })
      setConfirmedRecovery(previous => ({ ...previous, [item.id]: false }))
      setMessage('질문을 대화에 저장했습니다. AI는 호출하지 않았습니다.')
      onConversationSaved?.(workspaceId, sent.threadId, sent.bundleId)
    })
  }

  return <section aria-label="Slack 수신함" className="slack-panel">
    <h2>Slack 수신함</h2>
    <p role="status">{readError ? 'Slack 상태를 확인할 수 없습니다. 새로고침 후 다시 시도하세요.' : status ? stateNames[status.state] ?? 'Slack 상태를 확인할 수 없습니다.' : 'Slack 상태 확인 중'}</p>
    {readError && <p role="alert">수신 상태 조회에 실패했습니다. 아래 항목은 마지막 조회 결과이며 최신 상태가 아닐 수 있습니다.</p>}
    <p>앱이 켜져 있는 동안 등록한 채널의 새 메시지를 받습니다. 수신만으로 AI를 호출하지 않습니다.</p>
    <p>채널 연결·수신 항목·저장한 대화는 재시작 후 유지됩니다. 토큰만 앱 종료 시 사라집니다.</p>
    <p>채널별 읽기 권한은 확인되지 않았습니다. 연결 성공은 모든 채널의 수신 권한을 의미하지 않습니다.</p>
    <details><summary>고급 연결 설정 · 관리자 앱 준비 필요</summary>
      <p>회사 관리자가 설치한 Slack 앱에서 Socket Mode와 이벤트 구독을 활성화해야 합니다. 앱 토큰에는 connections:write 권한이 필요합니다.</p>
      <p>사용할 채널에 봇을 초대하고 해당 메시지 이벤트 및 읽기 권한을 허용하세요. 예: message.channels와 channels:history, app_mention과 app_mentions:read. 앱 등록·OAuth·회사 승인은 이 화면에서 대신 수행하지 않습니다.</p>
      <p>Slack은 읽기 전용입니다. 메시지 게시·수정·삭제는 지원하지 않습니다. 대화 저장과 수신함 정리는 이 앱의 로컬 자료에만 적용됩니다.</p>
      <p>파일·봇 메시지는 가져오지 않습니다. 받은 수정/삭제 이벤트는 반영하지만 이미 저장한 질문은 바꾸지 않습니다. 연결 종료 시 세션 토큰으로 최대 3회 재연결합니다. 앱 종료·연결 중단 동안의 누락 메시지는 복구하지 않습니다.</p>
      <p>미등록 채널의 이벤트는 저장하지 않습니다. 수신 항목은 최대 200개이며 중복 방지는 최대 24시간 보관합니다. 한도 도달 시 기존 항목을 지우지 않고 수신을 중지합니다.</p>
    {status && !readError && !connected && !['CONNECTING', 'RECONNECTING'].includes(status.state) && <form onSubmit={connect}>
      <fieldset disabled={busy}>
        <label>Slack 앱 토큰<input type="password" autoComplete="off" maxLength={2048} placeholder="xapp-…" value={appToken} onChange={event => setAppToken(event.target.value)} /></label>
        <label>Slack 봇 토큰<input type="password" autoComplete="off" maxLength={2048} placeholder="xoxb-…" value={botToken} onChange={event => setBotToken(event.target.value)} /></label>
        <label><input type="checkbox" checked={acknowledged} onChange={event => setAcknowledged(event.target.checked)} />Slack 이벤트 수신 연결에 동의합니다. AI 전송은 하지 않습니다.</label>
        <Button type="submit" disabled={!acknowledged || !appToken || !botToken}>Slack 수신 연결</Button>
      </fieldset>
    </form>}
    </details>
    {(status?.state === 'CONNECTED' || status?.state === 'CONNECTING' || status?.state === 'RECONNECTING') && <Button type="button" variant="outline" disabled={busy} onClick={() => void run(async signal => {
      await request('/slack/connection', { method: 'DELETE', signal })
    })}>Slack 연결 해제</Button>}
    {status?.teamId && <p>Slack 팀: {status.teamId}</p>}
    {!workspaceId ? <p>수신할 Workspace를 선택하세요.</p> : <>
      <form onSubmit={event => {
        event.preventDefault()
        if (!connected || !channel.trim()) return
        void run(async signal => {
          await request(`/slack/routes/${encodeURIComponent(channel.trim())}`, { method: 'PUT', signal, body: JSON.stringify({ workspaceId }) })
          if (!signal.aborted) setChannel('')
        })
      }}>
        <label>Slack 채널 ID<input value={channel} maxLength={64} placeholder="C… / G… / D…" disabled={!connected || busy} onChange={event => setChannel(event.target.value)} /></label>
        <Button type="submit" disabled={!connected || busy || !channel.trim()}>현재 Workspace로 수신</Button>
      </form>
      <ul aria-label="Slack 채널 연결">{routes.filter(route => route.workspaceId === workspaceId).map(route => <li key={`${route.teamId}/${route.channelId}`}>
        {route.teamId} / {route.channelId}
        <Button type="button" variant="ghost" disabled={busy} aria-label={`${route.channelId} 수신 연결 제거`} onClick={() => void run(async signal => {
          await request(`/slack/routes/${encodeURIComponent(route.teamId)}/${encodeURIComponent(route.channelId)}`, { method: 'DELETE', signal })
        })}>제거</Button>
      </li>)}</ul>
      <p>아래에서 질문을 선택해 대화에 저장하세요. Slack 원문 전체가 근거 자료로 등록되는 것은 아니며, 답변 실행은 대화에서 별도로 요청합니다.</p>
      {items.length === 0 && <p>이 Workspace의 수신 메시지가 없습니다.</p>}
      {items.map(item => <article key={item.id} aria-label={`Slack 메시지 ${item.id}`}>
        <p>{item.event.teamId} / {item.event.channelId} · {item.event.userId}</p>
        <pre>{item.event.text}</pre>
        {item.event.deleted ? <p>Slack에서 삭제된 메시지</p> : item.event.revisionTimestamp && item.event.revisionTimestamp !== item.event.timestamp && <p>Slack에서 수정된 메시지</p>}
        {item.sent ? <>
          <p>대화에 저장됨 · AI 자동 호출 없음</p>
          <p>저장한 질문은 Slack 수정/삭제와 별개로 유지됩니다: {item.sentQuestion}</p>
          {onConversationSaved && <Button type="button" disabled={busy} onClick={() => onConversationSaved(workspaceId, item.sent!.threadId, item.sent!.bundleId)}>저장한 대화 열기</Button>}
        </> : !item.event.deleted && <>
          {item.event.text.length > 256 && <p>긴 메시지입니다. 보낼 질문을 256자 이내로 직접 입력하세요.</p>}
          <label>대화에 보낼 질문<textarea maxLength={256} value={question(item)} disabled={busy} onChange={event => {
            setQuestions(previous => ({ ...previous, [item.id]: event.target.value }))
            setConfirmedRecovery(previous => ({ ...previous, [item.id]: false }))
          }} /></label>
          <Button type="button" disabled={busy || readError || !question(item).trim()} onClick={() => send(item)}>대화에 질문 저장</Button>
          {missingThreads[item.id] && <>
            <label><input type="checkbox" checked={!!confirmedRecovery[item.id]} disabled={busy || readError} onChange={event =>
              setConfirmedRecovery(previous => ({ ...previous, [item.id]: event.target.checked }))} />기존 대화는 삭제되었습니다. 이 질문을 새 로컬 대화로 저장하는 데 동의합니다. AI는 실행하지 않습니다.</label>
            <Button type="button" disabled={busy || readError || !question(item).trim() || !confirmedRecovery[item.id]}
              onClick={() => send(item, true)}>새 대화로 저장</Button>
          </>}
        </>}
        <SlackReplyPanel workspaceId={workspaceId} inboxItemId={item.id} disabled={busy}
          onBusyChange={value => setReplyBusy(previous => previous[item.id] === value ? previous : { ...previous, [item.id]: value })} />
        <Button type="button" variant="ghost" disabled={busy} onClick={() => void run(async signal => {
          await request(`${inboxPath}/${encodeURIComponent(item.id)}`, { method: 'DELETE', signal })
        })}>수신함에서 제거</Button>
      </article>)}
    </>}
    {error && <p role="alert">{error}</p>}
    {message && <p role="status">{message}</p>}
    <Button type="button" variant="outline" disabled={busy} onClick={() => void run(async () => {})}>Slack 수신함 새로고침</Button>
  </section>
}
