import { afterAll, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { writeFileSync } from 'node:fs'
import DesktopUpdate from './DesktopUpdate'
import { version } from '../../desktop/src-tauri/tauri.conf.json'

describe.skipIf(import.meta.env.VITE_UPDATE_ACCEPTANCE !== '1')('update acceptance: actual component, mocked native IPC', () => {
  const results: Record<string, unknown>[] = []
  afterAll(() => writeFileSync('../backend/target/update-ui-acceptance-results.json', JSON.stringify(results, null, 2)))
  for (let fixture = 0; fixture < 5; fixture++) for (let action = 0; action < 5; action++) {
    const id = `T${String(976 + fixture * 5 + action).padStart(4, '0')}`
    it(id, async () => {
      const result: Record<string, unknown> = { id, startedAt: new Date().toISOString(), fullAcceptancePassed: false,
        scope: 'REAL_UPDATE_COMPONENT_MOCKED_NATIVE_IPC', remaining: ['Actual signature/hash/CPU rejection and package execution', 'Real feed timeout and interrupted download', 'Native process restart; React remount only'] }
      let release!: () => void
      const pending = new Promise<void>(resolve => { release = resolve })
      const invoke = vi.fn(async (command: string) => {
        if (command === 'update_status') return { phase: fixture === 0 ? 'current' : fixture === 1 ? 'available' : 'error', version: fixture === 1 ? '99.0.0' : undefined }
        if (command === 'install_update') { await pending; if (fixture >= 2) throw new Error('synthetic native failure'); return null }
        throw new Error('Unexpected IPC command')
      })
      Object.defineProperty(window, '__TAURI_INTERNALS__', { configurable: true, value: { invoke } })
      const user = userEvent.setup()
      let view: ReturnType<typeof render> | undefined
      try {
        view = render(<DesktopUpdate />)
        await waitFor(() => expect(invoke).toHaveBeenCalledWith('update_status'))
        await user.click(screen.getByRole('button', { name: '앱 업데이트' }))
        expect(screen.getByText(`v${version}`)).toBeVisible()
        expect(invoke.mock.calls.filter(([command]) => command === 'install_update')).toHaveLength(0)
        if (fixture === 0) expect(await screen.findByRole('status')).toHaveTextContent('최신 버전입니다')
        if (fixture >= 2) expect(await screen.findByRole('alert')).toHaveTextContent('다시 시도')
        if (action === 1 || action === 2 || action === 3) {
          const button = screen.getByRole('button', { name: fixture === 0 ? '최신 버전 확인' : fixture === 1 ? '새 버전 설치' : '다시 확인' })
          await user.click(button)
          for (let repeat = 0; repeat < 5; repeat++) fireEvent.click(button)
          expect(button).toBeDisabled()
          expect(invoke.mock.calls.filter(([command]) => command === 'install_update')).toHaveLength(1)
          if (action === 3) await user.click(screen.getByRole('button', { name: '닫기' }))
          await act(async () => { release(); await pending })
          if (action === 3) await user.click(screen.getByRole('button', { name: '앱 업데이트' }))
          await waitFor(() => expect(screen.getByRole('button', { name: fixture >= 2 ? '다시 확인' : fixture === 1 ? '새 버전 설치' : '최신 버전 확인' })).toBeEnabled())
        }
        if (action === 4) {
          view.unmount(); view = render(<DesktopUpdate />)
          await user.click(screen.getByRole('button', { name: '앱 업데이트' }))
          expect(screen.getByText(`v${version}`)).toBeVisible()
          expect(invoke.mock.calls.filter(([command]) => command === 'install_update')).toHaveLength(0)
        }
        result.status = 'PARTIAL'; result.checkStatus = 'PASS'
      } catch (error) { result.status = 'FAIL'; result.checkStatus = 'FAIL'; result.failure = String(error); throw error }
      finally {
        release(); view?.unmount(); Reflect.deleteProperty(window, '__TAURI_INTERNALS__')
        result.commands = invoke.mock.calls.map(([command]) => command)
        result.elapsedMillis = Date.now() - Date.parse(result.startedAt as string)
        results.push(result)
      }
    }, 15000)
  }
})
