import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import KnowledgeOrganization from './KnowledgeOrganization'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const base = '/workspaces/one/knowledge-organization'
const cards = [{ id: 'card', title: '가상 카드' }, { id: 'root-card', title: '루트 카드' }]
const catalog = { folders: [{ id: 'folder', parentId: null, name: '가상 폴더' }, { id: 'child', parentId: 'folder', name: '하위 폴더' }],
  placements: [{ cardId: 'card', folderId: 'folder', revision: 3 }] }
beforeEach(() => { api.mockReset() })

it('전체·루트·하위 폴더 탐색은 카드 목록만 필터링한다', async () => {
  const user = userEvent.setup()
  const filter = vi.fn()
  api.mockResolvedValue(catalog)
  render(<KnowledgeOrganization workspaceId="one" cards={cards} selected="" disabled={false} revision={0} onFilter={filter} onBusy={vi.fn()} />)
  await screen.findByRole('button', { name: '폴더: 가상 폴더' })
  expect(filter).toHaveBeenLastCalledWith(null)
  await user.click(screen.getByRole('button', { name: '지식 루트' }))
  expect(filter).toHaveBeenLastCalledWith(['root-card'])
  await user.click(screen.getByRole('button', { name: '폴더: 가상 폴더' }))
  expect(filter).toHaveBeenLastCalledWith(['card'])
  await user.click(screen.getByRole('button', { name: '폴더: 하위 폴더' }))
  expect(filter).toHaveBeenLastCalledWith([])
  expect(screen.getByLabelText('지식 폴더 경로')).toHaveTextContent('가상 폴더 / 하위 폴더')
  expect(api.mock.calls.every(([, options]) => !options?.method)).toBe(true)
})

it('현재 폴더 아래 생성하며 실패해도 입력 이름을 보존한다', async () => {
  const user = userEvent.setup()
  api.mockResolvedValueOnce(catalog).mockRejectedValueOnce(new Error('중복 이름'))
  render(<KnowledgeOrganization workspaceId="one" cards={cards} selected="" disabled={false} revision={0} onFilter={vi.fn()} onBusy={vi.fn()} />)
  await user.click(await screen.findByRole('button', { name: '폴더: 가상 폴더' }))
  await user.type(screen.getByLabelText('새 지식 폴더 이름'), '새 폴더')
  await user.click(screen.getByRole('button', { name: '지식 폴더 생성' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('중복 이름')
  expect(screen.getByLabelText('새 지식 폴더 이름')).toHaveValue('새 폴더')
  expect(api).toHaveBeenCalledWith(`${base}/folders`, expect.objectContaining({ method: 'POST', body: JSON.stringify({ parentId: 'folder', name: '새 폴더' }) }))
})

it('카드를 루트로 이동할 때 문서 버전 대신 위치 revision을 보낸다', async () => {
  const user = userEvent.setup()
  api.mockResolvedValue(catalog)
  render(<KnowledgeOrganization workspaceId="one" cards={cards} selected="card" disabled={false} revision={0} onFilter={vi.fn()} onBusy={vi.fn()} />)
  await screen.findByRole('button', { name: '폴더: 가상 폴더' })
  await user.click(screen.getByRole('button', { name: '지식 루트' }))
  await user.click(screen.getByRole('button', { name: '선택한 카드를 현재 폴더로 이동' }))
  expect(api).toHaveBeenCalledWith(`${base}/cards/card`, expect.objectContaining({ method: 'PUT', body: JSON.stringify({ folderId: null, expectedRevision: 3 }) }))
})

it('폴더 삭제는 확인을 요구하며 취소는 요청을 보내지 않는다', async () => {
  const user = userEvent.setup()
  api.mockResolvedValue(catalog)
  render(<KnowledgeOrganization workspaceId="one" cards={cards} selected="" disabled={false} revision={0} onFilter={vi.fn()} onBusy={vi.fn()} />)
  await user.click(await screen.findByRole('button', { name: '폴더: 가상 폴더' }))
  await user.click(screen.getByRole('button', { name: '현재 지식 폴더 삭제' }))
  await user.click(screen.getByRole('button', { name: '폴더 삭제 취소' }))
  expect(api.mock.calls.some(([, options]) => options?.method === 'DELETE')).toBe(false)
  await user.click(screen.getByRole('button', { name: '현재 지식 폴더 삭제' }))
  api.mockRejectedValueOnce(new Error('빈 폴더만 삭제'))
  await user.click(screen.getByRole('button', { name: '빈 폴더 삭제 확인' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('빈 폴더만 삭제')
})

it('편집 중에는 이동·폴더 탐색을 차단하고 화면 이탈 시 조회를 중단한다', async () => {
  api.mockReturnValue(new Promise(() => {}))
  const view = render(<KnowledgeOrganization workspaceId="one" cards={cards} selected="card" disabled revision={0} onFilter={vi.fn()} onBusy={vi.fn()} />)
  expect(screen.getByRole('button', { name: '지식 루트' })).toBeDisabled()
  await waitFor(() => expect(api).toHaveBeenCalledTimes(1))
  const signal = api.mock.calls[0][1]?.signal
  view.unmount()
  expect(signal?.aborted).toBe(true)
})
