import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { ApiError, request } from './api'
import OperationalCapturePanel from './OperationalCapturePanel'

vi.mock('./api', async importOriginal => ({ ...await importOriginal<typeof import('./api')>(), request: vi.fn() }))
const api = vi.mocked(request)
const text = '<script>synthetic()</script>\nexact result\n'
const preview = { id: 'preview-one', digest: 'a'.repeat(64), text, bytes: new TextEncoder().encode(text).length,
  truncated: true, expiresAt: '2099-01-01T00:00:00Z', sessionOnly: true }
const attachment = { id: 'attachment-one', name: 'synthetic-result.txt', mediaType: 'text/plain', size: preview.bytes, characters: text.length, truncated: false }
const receipt = { id: preview.id, status: 'CAPTURED', attachment, contentHash: 'b'.repeat(64), externalCalls: 0 }
const callback = vi.fn()
const props = { workspaceId: 'one', provider: 'MYSQL' as const, resultId: 'result-one', onCaptured: callback }
const base = '/workspaces/one/operational-captures'
beforeEach(() => { vi.clearAllMocks(); api.mockReset(); api.mockResolvedValue(preview) })
afterEach(() => { vi.restoreAllMocks() })

async function approve() {
  await userEvent.click(screen.getByRole('button', { name: '첨부 내용 미리보기' }))
  await screen.findByLabelText('저장할 정확한 내용')
  await userEvent.click(screen.getByRole('checkbox'))
}

it('mounting does nothing; explicit preview renders exact inert text and requires approval', async () => {
  const view = render(<OperationalCapturePanel {...props} />)
  expect(api).not.toHaveBeenCalled()
  expect(callback).not.toHaveBeenCalled()
  await userEvent.click(screen.getByRole('button', { name: '첨부 내용 미리보기' }))
  expect((await screen.findByLabelText('저장할 정확한 내용')).textContent).toBe(preview.text)
  expect(view.container.querySelector('script')).toBeNull()
  expect(screen.getByText(/조회 결과가 잘렸습니다/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '저장하고 새 질문에 첨부' })).toBeDisabled()
  expect(api).toHaveBeenCalledExactlyOnceWith(`${base}/preview`, expect.objectContaining({ method: 'POST', body: JSON.stringify({ provider: 'MYSQL', resultId: 'result-one' }) }))
})

it('approved save captures once and passes the returned attachment without any AI call', async () => {
  render(<OperationalCapturePanel {...props} />)
  await approve()
  api.mockResolvedValueOnce(receipt)
  await userEvent.click(screen.getByRole('button', { name: '저장하고 새 질문에 첨부' }))
  await waitFor(() => expect(callback).toHaveBeenCalledExactlyOnceWith('one', attachment))
  const [path, options] = api.mock.calls[1]
  expect(path).toBe(`${base}/preview-one/capture`)
  expect(JSON.parse(options!.body as string)).toEqual({ requestId: expect.any(String), digest: preview.digest, approved: true })
  expect(screen.queryByRole('button', { name: '저장하고 새 질문에 첨부' })).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(2)
})

it('duplicate save clicks share a single in-flight operation', async () => {
  render(<OperationalCapturePanel {...props} />)
  await approve()
  let resolve!: (value: unknown) => void
  api.mockImplementationOnce(() => new Promise(done => { resolve = done }))
  const button = screen.getByRole('button', { name: '저장하고 새 질문에 첨부' })
  fireEvent.click(button); fireEvent.click(button)
  expect(api).toHaveBeenCalledTimes(2)
  await act(async () => { resolve(receipt) })
  expect(callback).toHaveBeenCalledExactlyOnceWith('one', attachment)
})

