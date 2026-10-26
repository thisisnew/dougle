import { useEffect, useRef, useState } from 'react'
import { ApiError, request } from './api'
import { Button } from './components/ui/button'

type Scope = { url: string; allowedGetPaths: string[] }
type Preview = { bundleId: string; digest: string; scope: Scope; expiresAt: string; maxAiCalls: number; limits: {
  maxSeconds: number; maxRequests: number; maxResponseBytes: number; maxTotalBytes: number; maxTextCharacters: number
} }
type Result = { url: string; capturedAt: string; title: string; text: string; truncated: boolean; status: string;
  observations: { method: string; path: string; status: number | null; blocked: boolean; candidatePath?: string | null }[] }
type Job = { id: string; workspaceId: string; bundleId: string; status: string; result?: Result | null; error?: string | null }
type Submission = { requestId: string; bundleId: string; digest: string; approved: true }
const active = (job: Job | null) => !!job && ['QUEUED', 'RUNNING', 'CANCELLING'].includes(job.status)
const safePath = (path: string) => path.length <= 512 && /^\/[a-zA-Z0-9/_.,~-]*$/.test(path)
  && !path.includes('//') && !path.split('/').some(segment => segment === '.' || segment === '..')
const pollIntervalMs = 1500
const pollDeadlineMs = 10000
const statusOrder = (status: string) => status === 'QUEUED' ? 0 : status === 'RUNNING' ? 1 : status === 'CANCELLING' ? 2 : 3
type Props = { workspaceId: string; bundleId: string; disabled?: boolean }

export default function BrowserInvestigationPanel(props: Props) {
  return <BrowserInvestigationSession key={JSON.stringify([props.workspaceId, props.bundleId])} {...props} />
}

