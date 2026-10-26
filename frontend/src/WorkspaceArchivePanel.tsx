import { useEffect, useRef, useState } from 'react'
import { Download, Upload } from 'lucide-react'
import { ApiError, request } from './api'
import { apiFetch } from './desktopTransport'
import { downloadApi } from './download'
import { Button } from './components/ui/button'

type Preview = { previewId: string; digest: string; expiresAt: string; workspaceName: string;
  materials: number; threads: number; turns: number; answers: number; limitation: string }
type Applied = { workspaceId: string; threadIds: string[]; materials: number; indexStatus: string }
type Submission = { requestId: string; previewId: string; digest: string }
export type WorkspaceArchivePanelProps = { workspaceId: string | null; workspaceNames: string[]; disabled?: boolean;
  onBusyChange: (busy: boolean) => void; onApplied: () => Promise<void> }
const maximumBytes = 9 * 1024 * 1024
const recoveryKey = 'dougle.workspace-archive.pending'
function parseSubmission(text: string): Submission {
  const value = JSON.parse(text) as Submission
  const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
  if (!value || Object.keys(value).sort().join(',') !== 'digest,previewId,requestId'
      || !uuid.test(value.requestId) || !uuid.test(value.previewId) || !/^[a-f0-9]{64}$/.test(value.digest)) throw new Error('복구 요청 JSON의 식별자와 확인 값을 확인하세요.')
  return value
}
function savedSubmission(): Submission | null {
  try { return parseSubmission(localStorage.getItem(recoveryKey) ?? '') } catch { return null }
}

