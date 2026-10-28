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

it('offers file folder and web inputs but no Github input', async () => {
  render(<SourcePanel {...props()} />)
  await userEvent.click(screen.getByRole('button', { name: '자료 추가' }))
  expect(screen.getByRole('button', { name: '파일' })).toBeVisible()
  expect(screen.getByRole('button', { name: '폴더' })).toBeVisible()
  expect(screen.queryByRole('button', { name: 'GitHub' })).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '웹 링크' }))
  expect(screen.getByRole('textbox', { name: '웹 URL' })).toBeVisible()
  expect(api.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false)
})

it.each(['GITHUB'])('preserves cached %s source and does not resync or offer authentication', async kind => {
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
  expect(api.mock.calls.filter(([, options]) => options?.method === 'POST').map(([path]) => path)).toEqual(['/workspaces/one/sources/web/sync', '/workspaces/one/sources/local/sync'])
})

it.each(['https://github.com/org/repo', 'https://GITHUB.COM./org/repo', 'https://raw.githubusercontent.com/org/repo/main/file'])('blocks Github pasted as a web link: %s', async location => {
  render(<SourcePanel {...props()} />)
  await userEvent.click(screen.getByRole('button', { name: '자료 추가' }))
  await userEvent.click(screen.getByRole('button', { name: '웹 링크' }))
  await userEvent.type(screen.getByRole('textbox', { name: '웹 URL' }), location)
  await userEvent.click(screen.getByRole('button', { name: '추가' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('GitHub 연결은 지원하지 않습니다')
  expect(api.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false)
})

it('automatically syncs a registered web document', async () => {
  api.mockImplementation(async (path, options) => options?.method !== 'POST' ? [] : path.endsWith('/sync') ? { status: 'READY' } : { id: 'web' })
  render(<SourcePanel {...props()} />)
  await userEvent.click(screen.getByRole('button', { name: '자료 추가' }))
  await userEvent.click(screen.getByRole('button', { name: '웹 링크' }))
  await userEvent.type(screen.getByRole('textbox', { name: '웹 URL' }), 'https://example.org/document')
  await userEvent.click(screen.getByRole('button', { name: '추가' }))
  await waitFor(() => expect(api).toHaveBeenCalledWith('/workspaces/one/sources/web/sync', expect.objectContaining({ method: 'POST' })))
  expect(api.mock.calls.find(([, options]) => options?.method === 'POST')?.[1]?.body).toContain('"kind":"URL"')
})

it('offers Confluence connection from a failed web source', async () => {
  api.mockResolvedValue([{ id: 'web', name: '팀 문서', kind: 'URL', status: 'ERROR', location: 'https://synthetic.atlassian.net/wiki/pages/123', error: 'Jira · Confluence 연결이 필요합니다.' }])
  const connect = vi.fn()
  render(<SourcePanel {...props()} onPluginConnect={connect} />)
  await userEvent.click(await screen.findByRole('button', { name: /팀 문서 연결 필요/ }))
  expect(connect).toHaveBeenCalledWith('ATLASSIAN')
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
