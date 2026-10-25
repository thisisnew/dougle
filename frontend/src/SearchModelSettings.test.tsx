import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import SearchModelSettings from './SearchModelSettings'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
beforeEach(() => api.mockReset())

it('기본 제공 검색 엔진의 실패를 재시도하며 별도 활성화를 요구하지 않는다', async () => {
  api.mockResolvedValue({ state: 'ERROR', downloaded: 0, total: 135429554, error: '준비 실패' })
  render(<SearchModelSettings />)
  expect(api).not.toHaveBeenCalled()
  await userEvent.click(screen.getByRole('button', { name: '검색 엔진' }))
  const dialog = await screen.findByRole('dialog')
  await waitFor(() => expect(within(dialog).getByRole('button', { name: '다시 시도' })).toBeEnabled())
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
  api.mockResolvedValue({ state: 'LOADING', downloaded: 0, total: 135429554, error: null })
  await userEvent.click(within(dialog).getByRole('button', { name: '다시 시도' }))
  expect(api).toHaveBeenCalledWith('/search-model/prepare', { method: 'POST' })
  expect(within(dialog).getByRole('progressbar')).not.toHaveAttribute('value')
  expect(screen.queryByText('최초 다운로드 약 260 MB')).not.toBeInTheDocument()
  expect(api.mock.calls.every(([path]) => path.startsWith('/search-model'))).toBe(true)
})

it('준비가 끝난 검색 파일은 다시 내려받도록 유도하지 않는다', async () => {
  api.mockResolvedValue({ state: 'READY', downloaded: 135429554, total: 135429554, error: null })
  render(<SearchModelSettings />)
  await userEvent.click(screen.getByRole('button', { name: '검색 엔진' }))
  expect(await screen.findByText('사용 중')).toBeVisible()
  expect(screen.queryByRole('button', { name: '검색 개선 켜기' })).not.toBeInTheDocument()
})
