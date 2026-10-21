import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import SourcePanel from './SourcePanel'
vi.mock('./PublishedSearchPreview', () => ({ default: ({ cardId, version }: { cardId: string; version: number }) => <div>게시본 열기 {cardId} v{version}</div> }))
vi.mock('./AnswerHistoryPanel', () => ({ default: ({ workspaceId, onClose }: { workspaceId: string; onClose: () => void }) => <div>이력 화면 {workspaceId}<button onClick={onClose}>이력 닫기</button></div> }))
import { request } from './api'

vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(), request: vi.fn() }))
const api = vi.mocked(request)
const source = {
  id: 'source', name: '가상 연구', kind: 'LOCAL_FOLDER', location: '/synthetic', domain: '연구',
  status: 'NEW', revision: null, error: null, syncedAt: null, rootEntryId: null,
  pollingEnabled: false, pollIntervalSeconds: 3600,
}

beforeEach(() => { api.mockReset() })

it('검색어 없이 답변 이력을 명시적으로 열고 닫는다', async () => {
  api.mockResolvedValue([])
  render(<SourcePanel workspaceId="one" revision={0} onChanged={vi.fn()} onSelect={vi.fn()} />)
  expect(screen.queryByText('이력 화면 one')).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '답변 이력' }))
  expect(await screen.findByText('이력 화면 one')).toBeInTheDocument()
  expect(api.mock.calls.some(([path]) => path.includes('/search'))).toBe(false)
  await userEvent.click(screen.getByRole('button', { name: '이력 닫기' }))
  expect(screen.queryByText('이력 화면 one')).not.toBeInTheDocument()
})

it('Source를 등록하고 별도 수동 동기화로 수집한다', async () => {
  const user = userEvent.setup()
  const changed = vi.fn()
  let created = false
  api.mockImplementation(async (path, options) => {
    if (options?.method === 'POST' && path.endsWith('/sources')) { created = true; return source }
    if (path.endsWith('/sync')) return { ...source, status: 'READY' }
    return created ? [source] : []
  })
  render(<SourcePanel workspaceId="one" revision={0} onChanged={changed} onSelect={vi.fn()} />)
  await user.click(screen.getByRole('button', { name: 'Source 등록' }))
  await user.type(screen.getByLabelText('Source 이름'), '가상 연구')
  await user.type(screen.getByLabelText('Source 위치'), '/synthetic')
  await user.type(screen.getByLabelText('Source 도메인'), '연구')
  await user.click(screen.getByRole('button', { name: '등록' }))
  await screen.findByText('가상 연구')
  expect(api.mock.calls.some(([path]) => path.endsWith('/sync'))).toBe(false)
  await user.click(screen.getByRole('button', { name: '동기화' }))
  await waitFor(() => expect(api).toHaveBeenCalledWith('/workspaces/one/sources/source/sync', expect.objectContaining({ method: 'POST' })))
  expect(changed).toHaveBeenCalled()
})

it('다중 도메인을 함께 검색하고 근거 파일을 선택한다', async () => {
  const user = userEvent.setup()
  const select = vi.fn()
  api.mockImplementation(async path => path.includes('/search?') ? [
    { entryId: 'file', name: '가상.md', domain: '연구', path: 'notes/가상.md', snippet: '<script>원문</script>', score: 1, startLine: 100, endLine: 105, bodyMatch: true },
  ] : [source, { ...source, id: 'other', name: '다른 자료', domain: '문헌' }])
  render(<SourcePanel workspaceId="one" revision={0} onChanged={vi.fn()} onSelect={select} />)
  await user.click(await screen.findByRole('checkbox', { name: '연구' }))
  await user.click(screen.getByRole('checkbox', { name: '문헌' }))
  await user.type(screen.getByLabelText('자료 검색'), '관측')
  await user.click(screen.getByRole('button', { name: '검색' }))
  await user.click(await screen.findByRole('button', { name: /가상.md/ }))
  expect(select).toHaveBeenCalledWith('file')
  expect(screen.getByText('본문 검색어 주변 · 추출 텍스트 100–105행 (일부)')).toBeInTheDocument()
  expect(api.mock.calls.some(([path]) => path.includes('domains=%EC%97%B0%EA%B5%AC&domains=%EB%AC%B8%ED%97%8C'))).toBe(true)
  expect(document.querySelector('.search-results script')).toBeNull()
})

