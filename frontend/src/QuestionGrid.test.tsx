import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import QuestionGrid from './QuestionGrid'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const question = { id: 'question-one', question: '가상 관측 결과는?', createdAt: '2026-10-24T00:00:00Z' }
beforeEach(() => { api.mockReset() })

it('Workspace가 없어도 빈 이력을 표시하고 잘못된 조회를 보내지 않는다', async () => {
  render(<QuestionGrid workspaceId={null} onSelect={vi.fn()} />)
  expect(await screen.findByText('질문 기록이 없습니다.')).toBeVisible()
  expect(api).not.toHaveBeenCalled()
})

it('조회 중 임시 문구나 빈 결과를 노출하지 않고 완료 후 목록으로 교체한다', async () => {
  let finish!: (value: unknown) => void
  api.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  render(<QuestionGrid workspaceId="one" onSelect={vi.fn()} />)
  expect(screen.getByRole('status', { name: '질문 기록 불러오는 중' })).toHaveTextContent('')
  expect(document.querySelector('.history-skeleton')).toHaveAttribute('aria-hidden', 'true')
  expect(screen.queryByText('기록을 불러오는 중…')).not.toBeInTheDocument()
  expect(screen.queryByText('질문 기록이 없습니다.')).not.toBeInTheDocument()
  await act(async () => finish([question]))
  expect(screen.getByRole('button', { name: question.question })).toBeVisible()
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
})

