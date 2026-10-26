import { useEffect, useRef, useState } from 'react'
import { Download, Upload } from 'lucide-react'
import { ApiError, request } from './api'
import { downloadApi } from './download'
import { Button } from './components/ui/button'

type Descriptor = { name: string; kind: string; location: null }
type Settings = { version: number; workspaceName: string; pollIntervalSeconds: number; sources: Descriptor[] }
type Preview = { settings: Settings; workspaceNameConflict: boolean; needsWorkspaceRename: boolean;
  entries: { source: Descriptor; needsLocation: boolean; needsReconnection: boolean; nameConflict: boolean }[];
  applySupported: boolean; limitation: string }
type Plan = { previewId: string; digest: string; expiresAt: string; workspaceName: string; sourcesToCreate: number;
  reconnectSourceIndices: number[]; pollingEnabled: boolean; limitation: string }
type Applied = { workspaceId: string; sourceIds: string[]; reconnectSourceIndices: number[]; pollingEnabled: boolean }
type ApplySubmission = { requestId: string; previewId: string; digest: string }
type Props = { workspaceId: string | null; workspaceNames: string[]; disabled?: boolean;
  onBusyChange: (busy: boolean) => void; onApplied: () => Promise<void> }
const maxBytes = 256 * 1024
const kinds: Record<string, string> = { LOCAL_FOLDER: '로컬 폴더', URL: '웹 링크', GITHUB: 'GitHub', MYSQL: 'MySQL' }

