import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import EvidenceOriginButton from './EvidenceOriginButton'
import { request, type EvidenceLocation } from './api'

vi.mock('./api', () => ({ request: vi.fn() }))
const api = vi.mocked(request)
const path = '/workspaces/one/knowledge/card/versions/2/evidence/snapshot/location'
const location: EvidenceLocation = {
  entry: { id: 'new-id', workspaceId: 'one', parentId: null, name: 'notes.txt', kind: 'FILE', area: 'ORIGINAL', size: 4, createdAt: 'now' },
  ancestors: [], freshness: 'STALE',
}
beforeEach(() => { api.mockReset() })

it('사용자가 요청할 때만 현재 위치를 조회하고 새 ID와 최신성 정보를 전달한다', async () => {
  const user = userEvent.setup()
  const open = vi.fn()
  const busy = vi.fn()
  api.mockResolvedValue(location)
  render(<EvidenceOriginButton path={path} disabled={false} onOpen={open} onBusy={busy} />)
  expect(api).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: '현재 원본에서 보기' }))
  expect(api).toHaveBeenCalledWith(path, expect.objectContaining({ signal: expect.any(AbortSignal) }))
  expect(open).toHaveBeenCalledWith(location)
  expect(busy.mock.calls).toEqual([[true], [false]])
})

it('삭제된 원본 오류는 현재 화면에 표시하고 이동하지 않는다', async () => {
  const user = userEvent.setup()
  const open = vi.fn()
  api.mockRejectedValue(new Error('현재 원본이 없습니다. 당시 근거 원본 다운로드를 이용하세요.'))
  render(<EvidenceOriginButton path={path} disabled={false} onOpen={open} onBusy={vi.fn()} />)
  await user.click(screen.getByRole('button', { name: '현재 원본에서 보기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('당시 근거 원본 다운로드')
  expect(open).not.toHaveBeenCalled()
})

it('편집 또는 다른 요청 중에는 탐색하지 않는다', async () => {
  const user = userEvent.setup()
  render(<EvidenceOriginButton path={path} disabled onOpen={vi.fn()} onBusy={vi.fn()} />)
  await user.click(screen.getByRole('button', { name: '현재 원본에서 보기' }))
  expect(api).not.toHaveBeenCalled()
})

it('화면 이탈 후 늦게 도착한 위치로 이동하지 않는다', async () => {
  const user = userEvent.setup()
  const open = vi.fn()
  let resolveLocation!: (value: EvidenceLocation) => void
  api.mockReturnValue(new Promise(resolve => { resolveLocation = resolve }))
  const view = render(<EvidenceOriginButton path={path} disabled={false} onOpen={open} onBusy={vi.fn()} />)
  await user.click(screen.getByRole('button', { name: '현재 원본에서 보기' }))
  const signal = api.mock.calls[0][1]?.signal
  view.unmount()
  await act(async () => { resolveLocation(location) })
  expect(signal?.aborted).toBe(true)
  expect(open).not.toHaveBeenCalled()
})
