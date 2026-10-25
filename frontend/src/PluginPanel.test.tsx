import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import PluginPanel from './PluginPanel'
import { pluginForUrl } from './plugins'
import { request } from './api'
import notionIcon from './assets/plugins/notion.svg'
import jiraIcon from './assets/plugins/jira.svg'
import confluenceIcon from './assets/plugins/confluence.svg'
import mysqlIcon from './assets/plugins/mysql.png'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const disconnected = { notionConnected: false, mysqlConnected: false, mysql: null }
beforeEach(() => { api.mockReset(); api.mockResolvedValue(disconnected) })

it('서비스 설명 없이 로컬 공식 로고와 서비스 이름만 표시한다', async () => {
  render(<PluginPanel />)
  await waitFor(() => expect(api).toHaveBeenCalled())
  for (const text of ['페이지와 문서', '이슈와 문서', '데이터베이스']) expect(screen.queryByText(text)).not.toBeInTheDocument()
  const images = document.querySelectorAll('.plugin-icon img')
  expect(images).toHaveLength(4)
  expect(Array.from(images).map(image => image.getAttribute('src'))).toEqual([
    notionIcon, jiraIcon, confluenceIcon, mysqlIcon,
  ])
  for (const image of images) expect(image).toHaveAttribute('alt', '')
})

