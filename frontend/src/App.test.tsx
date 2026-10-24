import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { request } from './api'
import { downloadApi } from './download'

vi.mock('./download', () => ({ downloadApi: vi.fn().mockResolvedValue(undefined) }))

vi.mock('./api', async importOriginal => ({
  ...await importOriginal<typeof import('./api')>(),
  request: vi.fn(),
}))
const api = vi.mocked(request)
const workspace = { id: 'one', name: '가상 연구', createdAt: '2026-10-21T00:00:00Z' }
const file = { id: 'file', workspaceId: 'one', parentId: null, name: 'notes.md', kind: 'FILE', area: 'ORIGINAL', size: 12, createdAt: workspace.createdAt }

beforeEach(() => { api.mockReset(); vi.mocked(downloadApi).mockClear() })

describe('Workspace와 자료 탐색기', () => {
  it('AI 창을 닫아도 상단에 마지막 로그인 확인 결과를 표시한다', async () => {
    localStorage.clear()
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', '') } })
    Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open') } })
    api.mockImplementation(async path => path === '/session'
      ? { workspaces: [], activeWorkspaceId: null }
      : { provider: 'CODEX', state: 'AUTHENTICATED', checkedAt: 'time', remoteConnection: 'NOT_CHECKED' })
    render(<App />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'CODEX' })).toBeEnabled(), { timeout: 5000 })
    await userEvent.click(screen.getByRole('button', { name: 'CODEX' }))
    const dialog = await screen.findByRole('dialog', { name: 'AI 연결' })
    expect(await within(dialog).findByText(/Codex · 로그인 확인/)).toBeInTheDocument()
    await userEvent.click(within(dialog).getByRole('button', { name: '닫기' }))
    expect(screen.getByRole('button', { name: 'CODEX' })).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'AI 연결' })).not.toBeInTheDocument()
  })

  it('세션 준비 전에는 버튼이 있어도 클릭되지 않고 준비 후에만 Workspace를 연다', async () => {
    let ready!: (value: { workspaces: never[]; activeWorkspaceId: null }) => void
    api.mockReturnValue(new Promise(resolve => { ready = resolve }))
    render(<App />)
    const button = screen.getByRole('button', { name: 'Workspace 생성' })
    expect(button).toBeDisabled()
    button.click()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    ready({ workspaces: [], activeWorkspaceId: null })
    await waitFor(() => expect(button).toBeEnabled())
    await userEvent.click(button)
    expect(await screen.findByRole('dialog', { name: '새 Workspace' })).toBeInTheDocument()
  })

  it.each([false, true])('근거에서 폴더와 파일 미리보기로 이동하며 이동 중 삭제=%s를 처리한다', async missing => {
    const user = userEvent.setup()
    const folder = { ...file, id: 'folder', kind: 'FOLDER', name: '가상 원본 폴더' }
    const currentFile = { ...file, id: 'new-id', parentId: 'folder' }
    const card = { id: 'card', workspaceId: 'one', title: '가상 지식', latestVersion: 1, createdAt: workspace.createdAt }
    api.mockImplementation(async path => {
      if (path.endsWith('/knowledge-organization')) return { folders: [], placements: [] }
      if (path === '/session') return { workspaces: [workspace], activeWorkspaceId: 'one' }
      if (path.endsWith('/knowledge')) return [card]
      if (path.endsWith('/knowledge/card')) return { card, version: {
        number: 1, title: card.title, markdown: '옛 근거 [E1]', status: 'DRAFT', manuallyEdited: true,
        evidence: [{ snapshotId: 'snapshot', path: 'notes.md', name: 'notes.md', sourceRevision: 'old', contentHash: 'hash',
          startLine: 1, endLine: 1, quote: '옛 내용', freshness: 'STALE' }],
      } }
      if (path.endsWith('/publication')) return { latestVersion: 1, review: null, publication: null, publishedEvidence: [] }
      if (path.endsWith('/location')) return { entry: currentFile, ancestors: [folder], freshness: 'STALE' }
      if (path.includes('parentId=folder')) return missing ? [] : [currentFile]
      if (path.endsWith('/preview')) return { supported: true, text: '새 원본 내용', reason: null }
      if (path.endsWith('/analysis')) return { text: '', status: 'PENDING', symbols: [] }
      return []
    })
    render(<App />)
    await user.click(await screen.findByRole('button', { name: '자료' }))
    await screen.findByText('자료를 담을 준비가 되었어요')
    await user.click(screen.getByText('더 보기'))
    await user.click(screen.getByRole('button', { name: '지식 문서' }))
    expect(screen.getByText('원본과 따로 보관하며, 문서별 검토·게시 상태를 확인할 수 있습니다.')).toBeInTheDocument()
    expect(screen.queryByText(/아직 미검증 상태입니다/)).not.toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: '가상 지식 · v1' }))
    await user.click(await screen.findByRole('button', { name: '현재 원본에서 보기' }))
    expect(api).toHaveBeenCalledWith('/workspaces/one/knowledge/card/versions/1/evidence/snapshot/location', expect.anything())
    expect(within(await screen.findByLabelText('폴더 경로')).getByRole('button', { name: '가상 원본 폴더' })).toBeInTheDocument()
    if (missing) {
      expect(await screen.findByRole('alert')).toHaveTextContent('이동 중 원본이 변경·삭제')
      expect(screen.queryByRole('button', { name: '원본 다운로드' })).not.toBeInTheDocument()
    } else {
      expect(await screen.findByText('새 원본 내용')).toBeInTheDocument()
      expect(screen.getByText(/현재 원본은 인용 당시 근거와 다릅니다/)).toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: '원본 다운로드' }))
      expect(downloadApi).toHaveBeenCalledWith('/workspaces/one/entries/new-id/content', file.name)
      await user.click(screen.getByRole('button', { name: '미리보기 닫기' }))
      expect(screen.queryByText(/현재 원본은 인용 당시 근거와 다릅니다/)).not.toBeInTheDocument()
    }
  })

  it('빈 상태에서 Workspace를 만들고 새 세션으로 이동한다', async () => {
    const user = userEvent.setup()
    let created = false
    api.mockImplementation(async (path, options) => {
      if (path.endsWith('/knowledge-organization')) return { folders: [], placements: [] }
      if (path === '/session') return { workspaces: created ? [workspace] : [], activeWorkspaceId: created ? 'one' : null }
      if (path === '/workspaces' && options?.method === 'POST') { created = true; return workspace }
      return []
    })
    render(<App />)
    await user.click(await screen.findByRole('button', { name: 'Workspace 만들기' }))
    await user.type(screen.getByLabelText('이름'), '가상 연구')
    await user.click(screen.getByRole('button', { name: '만들기' }))
    await screen.findByText('자료를 담을 준비가 되었어요')
    expect(api).toHaveBeenCalledWith('/workspaces', expect.objectContaining({ body: '{"name":"가상 연구"}' }))
    expect(screen.getByLabelText('Workspace 선택')).toHaveValue('one')
  })

  it('HTML 원문을 실행하지 않고 텍스트로 표시한다', async () => {
    const user = userEvent.setup()
    api.mockImplementation(async path => {
      if (path.endsWith('/knowledge-organization')) return { folders: [], placements: [] }
      if (path === '/session') return { workspaces: [workspace], activeWorkspaceId: 'one' }
      if (path.endsWith('/preview')) return { supported: true, text: '<script>window.injected=true</script>', reason: null }
      return [file]
    })
    render(<App />)
    await user.click(await screen.findByRole('button', { name: '자료' }))
    await user.click(await screen.findByRole('button', { name: 'notes.md' }))
    expect(await screen.findByText('<script>window.injected=true</script>')).toBeInTheDocument()
    expect(document.querySelector('.preview-content script')).toBeNull()
    await user.click(screen.getByRole('button', { name: '원본 다운로드' }))
    expect(downloadApi).toHaveBeenCalledWith('/workspaces/one/entries/file/content', file.name)
  })

  it('삭제 취소는 API를 호출하지 않으며 오류는 모달 안에 남긴다', async () => {
    const user = userEvent.setup()
    api.mockImplementation(async (path, options) => {
      if (path.endsWith('/knowledge-organization')) return { folders: [], placements: [] }
      if (path === '/session') return { workspaces: [workspace], activeWorkspaceId: 'one' }
      if (options?.method === 'DELETE') throw new Error('저장소를 사용할 수 없습니다.')
      return [file]
    })
    render(<App />)
    await user.click(await screen.findByRole('button', { name: '자료' }))
    await user.click(await screen.findByRole('button', { name: 'notes.md 삭제' }))
    await user.click(screen.getByRole('button', { name: '취소' }))
    expect(api.mock.calls.some(([, options]) => options?.method === 'DELETE')).toBe(false)
    await user.click(screen.getByRole('button', { name: 'notes.md 삭제' }))
    await user.click(screen.getByRole('button', { name: '삭제' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('저장소를 사용할 수 없습니다.')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('폴더로 이동하고 지식 영역 전환 시 경로를 초기화한다', async () => {
    const user = userEvent.setup()
    api.mockImplementation(async path => {
      if (path.endsWith('/knowledge-organization')) return { folders: [], placements: [] }
      if (path === '/session') return { workspaces: [workspace], activeWorkspaceId: 'one' }
      if (path.includes('parentId=folder') || path.endsWith('/knowledge')) return []
      return [{ ...file, id: 'folder', kind: 'FOLDER', name: '참고 자료' }]
    })
    render(<App />)
    await user.click(await screen.findByRole('button', { name: '자료' }))
    await user.click(await screen.findByRole('button', { name: '참고 자료' }))
    await waitFor(() => expect(api).toHaveBeenCalledWith('/workspaces/one/entries?area=ORIGINAL&parentId=folder', expect.anything()))
    await user.click(screen.getByText('더 보기'))
    await user.click(screen.getByRole('button', { name: '지식 문서' }))
    expect(await screen.findByText('첫 지식 문서를 만들어 보세요')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '파일 추가' })).not.toBeInTheDocument()
  })
})
