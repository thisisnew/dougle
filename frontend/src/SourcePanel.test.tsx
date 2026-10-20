import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import SourcePanel from './SourcePanel'
import { request } from './api'

vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(), request: vi.fn() }))
const api = vi.mocked(request)
const source = {
  id: 'source', name: '가상 연구', kind: 'LOCAL_FOLDER', location: '/synthetic', domain: '연구',
  status: 'NEW', revision: null, error: null, syncedAt: null, rootEntryId: null,
  pollingEnabled: false, pollIntervalSeconds: 3600,
}

beforeEach(() => { api.mockReset() })

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
    { entryId: 'file', name: '가상.md', domain: '연구', path: 'notes/가상.md', snippet: '<script>원문</script>', score: 1 },
  ] : [source, { ...source, id: 'other', name: '다른 자료', domain: '문헌' }])
  render(<SourcePanel workspaceId="one" revision={0} onChanged={vi.fn()} onSelect={select} />)
  await user.click(await screen.findByRole('checkbox', { name: '연구' }))
  await user.click(screen.getByRole('checkbox', { name: '문헌' }))
  await user.type(screen.getByLabelText('자료 검색'), '관측')
  await user.click(screen.getByRole('button', { name: '검색' }))
  await user.click(await screen.findByRole('button', { name: /가상.md/ }))
  expect(select).toHaveBeenCalledWith('file')
  expect(api.mock.calls.some(([path]) => path.includes('domains=%EC%97%B0%EA%B5%AC&domains=%EB%AC%B8%ED%97%8C'))).toBe(true)
  expect(document.querySelector('.search-results script')).toBeNull()
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
