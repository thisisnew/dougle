import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { request } from './api'
import { useSearchEngine } from './useSearchEngine'

vi.mock('./api', () => ({ request: vi.fn() }))
afterEach(() => { vi.useRealTimers(); vi.mocked(request).mockReset() })

it('앱 준비 전에는 조회하지 않고 준비 상태를 유지한다', () => {
  const { result } = renderHook(() => useSearchEngine(false))
  expect(result.current).toBe('STARTING')
  expect(request).not.toHaveBeenCalled()
})

it('준비 완료를 반영하고 해제 시 요청을 취소한다', async () => {
  vi.mocked(request).mockResolvedValue({ state: 'READY' })
  const { result, unmount } = renderHook(() => useSearchEngine(true))
  await waitFor(() => expect(result.current).toBe('READY'))
  const signal = vi.mocked(request).mock.calls[0][1]?.signal
  unmount()
  expect(signal?.aborted).toBe(true)
})

it('오류와 잘못된 응답을 잠금 상태로 처리하고 다음 조회로 회복한다', async () => {
  vi.useFakeTimers()
  vi.mocked(request).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce([]).mockResolvedValue({ state: 'READY' })
  const { result } = renderHook(() => useSearchEngine(true))
  await act(async () => {})
  expect(result.current).toBe('ERROR')
  await act(async () => { await vi.advanceTimersByTimeAsync(2000) })
  expect(result.current).toBe('ERROR')
  await act(async () => { await vi.advanceTimersByTimeAsync(2000) })
  expect(result.current).toBe('READY')
})
