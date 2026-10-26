import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import TeamSettingsPanel from './TeamSettingsPanel'
import { ApiError, request } from './api'
import { downloadApi } from './download'

vi.mock('./api', async importOriginal => ({ ...await importOriginal<typeof import('./api')>(), request: vi.fn() }))
vi.mock('./download', () => ({ downloadApi: vi.fn() }))
const api = vi.mocked(request)
const download = vi.mocked(downloadApi)
const settings = { version: 1, workspaceName: '가상 팀', pollIntervalSeconds: 3600,
  sources: [{ name: '가상 자료', kind: 'LOCAL_FOLDER', location: null }, { name: '가상 자료', kind: 'URL', location: null }] }
const preview = { settings, workspaceNameConflict: true, needsWorkspaceRename: false, applySupported: false, limitation: '위치는 다시 지정해야 합니다.',
  entries: settings.sources.map((source, index) => ({ source, needsLocation: true, needsReconnection: index === 1, nameConflict: index === 1 })) }
const props = { workspaceId: 'one', workspaceNames: ['가상 팀'], onBusyChange: vi.fn(), onApplied: vi.fn().mockResolvedValue(undefined) }
const file = () => new File([JSON.stringify(settings)], 'synthetic-team.json', { type: 'application/json' })
const importSettings = { ...settings, sources: [settings.sources[0], { ...settings.sources[1], name: '가상 웹' }, { name: '가상 DB', kind: 'MYSQL', location: null }] }
const importPreview = { ...preview, settings: importSettings, workspaceNameConflict: false,
  entries: importSettings.sources.map(source => ({ source, needsLocation: true, needsReconnection: source.kind !== 'LOCAL_FOLDER', nameConflict: false })) }
const plan = { previewId: 'synthetic-plan', digest: 'frozen-digest', expiresAt: '2099-01-01T00:00:00Z', workspaceName: '새 팀',
  sourcesToCreate: 2, reconnectSourceIndices: [2], pollingEnabled: false, limitation: '자동 갱신·동기화·AI는 실행하지 않습니다.' }
const applied = { workspaceId: 'imported', sourceIds: ['folder', 'web'], reconnectSourceIndices: [2], pollingEnabled: false }
async function fillImport() {
  await userEvent.upload(screen.getByLabelText('팀 설정 JSON 파일'), new File([JSON.stringify(importSettings)], 'synthetic-team.json', { type: 'application/json' }))
  await screen.findByRole('region', { name: '팀 설정 미리보기' })
  await userEvent.type(screen.getByLabelText('새 Workspace 이름'), '새 팀')
  await userEvent.type(screen.getByLabelText('자료 1 위치'), '/tmp/synthetic-team')
  await userEvent.type(screen.getByLabelText('자료 2 위치'), 'https://example.invalid/team')
}
async function confirmPlan() {
  await userEvent.click(screen.getByRole('button', { name: '적용 계획 확인' }))
  await userEvent.click(await screen.findByRole('checkbox', { name: '새 Workspace 생성과 위 자료 등록을 확인했습니다.' }))
}
beforeEach(() => { api.mockReset(); download.mockReset(); vi.clearAllMocks() })

it('명시적으로 선택한 Workspace의 구성만 JSON으로 다운로드한다', async () => {
  download.mockResolvedValue(undefined)
  render(<TeamSettingsPanel {...props} workspaceId="one/two" />)
  expect(api).not.toHaveBeenCalled()
  expect(download).not.toHaveBeenCalled()
  await userEvent.click(screen.getByRole('button', { name: '팀 설정 JSON 다운로드' }))
  expect(download).toHaveBeenCalledExactlyOnceWith('/team-settings/export/one%2Ftwo', 'dougle-team-settings.json')
  expect(api).not.toHaveBeenCalled()
  expect(screen.getByText(/인증정보, 자료 내용, 질문·답변/)).toBeInTheDocument()
})

it('Workspace가 없어도 가져오기를 열고 내보내기만 잠근다', () => {
  render(<TeamSettingsPanel {...props} workspaceId={null} />)
  expect(screen.getByRole('button', { name: '팀 설정 JSON 다운로드' })).toBeDisabled()
  expect(screen.getByLabelText('팀 설정 JSON 파일')).toBeEnabled()
  expect(api).not.toHaveBeenCalled()
})

