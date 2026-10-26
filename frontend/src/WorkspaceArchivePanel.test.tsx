import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import WorkspaceArchivePanel from './WorkspaceArchivePanel'
import { ApiError, request } from './api'
import { apiFetch } from './desktopTransport'
import { downloadApi } from './download'

vi.mock('./api', async importOriginal => ({ ...await importOriginal<typeof import('./api')>(), request: vi.fn() }))
vi.mock('./desktopTransport', () => ({ apiFetch: vi.fn() }))
vi.mock('./download', () => ({ downloadApi: vi.fn() }))
const api = vi.mocked(request)
const upload = vi.mocked(apiFetch)
const download = vi.mocked(downloadApi)
const recoveryKey = 'dougle.workspace-archive.pending'
const preview = { previewId: '11111111-1111-1111-1111-111111111111', digest: 'a'.repeat(64), expiresAt: '2099-01-01T00:00:00Z',
  workspaceName: '복사한 자료', materials: 2, threads: 1, turns: 2, answers: 1, limitation: '원격 연결과 실행 승인은 이전하지 않습니다.' }
const result = { workspaceId: 'imported', threadIds: ['thread'], materials: 2, indexStatus: 'QUEUED_LOCAL_REBUILD' }
const props = { workspaceId: 'workspace', workspaceNames: ['기존 자료'], onBusyChange: vi.fn(), onApplied: vi.fn().mockResolvedValue(undefined) }
function response(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } }) }
const file = () => new File(['synthetic zip bytes'], 'synthetic.zip', { type: 'application/zip' })
async function selectAndName() {
  await userEvent.upload(screen.getByLabelText('자료·대화 ZIP 파일'), file())
  await userEvent.type(screen.getByLabelText('아카이브용 새 Workspace 이름'), preview.workspaceName)
}
async function prepareAndConfirm() {
  await selectAndName()
  await userEvent.click(screen.getByRole('button', { name: '아카이브 미리보기' }))
  await userEvent.click(await screen.findByRole('checkbox', { name: '새 Workspace에 자료·대화 복사를 확인했습니다.' }))
}
beforeEach(() => {
  localStorage.clear(); api.mockReset(); upload.mockReset(); download.mockReset(); vi.clearAllMocks()
  props.onApplied.mockResolvedValue(undefined)
})

it('선택한 Workspace만 명시적으로 내려받고 지원하지 않는 범위를 표시한다', async () => {
  download.mockResolvedValue(undefined)
  render(<WorkspaceArchivePanel {...props} workspaceId="workspace/one" />)
  expect(download).not.toHaveBeenCalled()
  expect(screen.getByText(/지식 문서·질문 첨부·이전 형식 보관함/)).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '자료·대화 아카이브 다운로드' }))
  expect(download).toHaveBeenCalledExactlyOnceWith('/workspace-archives/export/workspace%2Fone', 'dougle-workspace-archive.zip')
  expect(api).not.toHaveBeenCalled(); expect(upload).not.toHaveBeenCalled()
})

it('Workspace가 없어도 가져오기는 가능하고 외부 작업 잠금은 모든 쓰기를 막는다', async () => {
  const view = render(<WorkspaceArchivePanel {...props} workspaceId={null} />)
  expect(screen.getByRole('button', { name: '자료·대화 아카이브 다운로드' })).toBeDisabled()
  expect(screen.getByLabelText('자료·대화 ZIP 파일')).toBeEnabled()
  view.rerender(<WorkspaceArchivePanel {...props} disabled />)
  expect(screen.getByLabelText('자료·대화 ZIP 파일')).toBeDisabled()
  expect(screen.getByLabelText('아카이브용 새 Workspace 이름')).toBeDisabled()
  expect(upload).not.toHaveBeenCalled()
})

