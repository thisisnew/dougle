import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import DesktopUpdate from './DesktopUpdate'
import { version as appVersion } from '../../desktop/src-tauri/tauri.conf.json'

afterEach(() => { Reflect.deleteProperty(window, '__TAURI_INTERNALS__') })
function desktop(invoke: ReturnType<typeof vi.fn>) {
  Object.defineProperty(window, '__TAURI_INTERNALS__', { configurable: true, value: { invoke } })
}

it('일반 웹에서는 설치 앱 업데이트 버튼을 노출하지 않는다', () => {
  render(<DesktopUpdate />)
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
})

it('업데이트가 없으면 로딩 대신 최신 버전 결과를 표시한다', async () => {
  desktop(vi.fn().mockResolvedValue({ phase: 'current' }))
  render(<DesktopUpdate />)
  await userEvent.click(screen.getByRole('button', { name: '앱 업데이트' }))
  expect(await screen.findByRole('status')).toHaveTextContent('최신 버전입니다.')
  expect(screen.getByRole('button', { name: '최신 버전 확인' })).toBeEnabled()
  expect(within(screen.getByRole('dialog')).getByText(`v${appVersion}`)).toBeVisible()
})

it('실패 상태에 남은 버전은 새 업데이트 배지로 표시하지 않는다', async () => {
  desktop(vi.fn().mockResolvedValue({ phase: 'error', version: '0.1.21' }))
  render(<DesktopUpdate />)
  await userEvent.click(screen.getByRole('button', { name: '앱 업데이트' }))
  const button = await screen.findByRole('button', { name: '다시 확인' })
  expect(button).toBeEnabled()
  expect(screen.queryByText('새 버전')).not.toBeInTheDocument()
})

it('새 버전은 표시만 하고 클릭 전 다운로드나 설치를 요청하지 않는다', async () => {
  const invoke = vi.fn().mockResolvedValue({ phase: 'available', version: '0.1.18' })
  desktop(invoke)
  render(<DesktopUpdate />)
  await userEvent.click(screen.getByRole('button', { name: '앱 업데이트' }))
  const button = await screen.findByRole('button', { name: '새 버전 설치' })
  expect(screen.getByText('v0.1.18')).toBeVisible()
  expect(invoke).toHaveBeenCalledTimes(1)
  expect(invoke).toHaveBeenCalledWith('update_status')
  await userEvent.click(button)
  expect(invoke).toHaveBeenCalledWith('install_update')
})

it('다운로드 진행률을 표시하고 중복 실행을 막는다', async () => {
  desktop(vi.fn().mockResolvedValue({ phase: 'downloading', version: '0.1.18', progress: 45 }))
  render(<DesktopUpdate />)
  await userEvent.click(screen.getByRole('button', { name: '앱 업데이트' }))
  expect(await screen.findByRole('button', { name: /다운로드 중 45%/ })).toBeDisabled()
})

it('실패하면 다시 확인할 수 있고 설치 성공으로 표시하지 않는다', async () => {
  const invoke = vi.fn().mockImplementation(async command => {
    if (command === 'install_update') throw new Error('native failure')
    return { phase: 'idle' }
  })
  desktop(invoke)
  render(<DesktopUpdate />)
  await waitFor(() => expect(invoke).toHaveBeenCalledWith('update_status'))
  await userEvent.click(screen.getByRole('button', { name: '앱 업데이트' }))
  await userEvent.click(screen.getByRole('button', { name: '최신 버전 확인' }))
  expect(await screen.findByRole('button', { name: '다시 확인' })).toBeEnabled()
})

it('설정 행과 모달을 구분하고 닫거나 화면을 옮겨도 자동 확인 상태를 유지한다', async () => {
  const invoke = vi.fn().mockResolvedValue({ phase: 'available', version: '9.9.9' })
  const available = vi.fn()
  desktop(invoke)
  const view = render(<DesktopUpdate onAvailableChange={available} />)
  const row = screen.getByRole('button', { name: '앱 업데이트' })
  expect(row).toHaveClass('settings-row')
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  await userEvent.click(row)
  expect(screen.getByRole('dialog', { name: '앱 업데이트' })).toBeVisible()
  await userEvent.click(screen.getByRole('button', { name: '닫기' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  await userEvent.click(row)
  view.rerender(<DesktopUpdate visible={false} onAvailableChange={available} />)
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  await waitFor(() => expect(available).toHaveBeenCalledWith(true))
  expect(invoke.mock.calls.every(([command]) => command === 'update_status')).toBe(true)
})
