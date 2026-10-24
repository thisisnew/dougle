import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import ChatPanel, { QuestionHistory } from './ChatPanel'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
vi.mock('./AnswerJobsPanel', () => ({ default: ({ initialJob }: { initialJob: { answer: string } }) => <p>{initialJob.answer}</p> }))
const api = vi.mocked(request)
const props = { workspaceId: 'one', selectedId: null, connection: { provider: 'CODEX' as const, state: 'AUTHENTICATED', model: 'fixture-model' }, onCreated: vi.fn(), onConnect: vi.fn(), onSources: vi.fn() }
const bundle = { id: 'bundle', workspaceId: 'one', digest: 'digest', payload: { question: '가상 질문', status: 'READY', items: [{ kind: 'ORIGINAL', label: 'R1', title: '가상 자료', quote: '근거', startLine: 1, endLine: 2 }] } }
beforeEach(() => { api.mockReset(); vi.clearAllMocks() })

it('미연결 상태에서는 질문 전송만 잠그고 자료와 연결을 열 수 있다', async () => {
  render(<ChatPanel {...props} connection={null} />)
  expect(screen.getByRole('textbox', { name: '질문' })).toBeDisabled()
  expect(screen.getByPlaceholderText('더글한테 물어보기')).toBeDisabled()
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '파일 첨부' })).toBeEnabled()
  await userEvent.click(screen.getByRole('button', { name: 'AI 연결하기' }))
  expect(props.onSources).not.toHaveBeenCalled()
  expect(props.onConnect).toHaveBeenCalledOnce()
  expect(api).not.toHaveBeenCalled()
})

it('시작 화면은 짧은 제목과 입력 안내만 표시한다', () => {
  render(<ChatPanel {...props} />)
  expect(screen.getByRole('heading', { name: 'Dougle' })).toBeInTheDocument()
  expect(screen.getByPlaceholderText('더글한테 물어보기')).toBeEnabled()
  expect(screen.queryByText('내 자료 기반 · 새 질문')).not.toBeInTheDocument()
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
  expect(api).toHaveBeenCalledWith('/workspaces/one/evidence-bundles', expect.objectContaining({ body: JSON.stringify({ question: '가상 질문', domains: [], includeKnowledge: false }) }))
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

it('질문 첨부를 근거에 연결하고 라이브러리 등록이나 AI 자동 전송은 하지 않는다', async () => {
  const attached = { id: 'image', name: 'synthetic.png', mediaType: 'image/png', size: 8, characters: 8, truncated: false }
  api.mockResolvedValueOnce(attached).mockResolvedValueOnce({ ...bundle, payload: { ...bundle.payload, items: [{ ...bundle.payload.items[0], kind: 'ATTACHMENT_IMAGE', title: 'synthetic.png' }] } })
  render(<ChatPanel {...props} />)
  await userEvent.upload(screen.getByLabelText('질문에 첨부할 파일'), new File(['synthetic'], 'synthetic.png', { type: 'image/png' }))
  expect(await screen.findByText('synthetic.png')).toBeInTheDocument()
  await userEvent.type(screen.getByRole('textbox'), '이미지를 확인해 주세요')
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  expect(await screen.findByText('[R1] synthetic.png · 첨부 이미지')).toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(2)
  expect(api).toHaveBeenLastCalledWith('/workspaces/one/evidence-bundles', expect.objectContaining({ body: JSON.stringify({ question: '이미지를 확인해 주세요', domains: [], includeKnowledge: false, attachmentIds: ['image'] }) }))
  expect(screen.getByRole('button', { name: '이 자료로 답변 준비' })).toBeEnabled()
  expect(props.onSources).not.toHaveBeenCalled()
})

it('Claude 이미지 질문은 전송 전에 안내하며 첨부와 질문을 보존한다', async () => {
  api.mockResolvedValue({ id: 'image', name: 'synthetic.png', mediaType: 'image/png', size: 8, characters: 8, truncated: false })
  render(<ChatPanel {...props} connection={{ ...props.connection, provider: 'CLAUDE' }} />)
  await userEvent.upload(screen.getByLabelText('질문에 첨부할 파일'), new File(['synthetic'], 'synthetic.png', { type: 'image/png' }))
  await screen.findByText('synthetic.png')
  await userEvent.type(screen.getByRole('textbox'), '이미지 질문')
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Codex')
  expect(screen.getByRole('textbox')).toHaveValue('이미지 질문')
  expect(api).toHaveBeenCalledTimes(1)
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

it('이전 편집형 지식이 포함된 미완료 질문은 그대로 전송하지 않고 원본으로 다시 질문한다', async () => {
  const legacy = { ...bundle, payload: { ...bundle.payload, items: [{ ...bundle.payload.items[0], kind: 'KNOWLEDGE' }] } }
  api.mockImplementation(async (path, options) => path.endsWith('/answer-jobs') ? [] : options?.method === 'POST' ? bundle : legacy)
  render(<ChatPanel {...props} selectedId="bundle" />)
  await userEvent.click(await screen.findByRole('button', { name: '원본으로 다시 질문' }))
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
  expect(screen.queryByRole('button', { name: '이 자료로 답변 준비' })).not.toBeInTheDocument()
  expect(screen.getByRole('textbox', { name: '질문' })).toHaveValue('가상 질문')
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  expect(api).toHaveBeenCalledWith('/workspaces/one/evidence-bundles', expect.objectContaining({ body: JSON.stringify({ question: '가상 질문', domains: [], includeKnowledge: false }) }))
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