it('이름이나 심볼만 일치하면 본문 일치와 구별한다', async () => {
  const user = userEvent.setup()
  api.mockImplementation(async path => path.includes('/search?') ? [
    { entryId: 'file', name: 'telescope', domain: '', path: 'telescope', snippet: '', score: 1, startLine: 0, endLine: 0, bodyMatch: false },
  ] : [])
  render(<SourcePanel workspaceId="one" revision={0} onChanged={vi.fn()} onSelect={vi.fn()} />)
  await user.type(screen.getByLabelText('자료 검색'), 'telescope')
  await user.click(screen.getByRole('button', { name: '검색' }))
  expect(await screen.findByText('이름·심볼 일치 · 본문 앞부분 · 본문 없음')).toBeInTheDocument()
})

it('게시본을 원본 선택과 구분해 열고 미게시 초안 검색은 제공하지 않는다', async () => {
  const user = userEvent.setup()
  const select = vi.fn()
  api.mockImplementation(async path => path.includes('/search?') ? [
    { kind: 'KNOWLEDGE', cardId: 'card', version: 2, entryId: null, name: '게시 문서', domain: '연구', path: '', snippet: '요약 [E1]', startLine: 1, endLine: 2, bodyMatch: true, freshness: 'MISSING' },
  ] : [])
  render(<SourcePanel workspaceId="one" revision={0} onChanged={vi.fn()} onSelect={select} />)
  await user.type(screen.getByLabelText('자료 검색'), '요약')
  await user.click(screen.getByRole('button', { name: '검색' }))
  await user.click(await screen.findByRole('button', { name: /게시 문서/ }))
  expect(screen.getByText('게시본 열기 card v2')).toBeInTheDocument()
  expect(select).not.toHaveBeenCalled()
  expect(screen.getByText(/게시 지식 v2 · 근거 MISSING/)).toBeInTheDocument()
  expect(api.mock.calls.some(([path]) => path.includes('includeKnowledge=true'))).toBe(true)
  await user.click(screen.getByRole('checkbox', { name: '게시 지식도 검색 (미게시 초안 제외)' }))
  await user.click(screen.getByRole('button', { name: '검색' }))
  expect(api.mock.calls.some(([path]) => path.includes('includeKnowledge=false'))).toBe(true)
})

it('삭제 확인을 취소하면 Source와 원본이 유지된다', async () => {
  const user = userEvent.setup()
  api.mockResolvedValue([source])
  render(<SourcePanel workspaceId="one" revision={0} onChanged={vi.fn()} onSelect={vi.fn()} />)
  await user.click(await screen.findByRole('button', { name: '가상 연구 Source 삭제' }))
  expect(screen.getByRole('alert')).toHaveTextContent('PC 원래 폴더·원격 저장소는 유지됩니다.')
  await user.click(screen.getByRole('button', { name: '취소' }))
  expect(api.mock.calls.some(([, options]) => options?.method === 'DELETE')).toBe(false)
})

it('실패한 수집은 오류와 이전 반영 버전을 함께 표시한다', async () => {
  api.mockResolvedValue([{ ...source, status: 'ERROR', revision: 'abcdef1234567890', error: '네트워크 연결 실패' }])
  render(<SourcePanel workspaceId="one" revision={0} onChanged={vi.fn()} onSelect={vi.fn()} />)
  expect(await screen.findByText('네트워크 연결 실패')).toBeInTheDocument()
  expect(screen.getByText(/반영 버전 abcdef123456/)).toBeInTheDocument()
  expect(screen.getByText(/오류 · 이전 자료 유지/)).toBeInTheDocument()
})
