import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import SourcePanel from './SourcePanel'
import SourceSettings from './SourceSettings'
import { selectFolder } from './folderPicker'
vi.mock('./folderPicker', () => ({ hasFolderPicker: () => true, selectFolder: vi.fn() }))
vi.mock('./PublishedSearchPreview', () => ({ default: ({ cardId, version }: { cardId: string; version: number }) => <div>게시본 열기 {cardId} v{version}</div> }))
vi.mock('./AnswerHistoryPanel', () => ({ default: ({ workspaceId, onClose }: { workspaceId: string; onClose: () => void }) => <div>이력 화면 {workspaceId}<button onClick={onClose}>이력 닫기</button></div> }))
vi.mock('./RelationPanel', () => ({ default: ({ seed }: { seed: { id: string; version: number } }) => <div>관계 대상 {seed.id} v{seed.version}</div> }))
import { request } from './api'

vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(), request: vi.fn() }))
const api = vi.mocked(request)
const source = {
  id: 'source', name: '가상 연구', kind: 'LOCAL_FOLDER', location: '/synthetic', domain: '연구',
  status: 'NEW', revision: null, error: null, syncedAt: null, rootEntryId: null,
  pollingEnabled: false, pollIntervalSeconds: 3600, nextPollAt: null, consecutiveFailures: 0, pollingRevision: 0,
}

beforeEach(() => { api.mockReset(); vi.mocked(selectFolder).mockReset() })

it('GitHub 등록 실패 시 입력을 보존하고 저장소 이름으로 재시도한다', async () => {
  let attempts = 0
  api.mockImplementation(async (_path, options) => {
    if (options?.method === 'POST') {
      if (++attempts === 1) throw new Error('등록 실패')
      return source
    }
    return []
  })
  render(<SourcePanel manageOnly workspaceId="one" revision={0} onChanged={vi.fn()} onSelect={vi.fn()} />)
  await userEvent.click(screen.getByRole('button', { name: '자료 추가' }))
  await userEvent.click(screen.getByRole('button', { name: 'GitHub' }))
  await userEvent.type(screen.getByLabelText('GitHub 주소'), 'https://github.com/example/project.git')
  await userEvent.click(screen.getByRole('button', { name: '추가' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('등록 실패')
  expect(screen.getByLabelText('GitHub 주소')).toHaveValue('https://github.com/example/project.git')
  await userEvent.click(screen.getByRole('button', { name: '추가' }))
  await waitFor(() => expect(screen.queryByLabelText('GitHub 주소')).not.toBeInTheDocument())
  expect(api).toHaveBeenCalledWith('/workspaces/one/sources', expect.objectContaining({ method: 'POST', body: JSON.stringify({ name: 'project', kind: 'GITHUB', location: 'https://github.com/example/project.git', domain: '' }) }))
})

it('웹 링크 주소가 유효하지 않으면 등록을 실행하지 않는다', async () => {
  api.mockResolvedValue([])
  render(<SourcePanel manageOnly workspaceId="one" revision={0} onChanged={vi.fn()} onSelect={vi.fn()} />)
  await userEvent.click(screen.getByRole('button', { name: '자료 추가' }))
  await userEvent.click(screen.getByRole('button', { name: '웹 링크' }))
  await userEvent.type(screen.getByLabelText('웹 링크 주소'), 'not a url')
  await userEvent.click(screen.getByRole('button', { name: '추가' }))
  expect(screen.getByLabelText('웹 링크 주소')).toBeInvalid()
  expect(api.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false)
})

it('통합 자료 추가에서 파일은 업로드만 열고 폴더 등록과 구분한다', async () => {
  api.mockResolvedValue([])
  const upload = vi.fn()
  render(<SourcePanel manageOnly workspaceId="one" revision={0} onChanged={vi.fn()} onSelect={vi.fn()} onUpload={upload} />)
  await userEvent.click(screen.getByRole('button', { name: '자료 추가' }))
  expect(screen.getByRole('button', { name: '파일' })).toHaveAttribute('aria-pressed', 'true')
  expect(screen.queryByLabelText('Source 위치')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '추가' })).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '파일 선택' }))
  expect(upload).toHaveBeenCalledOnce()
  expect(selectFolder).not.toHaveBeenCalled()
  expect(screen.queryByLabelText('Source 이름')).not.toBeInTheDocument()
  expect(screen.queryByLabelText('Source 도메인')).not.toBeInTheDocument()
  expect(screen.queryByText(/선택한 파일의 복사본/)).not.toBeInTheDocument()
  expect(api.mock.calls.some(([, options]) => options?.method)).toBe(false)
  await userEvent.click(screen.getByRole('button', { name: '폴더' }))
  expect(screen.getByRole('button', { name: '폴더 선택' })).toBeInTheDocument()
  expect(screen.queryByText(/폴더 안의 파일을 동기화/)).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '파일 선택' })).not.toBeInTheDocument()
})

