import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import PublishedSearchPreview from './PublishedSearchPreview'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const props = { workspaceId: 'one', cardId: 'card', version: 1, onClose: vi.fn() }
beforeEach(() => {
  api.mockReset(); props.onClose.mockReset()
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
})

it('검색된 버전을 정확히 읽고 근거 변경과 안전한 Markdown을 표시한다', async () => {
  api.mockResolvedValue({ version: { number: 1, title: '게시 제목', markdown: '# 게시 내용\n<script>bad()</script>\n![image](https://example.com/image)',
    evidence: [{ snapshotId: 'snapshot', name: 'fixture.txt', startLine: 2, endLine: 4, quote: 'stored evidence', freshness: 'STALE' }] } })
  render(<PublishedSearchPreview {...props} />)
  expect(await screen.findByText('게시 제목')).toBeInTheDocument()
  expect(api).toHaveBeenCalledWith('/workspaces/one/knowledge/card/versions/1', expect.objectContaining({ signal: expect.any(AbortSignal) }))
  expect(screen.getByText(/fixture.txt.*STALE/)).toBeInTheDocument()
  expect(document.querySelector('script, img')).toBeNull()
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '닫기' }))
  expect(props.onClose).toHaveBeenCalledTimes(1)
})

it('삭제된 지식의 오류를 보이고 Escape로 닫는다', async () => {
  api.mockRejectedValue(new Error('지식을 찾을 수 없습니다.'))
  render(<PublishedSearchPreview {...props} />)
  expect(await screen.findByRole('alert')).toHaveTextContent('지식을 찾을 수 없습니다.')
  fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }))
  expect(props.onClose).toHaveBeenCalledOnce()
})

it('창을 닫거나 Workspace를 떠나면 대기 요청을 취소한다', async () => {
  api.mockImplementation(() => new Promise(() => {}))
  const view = render(<PublishedSearchPreview {...props} />)
  await waitFor(() => expect(api).toHaveBeenCalledOnce())
  const signal = api.mock.calls[0][1]?.signal
  view.unmount()
  expect(signal?.aborted).toBe(true)
})