it('이력에서만 확인 후 삭제하고 실패 시 재시도한다', async () => {
  let attempts = 0
  api.mockImplementation(async (_path, options) => {
    if (options?.method === 'DELETE') {
      if (++attempts === 1) throw new Error('삭제 실패')
      return undefined
    }
    return [question]
  })
  const deleted = vi.fn()
  render(<QuestionGrid workspaceId="one" onSelect={vi.fn()} onDeleted={deleted} />)
  await userEvent.click(await screen.findByRole('checkbox', { name: `${question.question} 선택` }))
  await userEvent.click(screen.getByRole('button', { name: '선택 삭제 (1)' }))
  await userEvent.click(screen.getByRole('button', { name: '취소' }))
  expect(attempts).toBe(0)
  await userEvent.click(screen.getByRole('button', { name: '선택 삭제 (1)' }))
  await userEvent.click(screen.getByRole('button', { name: '삭제' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('1개 질문을 삭제하지 못했습니다.')
  await userEvent.click(screen.getByRole('button', { name: '삭제' }))
  await screen.findByText('질문 기록이 없습니다.')
  expect(deleted).toHaveBeenCalledWith(question.id)
  expect(api.mock.calls.filter(([, options]) => options?.method === 'DELETE').map(([path]) => path)).toEqual([
    '/workspaces/one/evidence-bundles/question-one', '/workspaces/one/evidence-bundles/question-one',
  ])
})

it('표시된 질문을 체크박스로 함께 삭제하고 실패 항목만 재시도한다', async () => {
  const second = { ...question, id: 'question-two', question: '다른 질문' }
  let failures = 0
  api.mockImplementation(async (path, options) => {
    if (options?.method === 'DELETE') {
      if (path.endsWith(second.id) && failures++ === 0) throw new Error('잠시 실패')
      return undefined
    }
    return [question, second]
  })
  const deleted = vi.fn()
  const select = vi.fn()
  render(<QuestionGrid workspaceId="one" onSelect={select} onDeleted={deleted} />)
  await userEvent.click(await screen.findByRole('checkbox', { name: '표시된 질문 전체 선택' }))
  expect(select).not.toHaveBeenCalled()
  expect(screen.queryByRole('button', { name: `${question.question} 삭제` })).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '선택 삭제 (2)' }))
  expect(screen.getByText('선택한 질문 2개')).toBeVisible()
  await userEvent.click(screen.getByRole('button', { name: '삭제' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('1개 질문')
  expect(deleted).toHaveBeenCalledExactlyOnceWith(question.id)
  await userEvent.click(screen.getByRole('button', { name: '삭제' }))
  await screen.findByText('질문 기록이 없습니다.')
  expect(deleted.mock.calls).toEqual([[question.id], [second.id]])
  expect(api.mock.calls.filter(([, options]) => options?.method === 'DELETE').map(([path]) => path)).toEqual([
    '/workspaces/one/evidence-bundles/question-one', '/workspaces/one/evidence-bundles/question-two', '/workspaces/one/evidence-bundles/question-two',
  ])
})

it('검색 변경 시 숨은 선택을 지우고 현재 결과만 전체 선택한다', async () => {
  api.mockResolvedValue([question, { ...question, id: 'two', question: '별도 질문' }])
  render(<QuestionGrid workspaceId="one" onSelect={vi.fn()} />)
  await userEvent.click(await screen.findByRole('checkbox', { name: '표시된 질문 전체 선택' }))
  await userEvent.type(screen.getByLabelText('질문 찾기'), '별도')
  expect(screen.getByRole('button', { name: '선택 삭제' })).toBeDisabled()
  await userEvent.click(screen.getByRole('checkbox', { name: '표시된 질문 전체 선택' }))
  expect(screen.getByRole('button', { name: '선택 삭제 (1)' })).toBeEnabled()
})

it('제목과 날짜 목록을 표시하고 선택할 때 해당 질문으로 이동한다', async () => {
  api.mockResolvedValue([question])
  const select = vi.fn()
  render(<QuestionGrid workspaceId="one" onSelect={select} />)
  await userEvent.click(await screen.findByRole('button', { name: question.question }))
  expect(select).toHaveBeenCalledWith(question.id)
  expect(api).toHaveBeenCalledTimes(1)
  expect(api.mock.calls[0][0]).toBe('/workspaces/one/evidence-bundles')
  expect(screen.queryByText('대화 보관함 관리')).not.toBeInTheDocument()
  expect(screen.queryByText('답변 당시 근거')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '새 질문' })).not.toBeInTheDocument()
})

it('검색은 로컬 필터만 사용하고 AI를 호출하지 않는다', async () => {
  api.mockResolvedValue([question])
  render(<QuestionGrid workspaceId="one" onSelect={vi.fn()} />)
  await screen.findByRole('button', { name: question.question })
  await userEvent.type(screen.getByLabelText('질문 찾기'), '없는 내용')
  expect(screen.getByText('일치하는 질문이 없습니다.')).toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(1)
})

it('Workspace 전환 후 이전 응답을 표시하지 않는다', async () => {
  let resolveOld!: (value: unknown) => void
  api.mockImplementation(path => path.includes('/one/') ? new Promise(resolve => { resolveOld = resolve }) : Promise.resolve([]))
  const view = render(<QuestionGrid workspaceId="one" onSelect={vi.fn()} />)
  await waitFor(() => expect(api).toHaveBeenCalledOnce())
  const signal = api.mock.calls[0][1]!.signal!
  view.rerender(<QuestionGrid workspaceId="two" onSelect={vi.fn()} />)
  expect(signal.aborted).toBe(true)
  await screen.findByText('질문 기록이 없습니다.')
  await act(async () => resolveOld([question]))
  expect(screen.queryByRole('button', { name: question.question })).not.toBeInTheDocument()
})

it('실패 시 재시도하고 질문 문자열을 HTML로 실행하지 않는다', async () => {
  api.mockRejectedValueOnce(new Error('일시 오류')).mockResolvedValueOnce([{ ...question, question: '<img src=x>' }])
  render(<QuestionGrid workspaceId="one" onSelect={vi.fn()} />)
  await userEvent.click(await screen.findByRole('button', { name: '다시 불러오기' }))
  expect(await screen.findByRole('button', { name: '<img src=x>' })).toBeInTheDocument()
  expect(document.querySelector('img')).toBeNull()
})
