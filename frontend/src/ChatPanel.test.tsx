import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { useState, type ReactNode } from 'react'
import ChatPanel, { QuestionHistory } from './ChatPanel'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
vi.mock('./AnswerJobsPanel', () => ({ default: ({ initialJob, references }: { initialJob: { answer: string; status: string }; references: (answer: string) => ReactNode }) => <><p>{initialJob.answer}</p>{initialJob.status === 'SUCCEEDED' && references(initialJob.answer)}</> }))
const api = vi.mocked(request)
const props = { workspaceId: 'one', selectedId: null, connection: { provider: 'CODEX' as const, state: 'AUTHENTICATED', model: 'fixture-model' }, onCreated: vi.fn(), onConnect: vi.fn(), onSources: vi.fn() }
const bundle = { id: 'bundle', workspaceId: 'one', digest: 'digest', payload: { question: '가상 질문', status: 'READY', items: [{ kind: 'ORIGINAL', label: 'R1', title: '가상 자료', quote: '근거', startLine: 1, endLine: 2 }] } }
const prepared = { bundleId: 'bundle', bundleDigest: 'digest', provider: 'CODEX', requestedModel: 'fixture-model', digest: 'preview', validation: { eligible: true } }
const approved = { id: 'approval', bundleId: 'bundle', digest: 'preview', provider: 'CODEX', requestedModel: 'fixture-model' }
const completed = { id: 'job', bundleId: 'bundle', approvalId: 'approval', status: 'SUCCEEDED', provider: 'CODEX', requestedModel: 'fixture-model', answer: '가상 답변 [R1]', error: null }
beforeEach(() => { api.mockReset(); vi.clearAllMocks() })

it('검색 준비 중에는 질문을 막고 준비 완료 후 입력을 연다', async () => {
  const view = render(<ChatPanel {...props} searchState="INDEXING" />)
  expect(screen.getByRole('textbox', { name: '질문' })).toBeDisabled()
  expect(screen.getByRole('status')).toHaveTextContent('자료 검색을 준비하고 있어요')
  fireEvent.submit(document.querySelector('form')!)
  expect(api.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false)
  view.rerender(<ChatPanel {...props} searchState="READY" />)
  expect(screen.getByRole('textbox', { name: '질문' })).toBeEnabled()
  view.rerender(<ChatPanel {...props} searchState="ERROR" />)
  expect(screen.getByRole('textbox', { name: '질문' })).toBeDisabled()
  expect(screen.getByRole('status')).toHaveTextContent('설정에서 다시 시도')
})

it('답변에서 인용하지 않은 후보와 코드블록 속 번호는 출처로 표시하지 않는다', async () => {
  const value = { ...bundle, payload: { ...bundle.payload, items: [bundle.payload.items[0],
    { ...bundle.payload.items[0], label: 'R2', title: '실제 인용 자료' }] } }
  api.mockImplementation(async path => path.endsWith('/answer-jobs')
    ? [{ ...completed, workspaceId: 'one', answer: '확인한 내용입니다. [R2]\n```\n[R1]\n```' }] : value)
  render(<ChatPanel {...props} selectedId="bundle" />)
  expect(await screen.findByText('[R2] 실제 인용 자료')).toBeVisible()
  expect(screen.queryByText('[R1] 가상 자료')).not.toBeInTheDocument()
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
})