it('자료 목록 갱신으로 사라진 도메인을 숨은 검색 조건으로 남기지 않는다', async () => {
  let current = source
  api.mockImplementation(async path => path.includes('/search?') ? [] : [current])
  const view = render(<SourcePanel workspaceId="one" revision={0} onChanged={vi.fn()} onSelect={vi.fn()} />)
  await userEvent.click(screen.getByText('검색 범위 · 전체 분류'))
  await userEvent.click(await screen.findByRole('checkbox', { name: '연구' }))
  current = { ...source, domain: '새 분류', pollingRevision: 1 }
  view.rerender(<SourcePanel workspaceId="one" revision={1} onChanged={vi.fn()} onSelect={vi.fn()} />)
  expect(await screen.findByRole('checkbox', { name: '새 분류' })).not.toBeChecked()
  await userEvent.type(screen.getByLabelText('자료 검색'), '관측')
  await userEvent.click(screen.getByRole('button', { name: '검색' }))
  const searchPath = api.mock.calls.find(([path]) => path.includes('/search?'))?.[0]
  expect(searchPath).toBeDefined()
  expect(searchPath).not.toContain('domains=')
})

it('수정 없이 삭제 확인 후 새 자료를 등록한다', async () => {
  let removed = false
  api.mockImplementation(async (path, options) => {
    if (options?.method === 'DELETE') { removed = true; return undefined }
    if (options?.method === 'POST') return { ...source, id: 'new-source' }
    return removed ? [] : [source]
  })
  render(<SourcePanel manageOnly workspaceId="one" revision={0} onChanged={vi.fn()} onSelect={vi.fn()} />)
  await screen.findByText('가상 연구')
  expect(screen.queryByRole('button', { name: /수정/ })).not.toBeInTheDocument()
  expect(screen.queryByText('내 자료')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '동기화' })).toHaveTextContent('')
  await userEvent.click(screen.getByRole('button', { name: '가상 연구 Source 삭제' }))
  await userEvent.click(screen.getByRole('button', { name: '삭제' }))
  await waitFor(() => expect(screen.queryByText('가상 연구')).not.toBeInTheDocument())
  await userEvent.click(screen.getByRole('button', { name: '자료 추가' }))
  await userEvent.click(screen.getByRole('button', { name: '웹 링크' }))
  await userEvent.type(screen.getByLabelText('웹 링크 주소'), 'https://example.invalid/new')
  await userEvent.click(screen.getByRole('button', { name: '추가' }))
  await waitFor(() => expect(api).toHaveBeenCalledWith('/workspaces/one/sources', expect.objectContaining({ method: 'POST', body: JSON.stringify({ name: 'new', kind: 'URL', location: 'https://example.invalid/new', domain: '' }) })))
  expect(api.mock.calls.some(([, options]) => options?.method === 'PATCH')).toBe(false)
  expect(api.mock.calls.some(([path]) => /sync|generation|answer/.test(path))).toBe(false)
})

it('검색된 게시 버전을 관련 자료 화면에 전달한다', async () => {
  api.mockImplementation(async path => path.includes('/search?') ? [
    { kind: 'KNOWLEDGE', cardId: 'card', version: 2, entryId: null, name: '게시 문서', domain: '', path: '', snippet: '요약', startLine: 1, endLine: 1, bodyMatch: true, freshness: 'CURRENT' },
  ] : [])
  render(<SourcePanel workspaceId="one" revision={0} onChanged={vi.fn()} onSelect={vi.fn()} />)
  await userEvent.type(screen.getByLabelText('자료 검색'), '요약')
  await userEvent.click(screen.getByRole('button', { name: '검색' }))
  await userEvent.click(await screen.findByText('관련 자료 더 보기'))
  await userEvent.click(await screen.findByRole('button', { name: '게시 문서 관련 자료' }))
  expect(await screen.findByText('관계 대상 card v2')).toBeInTheDocument()
  expect(screen.queryByText('게시본 열기 card v2')).not.toBeInTheDocument()
})