function BrowserInvestigationSession({ workspaceId, bundleId, disabled = false }: Props) {
  const [open, setOpen] = useState(false)
  const [available, setAvailable] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [url, setUrl] = useState('')
  const [paths, setPaths] = useState('')
  const [preview, setPreview] = useState<Preview | null>(null)
  const [submission, setSubmission] = useState<Submission | null>(null)
  const [job, setJob] = useState<Job | null>(null)
  const locked = useRef(false)
  const lifetime = useRef(new AbortController())
  const pollEpoch = useRef(0)
  const base = `/workspaces/${encodeURIComponent(workspaceId)}/browser-investigations`

  useEffect(() => {
    const controller = new AbortController()
    lifetime.current = controller
    return () => controller.abort()
  }, [])

  function checked(value: Job) {
    if (value.workspaceId !== workspaceId || value.bundleId !== bundleId) throw new Error('브라우저 조사 대상이 다릅니다.')
    return value
  }

  function acceptJob(value: Job) {
    setJob(previous => previous?.id === value.id
      && (!active(previous) || statusOrder(value.status) < statusOrder(previous.status)) ? previous : value)
  }

  async function act(action: (signal: AbortSignal) => Promise<void>) {
    if (locked.current || disabled) return
    locked.current = true; setBusy(true); setMessage('')
    const owner = lifetime.current.signal
    const controller = new AbortController()
    const abort = () => controller.abort()
    owner.addEventListener('abort', abort, { once: true })
    let rejectAborted!: () => void
    const aborted = new Promise<never>((_resolve, reject) => {
      rejectAborted = () => reject(new Error('요청 시간이 초과되었습니다. 접수 여부를 다시 확인해 주세요.'))
      controller.signal.addEventListener('abort', rejectAborted, { once: true })
    })
    const deadline = setTimeout(abort, pollDeadlineMs)
    if (owner.aborted) abort()
    try { await Promise.race([owner.aborted ? Promise.resolve() : action(controller.signal), aborted]) }
    catch (error) { if (!owner.aborted) setMessage((error as Error).message) }
    finally {
      clearTimeout(deadline)
      owner.removeEventListener('abort', abort)
      controller.signal.removeEventListener('abort', rejectAborted)
      locked.current = false
      if (!owner.aborted) setBusy(false)
    }
  }

  async function show() {
    setOpen(true)
    await act(async signal => {
      const capability = await request<{ status: string; message: string }>(`${base}/capability`, { signal })
      if (signal.aborted) return
      setAvailable(capability.status === 'AVAILABLE')
      setMessage(capability.message)
      if (capability.status !== 'AVAILABLE') return
      const jobs = await request<Job[]>(`${base}/jobs?bundleId=${encodeURIComponent(bundleId)}`, { signal })
      if (!signal.aborted && jobs.length) { jobs.forEach(checked); acceptJob(jobs[jobs.length - 1]) }
    })
  }

  async function prepare() {
    await act(async signal => {
      const value = await request<Preview>(`${base}/preview`, { method: 'POST', signal,
        body: JSON.stringify({ bundleId, url, allowedGetPaths: paths.split('\n').map(path => path.trim()).filter(Boolean) }) })
      if (!signal.aborted) { setPreview(value); setJob(null) }
    })
  }

  async function start() {
    if (!preview || active(job)) return
    const value = submission ?? { requestId: crypto.randomUUID(), bundleId, digest: preview.digest, approved: true as const }
    await act(async signal => {
      setSubmission(value)
      try {
        const result = await request<Job>(`${base}/jobs`, { method: 'POST', signal, body: JSON.stringify(value) })
        if (!signal.aborted) { acceptJob(checked(result)); setSubmission(null); setPreview(null) }
      } catch (error) {
        if (!signal.aborted && error instanceof ApiError && [400, 403, 404, 409, 410, 413, 422].includes(error.status)) {
          setSubmission(null); setPreview(null)
        }
        throw error
      }
    })
  }

  useEffect(() => {
    if (!open || !active(job)) return
    const id = job!.id
    let disposed = false
    let controller: AbortController | undefined
    let deadline: ReturnType<typeof setTimeout> | undefined
    let timer: ReturnType<typeof setTimeout>
    async function poll() {
      const requestController = new AbortController()
      controller = requestController
      const epoch = pollEpoch.current
      deadline = setTimeout(() => {
        requestController.abort()
        if (!disposed && epoch === pollEpoch.current) setMessage('조사 상태 요청 시간이 초과되었습니다. 상태를 다시 확인합니다.')
      }, pollDeadlineMs)
      try {
        const value = await request<Job>(`${base}/jobs/${encodeURIComponent(id)}`, { signal: requestController.signal })
        if (disposed || requestController.signal.aborted || epoch !== pollEpoch.current) return
        if (checked(value).id !== id) throw new Error('브라우저 조사 작업이 다릅니다.')
        acceptJob(value)
      } catch {
        if (!disposed && !requestController.signal.aborted && epoch === pollEpoch.current) setMessage('조사 상태를 다시 확인 중입니다.')
      } finally {
        clearTimeout(deadline)
        if (!disposed) timer = setTimeout(() => void poll(), pollIntervalMs)
      }
    }
    timer = setTimeout(() => void poll(), pollIntervalMs)
    return () => { disposed = true; clearTimeout(timer); clearTimeout(deadline); controller?.abort() }
  }, [base, open, job?.id, job?.status])

  async function cancel() {
    if (!job) return
    await act(async signal => {
      pollEpoch.current++
      const value = await request<Job>(`${base}/jobs/${encodeURIComponent(job.id)}/cancel`, { method: 'POST', signal })
      if (!signal.aborted) acceptJob(checked(value))
    })
  }

  function addCandidate(path: string) {
    if (locked.current || disabled || active(job) || preview || submission || !job?.result || !safePath(path) || path.length > 160) return
    const sourceUrl = job.result.url
    const navigation = new URL(sourceUrl).pathname
    const existing = url === sourceUrl ? paths.split('\n').map(value => value.trim()).filter(safePath)
      : job.result.observations.filter(item => item.method === 'GET' && !item.blocked && safePath(item.path)).map(item => item.path)
    const proposed = [...new Set([navigation, ...existing, path])]
    if (proposed.length > 32) { setMessage('GET 경로는 최대 32개입니다. 기존 경로를 정리해 주세요.'); return }
    setUrl(sourceUrl); setPaths(proposed.join('\n'))
    setMessage('후보 경로를 새 범위 초안에 추가했습니다. 접근 범위를 다시 확인하고 새로 동의해야 요청합니다. 쿼리가 붙은 요청은 계속 차단됩니다.')
  }

  if (!open) return <Button variant="ghost" size="sm" disabled={disabled} onClick={() => void show()}>브라우저 화면 조사</Button>
  return <section aria-label="브라우저 화면 조사">
    <p>별도 임시 브라우저에서 승인한 공개 HTTPS 화면과 GET 경로만 읽습니다. 기존 로그인·쿠키를 사용하지 않고 AI에는 보내지 않습니다.</p>
    <p>클릭·입력·POST·다른 사이트·리다이렉트는 차단합니다. GET도 서버 기록 등 영향을 남길 수 있습니다. 로그인 화면은 지원하지 않습니다.</p>
    {message && <p role="status">{message}</p>}
    {available && !preview && !active(job) && <>
      <label>화면 URL<input aria-label="화면 URL" value={url} maxLength={2048} disabled={busy || disabled}
        placeholder="https://example.com/app" onChange={event => setUrl(event.target.value)} /></label>
      <label>허용할 정확한 GET 경로 (한 줄에 하나, 화면 경로 포함)<textarea aria-label="허용 GET 경로"
        value={paths} maxLength={16416} disabled={busy || disabled} placeholder={'/app\n/app.js\n/api/status'}
        onChange={event => setPaths(event.target.value)} /></label>
      <Button variant="outline" disabled={busy || disabled || !url || !paths} onClick={() => void prepare()}>접근 범위 확인</Button>
    </>}
    {preview && <>
      <p>이 화면: {preview.scope.url}</p>
      <pre>{preview.scope.allowedGetPaths.join('\n')}</pre>
      <p>최대 {preview.limits.maxSeconds}초 · {preview.limits.maxRequests}개 요청 · AI 호출 {preview.maxAiCalls}회</p>
      <p>응답당 {preview.limits.maxResponseBytes}바이트, 합계 {preview.limits.maxTotalBytes}바이트, 화면 텍스트 {preview.limits.maxTextCharacters}자 제한</p>
      <Button variant="outline" disabled={busy || disabled} onClick={() => void start()}>{submission ? '접수 상태 확인' : '이 범위로 브라우저 조사 시작'}</Button>
      {!submission && <Button variant="ghost" disabled={busy} onClick={() => setPreview(null)}>범위 수정</Button>}
    </>}
    {job && <>
      <p role="status">{job.status === 'CANCELLING' ? '브라우저 조사 취소 중…' : active(job) ? '브라우저 조사 중…' : job.status === 'SUCCEEDED' ? '브라우저 조사 완료' : job.status === 'CANCELLED' ? '조사 취소됨' : '브라우저 조사 실패'}</p>
      {active(job) && <Button variant="ghost" disabled={busy} onClick={() => void cancel()}>조사 취소</Button>}
      {job.error && <p role="alert">{job.error}</p>}
      {job.result && <details open><summary>브라우저 관찰 · 검증된 답변이 아님</summary>
        {job.result.status === 'AUTH_REQUIRED' && <p>로그인이 필요한 화면입니다. 기존 계정 정보를 가져오지 않았습니다.</p>}
        {job.result.status === 'NAVIGATION_BLOCKED' && <p>화면 접근이 차단되었습니다. 리다이렉트나 오류 응답을 따라가지 않았습니다.</p>}
        <p>{job.result.title}</p><pre>{job.result.text}</pre>
        {job.result.truncated && <p>제한으로 일부 결과만 표시합니다.</p>}
        <p>후보 경로는 페이지가 요청한 값이며 안전성이 검증되지 않았습니다. 추가 버튼은 초안만 바꾸며, 새 동의 전에는 요청하지 않습니다.</p>
        <table><thead><tr><th>메서드</th><th>경로</th><th>상태</th><th>새 범위</th></tr></thead><tbody>
          {job.result.observations.map((item, index) => <tr key={index}><td>{item.method}</td><td>{item.path}</td>
            <td>{item.blocked ? '차단' : 'GET'} {item.status ?? ''}</td>
            <td>{item.blocked && item.method === 'GET' && item.candidatePath && item.candidatePath === item.path
              && item.candidatePath.length <= 160 && safePath(item.candidatePath) &&
              <Button variant="ghost" size="sm" aria-label={`${item.candidatePath} 새 범위 초안에 추가`}
                disabled={busy || disabled || active(job) || !!preview || !!submission}
                onClick={() => addCandidate(item.candidatePath!)}>새 범위 초안에 추가</Button>}</td></tr>)}
        </tbody></table>
      </details>}
    </>}
    <Button variant="ghost" disabled={busy} onClick={() => setOpen(false)}>접기</Button>
  </section>
}