it('연속 질문은 이전 질문과 답변을 유지한 채 아래에 추가하고 각각 저장한다', async () => {
  let count = 0
  api.mockImplementation(async (path, options) => {
    if (path.endsWith('/evidence-bundles')) {
      count++
      return { ...bundle, id: `bundle-${count}`, payload: { ...bundle.payload, question: JSON.parse(options!.body as string).question } }
    }
    const bundleId = `bundle-${count}`
    if (path.endsWith('/answer-preview')) return { ...prepared, bundleId }
    if (path.endsWith('/answer-approval')) return options?.method === 'POST'
      ? { status: 'APPROVED', approval: { ...approved, bundleId } } : { status: 'NONE', approval: null }
    return { ...completed, bundleId, id: `job-${count}`, answer: `답변 ${count} [R1]` }
  })
  function Conversation() {
    const [selectedId, setSelectedId] = useState<string | null>(null)
    return <ChatPanel {...props} selectedId={selectedId} onCreated={setSelectedId} />
  }
  render(<Conversation />)
  await userEvent.type(screen.getByRole('textbox'), '첫 질문{Enter}')
  const firstAnswer = await screen.findByText('답변 1 [R1]')
  await waitFor(() => expect(screen.getByRole('textbox')).toBeEnabled())
  await userEvent.type(screen.getByRole('textbox'), '두 번째 질문{Enter}')
  const secondAnswer = await screen.findByText('답변 2 [R1]')
  expect(screen.getByRole('heading', { name: '첫 질문' })).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: '두 번째 질문' })).toBeInTheDocument()
  expect(screen.getByText('답변 1 [R1]')).toBe(firstAnswer)
  expect(firstAnswer.compareDocumentPosition(secondAnswer) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(screen.getAllByRole('region', { name: '참고 자료' })).toHaveLength(2)
  expect(count).toBe(2)
  expect(api.mock.calls.filter(([path]) => path.endsWith('/answer-jobs'))).toHaveLength(2)
  await waitFor(() => expect(screen.getByRole('textbox')).toBeEnabled())
})

it('근거 없는 질문 뒤에도 새 질문을 보내고 다른 이력 선택 시 대화를 섞지 않는다', async () => {
  let count = 0
  api.mockImplementation(async (path, options) => {
    if (path.endsWith('/answer-jobs')) return []
    return { ...bundle, id: options?.method === 'POST' ? `empty-${++count}` : 'other',
      payload: { ...bundle.payload, question: options?.method === 'POST' ? JSON.parse(options.body as string).question : '다른 이력', status: 'NO_EVIDENCE', items: [] } }
  })
  const view = render(<ChatPanel {...props} />)
  await userEvent.type(screen.getByRole('textbox'), '첫 빈 질문{Enter}')
  await waitFor(() => expect(screen.getByRole('textbox')).toBeEnabled())
  await userEvent.type(screen.getByRole('textbox'), '두 번째 빈 질문{Enter}')
  await waitFor(() => expect(screen.getAllByText('질문과 관련된 내용을 찾지 못했어요.')).toHaveLength(2))
  view.rerender(<ChatPanel {...props} selectedId="other" />)
  await screen.findByRole('heading', { name: '다른 이력' })
  expect(screen.queryByRole('heading', { name: '첫 빈 질문' })).not.toBeInTheDocument()
  expect(screen.queryByRole('heading', { name: '두 번째 빈 질문' })).not.toBeInTheDocument()
})

it('자료를 찾는 동안 즉시 진행을 표시하고 중복 전송을 막는다', async () => {
  api.mockImplementation(() => new Promise(() => {}))
  const view = render(<ChatPanel {...props} />)
  await userEvent.type(screen.getByRole('textbox'), '가상 질문')
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  expect(screen.getByRole('status')).toHaveTextContent('확인해보겠습니다')
  expect(screen.getByRole('heading', { name: '가상 질문' })).toBeInTheDocument()
  fireEvent.submit(document.querySelector('form')!)
  expect(api).toHaveBeenCalledOnce()
  const signal = api.mock.calls[0][1]!.signal!
  view.unmount()
  expect(signal.aborted).toBe(true)
})

it('환영 로고의 o에만 캐릭터를 표시하고 오른쪽에는 중복 배치하지 않는다', () => {
  render(<ChatPanel {...props} />)
  const heading = screen.getByRole('heading', { name: 'Dougle' })
  const mascot = heading.querySelector('.wordmark-face img')
  expect(mascot).toHaveAttribute('alt', '')
  expect(mascot?.parentElement).toHaveAttribute('aria-hidden', 'true')
  expect(heading.querySelectorAll('img')).toHaveLength(1)
  expect(mascot).toHaveAttribute('src', expect.stringContaining('dougle-mascot-transparent.png'))
})

