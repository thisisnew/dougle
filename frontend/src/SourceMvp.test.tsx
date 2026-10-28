import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import SourcePanel from './SourcePanel'
import PluginPanel from './PluginPanel'
import { request } from './api'
import { selectFolder } from './folderPicker'
vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(), request: vi.fn() }))
vi.mock('./folderPicker', () => ({ hasFolderPicker: () => true, selectFolder: vi.fn() }))
const api = vi.mocked(request)
beforeEach(() => { api.mockReset(); api.mockResolvedValue([]); vi.mocked(selectFolder).mockReset() })
const props = () => ({ workspaceId: 'one', revision: 0, onChanged: vi.fn(), onSelect: vi.fn(), onUpload: vi.fn() })

it('offers only file and folder inputs without URL entry', async () => {
  render(<SourcePanel {...props()} />)
  await userEvent.click(screen.getByRole('button', { name: '자료 추가' }))
  expect(screen.getByRole('button', { name: '파일' })).toBeVisible()
  expect(screen.getByRole('button', { name: '폴더' })).toBeVisible()
  for (const name of ['웹 링크', 'GitHub']) expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
  expect(document.querySelector('input[type="url"]')).toBeNull()
  expect(api.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false)
})

it.each(['URL', 'GITHUB'])('preserves cached %s source and does not resync or offer authentication', async kind => {
  const saved = { id: 'saved', name: '기존 자료', kind, status: 'READY', rootEntryId: 'root', location: 'https://example.org', pollingEnabled: true }
  api.mockResolvedValue([saved])
  const callbacks = props()
  render(<SourcePanel {...callbacks} />)
  await userEvent.click(await screen.findByRole('button', { name: '기존 자료' }))
  expect(callbacks.onSelect).toHaveBeenCalledWith('root')
  expect(screen.getByText(/보관 자료 · 직접 연결 종료/)).toBeVisible()
  expect(screen.getByRole('button', { name: '동기화' })).toBeDisabled()
  expect(api.mock.calls.some(([, options]) => options?.method === 'POST' || options?.method === 'DELETE')).toBe(false)
})

it('syncs a local folder without sending remote archive sync requests', async () => {
  const sources = [{ id: 'web', kind: 'URL', name: '웹 보관' }, { id: 'git', kind: 'GITHUB', name: '코드 보관' }, { id: 'local', kind: 'LOCAL_FOLDER', name: '폴더' }]
  api.mockImplementation(async (_, options) => options?.method === 'POST' ? { status: 'READY' } : sources)
  render(<SourcePanel {...props()} />)
  await waitFor(() => expect(screen.getByRole('button', { name: '동기화' })).toBeEnabled())
  await userEvent.click(screen.getByRole('button', { name: '동기화' }))
  await waitFor(() => expect(api).toHaveBeenCalledWith('/workspaces/one/sources/local/sync', expect.objectContaining({ method: 'POST' })))
  expect(api.mock.calls.filter(([, options]) => options?.method === 'POST').map(([path]) => path)).toEqual(['/workspaces/one/sources/local/sync'])
})

it('cancelling a folder picker does not register or collect', async () => {
  vi.mocked(selectFolder).mockResolvedValue(null)
  render(<SourcePanel {...props()} />)
  await userEvent.click(screen.getByRole('button', { name: '자료 추가' }))
  await userEvent.click(screen.getByRole('button', { name: '폴더' }))
  await userEvent.click(screen.getByRole('button', { name: '폴더 선택' }))
  expect(selectFolder).toHaveBeenCalledTimes(1)
  expect(api.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false)
})

it('removes GitHub authentication from settings', async () => {
  render(<PluginPanel />)
  expect(await screen.findByRole('button', { name: 'MySQL 연결 설정' })).toBeVisible()
  expect(screen.queryByRole('button', { name: 'GitHub 연결 설정' })).not.toBeInTheDocument()
  expect(screen.queryByLabelText('GitHub 읽기 토큰')).not.toBeInTheDocument()
  expect(api.mock.calls.some(([path]) => path.includes('/github'))).toBe(false)
})