it('자동 갱신을 명시적으로 저장하고 수집이나 AI를 바로 호출하지 않는다', async () => {
  api.mockResolvedValue([source])
  render(<SourceSettings workspaceId="one" />)
  await userEvent.click(await screen.findByRole('checkbox', { name: '자동 갱신' }))
  expect(api.mock.calls.some(([, options]) => options?.method === 'PATCH')).toBe(false)
  await userEvent.click(screen.getByRole('button', { name: '갱신 설정 저장' }))
  await waitFor(() => expect(api.mock.calls.some(([, options]) => options?.method === 'PATCH')).toBe(true))
  expect(api).toHaveBeenCalledWith('/workspaces/one/sources/source/polling', expect.objectContaining({
    method: 'PATCH', body: JSON.stringify({ enabled: true, intervalSeconds: 3600, expectedRevision: 0 }),
  }))
  expect(api.mock.calls.some(([path]) => /sync|generation|answer/.test(path))).toBe(false)
})

it('검색어 없이 답변 이력을 명시적으로 열고 닫는다', async () => {
  api.mockResolvedValue([])
  render(<SourcePanel workspaceId="one" revision={0} onChanged={vi.fn()} onSelect={vi.fn()} />)
  expect(screen.queryByText('이력 화면 one')).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '이전 답변 보기' }))
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
  await user.click(screen.getByRole('button', { name: '자료 추가' }))
  vi.mocked(selectFolder).mockResolvedValue('/synthetic')
  expect(screen.queryByLabelText('Source 위치')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '추가' })).toBeDisabled()
  await user.click(screen.getByRole('button', { name: '폴더 선택' }))
  expect(await screen.findByLabelText('선택한 폴더')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '추가' }))
  await screen.findByText('가상 연구')
  expect(api).toHaveBeenCalledWith('/workspaces/one/sources', expect.objectContaining({ method: 'POST', body: JSON.stringify({ name: 'synthetic', kind: 'LOCAL_FOLDER', location: '/synthetic', domain: '' }) }))
  expect(api.mock.calls.some(([path]) => path.endsWith('/sync'))).toBe(false)
  expect(screen.getByRole('button', { name: '동기화' })).toBeVisible()
  await user.click(screen.getByRole('button', { name: '동기화' }))
  await waitFor(() => expect(api).toHaveBeenCalledWith('/workspaces/one/sources/source/sync', expect.objectContaining({ method: 'POST' })))
  expect(changed).toHaveBeenCalled()
})

it('폴더 선택 취소는 등록하지 않고 종류 변경 시 이전 경로를 비운다', async () => {
  api.mockResolvedValue([])
  vi.mocked(selectFolder).mockResolvedValueOnce(null).mockResolvedValueOnce('/synthetic')
  render(<SourcePanel manageOnly workspaceId="one" revision={0} onChanged={vi.fn()} onSelect={vi.fn()} />)
  await userEvent.click(screen.getByRole('button', { name: '자료 추가' }))
  await userEvent.click(screen.getByRole('button', { name: '폴더 선택' }))
  expect(screen.getByRole('button', { name: '추가' })).toBeDisabled()
  expect(api.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false)
  await userEvent.click(screen.getByRole('button', { name: '폴더 선택' }))
  expect(await screen.findByLabelText('선택한 폴더')).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '웹 링크' }))
  expect(screen.getByLabelText('웹 링크 주소')).toHaveValue('')
  await userEvent.click(screen.getByRole('button', { name: '폴더' }))
  expect(screen.getByRole('button', { name: '추가' })).toBeDisabled()
})