it('ZIP 바이트를 올바른 Content-Type으로 미리보기만 보내고 확인 후에만 적용한다', async () => {
  upload.mockResolvedValueOnce(response(preview)); api.mockResolvedValueOnce(result)
  render(<WorkspaceArchivePanel {...props} />)
  await selectAndName()
  expect(upload).not.toHaveBeenCalled()
  await userEvent.click(screen.getByRole('button', { name: '아카이브 미리보기' }))
  const apply = await screen.findByRole('button', { name: '자료·대화 가져오기' })
  expect(apply).toBeDisabled(); expect(api).not.toHaveBeenCalled()
  expect(upload).toHaveBeenCalledExactlyOnceWith(`/workspace-archives/import-preview?workspaceName=${encodeURIComponent(preview.workspaceName)}`,
    expect.objectContaining({ method: 'POST', headers: { 'Content-Type': 'application/zip' }, body: expect.any(File), signal: expect.any(AbortSignal) }))
  await userEvent.click(screen.getByRole('checkbox', { name: '새 Workspace에 자료·대화 복사를 확인했습니다.' }))
  await userEvent.dblClick(apply)
  expect(await screen.findByRole('region', { name: '자료·대화 이전 결과' })).toHaveTextContent('검색 색인 재구성을 요청했습니다')
  expect(api).toHaveBeenCalledTimes(1)
  expect(JSON.parse(api.mock.calls[0][1]!.body as string)).toEqual({ requestId: expect.stringMatching(/^[a-f0-9-]{36}$/), previewId: preview.previewId, digest: preview.digest })
  expect(props.onApplied).toHaveBeenCalledTimes(1)
  expect(localStorage.getItem(recoveryKey)).toBeNull()
  expect(props.onBusyChange).toHaveBeenLastCalledWith(false)
})

it('이름·파일 변경은 기존 확인과 미리보기를 무효화한다', async () => {
  upload.mockResolvedValueOnce(response(preview))
  render(<WorkspaceArchivePanel {...props} />)
  await prepareAndConfirm()
  await userEvent.type(screen.getByLabelText('아카이브용 새 Workspace 이름'), ' 수정')
  expect(screen.queryByRole('region', { name: '자료·대화 이전 미리보기' })).not.toBeInTheDocument()
  expect(api).not.toHaveBeenCalled()
})

