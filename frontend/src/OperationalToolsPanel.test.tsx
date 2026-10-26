import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import OperationalToolsPanel from './OperationalToolsPanel'
import { request } from './api'
import type { QuestionAttachment } from './useQuestionAttachments'

type ChildProps = { workspaceId: string; disabled: boolean; revision?: string;
  onBusyChange: (busy: boolean) => void; onCaptured: (workspaceId: string, attachment: QuestionAttachment) => void }
const children = vi.hoisted(() => ({ mysql: null as ChildProps | null, datadog: null as ChildProps | null }))
vi.mock('./api', () => ({ request: vi.fn() }))
vi.mock('./MysqlReadPanel', () => ({ default: (props: ChildProps) => {
  children.mysql = props
  return <button disabled={props.disabled}>MySQL query fixture</button>
} }))
vi.mock('./DatadogReadPanel', () => ({ default: (props: ChildProps) => {
  children.datadog = props
  return <button disabled={props.disabled}>Datadog query fixture</button>
} }))
const api = vi.mocked(request)
const revision = '11111111-1111-1111-1111-111111111111'
const openRequest = { requestId: 'explicit-click', proposal: { jobId: 'job', workspaceId: 'workspace', bundleId: 'bundle', kind: 'DATADOG_LOGS' as const,
  requiredFields: ['environment', 'service', 'indexes', 'from', 'to', 'limit'], executionAllowed: false as const } }
const configured = { configured: true, site: 'US1', revision }
const unconfigured = { configured: false, site: null, revision: null }
const attachment = { id: 'synthetic', name: 'query.txt', mediaType: 'text/plain', size: 10, characters: 10, truncated: false }
const props = { workspaceId: 'workspace', onBusyChange: vi.fn(), onCaptured: vi.fn() }
function metadata(mysql = true, datadog: unknown = configured) {
  api.mockResolvedValueOnce({ mysqlConnected: mysql }).mockResolvedValueOnce(datadog)
}
async function open() {
  fireEvent.click(screen.getByRole('button', { name: '운영 자료 조회' }))
  await waitFor(() => expect(screen.queryByText('운영 자료 연결 상태 확인 중…')).not.toBeInTheDocument())
}
beforeEach(() => { api.mockReset(); vi.clearAllMocks(); children.mysql = null; children.datadog = null })

it('명시적 제안 열기는 한 번만 메타데이터를 읽고 해당 도구에 조회 범위를 전달하지 않는다', async () => {
  metadata()
  const handled = vi.fn()
  const view = render(<OperationalToolsPanel {...props} currentBundleId="bundle" onOpenRequestHandled={handled} />)
  expect(api).not.toHaveBeenCalled()
  view.rerender(<OperationalToolsPanel {...props} currentBundleId="bundle" openRequest={openRequest} onOpenRequestHandled={handled} />)
  expect(await screen.findByRole('button', { name: 'Datadog query fixture' })).toBeVisible()
  expect(screen.queryByRole('button', { name: 'MySQL query fixture' })).not.toBeInTheDocument()
  expect(children.datadog).toEqual({ workspaceId: 'workspace', revision, disabled: false,
    onBusyChange: expect.any(Function), onCaptured: expect.any(Function) })
  expect(handled).toHaveBeenCalledExactlyOnceWith('explicit-click')
  expect(api).toHaveBeenCalledTimes(2)
  fireEvent.click(screen.getByRole('button', { name: '운영 자료 조회' }))
  view.rerender(<OperationalToolsPanel {...props} currentBundleId="bundle" openRequest={{ ...openRequest }} onOpenRequestHandled={handled} />)
  expect(screen.getByRole('button', { name: '운영 자료 조회' })).toHaveAttribute('aria-expanded', 'false')
  expect(api).toHaveBeenCalledTimes(2)
})