it('자료 화면에는 자동 갱신 설정을 노출하지 않는다', async () => {
  api.mockResolvedValue([source])
  render(<SourcePanel manageOnly workspaceId="one" revision={0} onChanged={vi.fn()} onSelect={vi.fn()} />)
  await screen.findByText('가상 연구')
  expect(screen.queryByText('자동 갱신 설정')).not.toBeInTheDocument()
})

it('다중 도메인을 함께 검색하고 근거 파일을 선택한다', async () => {
  const user = userEvent.setup()
  const select = vi.fn()
  api.mockImplementation(async path => path.includes('/search?') ? [
    { entryId: 'file', name: '가상.md', domain: '연구', path: 'notes/가상.md', snippet: '<script>원문</script>', score: 1, startLine: 100, endLine: 105, bodyMatch: true },
  ] : [source, { ...source, id: 'other', name: '다른 자료', domain: '문헌' }])
  render(<SourcePanel workspaceId="one" revision={0} onChanged={vi.fn()} onSelect={select} />)
  await user.click(screen.getByText('검색 범위 · 전체 분류'))
  await user.click(await screen.findByRole('checkbox', { name: '연구' }))
  await user.click(screen.getByRole('checkbox', { name: '문헌' }))
  await user.type(screen.getByLabelText('자료 검색'), '관측')
  await user.click(screen.getByRole('button', { name: '검색' }))
  await user.click(await screen.findByRole('button', { name: /가상.md.*본문 검색어 주변/ }))
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
  await user.click(await screen.findByRole('button', { name: /게시 문서.*게시 지식 v2/ }))
  expect(screen.getByText('게시본 열기 card v2')).toBeInTheDocument()
  expect(select).not.toHaveBeenCalled()
  expect(screen.getByText(/게시 지식 v2 · 근거 MISSING/)).toBeInTheDocument()
  expect(api.mock.calls.some(([path]) => path.includes('includeKnowledge=true'))).toBe(true)
  await user.click(screen.getByText('검색 범위 · 전체 분류'))
  await user.click(screen.getByRole('checkbox', { name: /게시한 지식 문서도 포함/ }))
  await user.click(screen.getByRole('button', { name: '검색' }))
  expect(api.mock.calls.some(([path]) => path.includes('includeKnowledge=false'))).toBe(true)
})

it('삭제 확인을 취소하면 Source와 원본이 유지된다', async () => {
  const user = userEvent.setup()
  api.mockResolvedValue([source])
  render(<SourcePanel workspaceId="one" revision={0} onChanged={vi.fn()} onSelect={vi.fn()} />)
  await user.click(await screen.findByRole('button', { name: '가상 연구 Source 삭제' }))
  expect(screen.getByRole('alert')).toHaveTextContent('삭제하시겠습니까?')
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
it('동기화는 모든 연결 자료를 처리하고 하나가 실패해도 다음 자료를 계속한다', async () => {
  const other = { ...source, id: 'second', name: '두 번째 자료' }
  api.mockImplementation(async (path) => {
    if (path.endsWith('/source/sync')) throw new Error('연결 실패')
    if (path.endsWith('/second/sync')) return { ...other, status: 'READY' }
    return [source, other]
  })
  const changed = vi.fn()
  render(<SourcePanel manageOnly workspaceId="one" revision={0} onChanged={changed} onSelect={vi.fn()} />)
  await screen.findByText('두 번째 자료')
  expect(screen.getAllByRole('button', { name: '동기화' })).toHaveLength(1)
  await userEvent.click(screen.getByRole('button', { name: '동기화' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('가상 연구: 연결 실패')
  expect(api).toHaveBeenCalledWith('/workspaces/one/sources/second/sync', expect.objectContaining({ method: 'POST' }))
  expect(changed).toHaveBeenCalledOnce()
  expect(api.mock.calls.some(([path]) => /answer|generation/.test(path))).toBe(false)
})

it('동기화 중인 자료가 있으면 동기화를 중복 실행하지 않는다', async () => {
  api.mockResolvedValue([{ ...source, status: 'SYNCING' }])
  render(<SourcePanel manageOnly workspaceId="one" revision={0} onChanged={vi.fn()} onSelect={vi.fn()} />)
  await screen.findByText('가상 연구')
  expect(screen.getByRole('button', { name: '동기화' })).toBeDisabled()
})
