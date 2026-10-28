import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { request } from './api'
import { downloadApi } from './download'

vi.mock('./download', () => ({ downloadApi: vi.fn().mockResolvedValue(undefined) }))
vi.mock('./folderPicker', () => ({ hasFolderPicker: () => true, selectFolder: vi.fn().mockResolvedValue('/synthetic/source') }))

vi.mock('./api', async importOriginal => ({
  ...await importOriginal<typeof import('./api')>(),
  request: vi.fn(),
}))
const api = vi.mocked(request)
const workspace = { id: 'one', name: '가상 연구', createdAt: '2026-10-21T00:00:00Z' }
const file = { id: 'file', workspaceId: 'one', parentId: null, name: 'notes.md', kind: 'FILE', area: 'ORIGINAL', size: 12, createdAt: workspace.createdAt }

beforeEach(() => { api.mockReset(); vi.mocked(downloadApi).mockClear() })

describe('Workspace와 자료 탐색기', () => {
  it('manual queries are absent while plugin connections remain available', async () => {
    api.mockImplementation(async path => {
      if (path === '/session') return { workspaces: [workspace], activeWorkspaceId: 'one' }
      if (path === '/plugins') return { mysqlConnected: true, mysql: null }
      return []
    })
    render(<App />)
    await screen.findByRole('heading', { name: 'Dougle' })
    expect(screen.queryByRole('region', { name: '업무 환경 연결' })).not.toBeInTheDocument()
    for (const name of ['GitHub 연결', 'Notion 연결', 'Jira · Confluence 연결', 'MySQL 연결']) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
    }
    expect(document.querySelector('.topbar .provider-trigger')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '운영 자료 조회' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '설정' }))
    await userEvent.click(screen.getByRole('button', { name: '플러그인' }))
    expect(await screen.findByRole('button', { name: 'MySQL 연결 설정' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: 'Slack 문의' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'DB 데이터 조회' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('테이블')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '조회 범위 확인' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Datadog 연결' })).not.toBeInTheDocument()
    expect(api.mock.calls.some(([path]) => /slack|datadog/i.test(path))).toBe(false)
    for (const label of ['테이블', '환경', '서비스', '조회할 열 (쉼표로 구분)']) expect(screen.queryByLabelText(label)).not.toBeInTheDocument()
    expect(api.mock.calls.some(([path]) => /investigations|operational-captures|tool-proposal/.test(path))).toBe(false)
  })

  it.each([true, false])('부분 내보내기 메뉴와 화면을 제공하지 않는다 (Workspace: %s)', async hasWorkspace => {
    api.mockImplementation(async path => path === '/session'
      ? { workspaces: hasWorkspace ? [workspace] : [], activeWorkspaceId: hasWorkspace ? 'one' : null }
      : [])
    render(<App />)
    if (hasWorkspace) await screen.findByRole('heading', { name: 'Dougle' })
    else await screen.findByRole('button', { name: 'Workspace 만들기' })
    await userEvent.click(screen.getByRole('button', { name: '설정' }))
    expect(screen.getByRole('heading', { name: '설정' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'Workspace 관리' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '플러그인' })).toBeEnabled()
    for (const name of ['팀 설정', '팀 설정 JSON 다운로드', '자료·대화 아카이브 다운로드']) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
    }
    expect(screen.queryByText('자료·대화 이전')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('팀 설정 JSON 파일')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('자료·대화 ZIP 파일')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '자료' }))
    expect(screen.queryByText('팀 설정')).not.toBeInTheDocument()
    expect(api.mock.calls.some(([path]) => /team-settings|workspace-archives/.test(path))).toBe(false)
    expect(downloadApi).not.toHaveBeenCalled()
  })

  it('Workspace가 없어도 플러그인 목록과 연결 설정을 연다', async () => {
    api.mockImplementation(async path => path === '/session' ? { workspaces: [], activeWorkspaceId: null } : [])
    render(<App />)
    await screen.findByRole('button', { name: 'Workspace 만들기' })
    expect(screen.queryByRole('button', { name: '플러그인' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '설정' }))
    expect(screen.queryByRole('button', { name: '검색 엔진' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '플러그인' }))
    expect(screen.getByRole('heading', { name: '플러그인' })).toBeVisible()
    await userEvent.click(screen.getByRole('button', { name: 'Notion 연결 설정' }))
    expect(screen.getByRole('dialog', { name: 'Notion 연결' })).toBeVisible()
  })
  it.each([false, true])('동기화 중 탭과 Workspace·설정을 잠그고 종료 시 복구한다 (실패: %s)', async fail => {
    let finish!: () => void
    const pending = new Promise<void>(resolve => { finish = resolve })
    api.mockImplementation(async path => {
      if (path === '/session') return { workspaces: [workspace], activeWorkspaceId: 'one' }
      if (path.includes('/entries?')) return [file]
      if (path.endsWith('/files/sync')) {
        await pending
        if (fail) throw new Error('동기화 실패')
        return { processed: 1, failures: [] }
      }
      return []
    })
    render(<App />)
    await screen.findByRole('heading', { name: 'Dougle' })
    await userEvent.click(screen.getByRole('button', { name: '자료' }))
    await screen.findByRole('button', { name: file.name })
    await userEvent.click(screen.getByRole('button', { name: '동기화' }))
    expect(await screen.findByRole('region', { name: '동기화 진행 상황' })).toBeVisible()
    for (const name of ['대화', '자료', '질문 기록', '설정', 'Workspace 생성']) {
      expect(screen.getByRole('button', { name })).toBeDisabled()
      fireEvent.click(screen.getByRole('button', { name }))
    }
    expect(screen.getByRole('combobox', { name: 'Workspace 선택' })).toBeDisabled()
    expect(screen.getByRole('heading', { name: '자료', level: 1 })).toBeVisible()
    finish()
    await waitFor(() => expect(screen.getByRole('button', { name: '설정' })).toBeEnabled(), { timeout: 5000 })
    expect(screen.getByRole('combobox', { name: 'Workspace 선택' })).toBeEnabled()
    if (fail) expect(screen.getByRole('button', { name: /파일 동기화 동기화 실패/ })).toBeVisible()
    await userEvent.click(screen.getByRole('button', { name: '설정' }))
    expect(screen.getByRole('heading', { name: '설정' })).toBeVisible()
  }, 15000)

  it('폴더 등록의 첫 동기화 중에도 화면 전환을 막는다', async () => {
    let finish!: () => void
    const pending = new Promise<void>(resolve => { finish = resolve })
    api.mockImplementation(async (path, options) => {
      if (path === '/session') return { workspaces: [workspace], activeWorkspaceId: 'one' }
      if (path.endsWith('/sources') && options?.method === 'POST') { await pending; return { id: 'new-source', status: 'READY' } }
      return []
    })
    render(<App />)
    await screen.findByRole('heading', { name: 'Dougle' })
    await userEvent.click(screen.getByRole('button', { name: '자료' }))
    await userEvent.click(screen.getByRole('button', { name: '자료 추가' }))
    await userEvent.click(screen.getByRole('button', { name: '폴더' }))
    await userEvent.click(screen.getByRole('button', { name: '폴더 선택' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '설정' })).toBeDisabled())
    expect(screen.getByRole('button', { name: '대화' })).toBeDisabled()
    expect(screen.getByRole('combobox', { name: 'Workspace 선택' })).toBeDisabled()
    finish()
    await waitFor(() => expect(screen.getByRole('button', { name: '설정' })).toBeEnabled(), { timeout: 5000 })
    expect(screen.getByRole('button', { name: '폴더 선택' })).toBeVisible()
  }, 15000)

  it('빈 서재에서도 질문 기록을 열 수 있고 대화 제목과 로고 시작 화면으로 돌아온다', async () => {
    api.mockImplementation(async path => path === '/session' ? { workspaces: [], activeWorkspaceId: null } : [])
    render(<App />)
    await screen.findByRole('button', { name: 'Workspace 만들기' })
    expect(screen.getByRole('heading', { name: '대화', level: 1 })).toBeVisible()
    expect(screen.getByRole('img', { name: 'Dougle' })).toBeVisible()
    expect(screen.getByRole('button', { name: '질문 기록' })).toBeEnabled()
    await userEvent.click(screen.getByRole('button', { name: '질문 기록' }))
    expect(screen.getByRole('heading', { name: '질문 기록', level: 1 })).toBeVisible()
    expect(await screen.findByText('질문 기록이 없습니다.')).toBeVisible()
    expect(api.mock.calls.some(([path]) => path.includes('/chat-threads'))).toBe(false)
    await userEvent.click(screen.getByRole('button', { name: '대화' }))
    expect(screen.getByRole('heading', { name: '대화', level: 1 })).toBeVisible()
    expect(screen.getByRole('button', { name: 'Workspace 만들기' })).toBeVisible()
  })

  it('설정은 사이드바 하단에 두고 업데이트 확인은 설정 화면에서만 표시한다', async () => {
    const invoke = vi.fn().mockResolvedValue({ phase: 'available', version: '9.9.9' })
    Object.defineProperty(window, '__TAURI_INTERNALS__', { configurable: true, value: { invoke } })
    api.mockImplementation(async path => path === '/session' ? { workspaces: [], activeWorkspaceId: null } : [])
    try {
      const { container } = render(<App />)
      await screen.findByRole('button', { name: 'Workspace 만들기' })
      expect(screen.queryByText('첫 번째 지식 서재를 만들어 보세요')).not.toBeInTheDocument()
      expect(screen.queryByText('파일은 이 PC에 저장되며, 원래 파일은 변경되지 않습니다.')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: '설정' }).closest('.sidebar-bottom')).not.toBeNull()
      expect(screen.queryByRole('button', { name: '앱 업데이트' })).not.toBeInTheDocument()
      await waitFor(() => expect(container.querySelector('.settings-update-dot')).not.toBeNull())
      await userEvent.click(screen.getByRole('button', { name: '설정' }))
      await userEvent.click(await screen.findByRole('button', { name: '앱 업데이트' }))
      expect(await screen.findByRole('button', { name: '새 버전 설치' })).toBeVisible()
      await userEvent.click(screen.getByRole('button', { name: '닫기' }))
      await userEvent.click(screen.getByRole('button', { name: 'Workspace 관리' }))
      expect(screen.getByText('Workspace가 없습니다')).toBeVisible()
      expect(screen.queryByText('아직 Workspace가 없습니다.')).not.toBeInTheDocument()
    } finally { Reflect.deleteProperty(window, '__TAURI_INTERNALS__') }
  })

  it('Workspace 전환 동안 이전 화면을 잠그고 새 자료가 준비될 때까지 스켈레톤을 유지한다', async () => {
    const other = { ...workspace, id: 'two', name: '다른 서재' }
    let active = 'one'
    let finish!: () => void
    const pending = new Promise<void>(resolve => { finish = resolve })
    api.mockImplementation(async (path, options) => {
      if (path === '/session') return { workspaces: [workspace, other], activeWorkspaceId: active }
      if (path === '/workspaces/two/active' && options?.method === 'PUT') { active = 'two'; return {} }
      if (path.includes('/workspaces/two/entries?')) { await pending; return [{ ...file, id: 'second', workspaceId: 'two', name: '다른 자료.txt' }] }
      if (path.includes('/entries?')) return [file]
      return []
    })
    const { container } = render(<App />)
    await screen.findByRole('heading', { name: 'Dougle' })
    expect(container.querySelector('.topbar')).not.toHaveTextContent(workspace.name)
    await userEvent.click(screen.getByRole('button', { name: '자료' }))
    expect(await screen.findByRole('button', { name: file.name })).toBeVisible()
    await userEvent.click(screen.getByRole('combobox', { name: 'Workspace 선택' }))
    await userEvent.click(screen.getByRole('option', { name: other.name }))
    expect(await screen.findByRole('status', { name: 'Workspace 불러오는 중' })).toBeVisible()
    expect(screen.getByRole('combobox', { name: 'Workspace 선택' })).toBeDisabled()
    expect(screen.queryByRole('button', { name: file.name })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '질문 보내기' })).not.toBeInTheDocument()
    finish()
    await waitFor(() => expect(screen.queryByRole('status', { name: 'Workspace 불러오는 중' })).not.toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: '자료' }))
    expect(await screen.findByRole('button', { name: '다른 자료.txt' })).toBeVisible()
    expect(screen.queryByRole('button', { name: file.name })).not.toBeInTheDocument()
  })

  it('Workspace 전환 실패 시 이전 선택을 유지하고 다시 선택할 수 있다', async () => {
    const other = { ...workspace, id: 'two', name: '다른 서재' }
    api.mockImplementation(async path => {
      if (path === '/session') return { workspaces: [workspace, other], activeWorkspaceId: 'one' }
      if (path.endsWith('/active')) throw new Error('전환 실패')
      return []
    })
    render(<App />)
    await screen.findByRole('heading', { name: 'Dougle' })
    await userEvent.click(screen.getByRole('combobox', { name: 'Workspace 선택' }))
    await userEvent.click(screen.getByRole('option', { name: other.name }))
    expect(await screen.findByRole('alert')).toHaveTextContent('전환 실패')
    expect(screen.getByRole('combobox', { name: 'Workspace 선택' })).toBeEnabled()
    expect(screen.getByRole('combobox', { name: 'Workspace 선택' })).toHaveTextContent(workspace.name)
    expect(screen.queryByRole('status', { name: 'Workspace 불러오는 중' })).not.toBeInTheDocument()
  })

  it('새 Workspace 설명은 숨기고 이름 입력을 20자로 제한한다', async () => {
    api.mockImplementation(async path => path === '/session' ? { workspaces: [], activeWorkspaceId: null } : [])
    render(<App />)
    await screen.findByRole('button', { name: 'Workspace 만들기' })
    await userEvent.click(screen.getByRole('button', { name: 'Workspace 생성' }))
    expect(screen.queryByText('프로젝트나 관심 주제별로 자료를 분리해 보관하세요.')).not.toBeInTheDocument()
    const input = screen.getByRole('textbox', { name: '이름' })
    expect(input).toHaveAttribute('maxlength', '20')
    await userEvent.type(input, '가'.repeat(21))
    expect(input).toHaveValue('가'.repeat(20))
    expect(screen.getByText('20 / 20')).toBeVisible()
  })

  it('질문 목록은 사이드바가 아닌 질문 기록 탭에서만 표시한다', async () => {
    const question = { id: 'saved', question: '저장된 가상 질문', createdAt: workspace.createdAt }
    api.mockImplementation(async path => {
      if (path === '/session') return { workspaces: [workspace], activeWorkspaceId: 'one' }
      if (path.endsWith('/chat-threads')) return [question]
      return []
    })
    render(<App />)
    await screen.findByRole('heading', { name: 'Dougle' })
    expect(screen.queryByRole('region', { name: '최근 질문' })).not.toBeInTheDocument()
    expect(screen.queryByText(question.question)).not.toBeInTheDocument()
    expect(api.mock.calls.some(([path]) => path.endsWith('/chat-threads'))).toBe(false)
    await userEvent.click(screen.getByRole('button', { name: '질문 기록' }))
    expect(await screen.findByRole('button', { name: question.question })).toBeVisible()
    expect(screen.getAllByRole('listitem')).toHaveLength(1)
    expect(screen.queryByRole('region', { name: '최근 질문' })).not.toBeInTheDocument()
  })

  it.each([false, true])('파일 선택 직후 진행 표시하고 완료/실패 후 복구한다: 실패 %s', async fails => {
    let complete!: () => void
    let uploaded = false
    const pending = new Promise<void>(resolve => { complete = resolve })
    api.mockImplementation(async (path, options) => {
      if (path === '/session') return { workspaces: [workspace], activeWorkspaceId: 'one' }
      if (path.endsWith('/files') && options?.method === 'POST') {
        await pending
        if (fails) throw new Error('업로드 실패')
        uploaded = true
        return file
      }
      if (path.includes('/entries?')) return uploaded ? [file] : []
      return []
    })
    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: '자료' }))
    await userEvent.click(screen.getByRole('button', { name: '자료 추가' }))
    await userEvent.upload(screen.getByLabelText('파일 업로드'), new File(['notes'], 'notes.md'))
    const progress = screen.getByRole('button', { name: '추가 중…' })
    expect(progress).toBeDisabled()
    expect(progress).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByRole('button', { name: '동기화' })).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByRole('button', { name: '설정' })).toBeDisabled()
    expect(screen.getByRole('combobox', { name: 'Workspace 선택' })).toBeDisabled()
    expect(screen.getByText('notes.md').closest('[role="status"]')).toHaveTextContent('추가 중…')
    expect(screen.queryByText('자료를 추가해 보세요')).not.toBeInTheDocument()
    await userEvent.click(progress)
    expect(api.mock.calls.filter(([path]) => path.endsWith('/files'))).toHaveLength(1)
    complete()
    await waitFor(() => expect(screen.getByRole('button', { name: '파일 선택' })).toBeEnabled())
    expect(screen.queryByText('추가 중…')).not.toBeInTheDocument()
    if (fails) {
      expect(await screen.findByRole('alert')).toHaveTextContent('업로드 실패')
      await userEvent.upload(screen.getByLabelText('파일 업로드'), new File(['notes'], 'notes.md'))
      await waitFor(() => expect(api.mock.calls.filter(([path]) => path.endsWith('/files'))).toHaveLength(2))
    } else expect(await screen.findByRole('button', { name: 'notes.md' })).toBeEnabled()
  })

  it('파일 선택 취소는 업로드나 진행 표시를 시작하지 않는다', async () => {
    api.mockImplementation(async path => path === '/session' ? { workspaces: [workspace], activeWorkspaceId: 'one' } : [])
    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: '자료' }))
    await userEvent.click(screen.getByRole('button', { name: '자료 추가' }))
    await userEvent.upload(screen.getByLabelText('파일 업로드'), [])
    expect(screen.getByRole('button', { name: '파일 선택' })).toBeEnabled()
    expect(screen.queryByText('추가 중…')).not.toBeInTheDocument()
    expect(api.mock.calls.some(([path]) => path.endsWith('/files'))).toBe(false)
  })

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

  it('프로필 사진과 이전 브랜드 설명을 숨긴다', async () => {
    api.mockImplementation(async path => path === '/session' ? { workspaces: [], activeWorkspaceId: null } : [])
    render(<App />)
    await screen.findByRole('button', { name: 'Workspace 만들기' })
    expect(screen.queryByRole('img', { name: '더글' })).not.toBeInTheDocument()
    expect(within(document.querySelector<HTMLElement>('.sidebar')!).queryByRole('img', { name: 'Dougle' })).not.toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Dougle' })).toBeVisible()
    expect(screen.queryByText('나만의 지식이 쌓이는 곳')).not.toBeInTheDocument()
    expect(screen.queryByText('Dougle')).not.toBeInTheDocument()
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
    const settingsRow = screen.getByRole('button', { name: 'Workspace 관리' })
    expect(settingsRow).toHaveClass('settings-row')
    expect(settingsRow.querySelectorAll('button')).toHaveLength(0)
    expect(screen.queryByRole('heading', { name: 'Workspace' })).not.toBeInTheDocument()
    expect(screen.queryByText('이 PC에 자료 저장')).not.toBeInTheDocument()
    expect(screen.queryByText('서재 관리')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Workspace 삭제/ })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Workspace 관리' }))
    const manager = screen.getByRole('dialog', { name: 'Workspace 관리' })
    expect(manager).toHaveClass('workspace-manager-dialog')
    expect(manager.querySelectorAll('.workspace-manager-heading')).toHaveLength(2)
    expect(manager).not.toHaveAttribute('aria-describedby')
    expect(within(manager).queryByText('Workspace를 한곳에서 관리하세요.')).not.toBeInTheDocument()
    expect(within(manager).queryByText('사용 중')).not.toBeInTheDocument()
    expect(within(manager).queryByRole('button', { name: '새 Workspace' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: `${target.name} Workspace 삭제` }))
    expect(screen.getByRole('dialog', { name: '삭제하시겠습니까?' })).toHaveTextContent(target.name)
    await user.click(screen.getByRole('button', { name: '취소' }))
    expect(api.mock.calls.some(([, options]) => options?.method === 'DELETE')).toBe(false)
    await user.click(screen.getByRole('button', { name: `${target.name} Workspace 삭제` }))
    await user.click(screen.getByRole('button', { name: '삭제' }))
    await screen.findByRole('dialog', { name: 'Workspace 관리' })
    expect(api).toHaveBeenCalledWith(`/workspaces/${target.id}`, { method: 'DELETE' })
    expect(screen.queryByRole('button', { name: `${target.name} Workspace 삭제` })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '닫기' }))
    expect(screen.getByRole('combobox', { name: 'Workspace 선택' })).toHaveTextContent(active ? other.name : workspace.name)
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
    expect(await within(dialog).findByRole('button', { name: '연결됨' })).toBeDisabled()
    expect(within(dialog).queryByText(/Codex · 로그인 확인/)).not.toBeInTheDocument()
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
    expect(screen.getByRole('combobox', { name: 'Workspace 선택' })).toHaveTextContent(workspace.name)
  })

  it('큰 자료의 앞부분 미리보기와 전체 검색을 구분한다', async () => {
    const user = userEvent.setup()
    const reason = '큰 문서는 추출문 앞부분만 미리 봅니다. 검색은 전체 추출문을 사용합니다.'
    api.mockImplementation(async path => {
      if (path === '/session') return { workspaces: [workspace], activeWorkspaceId: 'one' }
      if (path.endsWith('/preview')) return { supported: true, text: '가상 문서의 앞부분', reason }
      if (path.endsWith('/sources')) return []
      return [file]
    })
    render(<App />)
    await user.click(await screen.findByRole('button', { name: '자료' }))
    await user.click(await screen.findByRole('button', { name: 'notes.md' }))
    expect(await screen.findByText(reason)).toBeInTheDocument()
    expect(screen.getByText('가상 문서의 앞부분')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '원본 다운로드' })).toBeEnabled()
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
    api.mockClear()
    await user.click(screen.getByRole('button', { name: '자료' }))
    await waitFor(() => expect(api).toHaveBeenCalledWith('/workspaces/one/entries?area=ORIGINAL', expect.anything()))
    expect(screen.getByRole('button', { name: '자료 추가' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '파일 추가' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '새 폴더' })).not.toBeInTheDocument()
  })
})
