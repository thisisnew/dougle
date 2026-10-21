import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import CliStatusPanel from './CliStatusPanel'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
beforeEach(() => { api.mockReset() })

it('사용자 요청 때만 버전을 확인하고 로그인 성공으로 표시하지 않는다', async () => {
  const user = userEvent.setup()
  api.mockResolvedValue({ status: 'AVAILABLE', version: '0.1.0', requestedModel: 'synthetic' })
  render(<CliStatusPanel provider="CODEX" disabled={false} />)
  expect(api).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: 'CLI 설치 확인' }))
  expect(await screen.findByRole('status')).toHaveTextContent('버전 0.1.0')
  expect(screen.getByRole('status')).toHaveTextContent('로그인·원격 연결·모델 이용 가능 여부는 미확인')
  expect(api.mock.calls[0][0]).toBe('/ai/providers/CODEX/probe')
  expect(api.mock.calls[0][1]?.method).toBe('POST')
})

it('제공자를 바꾸면 이전 확인을 취소하고 늦은 응답을 폐기한다', async () => {
  const user = userEvent.setup()
  let resolve!: (value: unknown) => void
  api.mockImplementation(() => new Promise(done => { resolve = done }) as ReturnType<typeof request>)
  const view = render(<CliStatusPanel provider="CODEX" disabled={false} />)
  await user.click(screen.getByRole('button'))
  const signal = api.mock.calls[0][1]?.signal
  view.rerender(<CliStatusPanel provider="CLAUDE" disabled={false} />)
  expect(signal?.aborted).toBe(true)
  resolve({ status: 'AVAILABLE', version: 'old-version', requestedModel: '' })
  await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument())
  expect(api).toHaveBeenCalledTimes(1)
})

it('실행 실패를 설치 성공으로 표시하지 않는다', async () => {
  api.mockResolvedValue({ status: 'UNAVAILABLE', version: null, requestedModel: '' })
  render(<CliStatusPanel provider="CODEX" disabled={false} />)
  await userEvent.click(screen.getByRole('button'))
  expect(await screen.findByRole('status')).toHaveTextContent('CLI 실행을 확인하지 못했습니다')
})