it('uncertain save permits only GET recovery even while parent navigation is locked', async () => {
  const busy = vi.fn()
  const view = render(<OperationalCapturePanel {...props} onBusyChange={busy} />)
  await approve()
  api.mockRejectedValueOnce(new Error('Synthetic response lost'))
  await userEvent.click(screen.getByRole('button', { name: '저장하고 새 질문에 첨부' }))
  await screen.findByText(/저장 접수 여부/)
  expect(busy).toHaveBeenLastCalledWith(true)
  expect(screen.getByRole('button', { name: '첨부 내용 미리보기' })).toBeDisabled()
  expect(screen.queryByRole('button', { name: '저장하고 새 질문에 첨부' })).not.toBeInTheDocument()
  view.rerender(<OperationalCapturePanel {...props} onBusyChange={busy} disabled />)
  api.mockRejectedValueOnce(new Error('Still offline'))
  await userEvent.click(screen.getByRole('button', { name: '저장 결과 확인' }))
  api.mockResolvedValueOnce(receipt)
  await userEvent.click(screen.getByRole('button', { name: '저장 결과 확인' }))
  await waitFor(() => expect(callback).toHaveBeenCalledExactlyOnceWith('one', attachment))
  expect(api.mock.calls.slice(2).every(([path, options]) => path === `${base}/preview-one` && !options?.method)).toBe(true)
  expect(api.mock.calls.filter(([path]) => path.endsWith('/capture'))).toHaveLength(1)
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

it.each([400, 403, 404, 409, 410])('status %s clears stale approval and requires a new preview', async status => {
  render(<OperationalCapturePanel {...props} />)
  await approve()
  api.mockRejectedValueOnce(new ApiError('Synthetic expired', status))
  await userEvent.click(screen.getByRole('button', { name: '저장하고 새 질문에 첨부' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('새 미리보기가 필요합니다')
  expect(screen.queryByLabelText('저장할 정확한 내용')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '저장 결과 확인' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '첨부 내용 미리보기' })).toBeEnabled()
  expect(callback).not.toHaveBeenCalled()
})

it('local expiry never submits a stale approval', async () => {
  api.mockResolvedValue({ ...preview, expiresAt: '2000-01-01T00:00:00Z' })
  render(<OperationalCapturePanel {...props} />)
  await approve()
  await userEvent.click(screen.getByRole('button', { name: '저장하고 새 질문에 첨부' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('미리보기가 만료')
  expect(api).toHaveBeenCalledTimes(1)
})

it('unmount aborts pending save and ignores late receipt/navigation', async () => {
  const busy = vi.fn()
  const view = render(<OperationalCapturePanel {...props} onBusyChange={busy} />)
  await approve()
  let resolve!: (value: unknown) => void
  api.mockImplementationOnce(() => new Promise(done => { resolve = done }))
  fireEvent.click(screen.getByRole('button', { name: '저장하고 새 질문에 첨부' }))
  const signal = api.mock.calls[1][1]!.signal!
  view.unmount()
  expect(signal.aborted).toBe(true)
  await act(async () => { resolve(receipt) })
  expect(callback).not.toHaveBeenCalled()
  expect(busy).toHaveBeenLastCalledWith(false)
})

it('changing workspace/provider/result discards preview without automatically fetching or capturing', async () => {
  const view = render(<OperationalCapturePanel {...props} />)
  await approve()
  view.rerender(<OperationalCapturePanel {...props} workspaceId="two" provider="DATADOG" resultId="result-two" />)
  expect(screen.queryByLabelText('저장할 정확한 내용')).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(1)
  await userEvent.click(screen.getByRole('button', { name: '첨부 내용 미리보기' }))
  expect(api).toHaveBeenLastCalledWith('/workspaces/two/operational-captures/preview', expect.objectContaining({
    body: JSON.stringify({ provider: 'DATADOG', resultId: 'result-two' }),
  }))
  expect(callback).not.toHaveBeenCalled()
})

it.each(['RUNNING', 'PREVIEW'])('%s receipts require GET recovery and never repeat capture', async status => {
  render(<OperationalCapturePanel {...props} />)
  await approve()
  api.mockResolvedValueOnce({ ...receipt, status, attachment: null, contentHash: null })
  await userEvent.click(screen.getByRole('button', { name: '저장하고 새 질문에 첨부' }))
  await screen.findByRole('button', { name: '저장 결과 확인' })
  expect(callback).not.toHaveBeenCalled()
  api.mockResolvedValueOnce(receipt)
  await userEvent.click(screen.getByRole('button', { name: '저장 결과 확인' }))
  expect(api).toHaveBeenLastCalledWith(`${base}/${preview.id}`, expect.not.objectContaining({ method: 'POST' }))
  expect(callback).toHaveBeenCalledExactlyOnceWith('one', attachment)
})

it('FAILED is terminal and releases the navigation lock without a recovery loop', async () => {
  const busy = vi.fn()
  render(<OperationalCapturePanel {...props} onBusyChange={busy} />)
  await approve()
  api.mockResolvedValueOnce({ ...receipt, status: 'FAILED', attachment: null, contentHash: null })
  await userEvent.click(screen.getByRole('button', { name: '저장하고 새 질문에 첨부' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('저장에 실패했습니다')
  expect(screen.queryByRole('button', { name: '저장 결과 확인' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '첨부 내용 미리보기' })).not.toBeInTheDocument()
  expect(busy).toHaveBeenLastCalledWith(false)
  expect(callback).not.toHaveBeenCalled()
})

it.each([
  { ...receipt, id: 'wrong-preview' },
  { ...receipt, externalCalls: 1 },
  { ...receipt, attachment: { ...attachment, mediaType: 'text/html' } },
  { ...receipt, attachment: { ...attachment, size: attachment.size + 1 } },
  { ...receipt, attachment: { ...attachment, characters: attachment.characters + 1 } },
  { ...receipt, attachment: { ...attachment, truncated: true } },
])('rejects mismatched or unsafe receipt %# before handing off', async invalid => {
  render(<OperationalCapturePanel {...props} />)
  await approve()
  api.mockResolvedValueOnce(invalid)
  await userEvent.click(screen.getByRole('button', { name: '저장하고 새 질문에 첨부' }))
  await screen.findByRole('button', { name: '저장 결과 확인' })
  expect(callback).not.toHaveBeenCalled()
  expect(screen.queryByRole('button', { name: '저장하고 새 질문에 첨부' })).not.toBeInTheDocument()
})

it('a timed-out save unlocks GET recovery without issuing another write', async () => {
  const view = render(<OperationalCapturePanel {...props} />)
  await approve()
  vi.useFakeTimers()
  api.mockImplementationOnce((_path, options) => new Promise((_, reject) => {
    options?.signal?.addEventListener('abort', () => reject(new Error('Synthetic timeout')), { once: true })
  }))
  try {
    fireEvent.click(screen.getByRole('button', { name: '저장하고 새 질문에 첨부' }))
    await act(async () => { await vi.advanceTimersByTimeAsync(15000) })
    expect(screen.getByRole('button', { name: '저장 결과 확인' })).toBeEnabled()
    expect(api).toHaveBeenCalledTimes(2)
    expect(callback).not.toHaveBeenCalled()
  } finally { view.unmount(); vi.useRealTimers() }
})

it('failed replacement preview discards the previous approval instead of saving stale text', async () => {
  render(<OperationalCapturePanel {...props} />)
  await approve()
  api.mockRejectedValueOnce(new Error('Synthetic preview failure'))
  await userEvent.click(screen.getByRole('button', { name: '첨부 내용 미리보기' }))
  await screen.findByRole('alert')
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '저장하고 새 질문에 첨부' })).not.toBeInTheDocument()
  expect(callback).not.toHaveBeenCalled()
})

it('a save that ignores abort still releases the request lock and only reconciles through GET', async () => {
  const busy = vi.fn()
  const view = render(<OperationalCapturePanel {...props} onBusyChange={busy} />)
  await approve()
  let complete!: (value: unknown) => void
  api.mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
  vi.useFakeTimers()
  try {
    fireEvent.click(screen.getByRole('button', { name: '저장하고 새 질문에 첨부' }))
    const signal = api.mock.calls.at(-1)![1]!.signal!
    await act(async () => { await vi.advanceTimersByTimeAsync(15000) })
    expect(signal.aborted).toBe(true)
    expect(screen.getByRole('button', { name: '저장 결과 확인' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: '저장하고 새 질문에 첨부' })).not.toBeInTheDocument()
    expect(busy).toHaveBeenLastCalledWith(true)
    await act(async () => complete(receipt))
    expect(callback).not.toHaveBeenCalled()
    expect(api).toHaveBeenCalledTimes(2)
    let completeRecovery!: (value: unknown) => void
    api.mockImplementationOnce(() => new Promise(resolve => { completeRecovery = resolve }))
    fireEvent.click(screen.getByRole('button', { name: '저장 결과 확인' }))
    await act(async () => { await vi.advanceTimersByTimeAsync(15000) })
    expect(screen.getByRole('button', { name: '저장 결과 확인' })).toBeEnabled()
    await act(async () => completeRecovery(receipt))
    expect(callback).not.toHaveBeenCalled()
    api.mockResolvedValueOnce(receipt)
    await act(async () => fireEvent.click(screen.getByRole('button', { name: '저장 결과 확인' })))
    expect(api).toHaveBeenCalledTimes(4)
    for (const [path, options] of api.mock.calls.slice(2)) {
      expect(path).toBe(`${base}/${preview.id}`)
      expect(options?.method).toBeUndefined()
      expect(options?.body).toBeUndefined()
    }
    expect(api.mock.calls.filter(([path]) => path.endsWith('/capture'))).toHaveLength(1)
    expect(callback).toHaveBeenCalledExactlyOnceWith(props.workspaceId, attachment)
    expect(busy).toHaveBeenLastCalledWith(false)
  } finally { view.unmount(); vi.useRealTimers() }
})

it('a preview that ignores abort releases navigation and cannot install its late approval', async () => {
  const busy = vi.fn()
  let complete!: (value: unknown) => void
  api.mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
  const view = render(<OperationalCapturePanel {...props} onBusyChange={busy} />)
  vi.useFakeTimers()
  try {
    fireEvent.click(screen.getByRole('button', { name: '첨부 내용 미리보기' }))
    expect(busy).toHaveBeenLastCalledWith(true)
    await act(async () => { await vi.advanceTimersByTimeAsync(15000) })
    expect(screen.getByRole('alert')).toHaveTextContent('시간이 초과')
    expect(screen.getByRole('button', { name: '첨부 내용 미리보기' })).toBeEnabled()
    expect(busy).toHaveBeenLastCalledWith(false)
    await act(async () => complete(preview))
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('저장할 정확한 내용')).not.toBeInTheDocument()
    expect(callback).not.toHaveBeenCalled()
    expect(api).toHaveBeenCalledTimes(1)
  } finally { view.unmount(); vi.useRealTimers() }
})
