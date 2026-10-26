import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import DatadogPanel from './DatadogPanel'
import { ApiError, request } from './api'

vi.mock('./api', async importOriginal => ({ ...await importOriginal<typeof import('./api')>(), request: vi.fn() }))
const api = vi.mocked(request)
const status = { configured: true, site: 'EU1', revision: 'fixture-revision' }
const from = new Date(Date.now() - 1800000).toISOString().slice(0, 16)
const to = new Date(Date.now() - 60000).toISOString().slice(0, 16)
const query = { connectionRevision: status.revision, environment: 'test', service: 'orders', indexes: ['fixture'],
  from: `${from}:00.000Z`, to: `${to}:00.000Z`, limit: 20 }
const preview = { id: 'preview', workspaceId: 'workspace', site: 'EU1', query,
  expiresAt: new Date(Date.now() + 600000).toISOString(), maxBytes: 16384, timeoutSeconds: 10, sessionOnly: true }
const empty = { id: 'preview', status: 'EMPTY', output: '', rows: 0, truncated: false }
beforeEach(() => api.mockReset())

async function fields() {
  await screen.findByLabelText('환경')
  for (const [label, value] of Object.entries({ 환경: 'test', 서비스: 'orders', '인덱스 (쉼표 구분)': 'fixture', '조회 시작 (UTC)': from, '조회 종료 (UTC)': to })) {
    fireEvent.change(screen.getByLabelText(label), { target: { value } })
  }
}
async function prepare() {
  await fields()
  fireEvent.click(screen.getByRole('button', { name: '조회 미리보기' }))
  await screen.findByLabelText('표시한 범위의 일회 로그 조회를 승인합니다.')
}
function execute() {
  fireEvent.click(screen.getByLabelText('표시한 범위의 일회 로그 조회를 승인합니다.'))
  fireEvent.click(screen.getByRole('button', { name: '승인한 로그 조회' }))
}

it('requires workspace and hides manual keys; never implies OAuth or verified access', async () => {
  const view = render(<DatadogPanel workspaceId={null} />)
  expect(api).not.toHaveBeenCalled()
  api.mockResolvedValueOnce(status)
  view.rerender(<DatadogPanel workspaceId="workspace" />)
  expect(await screen.findByText(/연결 정보 저장됨/)).toHaveTextContent('접근 권한 미확인')
  expect(screen.getByText(/OAuth.*아직 제공되지/)).toBeVisible()
  expect(screen.queryByLabelText('Datadog API 키')).toBeNull()
  expect(api).toHaveBeenCalledTimes(1)
})

