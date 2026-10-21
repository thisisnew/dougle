import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import KnowledgePanel, { DraftForm } from './KnowledgePanel'
import { request } from './api'

vi.mock('./api', async importOriginal => ({ ...await importOriginal<typeof import('./api')>(), request: vi.fn() }))
vi.mock('./PublicationPanel', () => ({ default: () => <section aria-label="사람 검토와 게시" /> }))
const api = vi.mocked(request)
const card = { id: 'card', workspaceId: 'one', title: '관측 초안', latestVersion: 1, createdAt: '2026-10-21' }
const document = {
  card,
  version: { number: 1, title: card.title, markdown: '<script>window.injected=true</script>', status: 'DRAFT', manuallyEdited: true,
    evidence: [{ snapshotId: 'snapshot', path: 'notes.txt', name: 'notes.txt', sourceRevision: 'revision', contentHash: 'hash',
      startLine: 1, endLine: 2, quote: '과거 원문', freshness: 'MISSING' }] },
}
beforeEach(() => { api.mockReset() })

it('선택한 카드를 폴더로 배치하고 목록만 필터링하며 본문은 보존한다', async () => {
  const user = userEvent.setup()
  let moved = false
  api.mockImplementation(async (path, options) => {
    if (path.endsWith('/knowledge-organization/cards/card') && options?.method === 'PUT') { moved = true; return {} }
    if (path.endsWith('/knowledge-organization')) return { folders: [{ id: 'folder', parentId: null, name: '분류' }],
      placements: moved ? [{ cardId: 'card', folderId: 'folder', revision: 1 }] : [] }
    if (path.endsWith('/generation-jobs')) return []
    return path.endsWith('/knowledge') ? [card] : document
  })
  render(<KnowledgePanel workspaceId="one" />)
  await user.click(await screen.findByRole('button', { name: '관측 초안 · v1' }))
  await user.click(await screen.findByRole('button', { name: '폴더: 분류' }))
  expect(screen.queryByRole('button', { name: '관측 초안 · v1' })).not.toBeInTheDocument()
  expect(screen.getByLabelText('지식 Markdown')).toHaveTextContent(document.version.markdown)
  await user.click(screen.getByRole('button', { name: '선택한 카드를 현재 폴더로 이동' }))
  expect(await screen.findByRole('button', { name: '관측 초안 · v1' })).toBeInTheDocument()
  expect(api).toHaveBeenCalledWith('/workspaces/one/knowledge-organization/cards/card', expect.objectContaining({ method: 'PUT',
    body: JSON.stringify({ folderId: 'folder', expectedRevision: 0 }) }))
  await user.click(screen.getByRole('button', { name: '초안 편집' }))
  expect(screen.getByRole('button', { name: '지식 루트' })).toBeDisabled()
})

it('추출 텍스트의 선택한 행으로 미검증 수동 초안을 저장한다', async () => {
  const user = userEvent.setup()
  api.mockResolvedValue(document)
  render(<DraftForm workspaceId="one" entryId="file" text={'첫째 행\n둘째 행'} />)
  await user.click(screen.getByRole('button', { name: '근거로 지식 초안 작성' }))
  await user.type(screen.getByLabelText('지식 제목'), '관측 초안')
  await user.type(screen.getByLabelText('Markdown 초안'), '사람이 쓴 내용')
  await user.clear(screen.getByLabelText('근거 끝 행'))
  await user.type(screen.getByLabelText('근거 끝 행'), '2')
  expect(screen.getByLabelText('선택한 근거')).toHaveTextContent('둘째 행')
  await user.click(screen.getByRole('button', { name: '미검증 초안 저장' }))
  expect(await screen.findByRole('status')).toHaveTextContent('수동 초안을 저장했습니다')
  expect(api).toHaveBeenCalledWith('/workspaces/one/knowledge', expect.objectContaining({ method: 'POST',
    body: JSON.stringify({ title: '관측 초안', markdown: '사람이 쓴 내용', citations: [{ entryId: 'file', startLine: 1, endLine: 2 }] }) }))
})

it('원본이 삭제된 근거와 Markdown을 실행하지 않고 표시한다', async () => {
  const user = userEvent.setup()
  api.mockImplementation(async path => path.endsWith('/knowledge-organization') ? { folders: [], placements: [] } : path.endsWith('/generation-jobs') ? [] : path.endsWith('/knowledge') ? [card] : document)
  const { container } = render(<KnowledgePanel workspaceId="one" />)
  await user.click(await screen.findByRole('button', { name: '관측 초안 · v1' }))
  expect(await screen.findByLabelText('지식 Markdown')).toHaveTextContent('<script>window.injected=true</script>')
  expect(container.querySelector('script')).toBeNull()
  expect(screen.getByText('원본 삭제됨 · 근거 복사본 보존')).toBeInTheDocument()
  expect(screen.getByRole('link', { name: '당시 근거 원본 다운로드' })).toHaveAttribute('href', '/api/workspaces/one/knowledge/card/evidence/snapshot/content')
})