export default function WorkspaceArchivePanel({ workspaceId, workspaceNames, disabled = false, onBusyChange, onApplied }: WorkspaceArchivePanelProps) {
  const [file, setFile] = useState<File | null>(null)
  const [workspaceName, setWorkspaceName] = useState('')
  const [preview, setPreview] = useState<Preview | null>(null)
  const [confirmed, setConfirmed] = useState(false)
  const [submission, setSubmission] = useState<Submission | null>(savedSubmission)
  const [recoveryText, setRecoveryText] = useState(() => { const saved = savedSubmission(); return saved ? JSON.stringify(saved) : '' })
  const [checkedExisting, setCheckedExisting] = useState(false)
  const [applied, setApplied] = useState<Applied | null>(null)
  const [refreshFailed, setRefreshFailed] = useState(false)
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const pending = useRef<AbortController | null>(null)
  const locked = useRef(false)
  const mounted = useRef(true)
  const busyCallback = useRef(onBusyChange)
  busyCallback.current = onBusyChange
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; pending.current?.abort(); busyCallback.current(false) }
  }, [])
  const name = workspaceName.trim()
  const nameConflict = workspaceNames.some(existing => existing.toLocaleLowerCase() === name.toLocaleLowerCase())
  const validName = name.length > 0 && name.length <= 20 && !/[\\/\u0000-\u001f\u007f]/.test(name) && !['.', '..'].includes(name)
  const frozen = disabled || !!busy || !!submission || !!applied

  function begin(message: string) {
    if (disabled || locked.current) return null
    locked.current = true
    setBusy(message); setError(''); busyCallback.current(true)
    const controller = new AbortController()
    pending.current = controller
    return controller
  }
  function finish() {
    locked.current = false
    if (mounted.current) { setBusy(''); busyCallback.current(false) }
  }
  function invalidate() { setPreview(null); setConfirmed(false); setError('') }

  async function exportArchive() {
    if (!workspaceId) return
    const controller = begin('아카이브 준비 중…')
    if (!controller) return
    try { await downloadApi(`/workspace-archives/export/${encodeURIComponent(workspaceId)}`, 'dougle-workspace-archive.zip') }
    catch (reason) { if (mounted.current) setError((reason as Error).message) }
    finally { finish() }
  }

  function selectFile(selected: File | undefined) {
    if (frozen || locked.current) return
    invalidate(); setFile(null)
    if (!selected) return
    if (selected.size === 0 || selected.size > maximumBytes) { setError('비어 있지 않은 9 MiB 이하 ZIP 파일을 선택하세요.'); return }
    setFile(selected)
  }

  async function prepare() {
    if (!file || !validName || nameConflict || submission || applied) return
    const controller = begin('아카이브 검증·로컬 분석 중…')
    if (!controller) return
    setPreview(null); setConfirmed(false)
    try {
      const response = await apiFetch(`/workspace-archives/import-preview?workspaceName=${encodeURIComponent(name)}`, {
        method: 'POST', headers: { 'Content-Type': 'application/zip' }, body: file, signal: controller.signal,
      })
      if (!response.ok) {
        const problem = await response.json().catch(() => ({}))
        throw new ApiError(problem.detail || `아카이브를 확인하지 못했습니다. (${response.status})`, response.status)
      }
      const value = await response.json() as Preview
      if (controller.signal.aborted || !mounted.current) return
      if (!value.previewId || !/^[a-f0-9]{64}$/.test(value.digest) || value.workspaceName !== name
          || !Number.isFinite(Date.parse(value.expiresAt)) || typeof value.limitation !== 'string'
          || ![value.materials, value.threads, value.turns, value.answers].every(count => Number.isInteger(count) && count >= 0)
          || value.materials > 63 || value.threads > 100 || value.turns > 100 || value.answers > 100) {
        throw new Error('아카이브 미리보기 응답을 확인하지 못했습니다.')
      }
      setPreview(value)
    } catch (reason) { if (mounted.current && !controller.signal.aborted) setError((reason as Error).message) }
    finally { finish() }
  }

  async function apply() {
    if (!preview || !confirmed || applied) return
    if (!submission && Date.parse(preview.expiresAt) <= Date.now()) {
      invalidate(); setError('미리보기가 만료되었습니다. 아카이브를 다시 확인하세요.'); return
    }
    const controller = begin('새 Workspace에 자료·대화 가져오는 중…')
    if (!controller) return
    const next = submission ?? { requestId: crypto.randomUUID(), previewId: preview.previewId, digest: preview.digest }
    setSubmission(next)
    setRecoveryText(JSON.stringify(next))
    try {
      localStorage.setItem(recoveryKey, JSON.stringify(next))
      const value = await request<Applied>('/workspace-archives/import-apply', { method: 'POST', body: JSON.stringify(next), signal: controller.signal })
      if (controller.signal.aborted || !mounted.current) return
      if (!value.workspaceId || !Array.isArray(value.threadIds) || value.materials !== preview.materials || value.threadIds.length !== preview.threads
          || value.indexStatus !== 'QUEUED_LOCAL_REBUILD') throw new Error('적용 결과가 불확실합니다. 같은 미리보기로 결과를 다시 확인하세요.')
      setApplied(value)
      localStorage.removeItem(recoveryKey)
      try { await onApplied(); if (mounted.current) setRefreshFailed(false) }
      catch { if (mounted.current) { setRefreshFailed(true); setError('가져오기는 완료했지만 Workspace 목록을 갱신하지 못했습니다. 목록만 다시 불러오세요.') } }
    } catch (reason) {
      if (mounted.current && !controller.signal.aborted) {
        setError((reason as Error).message)
        if (!submission && reason instanceof ApiError && [400, 403, 404, 409, 410, 413, 422].includes(reason.status)) {
          localStorage.removeItem(recoveryKey)
          setSubmission(null); setPreview(null); setConfirmed(false)
        }
      }
    } finally { finish() }
  }

  async function recover() {
    let previous: Submission
    try { previous = parseSubmission(recoveryText) }
    catch { setError('복구 요청 JSON의 식별자와 확인 값을 확인하세요.'); return }
    const controller = begin('기존 아카이브 가져오기 결과 확인 중…')
    if (!controller) return
    setSubmission(previous)
    try {
      localStorage.setItem(recoveryKey, JSON.stringify(previous))
      const value = await request<Applied>('/workspace-archives/import-apply', { method: 'POST', body: JSON.stringify(previous), signal: controller.signal })
      if (!mounted.current || controller.signal.aborted) return
      if (!value.workspaceId || !Array.isArray(value.threadIds) || !Number.isInteger(value.materials) || value.indexStatus !== 'QUEUED_LOCAL_REBUILD') throw new Error('기존 가져오기 결과를 확인하지 못했습니다.')
      setApplied(value); localStorage.removeItem(recoveryKey)
      try { await onApplied(); if (mounted.current) setRefreshFailed(false) }
      catch { if (mounted.current) { setRefreshFailed(true); setError('가져오기 결과는 확인했지만 Workspace 목록을 갱신하지 못했습니다.') } }
    } catch (reason) { if (mounted.current && !controller.signal.aborted) setError((reason as Error).message) }
    finally { finish() }
  }

  function resetRecovery() {
    if (!checkedExisting || disabled || locked.current) return
    localStorage.removeItem(recoveryKey)
    setSubmission(null); setRecoveryText(''); setCheckedExisting(false); setFile(null); invalidate()
  }

  async function refresh() {
    const controller = begin('Workspace 목록 갱신 중…')
    if (!controller) return
    try { await onApplied(); if (mounted.current) setRefreshFailed(false) }
    catch { if (mounted.current) setError('Workspace 목록을 갱신하지 못했습니다. 다시 시도하세요.') }
    finally { finish() }
  }

  return <section className="settings-page workspace-archive-page" aria-label="자료·대화 이전">
    <div className="page-heading"><h2>자료·대화 이전</h2></div>
    <p>원본 파일·폴더와 대화의 질문·완료 답변·원본 근거를 새 Workspace로 복사합니다. 연결 위치·인증·실행 승인은 제외하며 원격 동기화나 AI를 실행하지 않습니다.</p>
    <p>파일·대화 본문은 그대로 포함됩니다. 공유 전 비밀정보를 직접 확인하세요. 지식 문서·질문 첨부·이전 형식 보관함·미완료 답변은 지원하지 않으며 해당 자료가 있으면 내보내기를 거부합니다.</p>
    <p>전체 Workspace 백업이 아닙니다. 고정 문맥 요청·실패/취소 답변 작업·분류 범위가 있는 근거·변경되거나 삭제된 원본 근거도 이전할 수 없습니다. 수집 중에는 내보낼 수 없습니다. 원격 연결 설정·검색 제외 기록·앱 설정은 복사하지 않으며, 가져온 자료는 연결이 없는 독립 복사본입니다.</p>
    <Button variant="outline" disabled={disabled || !!busy || !workspaceId} onClick={() => void exportArchive()}><Download size={16} />자료·대화 아카이브 다운로드</Button>
    {!workspaceId && <p>내보내려면 Workspace를 선택하세요. 가져오기는 Workspace 없이도 가능합니다.</p>}
    <label><Upload size={16} aria-hidden="true" />자료·대화 ZIP 파일<input type="file" accept=".zip,application/zip" aria-label="자료·대화 ZIP 파일" disabled={frozen}
      onChange={event => { selectFile(event.target.files?.[0]); event.target.value = '' }} /></label>
    {file && <p>선택한 파일: {file.name}</p>}
    <small>ZIP 최대 9 MiB · 파일당 1 MiB · 전체 내용 8 MiB · 폴더 포함 63개 · 질문·답변 각 100개</small>
    <label>아카이브용 새 Workspace 이름<input maxLength={20} value={workspaceName} disabled={frozen}
      onChange={event => { if (!frozen) { setWorkspaceName(event.target.value); invalidate() } }} /></label>
    {nameConflict && !applied && <p role="alert">같은 이름의 Workspace가 있습니다. 새 이름을 입력하세요.</p>}
    <Button variant="outline" disabled={frozen || !file || !validName || nameConflict} onClick={() => void prepare()}>아카이브 미리보기</Button>
    {busy && <p role="status">{busy}</p>}
    {error && <p role="alert">{error}</p>}
    {preview && !applied && <section aria-label="자료·대화 이전 미리보기">
      <h3>{preview.workspaceName}</h3>
      <p>자료 {preview.materials}개 · 대화 {preview.threads}개 · 질문 {preview.turns}개 · 완료 답변 {preview.answers}개</p>
      <p>{preview.limitation}</p>
      <p>미리보기는 10분 또는 앱 재시작까지 유효합니다. 기존 Workspace를 덮어쓰거나 자동 전환하지 않습니다.</p>
      <label><input type="checkbox" checked={confirmed} disabled={disabled || !!busy || !!submission}
        onChange={event => setConfirmed(event.target.checked)} />새 Workspace에 자료·대화 복사를 확인했습니다.</label>
      <Button disabled={disabled || !!busy || !confirmed} onClick={() => void apply()}>{submission ? '아카이브 적용 상태 다시 확인' : '자료·대화 가져오기'}</Button>
      {submission && error && <p>결과가 불확실하면 동일한 요청 ID로 다시 확인하세요. 완료 결과는 앱 재시작 후에도 보존됩니다. 실행하지 않은 미리보기만 만료됩니다.</p>}
    </section>}
    {submission && !applied && <label>아카이브 복구 요청 JSON<textarea readOnly value={JSON.stringify(submission)} />
      <small>앱 주소가 바뀌거나 브라우저 저장소를 지우면 자동 복구 정보가 사라질 수 있습니다. 이 JSON을 별도로 보관하면 기존 요청을 확인할 수 있습니다. 파일 내용이나 인증정보는 포함하지 않습니다.</small></label>}
    {!preview && !applied && <section aria-label="이전 아카이브 가져오기 복구">
      <h3>이전 가져오기 결과 확인</h3>
      <label>보관한 아카이브 복구 요청<textarea value={recoveryText} disabled={disabled || !!busy} onChange={event => setRecoveryText(event.target.value)} /></label>
      <Button variant="outline" disabled={disabled || !!busy || !recoveryText.trim()} onClick={() => void recover()}>이전 아카이브 요청 확인</Button>
      <p>기존 요청만 다시 확인합니다. 이미 삭제한 Workspace의 완료 기록은 복원하지 않습니다. 미완료 요청은 원래 확인한 계획이 유효할 때만 완료될 수 있습니다.</p>
      {submission && <><label><input type="checkbox" checked={checkedExisting} disabled={disabled || !!busy}
        onChange={event => setCheckedExisting(event.target.checked)} />Workspace 목록에서 이전 가져오기 결과를 확인했습니다.</label>
        <Button variant="outline" disabled={disabled || !!busy || !checkedExisting} onClick={resetRecovery}>확인 후 다른 아카이브 가져오기</Button></>}
    </section>}
    {applied && <section aria-label="자료·대화 이전 결과">
      <h3>자료·대화 가져오기 완료 기록을 확인했습니다.</h3>
      <p>{preview?.workspaceName} · 자료 {applied.materials}개 · 대화 {applied.threadIds.length}개</p>
      <p>검색 색인 재구성을 요청했습니다. 검색 엔진이 준비된 뒤 검색할 수 있습니다. 원격 연결과 실행 승인은 이전하지 않았으며 자동 갱신·AI를 시작하지 않았습니다.</p>
      <p>이후 삭제한 Workspace는 이 기록 확인으로 다시 생성되지 않습니다.</p>
      {refreshFailed && <Button variant="outline" disabled={disabled || !!busy} onClick={() => void refresh()}>아카이브 Workspace 목록 다시 불러오기</Button>}
    </section>}
  </section>
}
