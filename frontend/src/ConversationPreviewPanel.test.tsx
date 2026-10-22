import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { request } from './api'
import ConversationPreviewPanel from './ConversationPreviewPanel'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const props = { workspaceId: 'one', conversationId: 'room', revision: 1, turns: [{ jobId: 'job', position: 1 }] }
const preview = {
  conversationId: 'room', revision: 1, provider: 'CODEX', requestedModel: 'fixture', digest: 'hash', prompt: 'safe request', characters: 12, maxCharacters: 20000,
  bundle: { id: 'bundle', workspaceId: 'one', payload: { question: '새 질문', items: [{ label: 'R1', title: '가상 자료', quote: '현재 근거', supports: [] }] } },
  history: [{ jobId: 'job', position: 1, question: '이전 질문', answer: '<img src="https://invalid.example">이전 답변 [R1]' }],
}
beforeEach(() => {
  vi.clearAllMocks()
  api.mockImplementation(async path => path.endsWith('/preview') ? preview : [{ id: 'bundle', question: '새 질문', status: 'READY' }])
})
async function choose() {
  await waitFor(() => expect(screen.getByLabelText('새 질문의 근거 묶음')).toBeEnabled())
  await userEvent.selectOptions(screen.getByLabelText('새 질문의 근거 묶음'), 'bundle')
  await userEvent.click(screen.getByRole('checkbox'))
}

it('이전 답변을 자동 선택하지 않고 명시적 미리보기만 요청한다', async () => {
  render(<ConversationPreviewPanel {...props} />)
  await waitFor(() => expect(screen.getByLabelText('새 질문의 근거 묶음')).toBeEnabled())
  expect(screen.getByRole('checkbox')).not.toBeChecked()
  expect(screen.getByRole('button', { name: '선택한 문맥 미리보기' })).toBeDisabled()
  expect(api).toHaveBeenCalledTimes(1)
  await choose()
  await userEvent.click(screen.getByRole('button', { name: '선택한 문맥 미리보기' }))
  const result = await screen.findByLabelText('문맥 미리보기 결과')
  expect(result.textContent!.indexOf('현재 근거')).toBeLessThan(result.textContent!.indexOf('이전 답변 [R1]'))
  expect(result).toHaveTextContent('사실 근거가 아님')
  expect(document.querySelector('img')).toBeNull()
  expect(api).toHaveBeenCalledWith('/workspaces/one/conversations/room/preview', expect.objectContaining({
    method: 'POST', body: JSON.stringify({ bundleId: 'bundle', expectedRevision: 1, jobIds: ['job'], provider: 'CODEX', requestedModel: '' }),
  }))
  expect(api.mock.calls.some(([path]) => /approval|answer-jobs/.test(path))).toBe(false)
})

it('모델이나 선택 변경은 이전 미리보기를 지우며 자동 재요청하지 않는다', async () => {
  render(<ConversationPreviewPanel {...props} />); await choose()
  await userEvent.click(screen.getByRole('button', { name: '선택한 문맥 미리보기' }))
  await screen.findByLabelText('문맥 미리보기 결과')
  await userEvent.type(screen.getByLabelText('문맥 요청 모델'), 'new-model')
  expect(screen.queryByLabelText('문맥 미리보기 결과')).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(2)
})

it('서버 범위 불일치를 차단한다', async () => {
  render(<ConversationPreviewPanel {...props} />); await choose()
  api.mockResolvedValueOnce({ ...preview, conversationId: 'other' })
  await userEvent.click(screen.getByRole('button', { name: '선택한 문맥 미리보기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('범위가 다릅니다')
  expect(screen.queryByLabelText('문맥 미리보기 결과')).not.toBeInTheDocument()
})

it('변경된 근거 오류를 표시하고 승인이나 전송을 하지 않는다', async () => {
  render(<ConversationPreviewPanel {...props} />); await choose()
  api.mockRejectedValueOnce(new Error('이전 답변의 근거가 변경되었습니다.'))
  await userEvent.click(screen.getByRole('button', { name: '선택한 문맥 미리보기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('근거가 변경')
  expect(api).toHaveBeenCalledTimes(2)
})

it('보관함 revision 변경 시 선택과 결과를 초기화하고 이전 요청을 중단한다', async () => {
  let resolve!: (value: unknown) => void
  const view = render(<ConversationPreviewPanel {...props} />); await choose()
  api.mockImplementationOnce(() => new Promise(complete => { resolve = complete }))
  await userEvent.click(screen.getByRole('button', { name: '선택한 문맥 미리보기' }))
  const signal = api.mock.calls[1][1]!.signal!
  view.rerender(<ConversationPreviewPanel {...props} revision={2} />)
  expect(signal.aborted).toBe(true)
  resolve(preview)
  await waitFor(() => expect(screen.getByLabelText('새 질문의 근거 묶음')).toBeEnabled())
  expect(screen.getByRole('checkbox')).not.toBeChecked()
  expect(screen.queryByLabelText('문맥 미리보기 결과')).not.toBeInTheDocument()
})
