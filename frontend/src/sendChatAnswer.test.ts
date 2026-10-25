import { beforeEach, expect, it, vi } from 'vitest'
import { request } from './api'
import { sendChatAnswer, type ChatSubmission } from './sendChatAnswer'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const preview = { bundleId: 'bundle', bundleDigest: 'frozen', provider: 'CODEX', requestedModel: 'model', digest: 'digest', validation: { eligible: true } }
const approval = { id: 'approval', bundleId: 'bundle', digest: 'digest', provider: 'CODEX', requestedModel: 'model' }
const job = { id: 'job', bundleId: 'bundle', approvalId: 'approval' }
beforeEach(() => api.mockReset())

it.each([
  { bundleId: 'other' }, { bundleDigest: 'changed' }, { provider: 'CLAUDE' }, { requestedModel: 'other' }, { validation: { eligible: false } },
])('전송 조건이 다르면 접수를 차단한다: %s', async mismatch => {
  api.mockResolvedValue({ ...preview, ...mismatch })
  await expect(sendChatAnswer('one', 'bundle', 'frozen', 'CODEX', 'model', new AbortController().signal, vi.fn())).rejects.toThrow('자료가 변경')
  expect(api).toHaveBeenCalledOnce()
})

it('응답 유실 뒤에는 동일 요청 ID로만 접수를 확인한다', async () => {
  api.mockResolvedValueOnce(preview).mockResolvedValueOnce({ approval: null }).mockResolvedValueOnce({ status: 'APPROVED', approval })
    .mockRejectedValueOnce(new Error('응답 유실')).mockResolvedValueOnce(job)
  let saved: ChatSubmission | undefined
  const remember = (value: ChatSubmission) => { saved = value }
  await expect(sendChatAnswer('one', 'bundle', 'frozen', 'CODEX', 'model', new AbortController().signal, remember)).rejects.toThrow('응답 유실')
  await expect(sendChatAnswer('one', 'bundle', 'frozen', 'CODEX', 'model', new AbortController().signal, remember, saved)).resolves.toEqual(job)
  expect(api.mock.calls[3][1]?.body).toBe(api.mock.calls[4][1]?.body)
  expect(api).toHaveBeenCalledTimes(5)
})

it('화면을 떠나면 준비 이후 자동 전송하지 않는다', async () => {
  const controller = new AbortController()
  api.mockImplementation(async () => { controller.abort(); return preview })
  await expect(sendChatAnswer('one', 'bundle', 'frozen', 'CODEX', 'model', controller.signal, vi.fn())).rejects.toThrow()
  expect(api).toHaveBeenCalledOnce()
})
