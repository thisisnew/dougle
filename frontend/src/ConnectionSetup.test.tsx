import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import ConnectionSetup from './ConnectionSetup'

beforeEach(() => localStorage.clear())
const props = () => ({ connection: null, disabled: false, onAi: vi.fn(), onPlugin: vi.fn() })

it('first-run setup offers explicit connections without starting authentication', async () => {
  const callbacks = props()
  render(<ConnectionSetup {...callbacks} />)
  expect(callbacks.onAi).not.toHaveBeenCalled()
  expect(callbacks.onPlugin).not.toHaveBeenCalled()
  await userEvent.click(screen.getByRole('button', { name: 'AI 연결 설정' }))
  expect(callbacks.onAi).toHaveBeenCalledTimes(1)
  await userEvent.click(screen.getByRole('button', { name: 'Jira · Confluence 연결' }))
  expect(callbacks.onPlugin).toHaveBeenCalledWith('ATLASSIAN')
  expect(screen.getByText(/비공개 저장소 인증은 아직 지원하지/)).toBeVisible()
})

it('dismissing persists only the guide preference, not authentication success', async () => {
  const callbacks = props()
  const view = render(<ConnectionSetup {...callbacks} />)
  await userEvent.click(screen.getByRole('button', { name: '안내 닫기' }))
  expect(screen.queryByRole('region')).not.toBeInTheDocument()
  view.unmount()
  render(<ConnectionSetup {...callbacks} />)
  expect(screen.queryByRole('region')).not.toBeInTheDocument()
  expect(callbacks.onAi).not.toHaveBeenCalled()
})

it('settings can reopen a dismissed guide and never claims remote model access', () => {
  localStorage.setItem('dougle.connection-setup.dismissed.v1', 'true')
  render(<ConnectionSetup {...props()} alwaysVisible connection={{ provider: 'CLAUDE', state: 'AUTHENTICATED' }} />)
  expect(screen.getByRole('button', { name: 'CLAUDE 로그인 확인됨' })).toBeEnabled()
  expect(screen.queryByRole('button', { name: '안내 닫기' })).not.toBeInTheDocument()
})

it('locks all connection actions during synchronization', () => {
  render(<ConnectionSetup {...props()} disabled />)
  for (const button of screen.getAllByRole('button')) expect(button).toBeDisabled()
})
