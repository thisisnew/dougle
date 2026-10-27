import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import SourcePanel from './SourcePanel'
import { request } from './api'
import fixtures from '../../docs/fixtures/routing-acceptance.json'

vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(), request: vi.fn() }))
vi.mock('./folderPicker', () => ({ hasFolderPicker: () => true, selectFolder: vi.fn() }))

describe.skipIf(import.meta.env.VITE_ROUTING_ACCEPTANCE !== '1')('routing acceptance: real component, mocked HTTP, no remote ingestion', () => {
  for (const fixture of fixtures) {
    for (const action of ['web', 'github', 'roundtrip', 'retry'] as const) {
      it(`${fixture.caseIds[action]} ${fixture.fixture} ${action}`, async () => {
        const api = vi.mocked(request)
        api.mockReset()
        const created: Record<string, unknown>[] = []
        let syncCalls = 0
        let registrationAttempts = 0
        api.mockImplementation(async (path, options) => {
          if (options?.method === 'POST' && path.endsWith('/sources')) {
            registrationAttempts++
            if (!fixture.expectedKind) throw new Error('올바른 공개 HTTPS 주소를 입력하세요.')
            const source = { ...JSON.parse(options.body as string), id: 'source', status: 'NEW', pollingEnabled: false }
            created.push(source)
            return source
          }
          if (options?.method === 'POST' && path.endsWith('/sync')) {
            syncCalls++
            return { ...created[0], status: 'READY' }
          }
          return created
        })
        const user = userEvent.setup()
        render(<SourcePanel manageOnly workspaceId="routing" revision={0} onChanged={vi.fn()} onSelect={vi.fn()} />)
        await user.click(screen.getByRole('button', { name: '자료 추가' }))
        await user.click(screen.getByRole('button', { name: action === 'github' ? 'GitHub' : '웹 링크' }))
        if (action === 'roundtrip') {
          await user.click(screen.getByRole('button', { name: 'GitHub' }))
          await user.click(screen.getByRole('button', { name: '웹 링크' }))
        }
        const input = screen.getByRole('textbox', { name: /주소$/ })
        if (action === 'retry') {
          fireEvent.change(input, { target: { value: ' ' } })
          fireEvent.submit(input.closest('form')!)
          expect(created).toHaveLength(0)
          fireEvent.change(input, { target: { value: '' } })
        }
        await user.click(input)
        await user.paste(fixture.input)
        if (action === 'roundtrip') await user.keyboard('{Enter}')
        else await user.click(screen.getByRole('button', { name: '추가' }))
        if (!fixture.expectedKind) {
          await waitFor(() => expect(screen.getByRole('button', { name: '추가' })).not.toHaveAttribute('aria-busy', 'true'))
          expect(created).toHaveLength(0)
          expect(syncCalls).toBe(0)
          if (fixture.input.includes('\n')) expect(registrationAttempts).toBe(0)
          return
        }
        await waitFor(() => expect(syncCalls).toBe(1))
        expect(created).toHaveLength(1)
        expect(created[0].kind).toBe(fixture.expectedKind)
        expect(created[0].location).toBe(fixture.expectedLocation)
        expect(input).toHaveValue('')
        expect(screen.getByRole('button', { name: fixture.expectedKind === 'GITHUB' ? 'GitHub' : '웹 링크' })).toHaveAttribute('aria-pressed', 'true')
      })
    }
  }
})
