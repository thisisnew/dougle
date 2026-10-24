import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import ChatPanel, { QuestionHistory } from './ChatPanel'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
vi.mock('./AnswerJobsPanel', () => ({ default: ({ initialJob }: { initialJob: { answer: string } }) => <p>{initialJob.answer}</p> }))
const api = vi.mocked(request)
const props = { workspaceId: 'one', selectedId: null, connection: { provider: 'CODEX' as const, state: 'AUTHENTICATED', model: 'fixture-model' }, onCreated: vi.fn(), onConnect: vi.fn(), onSources: vi.fn() }
const bundle = { id: 'bundle', workspaceId: 'one', digest: 'digest', payload: { question: '가상 질문', status: 'READY', items: [{ label: 'R1', title: '가상 자료', quote: '근거', startLine: 1, endLine: 2 }] } }
beforeEach(() => { api.mockReset(); vi.clearAllMocks() })

it('미연결 상태에서는 질문 전송만 잠그고 자료와 연결을 열 수 있다', async () => {
  render(<ChatPanel {...props} connection={null} />)
  expect(screen.getByRole('textbox', { name: '질문' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeDisabled()
  await userEvent.click(screen.getByRole('button', { name: '자료 추가' }))
  await userEvent.click(screen.getByRole('button', { name: 'AI 연결하기' }))
  expect(props.onSources).toHaveBeenCalledOnce()
  expect(props.onConnect).toHaveBeenCalledOnce()
  expect(api).not.toHaveBeenCalled()
})

it('시작 화면은 짧은 제목과 입력 안내만 표시한다', () => {
  render(<ChatPanel {...props} />)
  expect(screen.getByRole('heading', { name: '무엇이 궁금하세요?' })).toBeInTheDocument()
  expect(screen.getByPlaceholderText('궁금한 점을 물어보세요')).toBeEnabled()
  expect(screen.queryByText('흩어진 자료에서, 필요한 답을.')).not.toBeInTheDocument()
})

it('질문 한 번으로 로컬 근거를 만들고 모델을 미리보기에 고정하되 자동 전송하지 않는다', async () => {
  api.mockResolvedValueOnce(bundle).mockResolvedValueOnce({ bundleId: 'bundle', bundleDigest: 'digest', provider: 'CODEX', requestedModel: 'fixture-model', digest: 'preview', prompt: '가상 전송문', validation: { eligible: true } })
  render(<ChatPanel {...props} />)
  await userEvent.type(screen.getByRole('textbox'), '가상 질문')
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  expect(await screen.findByText('[R1] 가상 자료')).toBeInTheDocument()
  expect(props.onCreated).toHaveBeenCalledWith('bundle')
  expect(api).toHaveBeenCalledTimes(1)
  expect(api).toHaveBeenCalledWith('/workspaces/one/evidence-bundles', expect.objectContaining({ body: JSON.stringify({ question: '가상 질문', domains: [], includeKnowledge: true }) }))
  await userEvent.click(screen.getByRole('button', { name: '이 자료로 답변 준비' }))
  expect(await screen.findByRole('button', { name: '동의하고 답변 받기' })).toBeDisabled()
  expect(api).toHaveBeenLastCalledWith('/workspaces/one/evidence-bundles/bundle/answer-preview', expect.objectContaining({ body: JSON.stringify({ provider: 'CODEX', requestedModel: 'fixture-model' }) }))
  expect(api.mock.calls.some(([path]) => path.endsWith('answer-jobs'))).toBe(false)
})

it('한글 조합 중 Enter는 전송하지 않는다', async () => {
  render(<ChatPanel {...props} />)
  const input = screen.getByRole('textbox')
  await userEvent.type(input, '가상')
  fireEvent.keyDown(input, { key: 'Enter', isComposing: true })
  expect(api).not.toHaveBeenCalled()
})

it('다른 질문 선택 후 늦은 이전 응답을 무시한다', async () => {
  let finish!: (value: unknown) => void
  api.mockImplementation(path => path.endsWith('/old') ? new Promise(resolve => { finish = resolve }) : Promise.resolve([]))
  const view = render(<ChatPanel {...props} selectedId="old" />)
  view.rerender(<ChatPanel {...props} selectedId={null} />)
  finish({ ...bundle, id: 'old' })
  await waitFor(() => expect(screen.getByRole('textbox')).toBeEnabled())
  expect(screen.queryByText('[R1] 가상 자료')).not.toBeInTheDocument()
})

it('연결 없이도 이전 답변을 읽으며 새 AI 호출을 하지 않는다', async () => {
  api.mockImplementation(async path => path.endsWith('/answer-jobs') ? [{ id: 'job', workspaceId: 'one', bundleId: 'bundle', answer: '보존한 답변' }] : bundle)
  render(<ChatPanel {...props} selectedId="bundle" connection={null} />)
  expect(await screen.findByText('보존한 답변')).toBeInTheDocument()
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
})

it('최근 질문 클릭과 새 질문은 목록 조회 이외의 API를 부르지 않는다', async () => {
  api.mockResolvedValue([{ id: 'bundle', question: '가상 질문' }])
  const select = vi.fn()
  render(<QuestionHistory workspaceId="one" revision={0} selectedId="bundle" onSelect={select} />)
  await userEvent.click(await screen.findByRole('button', { name: '가상 질문' }))
  await userEvent.click(screen.getByRole('button', { name: '새 질문' }))
  expect(select.mock.calls).toEqual([['bundle'], [null]])
  expect(api).toHaveBeenCalledTimes(1)
})

it('질문 삭제는 확인 후 해당 질문만 삭제하며 원본 API를 호출하지 않는다', async () => {
  api.mockImplementation(async path => path.endsWith('/answer-jobs') ? [] : bundle)
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  const deleted = vi.fn()
  render(<ChatPanel {...props} selectedId="bundle" onDeleted={deleted} />)
  await screen.findByText('[R1] 가상 자료')
  await userEvent.click(screen.getByText('질문 관리'))
  await userEvent.click(screen.getByRole('button', { name: '질문과 답변 삭제' }))
  expect(api.mock.calls.some(([, options]) => options?.method === 'DELETE')).toBe(false)
  confirm.mockReturnValue(true)
  await userEvent.click(screen.getByRole('button', { name: '질문과 답변 삭제' }))
  await waitFor(() => expect(deleted).toHaveBeenCalledOnce())
  expect(api.mock.calls.filter(([, options]) => options?.method === 'DELETE').map(([path]) => path)).toEqual(['/workspaces/one/evidence-bundles/bundle'])
  confirm.mockRestore()
})
