import { afterEach, expect, it, vi } from 'vitest'
import { request } from './api'
import { downloadApi } from './download'

const token = 'a'.repeat(64)
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

it('attaches the native memory token while preserving request options', async () => {
  vi.stubGlobal('__FORGE_DESKTOP_SESSION__', token)
  const fetcher = vi.fn().mockResolvedValue(new Response('{}'))
  vi.stubGlobal('fetch', fetcher)
  const signal = new AbortController().signal
  await request('/session', { signal, headers: { 'X-Test': 'yes' } })
  const [url, options] = fetcher.mock.calls[0]
  expect(url).toBe('/api/session')
  expect(options.headers.get('X-Forge-Session')).toBe(token)
  expect(options.headers.get('X-Test')).toBe('yes')
  expect(options.signal).toBe(signal)
  expect(options.redirect).toBe('error')
  expect(options.mode).toBe('same-origin')
})

it('keeps development requests token-free even with a supplied session header', async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response('{}'))
  vi.stubGlobal('fetch', fetcher)
  await request('/session', { headers: { 'X-Forge-Session': 'untrusted' } })
  expect(fetcher.mock.calls[0][1].headers.has('X-Forge-Session')).toBe(false)
})

it.each(['https://example.com/file', '//example.com/file', '/../outside', '/%2e%2e/outside', '/\\example.com/file'])('rejects non-API downloads and requests: %s', async path => {
  vi.stubGlobal('__FORGE_DESKTOP_SESSION__', token)
  const fetcher = vi.fn()
  vi.stubGlobal('fetch', fetcher)
  await expect(request(path)).rejects.toThrow()
  await expect(downloadApi(path, 'file.txt')).rejects.toThrow()
  expect(fetcher).not.toHaveBeenCalled()
})

it('fails closed for malformed native tokens', async () => {
  vi.stubGlobal('__FORGE_DESKTOP_SESSION__', 'invalid')
  const fetcher = vi.fn()
  vi.stubGlobal('fetch', fetcher)
  await expect(request('/session')).rejects.toThrow('세션')
  expect(fetcher).not.toHaveBeenCalled()
})

it.each([
  ["attachment; filename*=UTF-8''%EA%B7%BC%EA%B1%B0.txt", '근거.txt'],
  ['attachment; filename="original name.txt"', 'original name.txt'],
  [null, 'fallback.txt'],
])('downloads original bytes and preserves filename %s', async (disposition, filename) => {
  vi.useFakeTimers()
  vi.stubGlobal('__FORGE_DESKTOP_SESSION__', token)
  const response = new Response('original bytes', { headers: disposition ? { 'Content-Disposition': disposition } : {} })
  const fetcher = vi.fn().mockResolvedValue(response)
  vi.stubGlobal('fetch', fetcher)
  const create = vi.fn().mockReturnValue('blob:http://localhost/synthetic')
  const revoke = vi.fn()
  vi.stubGlobal('URL', Object.assign(class extends URL {}, { createObjectURL: create, revokeObjectURL: revoke }))
  let downloaded = ''
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    downloaded = this.download
    expect(this.href).toBe('blob:http://localhost/synthetic')
  })
  await downloadApi('/files/one/content', 'fallback.txt')
  expect(downloaded).toBe(filename)
  expect(await create.mock.calls[0][0].text()).toBe('original bytes')
  expect(fetcher).toHaveBeenCalledOnce()
  expect(fetcher.mock.calls[0][1].headers.get('X-Forge-Session')).toBe(token)
  expect(document.querySelector('a[download]')).toBeNull()
  await vi.runAllTimersAsync()
  expect(revoke).toHaveBeenCalledWith('blob:http://localhost/synthetic')
})

it('does not create a download for HTTP errors', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('denied', { status: 403 })))
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click')
  await expect(downloadApi('/files/one/content', 'file')).rejects.toThrow('403')
  expect(click).not.toHaveBeenCalled()
})

it('explains WebKit transport failure without retrying a mutation', async () => {
  const fetcher = vi.fn().mockRejectedValue(new TypeError('Load failed'))
  vi.stubGlobal('fetch', fetcher)
  await expect(request('/sources', { method: 'POST' })).rejects.toThrow('앱 내부 연결이 끊겼습니다')
  expect(fetcher).toHaveBeenCalledOnce()
})

it('preserves intentional cancellation', async () => {
  const failure = new DOMException('aborted', 'AbortError')
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(failure))
  await expect(request('/session')).rejects.toBe(failure)
})
