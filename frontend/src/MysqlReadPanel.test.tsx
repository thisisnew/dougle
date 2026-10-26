import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import MysqlReadPanel from './MysqlReadPanel'
import { ApiError, request } from './api'

vi.mock('./api', async importOriginal => ({ ...await importOriginal<typeof import('./api')>(), request: vi.fn() }))
const api = vi.mocked(request)
const preview = { id: 'preview', workspaceId: 'workspace', connection: 'localhost:3306', database: 'fixture', maxBytes: 16384, timeoutSeconds: 10, expiresAt: '2099-01-01' }
beforeEach(() => { api.mockReset() })

async function prepare() {
  fireEvent.click(screen.getByRole('button', { name: 'DB 데이터 조회' }))
  fireEvent.change(screen.getByLabelText('테이블'), { target: { value: 'receipts' } })
  fireEvent.change(screen.getByLabelText('조회할 열 (쉼표로 구분)'), { target: { value: 'id, status' } })
  fireEvent.change(screen.getByLabelText('조건 열'), { target: { value: 'id' } })
  fireEvent.change(screen.getByLabelText('일치하는 값'), { target: { value: 'fixture-1' } })
  fireEvent.click(screen.getByRole('button', { name: '조회 범위 확인' }))
  await screen.findByRole('button', { name: '이 DB에서 조회' })
}

it('열기만으로 조회하지 않고 정확한 범위 확인 뒤에만 실행한다', async () => {
  api.mockResolvedValueOnce(preview).mockResolvedValueOnce({ id: 'preview', status: 'SUCCEEDED', output: '<script>unsafe</script>', rows: 1, truncated: true })
  render(<MysqlReadPanel workspaceId="workspace" />)
  expect(api).not.toHaveBeenCalled()
  await prepare()
  expect(api).toHaveBeenCalledTimes(1)
  expect(JSON.parse(api.mock.calls[0][1]!.body as string)).toEqual({ table: 'receipts', columns: ['id', 'status'], equalities: { id: 'fixture-1' }, limit: 20 })
  expect(screen.getByText(/localhost:3306/)).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '이 DB에서 조회' }))
  expect(await screen.findByText('1행 조회 완료')).toBeVisible()
  expect(screen.getByText(/일부 결과만 표시/)).toBeVisible()
  expect(screen.getByText('<script>unsafe</script>')).toBeVisible()
  expect(document.querySelector('script')).toBeNull()
  expect(JSON.parse(api.mock.calls[1][1]!.body as string)).toMatchObject({ approved: true, requestId: expect.any(String) })
  expect(api.mock.calls.some(([path]) => path.includes('answer') || path.includes('attachments'))).toBe(false)
})

it('응답이 유실되면 조회를 재실행하지 않고 GET으로 결과만 확인한다', async () => {
  api.mockResolvedValueOnce(preview).mockRejectedValueOnce(new Error('연결 끊김'))
    .mockResolvedValueOnce({ id: 'preview', status: 'EMPTY', output: '', rows: 0, truncated: false })
  render(<MysqlReadPanel workspaceId="workspace" />)
  await prepare()
  fireEvent.click(screen.getByRole('button', { name: '이 DB에서 조회' }))
  fireEvent.click(await screen.findByRole('button', { name: '결과 다시 확인' }))
  expect(await screen.findByText('조건에 맞는 데이터가 없습니다.')).toBeVisible()
  expect(api.mock.calls.filter(([path]) => path.endsWith('/execute'))).toHaveLength(1)
  expect(api.mock.calls[2][1]?.method).toBeUndefined()
})

it('조건을 변경하면 이전 미리보기 동의를 제거한다', async () => {
  api.mockResolvedValueOnce(preview)
  render(<MysqlReadPanel workspaceId="workspace" />)
  await prepare()
  fireEvent.change(screen.getByLabelText('일치하는 값'), { target: { value: 'fixture-2' } })
  expect(screen.queryByRole('button', { name: '이 DB에서 조회' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '조회 범위 확인' })).toBeVisible()
})

it('다른 Workspace 미리보기는 실행하지 않는다', async () => {
  api.mockResolvedValueOnce({ ...preview, workspaceId: 'other' })
  render(<MysqlReadPanel workspaceId="workspace" />)
  fireEvent.click(screen.getByRole('button', { name: 'DB 데이터 조회' }))
  for (const [label, value] of [['테이블', 'receipts'], ['조회할 열 (쉼표로 구분)', 'id'], ['조건 열', 'id']]) {
    fireEvent.change(screen.getByLabelText(label), { target: { value } })
  }
  fireEvent.click(screen.getByRole('button', { name: '조회 범위 확인' }))
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Workspace가 다릅니다'))
  expect(screen.queryByRole('button', { name: '이 DB에서 조회' })).not.toBeInTheDocument()
})

it('expired approval is discarded locally without executing', async () => {
  api.mockResolvedValueOnce({ ...preview, expiresAt: '2000-01-01' })
  render(<MysqlReadPanel workspaceId="workspace" />)
  await prepare()
  fireEvent.click(screen.getByRole('button', { name: '이 DB에서 조회' }))
  expect(await screen.findByText(/조회가 만료되었습니다/)).toBeVisible()
  expect(screen.queryByRole('button', { name: '이 DB에서 조회' })).toBeNull()
  expect(api).toHaveBeenCalledTimes(1)
})

it.each([404, 409])('receipt %s clears stale approval and releases navigation lock', async status => {
  const onBusyChange = vi.fn()
  api.mockResolvedValueOnce(preview).mockRejectedValueOnce(new Error('lost'))
    .mockRejectedValueOnce(new ApiError('expired or changed', status))
  render(<MysqlReadPanel workspaceId="workspace" onBusyChange={onBusyChange} />)
  await prepare()
  fireEvent.click(screen.getByRole('button', { name: '이 DB에서 조회' }))
  await screen.findByRole('button', { name: '결과 다시 확인' })
  expect(onBusyChange).toHaveBeenLastCalledWith(true)
  expect(screen.getByRole('button', { name: 'DB 데이터 조회' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '결과 다시 확인' }))
  await screen.findByRole('button', { name: '조회 범위 확인' })
  expect(screen.getByLabelText('테이블')).toBeEnabled()
  expect(onBusyChange).toHaveBeenLastCalledWith(false)
  expect(api.mock.calls.filter(([path]) => path.endsWith('/execute'))).toHaveLength(1)
})
