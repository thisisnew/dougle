import { afterEach, expect, it, vi } from 'vitest'
import { request } from './api'

afterEach(() => vi.unstubAllGlobals())

it('쓰기는 로컬 클라이언트 헤더를 포함하고 204를 처리한다', async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
  vi.stubGlobal('fetch', fetcher)
  await expect(request('/workspaces/one', { method: 'DELETE' })).resolves.toBeUndefined()
  const headers = fetcher.mock.calls[0][1].headers as Headers
  expect(headers.get('X-Forge-Client')).toBe('1')
})

it('서버 문제 응답을 사용자 오류 메시지로 전달한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ detail: '이미 존재합니다.' }), { status: 409 })))
  await expect(request('/workspaces')).rejects.toThrow('이미 존재합니다.')
})

it('파일 업로드의 multipart 경계는 브라우저가 생성한다', async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response('{}'))
  vi.stubGlobal('fetch', fetcher)
  await request('/files', { method: 'POST', body: new FormData() })
  expect((fetcher.mock.calls[0][1].headers as Headers).has('Content-Type')).toBe(false)
})
