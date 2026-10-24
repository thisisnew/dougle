import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import QuestionGrid from './QuestionGrid'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const question = { id: 'question-one', question: '가상 관측 결과는?', createdAt: '2026-10-24T00:00:00Z' }
beforeEach(() => { api.mockReset() })

it('제목과 날짜 카드만 표시하고 선택할 때 해당 질문으로 이동한다', async () => {
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