it.each(['disabled', 'workspace', 'bundle'])('잠금 또는 오래된 %s 제안을 버리고 나중에 자동 열지 않는다', async invalid => {
  const handled = vi.fn()
  const requested = invalid === 'workspace' ? { ...openRequest, proposal: { ...openRequest.proposal, workspaceId: 'other' } } : openRequest
  const view = render(<OperationalToolsPanel {...props} disabled={invalid === 'disabled'} currentBundleId={invalid === 'bundle' ? 'other' : 'bundle'}
    openRequest={requested} onOpenRequestHandled={handled} />)
  expect(handled).toHaveBeenCalledTimes(1)
  view.rerender(<OperationalToolsPanel {...props} currentBundleId="bundle" openRequest={openRequest} onOpenRequestHandled={handled} />)
  expect(api).not.toHaveBeenCalled()
  expect(screen.getByRole('button', { name: '운영 자료 조회' })).toHaveAttribute('aria-expanded', 'false')
})
afterEach(() => vi.useRealTimers())

it('bundle 변경은 제안 양식을 폐기하고 이전 캡처를 차단하며 돌아와도 자동 열지 않는다', async () => {
  metadata()
  const handled = vi.fn()
  const view = render(<OperationalToolsPanel {...props} currentBundleId="bundle" openRequest={openRequest} onOpenRequestHandled={handled} />)
  await screen.findByRole('button', { name: 'Datadog query fixture' })
  const oldChild = children.datadog!
  act(() => oldChild.onBusyChange(true))
  view.rerender(<OperationalToolsPanel {...props} currentBundleId="new-bundle" openRequest={openRequest} onOpenRequestHandled={handled} />)
  expect(screen.queryByRole('button', { name: 'Datadog query fixture' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '운영 자료 조회' })).toHaveAttribute('aria-expanded', 'false')
  expect(props.onBusyChange).toHaveBeenLastCalledWith(false)
  act(() => oldChild.onCaptured('workspace', attachment))
  expect(props.onCaptured).not.toHaveBeenCalled()
  view.rerender(<OperationalToolsPanel {...props} currentBundleId="bundle" openRequest={openRequest} onOpenRequestHandled={handled} />)
  expect(screen.getByRole('button', { name: '운영 자료 조회' })).toHaveAttribute('aria-expanded', 'false')
  expect(api).toHaveBeenCalledTimes(2)
  expect(handled).toHaveBeenCalledTimes(1)
})

it('bundle 변경은 진행 중 메타데이터를 중단하고 늦은 응답으로 양식을 복원하지 않는다', async () => {
  let complete!: (value: unknown) => void
  api.mockReturnValueOnce(new Promise(resolve => { complete = resolve })).mockResolvedValueOnce(configured)
  const view = render(<OperationalToolsPanel {...props} currentBundleId="bundle" openRequest={openRequest} />)
  const signals = api.mock.calls.map(([, options]) => options!.signal!)
  view.rerender(<OperationalToolsPanel {...props} currentBundleId="new-bundle" openRequest={null} />)
  expect(signals).toHaveLength(2)
  expect(signals.every(signal => signal.aborted)).toBe(true)
  await act(async () => complete({ mysqlConnected: true }))
  expect(screen.queryByRole('button', { name: 'Datadog query fixture' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '운영 자료 조회' })).toHaveAttribute('aria-expanded', 'false')
  expect(api).toHaveBeenCalledTimes(2)
  expect(props.onBusyChange).toHaveBeenLastCalledWith(false)
})

it('메타데이터 요청 시간 초과는 잠금을 풀고 늦은 응답 없이 수동 재확인만 허용한다', async () => {
  vi.useFakeTimers()
  let resolvePlugins!: (value: unknown) => void
  api.mockReturnValueOnce(new Promise(resolve => { resolvePlugins = resolve }))
    .mockResolvedValueOnce(configured)
  render(<OperationalToolsPanel {...props} />)
  fireEvent.click(screen.getByRole('button', { name: '운영 자료 조회' }))
  const signal = api.mock.calls[0][1]!.signal!
  await act(async () => { await vi.advanceTimersByTimeAsync(10000) })
  expect(signal.aborted).toBe(true)
  expect(screen.getByRole('alert')).toHaveTextContent('시간이 초과')
  expect(props.onBusyChange).toHaveBeenLastCalledWith(false)
  expect(api).toHaveBeenCalledTimes(2)
  metadata(false, unconfigured)
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '연결 상태 다시 확인' })) })
  expect(screen.getByText('MySQL 연결은 설정의 플러그인에서 구성하세요.')).toBeVisible()
  await act(async () => resolvePlugins({ mysqlConnected: true }))
  expect(children.mysql).toBeNull(); expect(children.datadog).toBeNull()
  expect(api).toHaveBeenCalledTimes(4)
  expect(props.onBusyChange).toHaveBeenLastCalledWith(false)
})