it('검색 결과가 없으면 짧은 안내만 표시한다', async () => {
  api.mockResolvedValue({ ...bundle, payload: { ...bundle.payload, status: 'NO_EVIDENCE', items: [] } })
  render(<ChatPanel {...props} />)
  await userEvent.type(screen.getByRole('textbox', { name: '질문' }), '가상 질문')
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  expect(await screen.findByText('질문과 관련된 내용을 찾지 못했어요.')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '자료 확인' })).not.toBeInTheDocument()
  expect(screen.queryByText(/자료를 확인하거나 질문을 바꿔/)).not.toBeInTheDocument()
  expect(screen.queryByText(/답변의 근거가 될 자료가 없어요/)).not.toBeInTheDocument()
})

it.each(['UNCHECKED', 'CHECKING', 'LOGIN_REQUIRED', 'TOOL_UNAVAILABLE', 'CHECK_FAILED'])('%s 상태는 입력과 전송을 잠그고 강제 submit도 보내지 않는다', state => {
  render(<ChatPanel {...props} connection={{ ...props.connection, state }} />)
  const input = screen.getByRole('textbox', { name: '질문' })
  expect(input).toBeDisabled()
  expect(input).toHaveAttribute('placeholder', 'AI를 연결해 주세요')
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeDisabled()
  fireEvent.change(input, { target: { value: '보내면 안 되는 가상 질문' } })
  fireEvent.submit(input.closest('form')!)
  expect(api).not.toHaveBeenCalled()
})

it('연결 확인 이후에만 입력을 풀고 연결이 사라지면 다시 잠근다', () => {
  const view = render(<ChatPanel {...props} connection={null} />)
  const input = screen.getByRole('textbox', { name: '질문' })
  expect(input).toBeDisabled()
  view.rerender(<ChatPanel {...props} />)
  expect(input).toBeEnabled()
  expect(input).toHaveAttribute('placeholder', '더글한테 물어보기')
  view.rerender(<ChatPanel {...props} connection={null} />)
  expect(input).toBeDisabled()
  expect(api).not.toHaveBeenCalled()
})

it('미연결 상태에서는 질문 전송만 잠그고 자료와 연결을 열 수 있다', async () => {
  render(<ChatPanel {...props} connection={null} />)
  expect(screen.getByRole('textbox', { name: '질문' })).toBeDisabled()
  expect(screen.getByPlaceholderText('AI를 연결해 주세요')).toBeDisabled()
  expect(screen.getByRole('button', { name: '질문 보내기' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '파일 첨부' })).toBeEnabled()
  expect(screen.queryByRole('button', { name: 'AI 연결하기' })).not.toBeInTheDocument()
  expect(props.onSources).not.toHaveBeenCalled()
  expect(props.onConnect).not.toHaveBeenCalled()
  expect(api).not.toHaveBeenCalled()
})

it('시작 화면은 짧은 제목과 입력 안내만 표시한다', () => {
  render(<ChatPanel {...props} />)
  expect(screen.getByRole('heading', { name: 'Dougle' })).toBeInTheDocument()
  expect(screen.getByPlaceholderText('더글한테 물어보기')).toBeEnabled()
  expect(screen.queryByText('내 자료 기반 · 새 질문')).not.toBeInTheDocument()
  expect(screen.queryByText('자료 전송은 동의 후에만 진행됩니다.')).not.toBeInTheDocument()
  expect(screen.queryByText('흩어진 자료에서, 필요한 답을.')).not.toBeInTheDocument()
})

