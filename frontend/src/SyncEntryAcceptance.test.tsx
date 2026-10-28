import { afterAll, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { writeFileSync } from 'node:fs'
import App from './App'
import { request } from './api'
import { selectFolder } from './folderPicker'

vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(), request: vi.fn() }))
vi.mock('./folderPicker', () => ({ hasFolderPicker: () => true, selectFolder: vi.fn() }))

describe.skipIf(import.meta.env.VITE_SYNC_ACCEPTANCE !== '1')('sync entry acceptance: actual React App, mocked API', () => {
  const results: Record<string, unknown>[] = []
  afterAll(() => writeFileSync('../backend/target/sync-entry-acceptance-results.json', JSON.stringify(results, null, 2)))
  for (let fixture = 0; fixture < 5; fixture++) {
    for (let action = 0; action < 5; action++) {
      const id = `T${String(551 + fixture * 5 + action).padStart(4, '0')}`
      it(id, async () => {
        const startedAt = new Date().toISOString()
        const result: Record<string, unknown> = { id, startedAt, scope: 'ACTUAL_REACT_APP_JSDOM_MOCKED_API', fullAcceptancePassed: false,
          remaining: ['Native picker/network/backend ingestion and search', 'Actual process restart and background scheduler', 'File upload has no cancellation control; only source sync cancellation exercised'] }
        let finish!: () => void
        const pending = new Promise<void>(resolve => { finish = resolve })
        const api = vi.mocked(request)
        api.mockReset()
        vi.mocked(selectFolder).mockResolvedValue('/synthetic/owned-folder')
        let registrations = 0
        let syncs = 0
        let cancelled = false
        let uploaded = false
        const workspace = { id: 'one', name: 'Synthetic', createdAt: startedAt }
        let source: Record<string, unknown> | null = null
        api.mockImplementation(async (path, options) => {
          if (path === '/session') return { workspaces: [workspace, { ...workspace, id: 'two', name: 'Other' }], activeWorkspaceId: 'one' }
          if (path.endsWith('/files') && options?.method === 'POST') { registrations++; await pending; uploaded = true; return { id: 'file', name: 'synthetic.txt' } }
          if (path.includes('/entries?')) return uploaded ? [{ id: 'file', workspaceId: 'one', name: 'synthetic.txt', kind: 'FILE', area: 'ORIGINAL', size: 6, createdAt: startedAt }] : []
          if (path.endsWith('/sources') && options?.method === 'POST') {
            registrations++
            source = { ...JSON.parse(options.body as string), id: 'source', status: 'NEW', pollingEnabled: false }
            return source
          }
          if (path.endsWith('/sources/source/sync')) {
            syncs++
            await pending
            source = { ...source, status: fixture === 4 || cancelled ? 'ERROR' : 'READY',
              error: fixture === 4 ? '폴더를 찾을 수 없습니다.' : cancelled ? '동기화를 중단했습니다.' : null }
            return source
          }
          if (path.endsWith('/progress')) return { stage: 'EXTRACTING', file: 'synthetic.txt', completed: 0, total: 1, cancellable: true }
          if (path.endsWith('/cancel')) { cancelled = true; finish(); return null }
          if (path.endsWith('/sources')) return source ? [source] : []
          return []
        })
        const user = userEvent.setup()
        let view: ReturnType<typeof render> | undefined
        try {
          view = render(<App />)
          await screen.findByRole('heading', { name: 'Dougle' })
          await user.click(screen.getByRole('button', { name: '자료' }))
          await user.click(screen.getByRole('button', { name: '자료 추가' }))
          if (fixture === 0) await user.upload(screen.getByLabelText('파일 업로드'), new File(['BLUE72'], 'synthetic.txt'))
          else {
            await user.click(screen.getByRole('button', { name: '폴더' }))
            await user.click(screen.getByRole('button', { name: '폴더 선택' }))
          }
          await waitFor(() => expect(registrations).toBe(1))
          await waitFor(() => expect(screen.getByRole('button', { name: '설정' })).toBeDisabled())
          if (fixture !== 0) await waitFor(() => expect(syncs).toBe(1))
          if (fixture !== 0) expect(source).toMatchObject({ kind: 'LOCAL_FOLDER' })
          for (const name of ['대화', '자료', '질문 기록', '설정', 'Workspace 생성']) {
            const button = screen.getByRole('button', { name })
            expect(button).toBeDisabled()
            if (action === 1) fireEvent.click(button)
          }
          expect(screen.getByRole('combobox', { name: 'Workspace 선택' })).toBeDisabled()
          expect(screen.getByRole('heading', { name: '자료', level: 1 })).toBeVisible()
          if (action === 2 && fixture !== 0) await user.click(await screen.findByRole('button', { name: /^중단$/ }))
          if (action === 3) {
            await user.click(screen.getByRole('button', { name: /^동기화$/ }))
            expect(registrations).toBe(1)
            expect(syncs).toBe(fixture === 0 ? 0 : 1)
          }
          await act(async () => { finish(); await pending })
          await waitFor(() => expect(screen.getByRole('button', { name: '설정' })).toBeEnabled())
          expect(screen.getByRole('combobox', { name: 'Workspace 선택' })).toBeEnabled()
          if (fixture === 4) {
            const warning = await screen.findByRole('button', { name: /동기화 실패: 자료를 찾을 수 없습니다/ })
            await user.hover(warning)
            expect(screen.getByRole('tooltip')).toHaveTextContent('자료를 찾을 수 없습니다')
          }
          if (action === 4) {
            view.unmount()
            view = render(<App />)
            await screen.findByRole('heading', { name: 'Dougle' })
            expect(registrations).toBe(1)
            expect(syncs).toBe(fixture === 0 ? 0 : 1)
          }
          result.status = 'PARTIAL'; result.checkStatus = 'PASS'
        } catch (error) {
          result.status = 'FAIL'; result.checkStatus = 'FAIL'; result.failure = String(error)
          throw error
        } finally {
          finish(); view?.unmount()
          result.registrations = registrations; result.syncs = syncs; result.cancelled = cancelled
          result.elapsedMillis = Date.now() - Date.parse(startedAt)
          results.push(result)
        }
      }, 15000)
    }
  }
})
