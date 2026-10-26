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
  await expect(request('/workspaces')).rejects.toMatchObject({ name: 'ApiError', status: 409 })
})

it('통신 오류는 확정적인 서버 거절 상태로 바꾸지 않는다', async () => {
  const failure = new TypeError('Network unavailable')
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(failure))
  await expect(request('/workspaces')).rejects.toBe(failure)
})

it('삭제된 Slack 대화의 구조화된 복구 정보만 검증해 전달한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ detail: 'Missing thread',
    code: 'SLACK_THREAD_MISSING', expectedThreadId: 'old-thread' }), { status: 409 })))
  await expect(request('/slack')).rejects.toMatchObject({ status: 409, code: 'SLACK_THREAD_MISSING', expectedThreadId: 'old-thread' })
})

it.each([
  { code: 'SLACK_THREAD_MISSING', expectedThreadId: '../other' },
  { code: 'SLACK_THREAD_MISSING', expectedThreadId: 42 },
  { code: 'SLACK_THREAD_MISSING', expectedThreadId: 'x'.repeat(129) },
  { code: 'OTHER_CONFLICT', expectedThreadId: 'old-thread' },
  { code: {}, expectedThreadId: 'old-thread' },
  { code: 'invalid code', expectedThreadId: 'old-thread' },
])('잘못된 오류 메타데이터는 복구 권한이 되지 않는다: %j', async metadata => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ detail: 'Conflict', ...metadata }), { status: 409 })))
  await expect(request('/slack')).rejects.toMatchObject({ expectedThreadId: undefined })
})

it('409가 아닌 응답과 null 문제 본문은 복구 메타데이터를 만들지 않는다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ code: 'SLACK_THREAD_MISSING', expectedThreadId: 'old-thread' }), { status: 500 }))
    .mockResolvedValueOnce(new Response('null', { status: 409 })))
  await expect(request('/slack')).rejects.toMatchObject({ status: 500, expectedThreadId: undefined })
  await expect(request('/slack')).rejects.toMatchObject({ status: 409, expectedThreadId: undefined })
})

it('파일 업로드의 multipart 경계는 브라우저가 생성한다', async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response('{}'))
  vi.stubGlobal('fetch', fetcher)
  await request('/files', { method: 'POST', body: new FormData() })
  expect((fetcher.mock.calls[0][1].headers as Headers).has('Content-Type')).toBe(false)
})