it('원본 JSON을 미리보기로만 보내고 충돌·재연결·빈 위치 입력을 표시한다', async () => {
  api.mockResolvedValue(preview)
  render(<TeamSettingsPanel {...props} />)
  await userEvent.upload(screen.getByLabelText('팀 설정 JSON 파일'), file())
  await screen.findByRole('region', { name: '팀 설정 미리보기' })
  expect(api).toHaveBeenCalledExactlyOnceWith('/team-settings/import-preview', expect.objectContaining({ method: 'POST', body: JSON.stringify(settings) }))
  expect(screen.getByText('같은 이름의 Workspace가 있습니다. 다른 이름으로 만들어 주세요.')).toBeInTheDocument()
  expect(screen.getByText('자료 이름이 중복됩니다. 다른 이름으로 바꿔 주세요.')).toBeInTheDocument()
  expect(screen.getByText(/재연결 필요/)).toBeInTheDocument()
  expect(screen.getByLabelText('새 Workspace 이름')).toHaveValue('')
  expect(screen.getByLabelText('자료 1 위치')).toHaveValue('')
  expect(screen.getByLabelText('자료 2 위치')).toHaveValue('')
  expect(props.onApplied).not.toHaveBeenCalled()
  expect(props.onBusyChange).toHaveBeenCalledWith(true)
  expect(props.onBusyChange).toHaveBeenLastCalledWith(false)
})