export default function TeamSettingsPanel({ workspaceId, workspaceNames, disabled = false, onBusyChange, onApplied }: Props) {
  const [preview, setPreview] = useState<Preview | null>(null)
  const [workspaceName, setWorkspaceName] = useState('')
  const [names, setNames] = useState<string[]>([])
  const [locations, setLocations] = useState<string[]>([])
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const [plan, setPlan] = useState<Plan | null>(null)
  const [confirmed, setConfirmed] = useState(false)
  const [submission, setSubmission] = useState<ApplySubmission | null>(null)
  const [applied, setApplied] = useState<Applied | null>(null)
  const [refreshFailed, setRefreshFailed] = useState(false)
  const pending = useRef<AbortController | null>(null)
  const reader = useRef<FileReader | null>(null)
  const locked = useRef(false)
  useEffect(() => { onBusyChange(!!busy) }, [busy, onBusyChange])
  useEffect(() => () => { pending.current?.abort(); reader.current?.abort(); onBusyChange(false) }, [onBusyChange])

  async function exportSettings() {
    if (!workspaceId || disabled || locked.current) return
    locked.current = true; setBusy('내보내는 중…'); setError('')
    try { await downloadApi(`/team-settings/export/${encodeURIComponent(workspaceId)}`, 'dougle-team-settings.json') }
    catch (reason) { setError((reason as Error).message) }
    finally { locked.current = false; setBusy('') }
  }

  async function importFile(file: File | undefined) {
    if (!file || disabled || locked.current || (submission && !applied)) return
    setPreview(null); setError(''); setNames([]); setLocations([]); setWorkspaceName('')
    setPlan(null); setConfirmed(false); setSubmission(null); setApplied(null); setRefreshFailed(false)
    if (file.size > maxBytes) { setError('팀 설정 파일은 256 KiB 이하여야 합니다.'); return }
    locked.current = true; setBusy('팀 설정 확인 중…')
    const controller = new AbortController()
    pending.current = controller
    try {
      const text = await new Promise<string>((resolve, reject) => {
        const fileReader = new FileReader()
        reader.current = fileReader
        fileReader.onload = () => resolve(String(fileReader.result))
        fileReader.onerror = () => reject(new Error('팀 설정 파일을 읽지 못했습니다.'))
        fileReader.onabort = () => reject(new Error('파일 읽기를 중단했습니다.'))
        fileReader.readAsText(file)
      })
      if (controller.signal.aborted) return
      if (new TextEncoder().encode(text).length > maxBytes) throw new Error('팀 설정 파일은 256 KiB 이하여야 합니다.')
      const value = await request<Preview>('/team-settings/import-preview', { method: 'POST', body: text, signal: controller.signal })
      if (controller.signal.aborted) return
      setPreview(value); setNames(value.entries.map(entry => entry.source.name)); setLocations(value.entries.map(() => ''))
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) { locked.current = false; setBusy('') } }
  }

  const nameConflict = workspaceNames.some(name => name.toLocaleLowerCase() === workspaceName.trim().toLocaleLowerCase())
  const validName = (name: string, maximum: number) => name.trim().length > 0 && name.trim().length <= maximum
    && !/[\\/\u0000-\u001f\u007f]/.test(name) && !['.', '..'].includes(name.trim())
  const duplicateNames = new Set(names.map(name => name.trim().toLocaleLowerCase())).size !== names.length
  const valid = !!preview && validName(workspaceName, 20) && !nameConflict && !duplicateNames
    && names.every(name => validName(name, 120))
    && preview.entries.every((entry, index) => entry.source.kind === 'MYSQL' || !!locations[index]?.trim())
  const editingDisabled = disabled || !!busy || !!submission
  function changeInput(change: () => void) {
    if (editingDisabled) return
    change(); setPlan(null); setConfirmed(false); setError('')
  }

  async function prepare() {
    if (!preview || !valid || disabled || locked.current || submission || applied) return
    locked.current = true; setBusy('적용 계획 확인 중…'); setError(''); setPlan(null); setConfirmed(false)
    const controller = new AbortController()
    pending.current = controller
    try {
      const body = JSON.stringify({ settings: { ...preview.settings, sources: preview.settings.sources.map((source, index) => ({ ...source, name: names[index].trim() })) },
        workspaceName: workspaceName.trim(), mappings: preview.entries.flatMap((entry, sourceIndex) => entry.source.kind === 'MYSQL' ? [] : [{ sourceIndex, location: locations[sourceIndex].trim() }]) })
      if (new TextEncoder().encode(body).length > maxBytes) throw new Error('위치를 포함한 팀 설정 요청은 256 KiB 이하여야 합니다.')
      const value = await request<Plan>('/team-settings/import-plan', { method: 'POST', body, signal: controller.signal })
      if (controller.signal.aborted) return
      if (value.pollingEnabled !== false || value.workspaceName !== workspaceName.trim() || !Number.isFinite(Date.parse(value.expiresAt))) throw new Error('적용 계획을 확인하지 못했습니다. 다시 확인해 주세요.')
      setPlan(value)
    } catch (reason) { if (!controller.signal.aborted) setError((reason as Error).message) }
    finally { if (!controller.signal.aborted) { locked.current = false; setBusy('') } }
  }

  async function apply() {
    if (!plan || !confirmed || disabled || locked.current || applied) return
    if (!submission && Date.parse(plan.expiresAt) <= Date.now()) { setPlan(null); setConfirmed(false); setError('적용 계획이 만료되었습니다. 다시 확인해 주세요.'); return }
    locked.current = true; setBusy('팀 설정 적용 중…'); setError('')
    const controller = new AbortController()
    pending.current = controller
    const next = submission ?? { requestId: crypto.randomUUID(), previewId: plan.previewId, digest: plan.digest }
    setSubmission(next)
    try {
      const value = await request<Applied>('/team-settings/import-apply', { method: 'POST', body: JSON.stringify(next), signal: controller.signal })
      if (controller.signal.aborted) return
      if (!value.workspaceId || value.pollingEnabled !== false) throw new Error('적용 결과를 확인하지 못했습니다. 같은 요청으로 다시 확인해 주세요.')
      setApplied(value)
      try { await onApplied(); setRefreshFailed(false) }
      catch { setRefreshFailed(true); setError('가져오기는 완료했지만 Workspace 목록을 갱신하지 못했습니다. 목록만 다시 불러와 주세요.') }
    } catch (reason) {
      if (!controller.signal.aborted) {
        if (reason instanceof ApiError && [400, 403, 404, 409, 410, 413, 422].includes(reason.status)) {
          setSubmission(null); setPlan(null); setConfirmed(false)
        }
        setError((reason as Error).message)
      }
    }
    finally { if (!controller.signal.aborted) { locked.current = false; setBusy('') } }
  }

  async function refreshWorkspaces() {
    if (!applied || disabled || locked.current) return
    locked.current = true; setBusy('Workspace 목록 갱신 중…'); setError('')
    try { await onApplied(); setRefreshFailed(false) }
    catch { setError('Workspace 목록을 갱신하지 못했습니다. 다시 불러와 주세요.') }
    finally { locked.current = false; setBusy('') }
  }

  return <section className="settings-page team-settings-page" aria-label="팀 설정">
    <div className="page-heading"><h1>팀 설정</h1></div>
    <p>Workspace 이름과 자료 구성만 공유합니다. 인증정보, 자료 내용, 질문·답변, 폴더·URL·DB 위치는 내보내지 않습니다. 공유 전 표시 이름에 비밀정보가 없는지 확인해 주세요.</p>
    <Button variant="outline" disabled={disabled || !!busy || !workspaceId} onClick={() => void exportSettings()}><Download size={16} />팀 설정 JSON 다운로드</Button>
    {!workspaceId && <p>내보내려면 Workspace를 선택해 주세요. 가져오기는 Workspace 없이도 가능합니다.</p>}
    <label><Upload size={16} aria-hidden="true" />팀 설정 JSON 파일<input type="file" accept=".json,application/json" aria-label="팀 설정 JSON 파일" disabled={disabled || !!busy || (!!submission && !applied)}
      onChange={event => { const file = event.target.files?.[0]; event.target.value = ''; void importFile(file) }} /></label>
    <small>최대 256 KiB · 미리보기만으로 설정을 적용하거나 자료를 수집하지 않습니다.</small>
    {busy && <p role="status">{busy}</p>}
    {error && <p role="alert">{error}</p>}
    {applied && <section aria-label="팀 설정 적용 결과"><h2>새 Workspace를 만들었습니다.</h2><p>{plan?.workspaceName} · 자료 {applied.sourceIds.length}개</p>
      <p>자동 갱신과 동기화는 시작하지 않았습니다. Workspace를 선택한 뒤 필요한 연결과 자료 수집을 직접 진행해 주세요.</p>
      {applied.reconnectSourceIndices.length > 0 && <p>MySQL {applied.reconnectSourceIndices.length}개는 생성하지 않았습니다. 플러그인에서 재연결한 뒤 직접 추가해 주세요.</p>}
      {refreshFailed && <Button variant="outline" disabled={disabled || !!busy} onClick={() => void refreshWorkspaces()}>Workspace 목록 다시 불러오기</Button>}
    </section>}
    {preview && !applied && <section aria-label="팀 설정 미리보기" className="source-form">
      <h2>{preview.settings.workspaceName}</h2>
      <p>자료 {preview.entries.length}개 · 기본 갱신 간격 {preview.settings.pollIntervalSeconds}초</p>
      {preview.workspaceNameConflict && <p>같은 이름의 Workspace가 있습니다. 다른 이름으로 만들어 주세요.</p>}
      {preview.needsWorkspaceRename && <p>새 Workspace 이름은 20자 이내로 바꿔 주세요.</p>}
      <label>새 Workspace 이름<input value={workspaceName} maxLength={20} disabled={editingDisabled} onChange={event => changeInput(() => setWorkspaceName(event.target.value))} /></label>
      {nameConflict && <p role="alert">같은 이름의 Workspace가 있습니다.</p>}
      {duplicateNames && <p role="alert">자료 이름은 서로 다르게 지정해 주세요.</p>}
      {preview.entries.map((entry, index) => <fieldset key={index} disabled={editingDisabled}>
        <legend>{index + 1}. {entry.source.name} · {kinds[entry.source.kind] ?? entry.source.kind}</legend>
        {entry.nameConflict && <p>자료 이름이 중복됩니다. 다른 이름으로 바꿔 주세요.</p>}
        {entry.needsReconnection && <p>재연결 필요 · 인증정보는 가져오지 않습니다. 적용 후 연결과 접근 권한을 직접 확인해 주세요.</p>}
        <label>자료 {index + 1} 이름<input value={names[index]} maxLength={120} onChange={event => changeInput(() => setNames(current => current.map((name, position) => position === index ? event.target.value : name)))} /></label>
        {entry.source.kind === 'MYSQL' ? <p>MySQL 자료는 생성하지 않습니다. 플러그인에서 재연결한 뒤 직접 추가해 주세요.</p> : <>
          {entry.needsLocation && <p>이 PC에서 사용할 위치를 직접 지정해 주세요.</p>}
          <label>자료 {index + 1} 위치<input value={locations[index]} maxLength={2048} autoComplete="off" placeholder={entry.source.kind === 'LOCAL_FOLDER' ? '/폴더/경로' : 'https://'}
            onChange={event => changeInput(() => setLocations(current => current.map((location, position) => position === index ? event.target.value : location)))} /></label>
          {entry.source.kind !== 'LOCAL_FOLDER' && <small>사용자정보·쿼리·fragment가 없는 HTTPS 주소를 입력해 주세요.</small>}
        </>}
      </fieldset>)}
      <p>자동 갱신은 꺼진 상태로 생성합니다. 자료 동기화와 AI 실행은 자동으로 시작하지 않습니다.</p>
      <Button variant="outline" disabled={editingDisabled || !valid} onClick={() => void prepare()}>적용 계획 확인</Button>
      {plan && <section aria-label="팀 설정 적용 계획"><h3>{plan.workspaceName} · 자료 {plan.sourcesToCreate}개 생성</h3><p>{plan.limitation}</p>
        <p>계획 유효 시간: {new Date(plan.expiresAt).toLocaleString('ko-KR')}</p>
        <label><input type="checkbox" checked={confirmed} disabled={editingDisabled} onChange={event => setConfirmed(event.target.checked)} />새 Workspace 생성과 위 자료 등록을 확인했습니다.</label>
        {submission && error && <p>같은 요청 ID로 적용 상태를 확인합니다. 확인 전에는 입력 내용을 변경하지 않습니다.</p>}
        <Button disabled={disabled || !!busy || !confirmed} onClick={() => void apply()}>{submission ? '적용 상태 다시 확인' : '새 Workspace에 적용'}</Button>
      </section>}
    </section>}
  </section>
}
