import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { request } from './api'

vi.mock('./api', async importOriginal => ({
  ...await importOriginal<typeof import('./api')>(),
  request: vi.fn(),
}))
const api = vi.mocked(request)
const workspace = { id: 'one', name: '가상 연구', createdAt: '2026-10-21T00:00:00Z' }
const file = { id: 'file', workspaceId: 'one', parentId: null, name: 'notes.md', kind: 'FILE', area: 'ORIGINAL', size: 12, createdAt: workspace.createdAt }

beforeEach(() => { api.mockReset() })

describe('Workspace와 자료 탐색기', () => {
  it('빈 상태에서 Workspace를 만들고 새 세션으로 이동한다', async () => {
    const user = userEvent.setup()
    let created = false
    api.mockImplementation(async (path, options) => {
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
      if (path === '/session') return { workspaces: [workspace], activeWorkspaceId: 'one' }
      if (path.endsWith('/preview')) return { supported: true, text: '<script>window.injected=true</script>', reason: null }
      return [file]
    })
    render(<App />)
    await user.click(await screen.findByRole('button', { name: 'notes.md' }))
    expect(await screen.findByText('<script>window.injected=true</script>')).toBeInTheDocument()
    expect(document.querySelector('.preview-content script')).toBeNull()
    expect(screen.getByRole('link', { name: '원본 다운로드' })).toHaveAttribute('href', '/api/workspaces/one/entries/file/content')
  })

  it('삭제 취소는 API를 호출하지 않으며 오류는 모달 안에 남긴다', async () => {
    const user = userEvent.setup()
    api.mockImplementation(async (path, options) => {
      if (path === '/session') return { workspaces: [workspace], activeWorkspaceId: 'one' }
      if (options?.method === 'DELETE') throw new Error('저장소를 사용할 수 없습니다.')
      return [file]
    })
    render(<App />)
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
      if (path === '/session') return { workspaces: [workspace], activeWorkspaceId: 'one' }
      if (path.includes('parentId=folder') || path.includes('KNOWLEDGE')) return []
      return [{ ...file, id: 'folder', kind: 'FOLDER', name: '참고 자료' }]
    })
    render(<App />)
    await user.click(await screen.findByRole('button', { name: '참고 자료' }))
    await waitFor(() => expect(api).toHaveBeenCalledWith('/workspaces/one/entries?area=ORIGINAL&parentId=folder', expect.anything()))
    await user.click(screen.getByRole('button', { name: '지식 문서' }))
    expect(await screen.findByText('아직 생성된 지식이 없습니다')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '파일 추가' })).not.toBeInTheDocument()
  })
})