it('응답 유실 시 식별자를 저장하고 같은 요청만 재시도한다', async () => {
  upload.mockResolvedValueOnce(response(preview)); api.mockRejectedValueOnce(new Error('응답 유실')).mockResolvedValueOnce(result)
  render(<WorkspaceArchivePanel {...props} />)
  await prepareAndConfirm()
  await userEvent.click(screen.getByRole('button', { name: '자료·대화 가져오기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('응답 유실')
  const saved = localStorage.getItem(recoveryKey)
  expect(saved).toBe(api.mock.calls[0][1]!.body)
  expect(screen.getByLabelText('아카이브용 새 Workspace 이름')).toBeDisabled()
  expect(screen.getByLabelText('자료·대화 ZIP 파일')).toBeDisabled()
  await userEvent.click(screen.getByRole('button', { name: '아카이브 적용 상태 다시 확인' }))
  await screen.findByRole('region', { name: '자료·대화 이전 결과' })
  expect(api.mock.calls[1][1]!.body).toBe(saved)
  expect(upload).toHaveBeenCalledTimes(1)
})

it('다시 마운트한 뒤 자동 적용 없이 저장한 요청의 완료 결과를 복구한다', async () => {
  const saved = JSON.stringify({ requestId: '22222222-2222-2222-2222-222222222222', previewId: preview.previewId, digest: preview.digest })
  localStorage.setItem(recoveryKey, saved); api.mockResolvedValueOnce(result)
  render(<WorkspaceArchivePanel {...props} />)
  expect(api).not.toHaveBeenCalled(); expect(upload).not.toHaveBeenCalled()
  expect(screen.getByLabelText('자료·대화 ZIP 파일')).toBeDisabled()
  await userEvent.click(screen.getByRole('button', { name: '이전 아카이브 요청 확인' }))
  await screen.findByRole('region', { name: '자료·대화 이전 결과' })
  expect(api.mock.calls[0][1]!.body).toBe(saved)
  expect(localStorage.getItem(recoveryKey)).toBeNull()
  expect(screen.getByText(/이후 삭제한 Workspace/)).toBeInTheDocument()
})

it('확정 거절은 입력을 보존하고 미리보기를 다시 받게 한다', async () => {
  upload.mockResolvedValueOnce(response(preview)); api.mockRejectedValueOnce(new ApiError('같은 이름의 Workspace가 있습니다.', 409))
  render(<WorkspaceArchivePanel {...props} />)
  await prepareAndConfirm(); await userEvent.click(screen.getByRole('button', { name: '자료·대화 가져오기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('같은 이름')
  expect(screen.getByLabelText('아카이브용 새 Workspace 이름')).toBeEnabled()
  expect(screen.queryByRole('button', { name: '자료·대화 가져오기' })).not.toBeInTheDocument()
  expect(localStorage.getItem(recoveryKey)).toBeNull()
})

it('성공 뒤 목록 갱신 실패는 적용을 반복하지 않고 목록만 다시 불러온다', async () => {
  upload.mockResolvedValueOnce(response(preview)); api.mockResolvedValueOnce(result)
  const refresh = vi.fn().mockRejectedValueOnce(new Error('목록 오류')).mockResolvedValueOnce(undefined)
  render(<WorkspaceArchivePanel {...props} onApplied={refresh} />)
  await prepareAndConfirm(); await userEvent.click(screen.getByRole('button', { name: '자료·대화 가져오기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('가져오기는 완료했지만')
  await userEvent.click(screen.getByRole('button', { name: '아카이브 Workspace 목록 다시 불러오기' }))
  await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
  expect(refresh).toHaveBeenCalledTimes(2); expect(api).toHaveBeenCalledTimes(1)
})

it('크기·빈 파일·이름 충돌을 전송 전에 거부한다', async () => {
  render(<WorkspaceArchivePanel {...props} />)
  const large = file(); Object.defineProperty(large, 'size', { value: 9 * 1024 * 1024 + 1 })
  await userEvent.upload(screen.getByLabelText('자료·대화 ZIP 파일'), large)
  expect(screen.getByRole('alert')).toHaveTextContent('9 MiB')
  await userEvent.upload(screen.getByLabelText('자료·대화 ZIP 파일'), new File([], 'empty.zip', { type: 'application/zip' }))
  expect(screen.getByRole('alert')).toHaveTextContent('비어 있지 않은')
  await userEvent.upload(screen.getByLabelText('자료·대화 ZIP 파일'), file())
  await userEvent.type(screen.getByLabelText('아카이브용 새 Workspace 이름'), '기존 자료')
  expect(screen.getByRole('button', { name: '아카이브 미리보기' })).toBeDisabled()
  expect(upload).not.toHaveBeenCalled()
})

it('만료된 미리보기와 손상 응답은 적용하지 않는다', async () => {
  upload.mockResolvedValueOnce(response({ ...preview, expiresAt: '2000-01-01T00:00:00Z' }))
  render(<WorkspaceArchivePanel {...props} />)
  await prepareAndConfirm(); await userEvent.click(screen.getByRole('button', { name: '자료·대화 가져오기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('만료')
  expect(api).not.toHaveBeenCalled()
  upload.mockResolvedValueOnce(response({ ...preview, materials: -1 }))
  await userEvent.click(screen.getByRole('button', { name: '아카이브 미리보기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('응답을 확인하지 못했습니다')
  expect(api).not.toHaveBeenCalled()
})

it('미리보기 오류를 표시하고 새 Workspace를 자동 생성하지 않는다', async () => {
  upload.mockResolvedValueOnce(response({ detail: '근거 해시가 다릅니다.' }, 400))
  render(<WorkspaceArchivePanel {...props} />)
  await selectAndName(); await userEvent.click(screen.getByRole('button', { name: '아카이브 미리보기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('근거 해시')
  expect(api).not.toHaveBeenCalled(); expect(props.onApplied).not.toHaveBeenCalled()
})

it('미리보기 중 언마운트하면 요청을 중단하고 외부 잠금을 해제한다', async () => {
  upload.mockReturnValueOnce(new Promise(() => {}))
  const view = render(<WorkspaceArchivePanel {...props} />)
  await selectAndName(); await userEvent.click(screen.getByRole('button', { name: '아카이브 미리보기' }))
  const signal = upload.mock.calls[0][1]!.signal
  view.unmount()
  expect(signal?.aborted).toBe(true)
  expect(props.onBusyChange).toHaveBeenLastCalledWith(false)
})

it('지원하지 않는 Workspace 다운로드 실패는 가져오기나 갱신을 실행하지 않는다', async () => {
  download.mockRejectedValueOnce(new Error('다운로드하지 못했습니다. (409)'))
  render(<WorkspaceArchivePanel {...props} />)
  expect(screen.getByText(/전체 Workspace 백업이 아닙니다/)).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '자료·대화 아카이브 다운로드' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('(409)')
  expect(api).not.toHaveBeenCalled(); expect(upload).not.toHaveBeenCalled()
  expect(props.onApplied).not.toHaveBeenCalled()
  expect(props.onBusyChange).toHaveBeenLastCalledWith(false)
})

it('다른 ZIP을 선택하면 기존 미리보기와 적용 확인을 폐기한다', async () => {
  upload.mockResolvedValueOnce(response(preview))
  render(<WorkspaceArchivePanel {...props} />)
  await prepareAndConfirm()
  await userEvent.upload(screen.getByLabelText('자료·대화 ZIP 파일'), new File(['other synthetic bytes'], 'other.zip', { type: 'application/zip' }))
  expect(screen.queryByRole('region', { name: '자료·대화 이전 미리보기' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '자료·대화 가져오기' })).not.toBeInTheDocument()
  expect(api).not.toHaveBeenCalled()
})

it('잘못된 복구 JSON은 서버로 보내지 않는다', async () => {
  render(<WorkspaceArchivePanel {...props} />)
  const input = screen.getByLabelText('보관한 아카이브 복구 요청')
  await userEvent.click(input)
  await userEvent.paste(JSON.stringify({ requestId: 'bad', previewId: preview.previewId, digest: preview.digest, token: 'synthetic' }))
  await userEvent.click(screen.getByRole('button', { name: '이전 아카이브 요청 확인' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('복구 요청 JSON')
  expect(api).not.toHaveBeenCalled(); expect(upload).not.toHaveBeenCalled()
})

it('불확실한 복구 기록은 목록 확인을 명시하기 전에는 지울 수 없다', async () => {
  const saved = JSON.stringify({ requestId: '22222222-2222-2222-2222-222222222222', previewId: preview.previewId, digest: preview.digest })
  localStorage.setItem(recoveryKey, saved)
  render(<WorkspaceArchivePanel {...props} />)
  const reset = screen.getByRole('button', { name: '확인 후 다른 아카이브 가져오기' })
  expect(reset).toBeDisabled()
  expect(localStorage.getItem(recoveryKey)).toBe(saved)
  await userEvent.click(screen.getByRole('checkbox', { name: 'Workspace 목록에서 이전 가져오기 결과를 확인했습니다.' }))
  await userEvent.click(reset)
  expect(localStorage.getItem(recoveryKey)).toBeNull()
  expect(screen.getByLabelText('자료·대화 ZIP 파일')).toBeEnabled()
  expect(api).not.toHaveBeenCalled(); expect(upload).not.toHaveBeenCalled()
})
