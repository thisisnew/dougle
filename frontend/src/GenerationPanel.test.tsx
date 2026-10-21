import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import GenerationPanel from './GenerationPanel'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const preview = { provider: 'CODEX', expectedVersion: 1, digest: 'hash', prompt: '선택 근거만 전송', requestedModel: 'synthetic-model' }
beforeEach(() => api.mockReset())

it('미리보기 후 건별 승인해야 작업 ID와 함께 생성을 요청한다', async () => {
  const user = userEvent.setup()
  const created = vi.fn()
  api.mockResolvedValueOnce(preview).mockResolvedValueOnce({ id: 'job', status: 'QUEUED' })
  render(<GenerationPanel base="/knowledge/one" disabled={false} onBusy={vi.fn()} onQueued={created} />)
  expect(api).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: '전송 내용 미리보기' }))
  expect(await screen.findByLabelText('AI 전송 내용')).toHaveTextContent(preview.prompt)
  expect(screen.getByLabelText('요청 모델')).toHaveTextContent('synthetic-model')
  const generate = screen.getByRole('button', { name: '승인하고 별도 AI 초안 생성' })
  expect(generate).toBeDisabled()
  await user.click(screen.getByRole('checkbox'))
  await user.click(generate)
  await waitFor(() => expect(created).toHaveBeenCalledWith('job'))
  expect(api.mock.calls[1][0]).toBe('/knowledge/one/generation/jobs')
  expect(JSON.parse(api.mock.calls[1][1]!.body as string)).toEqual({ requestId: expect.any(String), provider: 'CODEX', expectedVersion: 1, digest: 'hash', approved: true })
})

it('제공자를 바꾸면 이전 승인과 미리보기를 폐기한다', async () => {
  const user = userEvent.setup()
  api.mockResolvedValue(preview)
  render(<GenerationPanel base="/knowledge/one" disabled={false} onBusy={vi.fn()} onQueued={vi.fn()} />)
  await user.click(screen.getByRole('button', { name: '전송 내용 미리보기' }))
  await user.click(await screen.findByRole('checkbox'))
  await user.selectOptions(screen.getByRole('combobox'), 'CLAUDE')
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  expect(screen.queryByLabelText('AI 전송 내용')).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(1)
})

it('접수 응답을 잃으면 자동 재전송하지 않고 같은 ID로 수동 재전송한다', async () => {
  const user = userEvent.setup()
  api.mockResolvedValueOnce(preview).mockRejectedValueOnce(new Error('응답 연결 끊김')).mockResolvedValueOnce({ id: 'same-job', status: 'RUNNING' })
  const created = vi.fn()
  render(<GenerationPanel base="/knowledge/one" disabled={false} onBusy={vi.fn()} onQueued={created} />)
  await user.click(screen.getByRole('button', { name: '전송 내용 미리보기' }))
  await user.click(await screen.findByRole('checkbox'))
  await user.click(screen.getByRole('button', { name: '승인하고 별도 AI 초안 생성' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('응답 연결 끊김')
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  expect(created).not.toHaveBeenCalled()
  expect(api).toHaveBeenCalledTimes(2)
  await user.click(screen.getByRole('button', { name: '같은 요청 ID로 재전송' }))
  await waitFor(() => expect(created).toHaveBeenCalledWith('same-job'))
  expect(api.mock.calls[2][1]!.body).toBe(api.mock.calls[1][1]!.body)
})
