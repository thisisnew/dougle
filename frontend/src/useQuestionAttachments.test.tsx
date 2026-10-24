import { act, renderHook } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { request } from './api'
import { useQuestionAttachments } from './useQuestionAttachments'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const item = { id: 'file', name: 'note.txt', mediaType: 'text/plain', size: 4, characters: 4, truncated: false }
beforeEach(() => { api.mockReset() })

it('uploads locally with multipart and removes without any AI request', async () => {
  api.mockResolvedValue(item)
  const view = renderHook(() => useQuestionAttachments('one'))
  await act(() => view.result.current.add([new File(['note'], 'note.txt')]))
  expect(view.result.current.items).toEqual([item])
  expect(api).toHaveBeenCalledWith('/workspaces/one/attachments', expect.objectContaining({ method: 'POST', body: expect.any(FormData) }))
  await act(() => view.result.current.remove('file'))
  expect(view.result.current.items).toEqual([])
  expect(api.mock.calls.every(([path]) => path.includes('/attachments'))).toBe(true)
})

it('rejects empty, oversized and excessive files before upload', async () => {
  const view = renderHook(() => useQuestionAttachments('one'))
  await act(() => view.result.current.add([new File([], 'empty.txt')]))
  expect(view.result.current.error).toContain('빈 파일')
  await act(() => view.result.current.add([new File([new Uint8Array(5 * 1024 * 1024 + 1)], 'large.txt')]))
  expect(view.result.current.error).toContain('5 MiB')
  await act(() => view.result.current.add(Array.from({ length: 5 }, () => new File(['a'], 'note.txt'))))
  expect(view.result.current.error).toContain('4개')
  expect(api).not.toHaveBeenCalled()
})

it('late upload response after cancellation is discarded', async () => {
  let finish!: (value: unknown) => void
  api.mockImplementation((_path, options) => options?.method === 'POST' ? new Promise(resolve => { finish = resolve }) : Promise.resolve(undefined))
  const view = renderHook(() => useQuestionAttachments('one'))
  let uploading!: Promise<void>
  act(() => { uploading = view.result.current.add([new File(['note'], 'note.txt')]) })
  expect(view.result.current.uploading).toBe(true)
  act(() => view.result.current.cancel())
  await act(async () => { finish(item); await uploading })
  expect(view.result.current.items).toEqual([])
  expect(api).toHaveBeenLastCalledWith('/workspaces/one/attachments/file', { method: 'DELETE' })
})

it('locks composer during deletion and ignores obsolete responses after reset', async () => {
  api.mockResolvedValueOnce(item)
  const view = renderHook(() => useQuestionAttachments('one'))
  await act(() => view.result.current.add([new File(['note'], 'note.txt')]))
  let finish!: (value: unknown) => void
  api.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  let removing!: Promise<void>
  act(() => { removing = view.result.current.remove('file') })
  expect(view.result.current.uploading).toBe(true)
  act(() => view.result.current.reset(true))
  await act(async () => { finish(undefined); await removing })
  expect(view.result.current.items).toEqual([])
  expect(view.result.current.uploading).toBe(false)
})

it('successful binding keeps attachment while abandoned draft cleans up', async () => {
  api.mockResolvedValue(item)
  const view = renderHook(() => useQuestionAttachments('one'))
  await act(() => view.result.current.add([new File(['note'], 'note.txt')]))
  act(() => view.result.current.reset(true))
  expect(api).toHaveBeenCalledTimes(1)
  await act(() => view.result.current.add([new File(['note'], 'note.txt')]))
  view.unmount()
  expect(api).toHaveBeenLastCalledWith('/workspaces/one/attachments/file', { method: 'DELETE' })
})