it('질문 전송으로 자료 확인과 답변 접수를 한 번만 하고 출처는 답변 아래에 표시한다', async () => {
  api.mockResolvedValueOnce(bundle).mockResolvedValueOnce(prepared).mockResolvedValueOnce({ status: 'NONE', approval: null })
    .mockResolvedValueOnce({ status: 'APPROVED', approval: approved }).mockResolvedValueOnce(completed)
  render(<ChatPanel {...props} />)
  await userEvent.type(screen.getByRole('textbox'), '가상 질문')
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  const answer = await screen.findByText('가상 답변 [R1]')
  expect(props.onCreated).toHaveBeenCalledWith('bundle')
  expect(api).toHaveBeenCalledTimes(5)
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  expect(screen.queryByText('질문 관리')).not.toBeInTheDocument()
  expect(answer.compareDocumentPosition(screen.getByText('[R1] 가상 자료')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(api).toHaveBeenCalledWith('/workspaces/one/evidence-bundles/bundle/answer-preview', expect.objectContaining({ body: JSON.stringify({ provider: 'CODEX', requestedModel: 'fixture-model' }) }))
  expect(api.mock.calls.filter(([path]) => path.endsWith('answer-jobs'))).toHaveLength(1)
})

it('한글 조합 중 Enter는 전송하지 않는다', async () => {
  render(<ChatPanel {...props} />)
  const input = screen.getByRole('textbox')
  await userEvent.type(input, '가상')
  fireEvent.keyDown(input, { key: 'Enter', isComposing: true })
  expect(api).not.toHaveBeenCalled()
})

it('첨부 자체는 전송하지 않고 질문을 보낼 때 첨부와 함께 답변을 접수한다', async () => {
  const attached = { id: 'image', name: 'synthetic.png', mediaType: 'image/png', size: 8, characters: 8, truncated: false }
  api.mockResolvedValueOnce(attached).mockResolvedValueOnce({ ...bundle, payload: { ...bundle.payload, items: [{ ...bundle.payload.items[0], kind: 'ATTACHMENT_IMAGE', title: 'synthetic.png' }] } }).mockResolvedValueOnce(prepared).mockResolvedValueOnce({ status: 'NONE', approval: null }).mockResolvedValueOnce({ status: 'APPROVED', approval: approved }).mockResolvedValueOnce(completed)
  render(<ChatPanel {...props} />)
  await userEvent.upload(screen.getByLabelText('질문에 첨부할 파일'), new File(['synthetic'], 'synthetic.png', { type: 'image/png' }))
  expect(await screen.findByText('synthetic.png')).toBeInTheDocument()
  await userEvent.type(screen.getByRole('textbox'), '이미지를 확인해 주세요')
  await userEvent.click(screen.getByRole('button', { name: '질문 보내기' }))
  expect(await screen.findByText('[R1] synthetic.png · 첨부 이미지')).toBeInTheDocument()
  await screen.findByText('가상 답변 [R1]')
  expect(api).toHaveBeenCalledTimes(6)
  expect(api).toHaveBeenCalledWith('/workspaces/one/evidence-bundles', expect.objectContaining({ body: JSON.stringify({ question: '이미지를 확인해 주세요', domains: [], includeKnowledge: false, attachmentIds: ['image'] }) }))
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
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
  expect(screen.queryByText('최근 질문')).not.toBeInTheDocument()
})

it('질문이 없으면 사이드바 제목과 시작 안내를 표시하지 않는다', async () => {
  api.mockResolvedValue([])
  render(<QuestionHistory workspaceId="one" revision={0} selectedId={null} onSelect={vi.fn()} />)
  await waitFor(() => expect(api).toHaveBeenCalledOnce())
  expect(screen.queryByText('최근 질문')).not.toBeInTheDocument()
  expect(screen.queryByText('첫 질문을 시작해 보세요.')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '새 질문' })).not.toBeInTheDocument()
})

it('이력 열기는 재전송하지 않고 대화에는 관리나 삭제를 표시하지 않는다', async () => {
  api.mockImplementation(async path => path.endsWith('/answer-jobs') ? [] : bundle)
  render(<ChatPanel {...props} selectedId="bundle" />)
  await screen.findByRole('button', { name: '답변 받기' })
  expect(screen.queryByRole('region', { name: '참고 자료' })).not.toBeInTheDocument()
  expect(screen.queryByText('질문 관리')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '질문과 답변 삭제' })).not.toBeInTheDocument()
  expect(api.mock.calls.every(([, options]) => !options?.method || options.method === 'GET')).toBe(true)
})