it('마운트에서는 요청하지 않고 명시적으로 열 때 메타데이터 GET만 보낸다', async () => {
  metadata()
  render(<OperationalToolsPanel {...props} workspaceId="workspace/one" />)
  expect(api).not.toHaveBeenCalled()
  expect(screen.getByRole('button', { name: '운영 자료 조회' })).toHaveAttribute('aria-expanded', 'false')
  await open()
  expect(api.mock.calls.map(([path]) => path)).toEqual(['/plugins', '/plugins/datadog/workspace%2Fone'])
  for (const [, options] of api.mock.calls) {
    expect(options?.method).toBeUndefined()
    expect(options?.body).toBeUndefined()
    expect(options?.signal).toBeInstanceOf(AbortSignal)
  }
  expect(children.mysql?.workspaceId).toBe('workspace/one')
  expect(children.datadog?.revision).toBe(revision)
  expect(props.onCaptured).not.toHaveBeenCalled()
  await waitFor(() => expect(props.onBusyChange).toHaveBeenLastCalledWith(false))
})

it('연결이 없으면 설정 안내만 표시하고 조회·설정 패널을 열지 않는다', async () => {
  metadata(false, unconfigured)
  render(<OperationalToolsPanel {...props} />)
  await open()
  expect(screen.getByText('MySQL 연결은 설정의 플러그인에서 구성하세요.')).toBeVisible()
  expect(screen.getByText('Datadog 연결은 설정의 플러그인에서 구성하세요.')).toBeVisible()
  expect(children.mysql).toBeNull(); expect(children.datadog).toBeNull()
  expect(api).toHaveBeenCalledTimes(2)
})

it('Workspace 없음 또는 외부 잠금은 열기와 요청을 차단한다', () => {
  const view = render(<OperationalToolsPanel {...props} workspaceId={null} />)
  expect(screen.getByRole('button', { name: '운영 자료 조회' })).toBeDisabled()
  view.rerender(<OperationalToolsPanel {...props} disabled />)
  fireEvent.click(screen.getByRole('button', { name: '운영 자료 조회' }))
  expect(api).not.toHaveBeenCalled()
})

it.each([
  null,
  { configured: 'true', site: 'US1', revision },
  { configured: true, site: 'US1', revision: null },
  { configured: true, site: 'UNKNOWN', revision },
  { configured: false, site: null, revision },
])('잘못된 Datadog 메타데이터는 조회 패널을 활성화하지 않는다: %j', async invalid => {
  metadata(true, invalid)
  render(<OperationalToolsPanel {...props} />)
  await open()
  expect(screen.getByRole('alert')).toHaveTextContent('연결 상태를 확인하지 못했습니다')
  expect(children.mysql).toBeNull(); expect(children.datadog).toBeNull()
  expect(api).toHaveBeenCalledTimes(2)
})

it('MySQL capability는 실제 boolean이어야 하며 실패 후 자동 재시도하지 않는다', async () => {
  api.mockResolvedValueOnce({ mysqlConnected: 'true' }).mockResolvedValueOnce(unconfigured)
  render(<OperationalToolsPanel {...props} />)
  await open()
  expect(screen.getByRole('alert')).toBeVisible()
  expect(children.mysql).toBeNull()
  metadata(true, unconfigured)
  fireEvent.click(screen.getByRole('button', { name: '연결 상태 다시 확인' }))
  expect(await screen.findByRole('button', { name: 'MySQL query fixture' })).toBeEnabled()
  expect(api).toHaveBeenCalledTimes(4)
})

