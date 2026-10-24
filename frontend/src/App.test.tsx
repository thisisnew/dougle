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
  it('이름의 공백과 대소문자 차이는 중복으로 안내하고 생성하지 않는다', async () => {
    api.mockImplementation(async path => path === '/session'
      ? { workspaces: [{ ...workspace, name: 'Research' }], activeWorkspaceId: 'one' } : [])
    render(<App />)
    await waitFor(() => expect(screen.getByRole('button', {name:'Workspace 생성'})).toBeEnabled())
    await userEvent.click(screen.getByRole('button', {name:'Workspace 생성'}))
    await userEvent.type(screen.getByLabelText('이름'), ' research ')
    await userEvent.click(screen.getByRole('button', {name:'만들기'}))
    expect(await screen.findByRole('alert')).toHaveTextContent('같은 이름의 Workspace')
    expect(api.mock.calls.some(([path,options]) => path === '/workspaces' && options?.method === 'POST')).toBe(false)
  })

  it('자동 갱신은 별도 카드 대신 Workspace 관리 안의 토글로만 표시한다', async () => {
    api.mockImplementation(async path => path === '/session'
      ? { workspaces: [workspace], activeWorkspaceId: 'one' }
      : path.endsWith('/source-polling') ? {enabled:false,revision:0,mixed:false} : [])
    render(<App />)
    await userEvent.click(await screen.findByRole('button', {name:'설정'}))
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', {name:'Workspace 관리'}))
    expect(await screen.findByRole('switch', {name:'가상 연구 자동 갱신'})).toBeInTheDocument()
    expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument()
  })
  it('연결 자료와 업로드를 한 목록에 표시하고 연결 폴더는 중복하지 않는다', async () => {
    const folder = { ...file, id: 'source-root', name: '연결 폴더', kind: 'FOLDER' }
    api.mockImplementation(async path => {
      if (path === '/session') return { workspaces: [workspace], activeWorkspaceId: 'one' }
      if (path.endsWith('/sources')) return [{ id: 'source', name: '연결 폴더', kind: 'LOCAL_FOLDER', status: 'READY', rootEntryId: folder.id, location: '/synthetic', pollingEnabled: false }]
      if (path.endsWith('/entries/source-root')) return folder
      if (path.includes('parentId=source-root')) return []
      if (path.includes('/entries?')) return [folder, file]
      return []
    })
    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: '자료' }))
    await screen.findByRole('button', { name: 'notes.md' })
    await waitFor(() => expect(screen.getAllByRole('button', { name: '연결 폴더' })).toHaveLength(1))
    expect(within(screen.getByRole('region', { name: '자료 탐색기' })).getByRole('button', { name: '연결 폴더' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '연결 폴더' }))
    await waitFor(() => expect(api).toHaveBeenCalledWith('/workspaces/one/entries?area=ORIGINAL&parentId=source-root', expect.anything()))
    expect(screen.queryByRole('button', { name: '연결 폴더 Source 삭제' })).not.toBeInTheDocument()
  })

  it('Dougle와 탐정 캐릭터를 표시하고 이전 브랜드 설명은 숨긴다', async () => {
    api.mockImplementation(async path => path === '/session' ? { workspaces: [], activeWorkspaceId: null } : [])
    render(<App />)
    expect(await screen.findByRole('img', { name: '더글' })).toHaveAttribute('src', expect.stringContaining('clue-mascot'))
    expect(screen.queryByRole('img', { name: 'Dougle' })).not.toBeInTheDocument()
    expect(screen.queryByText('나만의 지식이 쌓이는 곳')).not.toBeInTheDocument()
    expect(screen.queryByText('Knowledge Forge')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '대화' }).querySelector('.lucide-message-square')).not.toBeNull()
    expect(screen.getByRole('button', { name: '질문 기록' }).querySelector('.lucide-history')).not.toBeNull()
    expect(document.querySelector('.welcome .pixel-landscape')).not.toBeInTheDocument()
  })
  it.each([false, true])('별도 관리 창에서 선택한 Workspace만 확인 후 삭제한다: 활성=%s', async active => {
    const user = userEvent.setup()
    const other = { ...workspace, id: 'two', name: '별도 연구' }
    const target = active ? workspace : other
    let removed = false
    api.mockImplementation(async (path, options) => {
      if (path === '/session') return { workspaces: [workspace, other].filter(item => !removed || item.id !== target.id), activeWorkspaceId: removed && active ? 'two' : 'one' }
      if (options?.method === 'DELETE') { removed = true; return undefined }
      return []
    })
    render(<App />)
    await waitFor(() => expect(screen.getByRole('button', { name: '설정' })).toBeEnabled())
    expect(screen.queryByRole('button', { name: 'Workspace 관리' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '설정' }))
    expect(screen.queryByText('이 PC에 자료 저장')).not.toBeInTheDocument()
    expect(screen.queryByText('서재 관리')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Workspace 삭제/ })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Workspace 관리' }))
    await user.click(screen.getByRole('button', { name: `${target.name} Workspace 삭제` }))
    expect(screen.getByRole('dialog')).toHaveTextContent(`“${target.name}”`)
    await user.click(screen.getByRole('button', { name: '취소' }))
    expect(api.mock.calls.some(([, options]) => options?.method === 'DELETE')).toBe(false)
    await user.click(screen.getByRole('button', { name: `${target.name} Workspace 삭제` }))
    await user.click(screen.getByRole('button', { name: '삭제' }))
    await screen.findByRole('dialog', { name: 'Workspace 관리' })
    expect(api).toHaveBeenCalledWith(`/workspaces/${target.id}`, { method: 'DELETE' })
    expect(screen.queryByRole('button', { name: `${target.name} Workspace 삭제` })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '닫기' }))
    expect(screen.getByLabelText('Workspace 선택')).toHaveValue(active ? 'two' : 'one')
  })

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

  it('지식 편집 메뉴 없이 원본과 자동 분석 결과만 보여 준다', async () => {
    const user = userEvent.setup()
    api.mockImplementation(async path => {
      if (path === '/session') return { workspaces: [workspace], activeWorkspaceId: 'one' }
      if (path.includes('/entries?')) return [file]
      if (path.endsWith('/preview')) return { supported: true, text: '가상 원본 내용', reason: null }
      if (path.endsWith('/analysis')) return { text: '자동 추출한 내용', status: 'READY', symbols: [] }
      return []
    })
    render(<App />)
    await user.click(await screen.findByRole('button', { name: '자료' }))
    expect(screen.getByRole('button', { name: '질문 기록' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '지식 문서' })).not.toBeInTheDocument()
    expect(screen.queryByRole('complementary', { name: '미리보기' })).not.toBeInTheDocument()
    expect(screen.queryByText('자료를 살펴보세요')).not.toBeInTheDocument()
    expect(screen.queryByText('원본 자료')).not.toBeInTheDocument()
    expect(screen.queryByText('원본 파일의 복사본을 로컬에 보관합니다.')).not.toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: 'notes.md' }))
    await user.click(await screen.findByText('분석 상세'))
    await user.click(await screen.findByText('추출된 텍스트'))
    expect(screen.getByText('자동 추출한 내용')).toBeInTheDocument()
    expect(screen.queryByText('이 자료로 지식 문서 만들기')).not.toBeInTheDocument()
    expect(within(screen.getByLabelText('추출 및 심볼')).queryByRole('textbox')).not.toBeInTheDocument()
    expect(api.mock.calls.some(([path]) => path.includes('/knowledge'))).toBe(false)
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
    await screen.findByText('자료를 추가해 보세요')
    expect(screen.queryByText('상단에서 자료를 추가하세요.')).not.toBeInTheDocument()
    expect(api).toHaveBeenCalledWith('/workspaces', expect.objectContaining({ body: '{"name":"가상 연구"}' }))
    expect(screen.getByLabelText('Workspace 선택')).toHaveValue('one')
  })

  it('HTML 원문을 실행하지 않고 텍스트로 표시한다', async () => {
    const user = userEvent.setup()
    api.mockImplementation(async path => {
      if (path.endsWith('/knowledge-organization')) return { folders: [], placements: [] }
      if (path === '/session') return { workspaces: [workspace], activeWorkspaceId: 'one' }
      if (path.endsWith('/preview')) return { supported: true, text: '<script>window.injected=true</script>', reason: null }
      if (path.endsWith('/sources')) return []
      return [file]
    })
    render(<App />)
    await user.click(await screen.findByRole('button', { name: '자료' }))
    await user.click(await screen.findByRole('button', { name: 'notes.md' }))
    expect(await screen.findByText('<script>window.injected=true</script>')).toBeInTheDocument()
    expect(document.querySelector('.preview-content script')).toBeNull()
    await user.click(screen.getByRole('button', { name: '원본 다운로드' }))
    expect(downloadApi).toHaveBeenCalledWith('/workspaces/one/entries/file/content', file.name)
    await user.click(screen.getByRole('button', { name: '미리보기 닫기' }))
    expect(screen.queryByRole('complementary', { name: '미리보기' })).not.toBeInTheDocument()
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

  it('폴더로 이동하고 대화에서 자료로 돌아오면 루트로 이동한다', async () => {
    const user = userEvent.setup()
    api.mockImplementation(async path => {
      if (path.endsWith('/knowledge-organization')) return { folders: [], placements: [] }
      if (path === '/session') return { workspaces: [workspace], activeWorkspaceId: 'one' }
      if (path.endsWith('/sources')) return []
      if (path.includes('parentId=folder') || path.endsWith('/knowledge')) return []
      return [{ ...file, id: 'folder', kind: 'FOLDER', name: '참고 자료' }]
    })
    render(<App />)
    await user.click(await screen.findByRole('button', { name: '자료' }))
    await user.click(await screen.findByRole('button', { name: '참고 자료' }))
    await waitFor(() => expect(api).toHaveBeenCalledWith('/workspaces/one/entries?area=ORIGINAL&parentId=folder', expect.anything()))
    await user.click(screen.getByRole('button', { name: '대화' }))
    await user.click(screen.getByRole('button', { name: '자료' }))
    await waitFor(() => expect(api).toHaveBeenLastCalledWith('/workspaces/one/entries?area=ORIGINAL', expect.anything()))
    expect(screen.getByRole('button', { name: '자료 추가' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '파일 추가' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '새 폴더' })).not.toBeInTheDocument()
  })
})