it('manual configuration is explicit, clears keys and makes no log call', async () => {
  api.mockResolvedValueOnce({ configured: false }).mockResolvedValueOnce(status)
  render(<DatadogPanel workspaceId="workspace" />)
  await waitFor(() => expect(screen.getByRole('button', { name: '관리자 연결' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '관리자 연결' }))
  expect(screen.getByLabelText('Datadog API 키')).toHaveAttribute('type', 'password')
  fireEvent.change(screen.getByLabelText('Datadog API 키'), { target: { value: 'synthetic_api_key_0000' } })
  fireEvent.change(screen.getByLabelText('Datadog Application 키'), { target: { value: 'synthetic_app_key_0000' } })
  expect(screen.getByRole('button', { name: '연결 정보 저장' })).toBeDisabled()
  fireEvent.change(screen.getByLabelText('Datadog 사이트'), { target: { value: 'EU1' } })
  fireEvent.click(screen.getByRole('button', { name: '연결 정보 저장' }))
  await screen.findByText(/연결 정보를 저장했습니다/)
  expect(api.mock.calls[1][0]).toBe('/plugins/datadog/workspace')
  expect(api.mock.calls[1][1]?.method).toBe('PUT')
  expect(api).toHaveBeenCalledTimes(2)
  fireEvent.click(screen.getByRole('button', { name: '관리자 연결' }))
  expect(screen.getByLabelText('Datadog API 키')).toHaveValue('')
  expect(screen.getByLabelText('Datadog Application 키')).toHaveValue('')
})

it('previews exact scope, executes only explicit consent and renders bounded escaped output', async () => {
  api.mockResolvedValueOnce(status).mockResolvedValueOnce(preview)
    .mockResolvedValueOnce({ ...empty, status: 'OK', output: '<script>synthetic</script>', rows: 1, truncated: true })
  render(<DatadogPanel workspaceId="workspace" />)
  await prepare()
  expect(api).toHaveBeenCalledTimes(2)
  expect(api.mock.calls[1][0]).toBe('/workspaces/workspace/datadog-investigations/preview')
  expect(JSON.parse(api.mock.calls[1][1]!.body as string)).toEqual(query)
  expect(screen.getByRole('button', { name: '승인한 로그 조회' })).toBeDisabled()
  expect(screen.getByText(/앱 세션에서만/)).toBeVisible()
  execute()
  await screen.findByText('1개 로그 조회 완료')
  expect(screen.getByText('<script>synthetic</script>')).toBeVisible()
  expect(document.querySelector('script')).toBeNull()
  expect(screen.getByText(/일부 로그만 표시/)).toBeVisible()
  expect(api.mock.calls[2][0]).toBe('/workspaces/workspace/datadog-investigations/preview/execute')
  expect(JSON.parse(api.mock.calls[2][1]!.body as string)).toEqual({ approved: true, requestId: expect.any(String) })
  expect(api.mock.calls.some(([path]) => path.includes('answer') || path.includes('attachments'))).toBe(false)
})

it('scope edits invalidate approval and wildcard or future windows cannot preview', async () => {
  api.mockResolvedValueOnce(status).mockResolvedValueOnce(preview)
  render(<DatadogPanel workspaceId="workspace" />)
  await prepare()
  fireEvent.click(screen.getByLabelText('표시한 범위의 일회 로그 조회를 승인합니다.'))
  fireEvent.change(screen.getByLabelText('서비스'), { target: { value: '*' } })
  expect(screen.queryByRole('button', { name: '승인한 로그 조회' })).toBeNull()
  expect(screen.getByRole('button', { name: '조회 미리보기' })).toBeDisabled()
  fireEvent.change(screen.getByLabelText('서비스'), { target: { value: 'orders' } })
  fireEvent.change(screen.getByLabelText('조회 종료 (UTC)'), { target: { value: '2099-01-01T12:00' } })
  expect(screen.getByRole('button', { name: '조회 미리보기' })).toBeDisabled()
  expect(api).toHaveBeenCalledTimes(2)
})

it('lost execution response uses receipt GET without replay or leaking error details', async () => {
  api.mockResolvedValueOnce(status).mockResolvedValueOnce(preview).mockRejectedValueOnce(new Error('secret-token'))
    .mockResolvedValueOnce(empty)
  render(<DatadogPanel workspaceId="workspace" />)
  await prepare(); execute()
  await screen.findByText(/자동 재실행하지 않습니다/)
  expect(screen.queryByText('secret-token')).toBeNull()
  expect(screen.getByLabelText('환경')).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '조회 상태 확인' }))
  await screen.findByText('조건에 맞는 로그가 없습니다.')
  expect(api.mock.calls[3][0]).toBe('/workspaces/workspace/datadog-investigations/preview')
  expect(api.mock.calls[3][1]?.method).toBeUndefined()
  expect(api.mock.calls.filter(([path]) => path.endsWith('/execute'))).toHaveLength(1)
})

