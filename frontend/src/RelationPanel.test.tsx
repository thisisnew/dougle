import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi, beforeEach } from 'vitest'
import RelationPanel from './RelationPanel'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const seed = { kind: 'ORIGINAL' as const, id: 'entry', version: null, title: '가상 자료' }
const target = { kind: 'KNOWLEDGE', id: 'card', title: '<script>가상 게시본</script>', version: 2, path: '', domains: ['가상'], relation: 'CITES_SOURCE', detail: '같은 출처' }
beforeEach(() => {
  api.mockReset()
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
})

it('읽기 전용 관계를 조회하고 게시 버전을 명시적으로 연다', async () => {
  api.mockResolvedValue({ items: [{ target, freshness: 'STALE' }], truncated: true })
  const close = vi.fn()
  const original = vi.fn()
  const knowledge = vi.fn()
  render(<RelationPanel workspaceId="one" seed={seed} domains={['가상', '연구']} onClose={close} onOriginal={original} onKnowledge={knowledge} />)
  expect(await screen.findByText(/STALE/)).toBeInTheDocument()
  expect(screen.getByText(/더 있음/)).toBeInTheDocument()
  const path = api.mock.calls[0][0]
  const query = new URLSearchParams(path.split('?')[1])
  expect(query.getAll('domains')).toEqual(['가상', '연구'])
  expect(query.get('id')).toBe('entry')
  expect(api.mock.calls[0][1]?.method).toBeUndefined()
  expect(document.querySelector('dialog script')).toBeNull()
  await userEvent.click(screen.getByRole('button', { name: target.title }))
  expect(knowledge).toHaveBeenCalledWith('card', 2)
  expect(original).not.toHaveBeenCalled()
  expect(close).toHaveBeenCalled()
  expect(api).toHaveBeenCalledTimes(1)
})

it('현재 원본 연결은 게시 당시 내용과 다를 수 있음을 표시한다', async () => {
  api.mockResolvedValue({ items: [{ target: { ...target, kind: 'ORIGINAL', id: 'file', version: null, relation: 'CITED_SOURCE' }, freshness: 'LOCAL_COPY' }], truncated: false })
  const original = vi.fn()
  render(<RelationPanel workspaceId="one" seed={{ kind: 'KNOWLEDGE', id: 'card', version: 1, title: '게시본' }} domains={[]} onClose={vi.fn()} onOriginal={original} onKnowledge={vi.fn()} />)
  await userEvent.click(await screen.findByRole('button', { name: target.title }))
  expect(original).toHaveBeenCalledWith('file')
  expect(screen.getByText(/게시 당시 내용과 다를 수/)).toBeInTheDocument()
  expect(api.mock.calls[0][0]).toContain('version=1')
})

it('대상 변경 시 이전 요청을 취소하고 오류를 표시한다', async () => {
  api.mockImplementationOnce(() => new Promise(() => {}))
  api.mockRejectedValueOnce(new Error('게시본을 다시 검색하세요.'))
  const props = { workspaceId: 'one', domains: [], onClose: vi.fn(), onOriginal: vi.fn(), onKnowledge: vi.fn() }
  const view = render(<RelationPanel {...props} seed={seed} />)
  const signal = api.mock.calls[0][1]?.signal
  view.rerender(<RelationPanel {...props} seed={{ ...seed, id: 'another' }} />)
  await waitFor(() => expect(signal?.aborted).toBe(true))
  expect(await screen.findByRole('alert')).toHaveTextContent('다시 검색')
})
