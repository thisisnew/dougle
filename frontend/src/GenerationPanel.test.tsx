import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import GenerationPanel from './GenerationPanel'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const preview = { provider: 'CODEX', expectedVersion: 1, digest: 'hash', prompt: '선택 근거만 전송' }
beforeEach(() => api.mockReset())

it('미리보기 후 건별 승인해야 생성하고 반환된 별도 초안을 연다', async () => {
  const user = userEvent.setup()
  const created = vi.fn()
  api.mockResolvedValueOnce(preview).mockResolvedValueOnce({ card: { id: 'new' } })
  render(<GenerationPanel base="/knowledge/one" disabled={false} onBusy={vi.fn()} onCreated={created} />)
  expect(api).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: '전송 내용 미리보기' }))
  expect(await screen.findByLabelText('AI 전송 내용')).toHaveTextContent(preview.prompt)
  const generate = screen.getByRole('button', { name: '승인하고 별도 AI 초안 생성' })
  expect(generate).toBeDisabled()
  await user.click(screen.getByRole('checkbox'))
  await user.click(generate)
  await waitFor(() => expect(created).toHaveBeenCalledWith('new'))
  expect(api).toHaveBeenLastCalledWith('/knowledge/one/generation', expect.objectContaining({
    body: JSON.stringify({ provider: 'CODEX', expectedVersion: 1, digest: 'hash', approved: true }),
  }))
})

it('제공자를 바꾸면 이전 승인과 미리보기를 폐기한다', async () => {
  const user = userEvent.setup()
  api.mockResolvedValue(preview)
  render(<GenerationPanel base="/knowledge/one" disabled={false} onBusy={vi.fn()} onCreated={vi.fn()} />)
  await user.click(screen.getByRole('button', { name: '전송 내용 미리보기' }))
  await user.click(await screen.findByRole('checkbox'))
  await user.selectOptions(screen.getByRole('combobox'), 'CLAUDE')
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  expect(screen.queryByLabelText('AI 전송 내용')).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(1)
})

it('CLI 실패는 오류를 표시하고 재승인 없이 재시도하지 않는다', async () => {
  const user = userEvent.setup()
  api.mockResolvedValueOnce(preview).mockRejectedValueOnce(new Error('CLI 로그인 확인'))
  const created = vi.fn()
  render(<GenerationPanel base="/knowledge/one" disabled={false} onBusy={vi.fn()} onCreated={created} />)
  await user.click(screen.getByRole('button', { name: '전송 내용 미리보기' }))
  await user.click(await screen.findByRole('checkbox'))
  await user.click(screen.getByRole('button', { name: '승인하고 별도 AI 초안 생성' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('CLI 로그인 확인')
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  expect(created).not.toHaveBeenCalled()
})
