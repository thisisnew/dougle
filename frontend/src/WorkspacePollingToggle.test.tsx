import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import WorkspacePollingToggle from './WorkspacePollingToggle'
import { request } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
beforeEach(() => api.mockReset())

it('저장 버튼 없이 토글 한 번으로 Workspace 설정을 저장한다', async () => {
  api.mockResolvedValueOnce({enabled:false,revision:0,mixed:false}).mockResolvedValueOnce({enabled:true,revision:1,mixed:false})
  render(<WorkspacePollingToggle workspaceId="one" name="가상 서재" />)
  const toggle = screen.getByRole('switch', {name:'가상 서재 자동 갱신'})
  await waitFor(() => expect(toggle).toBeEnabled())
  await userEvent.click(toggle)
  expect(api).toHaveBeenCalledWith('/workspaces/one/source-polling', expect.objectContaining({method:'PATCH',body:JSON.stringify({enabled:true,expectedRevision:0})}))
  await waitFor(() => expect(toggle).toHaveAttribute('aria-checked','true'))
  expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument()
})

it('꺼진 상태에서도 주기를 저장하고 다시 켤 때 선택을 유지한다', async () => {
  api.mockResolvedValueOnce({enabled:false,revision:0,mixed:false,intervalSeconds:3600})
    .mockResolvedValueOnce({enabled:false,revision:1,mixed:false,intervalSeconds:900})
    .mockResolvedValueOnce({enabled:true,revision:2,mixed:false,intervalSeconds:900})
  render(<WorkspacePollingToggle workspaceId="one" name="가상 서재" />)
  const interval = screen.getByRole('combobox', {name:'가상 서재 갱신 주기'})
  await waitFor(() => expect(interval).toBeEnabled())
  expect(interval).toHaveValue('3600')
  await userEvent.selectOptions(interval, '900')
  expect(api).toHaveBeenCalledWith('/workspaces/one/source-polling', expect.objectContaining({method:'PATCH',body:JSON.stringify({enabled:false,expectedRevision:0,intervalSeconds:900})}))
  await waitFor(() => expect(interval).toHaveValue('900'))
  expect(screen.getByRole('switch')).toHaveAttribute('aria-checked','false')
  await userEvent.click(screen.getByRole('switch'))
  await waitFor(() => expect(screen.getByRole('switch')).toHaveAttribute('aria-checked','true'))
  expect(interval).toHaveValue('900')
})

it('주기 저장 실패는 최신 값으로 복원하고 자동 재전송하지 않는다', async () => {
  api.mockResolvedValueOnce({enabled:true,revision:2,mixed:false,intervalSeconds:3600})
    .mockRejectedValueOnce(new Error('설정 충돌'))
    .mockResolvedValueOnce({enabled:true,revision:3,mixed:false,intervalSeconds:1800})
  render(<WorkspacePollingToggle workspaceId="one" name="가상 서재" />)
  const interval = screen.getByRole('combobox')
  await waitFor(() => expect(interval).toBeEnabled())
  await userEvent.selectOptions(interval, '300')
  expect(await screen.findByRole('alert')).toHaveTextContent('설정 충돌')
  await waitFor(() => expect(interval).toHaveValue('1800'))
  expect(api.mock.calls.filter(([,options]) => options?.method==='PATCH')).toHaveLength(1)
})

it('충돌을 표시하고 최신 값을 다시 읽되 자동으로 변경 요청을 재전송하지 않는다', async () => {
  api.mockResolvedValueOnce({enabled:true,revision:2,mixed:false})
    .mockRejectedValueOnce(new Error('설정 충돌')).mockResolvedValueOnce({enabled:false,revision:3,mixed:false})
  render(<WorkspacePollingToggle workspaceId="one" name="가상 서재" />)
  const toggle = screen.getByRole('switch')
  await waitFor(() => expect(toggle).toBeEnabled())
  await userEvent.click(toggle)
  expect(await screen.findByRole('alert')).toHaveTextContent('설정 충돌')
  await waitFor(() => expect(toggle).toHaveAttribute('aria-checked','false'))
  expect(api.mock.calls.filter(([,options]) => options?.method==='PATCH')).toHaveLength(1)
})

it('초기 조회 실패는 재시도하며 조회만으로 자동 갱신을 켜지 않는다', async () => {
  api.mockRejectedValueOnce(new Error('조회 실패')).mockResolvedValueOnce({enabled:false,revision:0,mixed:false})
  render(<WorkspacePollingToggle workspaceId="one" name="가상 서재" />)
  await screen.findByRole('alert')
  await userEvent.click(screen.getByRole('switch'))
  await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
  expect(api.mock.calls.every(([,options]) => !options?.method)).toBe(true)
})