it('사람 편집은 기대 버전을 전달하며 이전 근거를 유지한 새 버전으로 저장한다', async () => {
  const user = userEvent.setup()
  let saved = false
  api.mockImplementation(async (path, options) => {
    if (path.endsWith('/knowledge-organization')) return { folders: [], placements: [] }
    if (path.endsWith('/generation-jobs')) return []
    if (options?.method === 'POST') { saved = true; return document }
    const latest = saved ? 2 : 1
    if (path.endsWith('/knowledge')) return [{ ...card, latestVersion: latest }]
    return { card: { ...card, latestVersion: latest }, version: { ...document.version, number: latest, markdown: saved ? '새 내용' : '기존 내용' } }
  })
  render(<KnowledgePanel workspaceId="one" />)
  await user.click(await screen.findByRole('button', { name: '관측 초안 · v1' }))
  await user.click(await screen.findByRole('button', { name: '초안 편집' }))
  await user.clear(screen.getByLabelText('Markdown 초안'))
  await user.type(screen.getByLabelText('Markdown 초안'), '새 내용')
  await user.click(screen.getByRole('button', { name: '새 버전 저장' }))
  await waitFor(() => expect(screen.getByLabelText('지식 Markdown')).toHaveTextContent('새 내용'))
  expect(api).toHaveBeenCalledWith('/workspaces/one/knowledge/card/versions', expect.objectContaining({
    method: 'POST', body: JSON.stringify({ expectedVersion: 1, title: card.title, markdown: '새 내용' }),
  }))
  expect(await screen.findByRole('button', { name: '관측 초안 · v2' })).toBeInTheDocument()
})

it('충돌 응답에도 사용자가 입력한 초안을 잃지 않는다', async () => {
  const user = userEvent.setup()
  api.mockImplementation(async (path, options) => {
    if (path.endsWith('/knowledge-organization')) return { folders: [], placements: [] }
    if (path.endsWith('/generation-jobs')) return []
    if (options?.method === 'POST') throw new Error('다른 편집이 저장되었습니다. 최신 버전을 다시 열어 주세요.')
    return path.endsWith('/knowledge') ? [card] : document
  })
  render(<KnowledgePanel workspaceId="one" />)
  await user.click(await screen.findByRole('button', { name: '관측 초안 · v1' }))
  await user.click(await screen.findByRole('button', { name: '초안 편집' }))
  await user.clear(screen.getByLabelText('Markdown 초안'))
  await user.type(screen.getByLabelText('Markdown 초안'), '보존할 편집')
  await user.click(screen.getByRole('button', { name: '새 버전 저장' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('다른 편집이 저장되었습니다')
  expect(screen.getByLabelText('Markdown 초안')).toHaveValue('보존할 편집')
})

it('이전 버전은 읽기 전용이며 삭제 취소는 요청을 보내지 않는다', async () => {
  const user = userEvent.setup()
  api.mockImplementation(async path => {
    if (path.endsWith('/knowledge-organization')) return { folders: [], placements: [] }
    if (path.endsWith('/generation-jobs')) return []
    if (path.endsWith('/knowledge')) return [{ ...card, latestVersion: 2 }]
    return { card: { ...card, latestVersion: 2 }, version: { ...document.version, number: path.endsWith('/versions/1') ? 1 : 2 } }
  })
  render(<KnowledgePanel workspaceId="one" />)
  await user.click(await screen.findByRole('button', { name: '관측 초안 · v2' }))
  await user.click(await screen.findByRole('button', { name: '이전 버전' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '초안 편집' })).toBeDisabled())
  await user.click(screen.getByRole('button', { name: '지식 삭제' }))
  await user.click(screen.getByRole('button', { name: '취소' }))
  expect(api.mock.calls.some(([, options]) => options?.method === 'DELETE')).toBe(false)
})

it('초안 저장 실패를 알리고 입력값을 유지한다', async () => {
  const user = userEvent.setup()
  api.mockRejectedValue(new Error('근거 파일을 찾을 수 없습니다.'))
  render(<DraftForm workspaceId="one" entryId="file" text="가상 원문" />)
  await user.click(screen.getByRole('button', { name: '근거로 지식 초안 작성' }))
  await user.type(screen.getByLabelText('지식 제목'), '제목')
  await user.type(screen.getByLabelText('Markdown 초안'), '보존할 내용')
  await user.click(screen.getByRole('button', { name: '미검증 초안 저장' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('근거 파일을 찾을 수 없습니다')
  expect(screen.getByLabelText('Markdown 초안')).toHaveValue('보존할 내용')
})