it('MySQL에 실제 연결 입력과 테스트를 제공하고 임의로 연결 성공을 표시하지 않는다', async () => {
  render(<PluginPanel />)
  expect(screen.getByRole('heading', { name: '플러그인' })).toBeVisible()
  expect(screen.getByRole('article', { name: 'Notion' })).toBeVisible()
  expect(screen.getByRole('article', { name: 'MySQL' })).toBeVisible()
  await userEvent.click(screen.getByRole('button', { name: 'MySQL 연결 설정' }))
  expect(screen.getByRole('dialog', { name: 'MySQL 연결' })).toBeVisible()
  expect(screen.queryByText(/읽기 전용 계정으로 연결하세요/)).not.toBeInTheDocument()
  expect(screen.getByLabelText('호스트')).toHaveValue('localhost')
  expect(screen.getByLabelText('포트')).toHaveValue(3306)
  expect(screen.getByLabelText('비밀번호')).toHaveAttribute('type', 'password')
  expect(screen.getByRole('button', { name: '연결 테스트' })).toBeDisabled()
  expect(screen.queryByText('연결됨')).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '닫기' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

it('자료 오류에서 이동한 제공자의 설정을 바로 연다', async () => {
  render(<PluginPanel initialPlugin="NOTION" />)
  expect(screen.getByRole('dialog', { name: 'Notion 연결' })).toBeVisible()
  await waitFor(() => expect(api).toHaveBeenCalled())
  expect(screen.getByText('브라우저에서 로그인하고 연결을 승인해 주세요.')).toBeVisible()
  expect(screen.queryByLabelText('연결 토큰')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Notion 연결 관리' })).not.toBeInTheDocument()
  expect(screen.queryByRole('link')).not.toBeInTheDocument()
  expect(screen.queryByText(/읽을 페이지의|구글 비밀번호|Notion에 평소 방식/)).not.toBeInTheDocument()
})

it('브라우저 연결 성공 후에만 기존 자료를 동기화한다', async () => {
  let connected = false
  api.mockImplementation(async (path, options) => {
    if (path === '/plugins/browser/notion' && options?.method === 'POST') return { id: 'attempt', state: 'WAITING', message: '브라우저에서 연결을 완료해 주세요.' }
    if (path === '/plugins/browser/notion/attempt') { connected = true; return { id: 'attempt', state: 'CONNECTED', message: '연결되었습니다.' } }
    if (path.endsWith('/sources')) return [{ id: 'notion', kind: 'URL', name: '가상 페이지', location: 'https://app.notion.com/p/synthetic' }]
    if (path.endsWith('/sync')) return { status: 'READY' }
    return { ...disconnected, notionConnected: connected }
  })
  const busy = vi.fn()
  render(<PluginPanel initialPlugin="NOTION" workspaceId="one" onBusyChange={busy} />)
  expect(api.mock.calls.some(([path]) => path.endsWith('/sources'))).toBe(false)
  await userEvent.click(screen.getByRole('button', { name: '연결' }))
  await waitFor(() => expect(api).toHaveBeenCalledWith('/workspaces/one/sources/notion/sync', { method: 'POST' }))
  expect(screen.queryByLabelText('연결 토큰')).not.toBeInTheDocument()
  await waitFor(() => expect(busy).toHaveBeenLastCalledWith(false))
})

it('실패는 오류를 표시하고 재시도할 수 있으며 연결됨으로 바꾸지 않는다', async () => {
  api.mockImplementation(async (path) => { if (path === '/plugins/browser/notion') throw new Error('브라우저를 열지 못했습니다.'); return disconnected })
  render(<PluginPanel initialPlugin="NOTION" />)
  await waitFor(() => expect(screen.getByRole('button', { name: '연결' })).toBeEnabled())
  await userEvent.click(screen.getByRole('button', { name: '연결' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('브라우저를 열지 못했습니다.')
  expect(screen.getByRole('button', { name: '연결' })).toBeEnabled()
  expect(screen.queryByText('연결됨')).not.toBeInTheDocument()
})

it('인증 성공 후 자료 갱신 실패가 화면을 잠그지 않는다', async () => {
  api.mockImplementation(async path => {
    if (path === '/plugins') return disconnected
    if (path === '/plugins/browser/notion') return { id: 'attempt', state: 'WAITING', message: '승인 대기' }
    if (path === '/plugins/browser/notion/attempt') return { id: 'attempt', state: 'CONNECTED', message: '연결됨' }
    throw new Error('자료를 갱신하지 못했습니다.')
  })
  render(<PluginPanel initialPlugin="NOTION" workspaceId="one" />)
  await waitFor(() => expect(screen.getByRole('button', { name: '연결' })).toBeEnabled())
  await userEvent.click(screen.getByRole('button', { name: '연결' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('자료를 갱신하지 못했습니다.')
  expect(screen.getByRole('button', { name: '닫기' })).toBeEnabled()
})

it('저장된 MySQL 연결을 여는 것만으로 해제하지 않으며 구조 추가 시 기존 자료를 재사용한다', async () => {
  const mysql = { host: 'localhost', port: 3306, database: 'fixture', username: 'reader', password: '', sslMode: 'DISABLED' }
  api.mockImplementation(async path => {
    if (path.endsWith('/sources')) return [{ id: 'db', kind: 'MYSQL', location: 'localhost:3306/fixture' }]
    if (path.endsWith('/sync')) return { status: 'READY' }
    return { ...disconnected, mysqlConnected: true, mysql }
  })
  render(<PluginPanel workspaceId="one" />)
  await screen.findByText('연결됨')
  await userEvent.click(screen.getByRole('button', { name: 'MySQL 연결 설정' }))
  expect(screen.getByLabelText('데이터베이스')).toHaveValue('fixture')
  expect(api.mock.calls.some(([, options]) => options?.method === 'DELETE')).toBe(false)
  await userEvent.click(screen.getByRole('button', { name: 'DB 구조를 자료에 추가' }))
  await waitFor(() => expect(api).toHaveBeenCalledWith('/workspaces/one/sources/db/sync', { method: 'POST' }))
})

it('브라우저 대기는 취소할 수 있으며 중복 제출을 제공하지 않는다', async () => {
  api.mockImplementation(async path => path === '/plugins' ? disconnected : { id: 'attempt', state: 'WAITING', message: '브라우저에서 연결을 완료해 주세요.' })
  render(<PluginPanel initialPlugin="NOTION" />)
  await waitFor(() => expect(screen.getByRole('button', { name: '연결' })).toBeEnabled())
  await userEvent.click(screen.getByRole('button', { name: '연결' }))
  expect(screen.queryByRole('button', { name: '연결' })).not.toBeInTheDocument()
  await userEvent.click(await screen.findByRole('button', { name: '취소' }))
  expect(api).toHaveBeenCalledWith('/plugins/browser/notion/attempt', { method: 'DELETE' })
  await waitFor(() => expect(screen.getByRole('button', { name: '닫기' })).toBeEnabled())
})

it.each([
  ['https://app.notion.com/p/synthetic', 'NOTION'],
  ['https://www.notion.so/synthetic', 'NOTION'],
  ['https://example.notion.site/synthetic', 'NOTION'],
  ['https://notion.com/p/synthetic', 'NOTION'],
  ['https://notion.com/help', null],
  ['https://app.notion.com.example.org/p/synthetic', null],
  ['https://example.org/notion.so', null],
  ['not a URL', null],
  ['https://fixture.atlassian.net/wiki/spaces/TEST/pages/123/Page', 'ATLASSIAN'],
  ['https://fixture.atlassian.net.evil.example/wiki', null],
])('URL의 호스트로 플러그인을 구분한다: %s', (url, expected) => {
  expect(pluginForUrl(url)).toBe(expected)
})
