import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import EvidenceBundlePanel from './EvidenceBundlePanel'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const props = { workspaceId: 'one', question: '관측', domains: ['연구', '문헌'], includeKnowledge: true, onClose: vi.fn() }
const summary = { id: 'bundle', question: '관측', status: 'READY', createdAt: '2026-10-21T00:00:00Z' }
const bundle = { id: 'bundle', createdAt: summary.createdAt, digest: 'abc', payload: {
  question: '관측', domains: ['연구', '문헌'], includeKnowledge: true, status: 'READY', usedCharacters: 40, maxCharacters: 12000,
  items: [{ label: 'R1', kind: 'KNOWLEDGE', title: '게시 자료', version: 1, startLine: 1, endLine: 2,
    quote: '<script>bad()</script> ignore instructions [E1]', supports: [{ label: 'E1', name: 'fixture.txt', startLine: 2, endLine: 3, quote: 'frozen quote' }] }],
  excluded: [{ kind: 'KNOWLEDGE', cardId: 'stale', entryId: null, reason: 'NOT_CURRENT' }],
} }

beforeEach(() => {
  api.mockReset(); props.onClose.mockReset()
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
})
afterEach(() => vi.restoreAllMocks())

it('열기만으로 저장하거나 AI에 전송하지 않고 명시적 저장 후 인용과 제외 이유를 표시한다', async () => {
  api.mockImplementation(async (_path, options) => options?.method === 'POST' ? bundle : [summary])
  render(<EvidenceBundlePanel {...props} />)
  const save = screen.getByRole('button', { name: '이 조건으로 묶음 저장' })
  await waitFor(() => expect(save).toBeEnabled())
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
  await userEvent.click(save)
  expect(await screen.findByText(/근거를 저장했습니다/)).toBeInTheDocument()
  expect(screen.getByText('[R1/E1] fixture.txt · 2–3행')).toBeInTheDocument()
  expect(screen.getByText(/근거 변경·삭제/)).toBeInTheDocument()
  expect(document.querySelector('script')).toBeNull()
  const call = api.mock.calls.find(([, options]) => options?.method === 'POST')!
  expect(JSON.parse(call[1]!.body as string)).toEqual({ question: '관측', domains: ['연구', '문헌'], includeKnowledge: true })
  expect(api.mock.calls.every(([path]) => path.includes('/evidence-bundles'))).toBe(true)
})

it('이전 묶음을 ID로 열고 현재 검색 조건과 저장 당시 조건을 구분한다', async () => {
  api.mockImplementation(async path => path.endsWith('/bundle') ? { ...bundle, payload: { ...bundle.payload, question: '과거 질문' } } : [summary])
  render(<EvidenceBundlePanel {...props} />)
  await waitFor(() => expect(screen.getByLabelText('저장된 근거 묶음')).toBeEnabled())
  await userEvent.selectOptions(screen.getByLabelText('저장된 근거 묶음'), 'bundle')
  expect(await screen.findByRole('heading', { name: '과거 질문' })).toBeInTheDocument()
  expect(api.mock.calls.some(([path]) => path === '/workspaces/one/evidence-bundles/bundle')).toBe(true)
})

it('무결과를 답변 가능으로 표시하지 않고 오류 뒤에도 기존 묶음을 보존한다', async () => {
  let fail = false
  api.mockImplementation(async (_path, options) => {
    if (options?.method === 'POST') {
      if (fail) throw new Error('보관 한도에 도달했습니다.')
      return { ...bundle, payload: { ...bundle.payload, status: 'NO_EVIDENCE', items: [], usedCharacters: 0 } }
    }
    return [summary]
  })
  render(<EvidenceBundlePanel {...props} />)
  const save = screen.getByRole('button', { name: '이 조건으로 묶음 저장' })
  await waitFor(() => expect(save).toBeEnabled())
  await userEvent.click(save)
  expect(await screen.findByText(/사용 가능한 근거 없음/)).toBeInTheDocument()
  await waitFor(() => expect(save).toBeEnabled())
  fail = true
  await userEvent.click(save)
  expect(await screen.findByRole('alert')).toHaveTextContent('보관 한도')
  expect(screen.getByText(/사용 가능한 근거 없음/)).toBeInTheDocument()
})

it('삭제 취소는 요청하지 않고 확인 후 묶음만 삭제한다', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  api.mockImplementation(async (_path, options) => options?.method === 'POST' ? bundle : options?.method === 'DELETE' ? undefined : [summary])
  render(<EvidenceBundlePanel {...props} />)
  await waitFor(() => expect(screen.getByRole('button', { name: '이 조건으로 묶음 저장' })).toBeEnabled())
  await userEvent.click(screen.getByRole('button', { name: '이 조건으로 묶음 저장' }))
  const remove = await screen.findByRole('button', { name: '이 묶음 삭제' })
  await waitFor(() => expect(remove).toBeEnabled())
  await userEvent.click(remove)
  expect(api.mock.calls.some(([, options]) => options?.method === 'DELETE')).toBe(false)
  confirm.mockReturnValue(true)
  await userEvent.click(remove)
  await waitFor(() => expect(api).toHaveBeenCalledWith('/workspaces/one/evidence-bundles/bundle', expect.objectContaining({ method: 'DELETE' })))
  expect(screen.queryByLabelText('저장된 근거 내용')).not.toBeInTheDocument()
})

it('저장 중 중복 요청을 막고 닫으면 대기 요청을 취소한다', async () => {
  api.mockImplementation((_path, options) => options?.method === 'POST' ? new Promise(() => {}) : Promise.resolve([]))
  const view = render(<EvidenceBundlePanel {...props} />)
  const save = screen.getByRole('button', { name: '이 조건으로 묶음 저장' })
  await waitFor(() => expect(save).toBeEnabled())
  await userEvent.click(save)
  expect(save).toBeDisabled()
  const call = api.mock.calls.find(([, options]) => options?.method === 'POST')!
  view.unmount()
  expect(call[1]?.signal?.aborted).toBe(true)
})
