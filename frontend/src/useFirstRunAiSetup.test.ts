import { renderHook } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { useFirstRunAiSetup } from './useFirstRunAiSetup'
import type { ConnectionIndicator } from './connectionIndicator'

beforeEach(() => localStorage.clear())

it.each(['LOGIN_REQUIRED', 'TOOL_UNAVAILABLE'])('opens AI setup once for a new installation: %s', state => {
  const open = vi.fn()
  const view = renderHook(() => useFirstRunAiSetup({ provider: 'CODEX', state }, true, open))
  expect(open).toHaveBeenCalledExactlyOnceWith(true)
  view.rerender()
  view.unmount()
  renderHook(() => useFirstRunAiSetup({ provider: 'CODEX', state }, true, open))
  expect(open).toHaveBeenCalledTimes(1)
})

it('does not interrupt an authenticated user or reopen on later connection loss', () => {
  const open = vi.fn()
  const view = renderHook(({ state }) => useFirstRunAiSetup({ provider: 'CLAUDE', state }, true, open), { initialProps: { state: 'AUTHENTICATED' } })
  view.rerender({ state: 'LOGIN_REQUIRED' })
  view.unmount()
  renderHook(() => useFirstRunAiSetup({ provider: 'CLAUDE', state: 'LOGIN_REQUIRED' }, true, open))
  expect(open).not.toHaveBeenCalled()
})

it.each([null, 'CHECKING', 'CHECK_FAILED', 'UNSUPPORTED', 'UNCHECKED'])('does not guess login state from %s', state => {
  const open = vi.fn()
  renderHook(() => useFirstRunAiSetup(state ? { provider: 'CODEX', state } : null, true, open))
  expect(open).not.toHaveBeenCalled()
  expect(localStorage.length).toBe(0)
})

it('waits until the application is ready and idle', () => {
  const open = vi.fn()
  const connection: ConnectionIndicator = { provider: 'CODEX', state: 'LOGIN_REQUIRED' }
  const view = renderHook(({ ready }) => useFirstRunAiSetup(connection, ready, open), { initialProps: { ready: false } })
  expect(open).not.toHaveBeenCalled()
  view.rerender({ ready: true })
  expect(open).toHaveBeenCalledExactlyOnceWith(true)
})

it('does not repeat within the session when storage is unavailable', () => {
  const open = vi.fn()
  const storage = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('unavailable') })
  try {
    const view = renderHook(() => useFirstRunAiSetup({ provider: 'CODEX', state: 'LOGIN_REQUIRED' }, true, open))
    view.rerender()
    expect(open).toHaveBeenCalledExactlyOnceWith(true)
  } finally { storage.mockRestore() }
})