it('한 조회가 바쁘면 다른 조회와 접기를 잠그고 집계 잠금을 알린다', async () => {
  metadata()
  render(<OperationalToolsPanel {...props} />)
  await open()
  act(() => children.mysql!.onBusyChange(true))
  expect(screen.getByRole('button', { name: 'MySQL query fixture' })).toBeEnabled()
  expect(screen.getByRole('button', { name: 'Datadog query fixture' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '운영 자료 조회' })).toBeDisabled()
  expect(props.onBusyChange).toHaveBeenLastCalledWith(true)
  act(() => children.mysql!.onBusyChange(false))
  act(() => children.datadog!.onBusyChange(true))
  expect(screen.getByRole('button', { name: 'MySQL query fixture' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'Datadog query fixture' })).toBeEnabled()
  act(() => children.datadog!.onBusyChange(false))
  expect(props.onBusyChange).toHaveBeenLastCalledWith(false)
  expect(api).toHaveBeenCalledTimes(2)
})

it('현재 Workspace의 명시적 캡처만 전달하고 작업 중 언마운트하면 잠금을 해제한다', async () => {
  metadata()
  const view = render(<OperationalToolsPanel {...props} />)
  await open()
  const child = children.mysql!
  act(() => child.onCaptured('other', attachment))
  expect(props.onCaptured).not.toHaveBeenCalled()
  act(() => child.onCaptured('workspace', attachment))
  expect(props.onCaptured).toHaveBeenCalledExactlyOnceWith('workspace', attachment)
  act(() => child.onBusyChange(true))
  view.unmount()
  expect(props.onBusyChange).toHaveBeenLastCalledWith(false)
  act(() => child.onCaptured('workspace', attachment))
  expect(props.onCaptured).toHaveBeenCalledTimes(1)
})

it('Workspace 변경은 요청을 중단하고 늦은 응답을 무시하며 새 패널을 자동으로 열지 않는다', async () => {
  let resolvePlugins!: (value: unknown) => void
  api.mockReturnValueOnce(new Promise(resolve => { resolvePlugins = resolve }))
    .mockResolvedValueOnce(configured)
  const view = render(<OperationalToolsPanel {...props} />)
  fireEvent.click(screen.getByRole('button', { name: '운영 자료 조회' }))
  const signals = api.mock.calls.map(([, options]) => options!.signal!)
  view.rerender(<OperationalToolsPanel {...props} workspaceId="other" />)
  expect(signals.every(signal => signal.aborted)).toBe(true)
  await act(async () => resolvePlugins({ mysqlConnected: true }))
  expect(screen.getByRole('button', { name: '운영 자료 조회' })).toHaveAttribute('aria-expanded', 'false')
  expect(children.mysql).toBeNull(); expect(children.datadog).toBeNull()
  expect(api).toHaveBeenCalledTimes(2)
  expect(props.onBusyChange).toHaveBeenLastCalledWith(false)
})

it('읽기 상태 요청 중 접으면 중단하며 다시 열 때만 새 메타데이터를 읽는다', async () => {
  api.mockReturnValueOnce(new Promise(() => {})).mockReturnValueOnce(new Promise(() => {}))
  render(<OperationalToolsPanel {...props} />)
  fireEvent.click(screen.getByRole('button', { name: '운영 자료 조회' }))
  const signal = api.mock.calls[0][1]!.signal!
  fireEvent.click(screen.getByRole('button', { name: '운영 자료 조회' }))
  expect(signal.aborted).toBe(true)
  expect(props.onBusyChange).toHaveBeenLastCalledWith(false)
  metadata(false, unconfigured)
  await open()
  expect(api).toHaveBeenCalledTimes(4)
  expect(screen.getByText('MySQL 연결은 설정의 플러그인에서 구성하세요.')).toBeVisible()
})

it('요청 실패는 서버 오류 내용을 노출하지 않고 한국어로 표시한다', async () => {
  api.mockRejectedValueOnce(new Error('SYNTHETIC_PRIVATE_ERROR')).mockResolvedValueOnce(configured)
  render(<OperationalToolsPanel {...props} />)
  await open()
  expect(screen.getByRole('alert')).toHaveTextContent('운영 자료 연결 상태를 확인하지 못했습니다')
  expect(screen.queryByText('SYNTHETIC_PRIVATE_ERROR')).not.toBeInTheDocument()
  expect(props.onBusyChange).toHaveBeenLastCalledWith(false)
})