it('256 KiB를 넘는 파일은 서버에 전송하지 않는다', async () => {
  render(<TeamSettingsPanel {...props} />)
  await userEvent.upload(screen.getByLabelText('팀 설정 JSON 파일'), new File([' '.repeat(256 * 1024 + 1)], 'large.json', { type: 'application/json' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('256 KiB')
  expect(api).not.toHaveBeenCalled()
})

it('정확히 256 KiB인 JSON은 미리보기로 전송한다', async () => {
  api.mockResolvedValue(preview)
  const text = JSON.stringify(settings)
  const raw = text + ' '.repeat(256 * 1024 - new TextEncoder().encode(text).length)
  const boundary = new File([raw], 'boundary.json', { type: 'application/json' })
  expect(boundary.size).toBe(256 * 1024)
  render(<TeamSettingsPanel {...props} />)
  await userEvent.upload(screen.getByLabelText('팀 설정 JSON 파일'), boundary)
  await screen.findByRole('region', { name: '팀 설정 미리보기' })
  expect(api).toHaveBeenCalledTimes(1)
  expect(api.mock.calls[0][1]?.body).toBe(raw)
})

it('잘못된 새 파일은 이전 미리보기를 지우고 서버 오류를 보여준다', async () => {
  api.mockResolvedValueOnce(preview).mockRejectedValueOnce(new Error('JSON 필드를 확인하세요.'))
  render(<TeamSettingsPanel {...props} />)
  await userEvent.upload(screen.getByLabelText('팀 설정 JSON 파일'), file())
  await screen.findByRole('region', { name: '팀 설정 미리보기' })
  const raw = '{"version":1,"version":2}'
  await userEvent.upload(screen.getByLabelText('팀 설정 JSON 파일'), new File([raw], 'invalid.json', { type: 'application/json' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('JSON 필드를 확인하세요.')
  expect(screen.queryByRole('region', { name: '팀 설정 미리보기' })).not.toBeInTheDocument()
  expect(api.mock.calls[1][1]?.body).toBe(raw)
  expect(props.onApplied).not.toHaveBeenCalled()
})

it('작업 중 중복 클릭과 파일 변경을 잠그고 다운로드 실패 뒤 복구한다', async () => {
  let fail!: (reason: Error) => void
  download.mockImplementation(() => new Promise((_resolve, reject) => { fail = reject }))
  render(<TeamSettingsPanel {...props} />)
  await userEvent.dblClick(screen.getByRole('button', { name: '팀 설정 JSON 다운로드' }))
  expect(download).toHaveBeenCalledTimes(1)
  expect(screen.getByLabelText('팀 설정 JSON 파일')).toBeDisabled()
  await act(async () => fail(new Error('다운로드 실패')))
  expect(screen.getByRole('alert')).toHaveTextContent('다운로드 실패')
  expect(screen.getByLabelText('팀 설정 JSON 파일')).toBeEnabled()
  expect(props.onBusyChange).toHaveBeenLastCalledWith(false)
})

it('화면을 떠나면 미리보기를 취소하고 늦은 결과를 적용하지 않는다', async () => {
  let finish!: (value: unknown) => void
  api.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const view = render(<TeamSettingsPanel {...props} />)
  await userEvent.upload(screen.getByLabelText('팀 설정 JSON 파일'), file())
  await waitFor(() => expect(api).toHaveBeenCalledTimes(1))
  const signal = api.mock.calls[0][1]!.signal!
  view.unmount()
  expect(signal.aborted).toBe(true)
  await act(async () => finish(preview))
  expect(props.onApplied).not.toHaveBeenCalled()
  expect(props.onBusyChange).toHaveBeenLastCalledWith(false)
})

it('상위 작업 잠금이 켜져 있으면 다운로드와 가져오기를 막는다', async () => {
  render(<TeamSettingsPanel {...props} disabled />)
  expect(screen.getByLabelText('팀 설정 JSON 파일')).toBeDisabled()
  await userEvent.click(screen.getByRole('button', { name: '팀 설정 JSON 다운로드' }))
  expect(download).not.toHaveBeenCalled()
  expect(api).not.toHaveBeenCalled()
})

it('위치를 지정한 계획을 확인하고 동의한 뒤에만 새 Workspace를 생성하며 MySQL은 다시 연결한다', async () => {
  api.mockResolvedValueOnce(importPreview).mockResolvedValueOnce(plan).mockResolvedValueOnce(applied)
  render(<TeamSettingsPanel {...props} />)
  await fillImport()
  expect(screen.queryByLabelText('자료 3 위치')).not.toBeInTheDocument()
  expect(screen.getByText('MySQL 자료는 생성하지 않습니다. 플러그인에서 재연결한 뒤 직접 추가해 주세요.')).toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(1)
  await userEvent.click(screen.getByRole('button', { name: '적용 계획 확인' }))
  const apply = await screen.findByRole('button', { name: '새 Workspace에 적용' })
  expect(apply).toBeDisabled()
  expect(props.onApplied).not.toHaveBeenCalled()
  expect(api.mock.calls[1][0]).toBe('/team-settings/import-plan')
  expect(JSON.parse(api.mock.calls[1][1]!.body as string)).toEqual({ settings: importSettings, workspaceName: '새 팀',
    mappings: [{ sourceIndex: 0, location: '/tmp/synthetic-team' }, { sourceIndex: 1, location: 'https://example.invalid/team' }] })
  await userEvent.click(screen.getByRole('checkbox', { name: '새 Workspace 생성과 위 자료 등록을 확인했습니다.' }))
  await userEvent.dblClick(apply)
  expect(await screen.findByRole('region', { name: '팀 설정 적용 결과' })).toHaveTextContent('MySQL 1개는 생성하지 않았습니다.')
  expect(api).toHaveBeenCalledTimes(3)
  expect(api.mock.calls[2][0]).toBe('/team-settings/import-apply')
  expect(JSON.parse(api.mock.calls[2][1]!.body as string)).toEqual({ requestId: expect.stringMatching(/^[0-9a-f-]{36}$/), previewId: plan.previewId, digest: plan.digest })
  expect(props.onApplied).toHaveBeenCalledTimes(1)
  expect(api.mock.calls.every(([path]) => path.startsWith('/team-settings/'))).toBe(true)
})

it.each([new Error('응답 유실'), new ApiError('서버 오류', 500), new ApiError('게이트웨이 오류', 503)])('적용 결과가 불확실하면 입력을 고정하고 같은 요청 ID와 계획으로만 재시도한다: %s', async failure => {
  api.mockResolvedValueOnce(importPreview).mockResolvedValueOnce(plan).mockRejectedValueOnce(failure).mockResolvedValueOnce(applied)
  render(<TeamSettingsPanel {...props} />)
  await fillImport(); await confirmPlan()
  await userEvent.click(screen.getByRole('button', { name: '새 Workspace에 적용' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(failure.message)
  expect(screen.getByLabelText('새 Workspace 이름')).toBeDisabled()
  expect(screen.getByLabelText('자료 1 위치')).toBeDisabled()
  expect(screen.getByLabelText('팀 설정 JSON 파일')).toBeDisabled()
  expect(screen.getByRole('button', { name: '적용 계획 확인' })).toBeDisabled()
  expect(props.onApplied).not.toHaveBeenCalled()
  await userEvent.click(screen.getByRole('button', { name: '적용 상태 다시 확인' }))
  await screen.findByRole('region', { name: '팀 설정 적용 결과' })
  expect(api).toHaveBeenCalledTimes(4)
  expect(api.mock.calls[3][0]).toBe('/team-settings/import-apply')
  expect(api.mock.calls[3][1]?.body).toBe(api.mock.calls[2][1]?.body)
  expect(props.onApplied).toHaveBeenCalledTimes(1)
})

it.each([400, 403, 404, 409, 410, 413, 422])('적용의 확정 거절 %s는 입력을 보존하고 새 계획·새 요청으로 수정하게 한다', async status => {
  const revisedPlan = { ...plan, previewId: 'revised-plan', digest: 'revised-digest', workspaceName: '새 팀 수정' }
  api.mockResolvedValueOnce(importPreview).mockResolvedValueOnce(plan).mockRejectedValueOnce(new ApiError('적용이 거절되었습니다.', status))
    .mockResolvedValueOnce(revisedPlan).mockResolvedValueOnce(applied)
  render(<TeamSettingsPanel {...props} />)
  await fillImport(); await confirmPlan()
  await userEvent.click(screen.getByRole('button', { name: '새 Workspace에 적용' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('적용이 거절되었습니다.')
  expect(screen.getByLabelText('새 Workspace 이름')).toHaveValue('새 팀')
  expect(screen.getByLabelText('자료 1 위치')).toHaveValue('/tmp/synthetic-team')
  expect(screen.getByLabelText('새 Workspace 이름')).toBeEnabled()
  expect(screen.getByLabelText('자료 1 위치')).toBeEnabled()
  expect(screen.getByLabelText('팀 설정 JSON 파일')).toBeEnabled()
  expect(screen.queryByRole('region', { name: '팀 설정 적용 계획' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '적용 상태 다시 확인' })).not.toBeInTheDocument()
  expect(props.onApplied).not.toHaveBeenCalled()
  expect(api).toHaveBeenCalledTimes(3)
  const first = JSON.parse(api.mock.calls[2][1]!.body as string)
  await userEvent.type(screen.getByLabelText('새 Workspace 이름'), ' 수정')
  await confirmPlan()
  await userEvent.click(screen.getByRole('button', { name: '새 Workspace에 적용' }))
  await screen.findByRole('region', { name: '팀 설정 적용 결과' })
  const second = JSON.parse(api.mock.calls[4][1]!.body as string)
  expect(second).toEqual({ requestId: expect.any(String), previewId: revisedPlan.previewId, digest: revisedPlan.digest })
  expect(second.requestId).not.toBe(first.requestId)
  expect(api.mock.calls[3][0]).toBe('/team-settings/import-plan')
  expect(props.onApplied).toHaveBeenCalledTimes(1)
})

it('적용 성공 뒤 세션 갱신 실패는 생성 요청을 반복하지 않고 목록만 다시 불러온다', async () => {
  api.mockResolvedValueOnce(importPreview).mockResolvedValueOnce(plan).mockResolvedValueOnce(applied)
  const refresh = vi.fn().mockRejectedValueOnce(new Error('목록 오류')).mockResolvedValueOnce(undefined)
  render(<TeamSettingsPanel {...props} onApplied={refresh} />)
  await fillImport(); await confirmPlan()
  await userEvent.click(screen.getByRole('button', { name: '새 Workspace에 적용' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('가져오기는 완료했지만')
  expect(screen.queryByRole('button', { name: '적용 상태 다시 확인' })).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Workspace 목록 다시 불러오기' }))
  await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
  expect(refresh).toHaveBeenCalledTimes(2)
  expect(api).toHaveBeenCalledTimes(3)
})

it('계획 이후 이름이나 위치를 바꾸면 이전 확인과 적용 계획을 무효화한다', async () => {
  api.mockResolvedValueOnce(importPreview).mockResolvedValueOnce(plan)
  render(<TeamSettingsPanel {...props} />)
  await fillImport(); await confirmPlan()
  await userEvent.type(screen.getByLabelText('자료 1 위치'), '/changed')
  expect(screen.queryByRole('region', { name: '팀 설정 적용 계획' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '새 Workspace에 적용' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '적용 계획 확인' })).toBeEnabled()
  expect(api).toHaveBeenCalledTimes(2)
})

it('만료된 계획은 적용하지 않고 다시 확인하게 한다', async () => {
  api.mockResolvedValueOnce(importPreview).mockResolvedValueOnce({ ...plan, expiresAt: '2000-01-01T00:00:00Z' })
  render(<TeamSettingsPanel {...props} />)
  await fillImport(); await confirmPlan()
  await userEvent.click(screen.getByRole('button', { name: '새 Workspace에 적용' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('만료되었습니다')
  expect(screen.queryByRole('button', { name: '새 Workspace에 적용' })).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(2)
})

it('자동 갱신이 켜진 계획은 확인이나 적용을 허용하지 않는다', async () => {
  api.mockResolvedValueOnce(importPreview).mockResolvedValueOnce({ ...plan, pollingEnabled: true })
  render(<TeamSettingsPanel {...props} />)
  await fillImport()
  await userEvent.click(screen.getByRole('button', { name: '적용 계획 확인' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('적용 계획을 확인하지 못했습니다')
  expect(screen.queryByRole('button', { name: '새 Workspace에 적용' })).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(2)
})

it('계획 요청이 거부되면 이름과 위치를 보존하고 자동 적용하지 않는다', async () => {
  api.mockResolvedValueOnce(importPreview).mockRejectedValueOnce(new Error('폴더는 절대 경로로 지정하세요.'))
  render(<TeamSettingsPanel {...props} />)
  await fillImport()
  await userEvent.clear(screen.getByLabelText('자료 1 위치'))
  await userEvent.type(screen.getByLabelText('자료 1 위치'), 'relative-folder')
  await userEvent.click(screen.getByRole('button', { name: '적용 계획 확인' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('절대 경로')
  expect(screen.getByLabelText('새 Workspace 이름')).toHaveValue('새 팀')
  expect(screen.getByLabelText('자료 1 위치')).toHaveValue('relative-folder')
  expect(screen.getByLabelText('자료 1 위치')).toBeEnabled()
  expect(screen.queryByRole('button', { name: '새 Workspace에 적용' })).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(2)
})

it('빈 이름·위치나 충돌한 Workspace 이름은 계획을 요청하지 않는다', async () => {
  api.mockResolvedValue(importPreview)
  render(<TeamSettingsPanel {...props} />)
  await fillImport()
  await userEvent.clear(screen.getByLabelText('새 Workspace 이름'))
  expect(screen.getByRole('button', { name: '적용 계획 확인' })).toBeDisabled()
  await userEvent.type(screen.getByLabelText('새 Workspace 이름'), '가상 팀')
  expect(screen.getByRole('alert')).toHaveTextContent('같은 이름')
  expect(screen.getByRole('button', { name: '적용 계획 확인' })).toBeDisabled()
  await userEvent.clear(screen.getByLabelText('새 Workspace 이름'))
  await userEvent.type(screen.getByLabelText('새 Workspace 이름'), '새 팀')
  await userEvent.clear(screen.getByLabelText('자료 1 위치'))
  expect(screen.getByRole('button', { name: '적용 계획 확인' })).toBeDisabled()
  expect(api).toHaveBeenCalledTimes(1)
})