it('cancels on actual endpoint while execute is pending and discards no receipt', async () => {
  let finish!: (value: unknown) => void
  api.mockResolvedValueOnce(status).mockResolvedValueOnce(preview)
    .mockImplementationOnce(() => new Promise(resolve => { finish = resolve })).mockResolvedValueOnce(undefined)
  render(<DatadogPanel workspaceId="workspace" />)
  await prepare(); execute()
  fireEvent.click(screen.getByRole('button', { name: '로그 조회 취소' }))
  await screen.findByText(/취소를 요청했습니다/)
  expect(api.mock.calls[3][0]).toBe('/workspaces/workspace/datadog-investigations/preview/cancel')
  expect(api.mock.calls[3][1]?.method).toBe('POST')
  await act(async () => finish({ ...empty, status: 'CANCELLED' }))
  expect(screen.getByText('로그 조회가 취소되었습니다.')).toBeVisible()
})

it('restart or expiry fails closed and permits a fresh preview without retrying execution', async () => {
  api.mockResolvedValueOnce(status).mockResolvedValueOnce(preview).mockRejectedValueOnce(new Error('lost'))
    .mockRejectedValueOnce(new ApiError('restarted', 404))
  render(<DatadogPanel workspaceId="workspace" />)
  await prepare(); execute()
  await screen.findByText(/자동 재실행하지 않습니다/)
  fireEvent.click(screen.getByRole('button', { name: '조회 상태 확인' }))
  await screen.findByText(/새 미리보기가 필요합니다/)
  expect(screen.getByLabelText('환경')).toBeEnabled()
  expect(screen.queryByRole('button', { name: '승인한 로그 조회' })).toBeNull()
})

it('workspace change drops manual key state and old scoped controls', async () => {
  api.mockResolvedValue(status)
  const view = render(<DatadogPanel workspaceId="one" />)
  await screen.findByLabelText('환경')
  fireEvent.click(screen.getByRole('button', { name: '관리자 연결' }))
  fireEvent.change(screen.getByLabelText('Datadog API 키'), { target: { value: 'synthetic_api_key_0000' } })
  const signal = api.mock.calls[0][1]?.signal
  view.rerender(<DatadogPanel workspaceId="two" />)
  await screen.findByLabelText('환경')
  expect(signal?.aborted).toBe(true)
  expect(screen.queryByLabelText('Datadog API 키')).toBeNull()
  expect(screen.getByLabelText('환경')).toHaveValue('')
})

it('expired preview never executes', async () => {
  api.mockResolvedValueOnce(status).mockResolvedValueOnce({ ...preview, expiresAt: '2000-01-01T00:00:00Z' })
  render(<DatadogPanel workspaceId="workspace" />)
  await prepare(); execute()
  expect(await screen.findByText(/미리보기가 만료되었습니다/)).toBeVisible()
  expect(api).toHaveBeenCalledTimes(2)
})

it('PREVIEW receipt after a lost execute response remains locked until cancellation is confirmed', async () => {
  api.mockResolvedValueOnce(status).mockResolvedValueOnce(preview).mockRejectedValueOnce(new Error('lost'))
    .mockResolvedValueOnce({ ...empty, status: 'PREVIEW' }).mockResolvedValueOnce(undefined)
    .mockResolvedValueOnce({ ...empty, status: 'CANCELLED' })
  render(<DatadogPanel workspaceId="workspace" />)
  await prepare(); execute()
  await screen.findByText(/자동 재실행하지 않습니다/)
  fireEvent.click(screen.getByRole('button', { name: '조회 상태 확인' }))
  await screen.findByText(/실행 도착 여부가 아직 확정되지/)
  expect(screen.getByLabelText('환경')).toBeDisabled()
  expect(screen.queryByRole('button', { name: '새 조회 준비' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: '로그 조회 취소' }))
  await screen.findByText(/취소를 요청했습니다/)
  fireEvent.click(screen.getByRole('button', { name: '조회 상태 확인' }))
  await screen.findByText('로그 조회가 취소되었습니다.')
  expect(screen.getByRole('button', { name: '새 조회 준비' })).toBeEnabled()
  expect(api.mock.calls.filter(([path]) => path.endsWith('/execute'))).toHaveLength(1)
})
