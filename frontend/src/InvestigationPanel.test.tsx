import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import InvestigationPanel from './InvestigationPanel'
import { ApiError, request } from './api'

vi.mock('./api', async importOriginal => ({ ...await importOriginal<typeof import('./api')>(), request: vi.fn() }))
const api = vi.mocked(request)
const props = { workspaceId: 'workspace', bundleId: 'bundle', connection: { provider: 'CODEX' as const, state: 'AUTHENTICATED', model: 'fixture' }, disabled: false }
const preview = { digest: 'digest', prompt: '가상 질문과 고정 근거', characters: 12, maxCalls: 1, provider: 'CODEX', requestedModel: 'fixture' }
const job = { id: 'job', workspaceId: 'workspace', bundleId: 'bundle', status: 'SUCCEEDED', query: 'Strategy', output: '<script>untrusted</script>' }
const proposalJob = { ...job, decision: 'PROPOSE_MYSQL_READ', query: null, output: null }
beforeEach(() => { api.mockReset() })

it('does not offer answer capture for a truncated local search', async () => {
  api.mockResolvedValueOnce([{ ...job, decision: 'SEARCH', toolStatus: 'OK', truncated: true }])
  const onAnswer = vi.fn()
  render(<InvestigationPanel {...props} onAnswer={onAnswer} />)
  fireEvent.click(screen.getByRole('button', { name: '자료 더 찾아보기' }))
  expect(await screen.findByText(/질문의 범위를 좁혀/)).toBeVisible()
  expect(screen.queryByRole('button', { name: '이 자료로 다시 답변' })).not.toBeInTheDocument()
  expect(onAnswer).not.toHaveBeenCalled()
  expect(api).toHaveBeenCalledTimes(1)
})

it.each(['PROPOSE_MYSQL_READ', 'PROPOSE_DATADOG_LOGS'])('restores %s without a query or handoff', async decision => {
  api.mockResolvedValueOnce([{ ...proposalJob, decision }])
  render(<InvestigationPanel {...props} onAnswer={vi.fn()} />)
  expect(api).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '자료 더 찾아보기' }))
  expect(await screen.findByText('운영 조회 제안 · 실행하지 않음')).toBeVisible()
  expect(screen.getByText('DB·로그 조회는 아직 실행되지 않았습니다.')).toBeVisible()
  expect(screen.queryByRole('button', { name: '조회 양식 열기' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '이 자료로 다시 답변' })).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledExactlyOnceWith('/workspaces/workspace/investigation-plans/jobs?bundleId=bundle', expect.objectContaining({ signal: expect.any(AbortSignal) }))
})

async function prepare() {
  fireEvent.click(screen.getByRole('button', { name: '자료 더 찾아보기' }))
  await screen.findByRole('button', { name: '검색 시작' })
}

it.each(['PROPOSE_MYSQL_READ', 'PROPOSE_DATADOG_LOGS'])('does not present the pending %s scope identifier as evidence', async decision => {
  api.mockResolvedValueOnce([{ ...proposalJob, decision, toolStatus: 'PENDING_SCOPE', output: 'private-scope-id' }])
  render(<InvestigationPanel {...props} onAnswer={vi.fn()} />)
  fireEvent.click(screen.getByRole('button', { name: '자료 더 찾아보기' }))
  expect(await screen.findByText('운영 조회 제안 · 실행하지 않음')).toBeVisible()
  expect(screen.queryByText('private-scope-id')).not.toBeInTheDocument()
  expect(screen.queryByText('검색 결과 · 답변으로 검증되지 않은 자료')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '이 자료로 다시 답변' })).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(1)
})

it('열기 전에는 조회도 실행도 하지 않고 명시적 시작 전에 AI 요청을 제출하지 않는다', async () => {
  api.mockImplementation(async path => path.endsWith('/preview') ? preview : [])
  render(<InvestigationPanel {...props} />)
  expect(api).not.toHaveBeenCalled()
  await prepare()
  expect(api.mock.calls.map(([path]) => path)).toEqual([
    '/workspaces/workspace/investigation-plans/jobs?bundleId=bundle', '/workspaces/workspace/investigation-plans/preview',
  ])
  expect(screen.getByText(/운영 DB·로그는 조회하지 않습니다/)).toBeVisible()
})

it('승인한 검색을 한 번 제출하고 결과를 실행 가능한 HTML이나 검증된 답변으로 취급하지 않는다', async () => {
  api.mockImplementation(async (path, options) => path.endsWith('/preview') ? preview : options?.method === 'POST' ? job : [])
  const view = render(<InvestigationPanel {...props} />)
  await prepare()
  fireEvent.click(screen.getByRole('button', { name: '검색 시작' }))
  expect(await screen.findByText(job.output)).toBeVisible()
  expect(view.container.querySelector('script')).toBeNull()
  expect(screen.getByText('검색 결과 · 답변으로 검증되지 않은 자료')).toBeVisible()
  const submissions = api.mock.calls.filter(([path, options]) => path.endsWith('/jobs') && options?.method === 'POST')
  expect(submissions).toHaveLength(1)
  expect(JSON.parse(submissions[0][1]!.body as string)).toEqual({ requestId: expect.any(String), bundleId: 'bundle', provider: 'CODEX', requestedModel: 'fixture', digest: 'digest', approved: true })
})

it('접수 결과를 잃어도 새로운 ID로 자동 재전송하지 않는다', async () => {
  let attempts = 0
  api.mockImplementation(async (path, options) => {
    if (path.endsWith('/preview')) return preview
    if (options?.method === 'POST') { if (++attempts === 1) throw new Error('연결 끊김'); return job }
    return []
  })
  render(<InvestigationPanel {...props} />)
  await prepare()
  fireEvent.click(screen.getByRole('button', { name: '검색 시작' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('연결 끊김')
  expect(attempts).toBe(1)
  fireEvent.click(screen.getByRole('button', { name: '접수 상태 확인' }))
  await screen.findByText('자료 검색 완료')
  const submissions = api.mock.calls.filter(([path, options]) => path.endsWith('/jobs') && options?.method === 'POST')
  expect(submissions[0][1]!.body).toBe(submissions[1][1]!.body)
})

it('기존 작업을 복원하되 새 미리보기나 AI 호출을 생성하지 않는다', async () => {
  api.mockResolvedValue([job])
  render(<InvestigationPanel {...props} />)
  fireEvent.click(screen.getByRole('button', { name: '자료 더 찾아보기' }))
  await screen.findByText('자료 검색 완료')
  expect(api).toHaveBeenCalledTimes(1)
  expect(screen.queryByRole('button', { name: '검색 시작' })).toBeNull()
})

it('다른 Workspace의 작업은 표시하지 않는다', async () => {
  api.mockResolvedValue([{ ...job, workspaceId: 'other' }])
  render(<InvestigationPanel {...props} />)
  fireEvent.click(screen.getByRole('button', { name: '자료 더 찾아보기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('조사 대상이 다릅니다')
  expect(screen.queryByText(job.output)).toBeNull()
})

it('검색 자료는 JSON 식별자 대신 행 범위와 발췌문으로 표시한다', async () => {
  api.mockResolvedValue([{ ...job, output: 'LOCAL_SEARCH_EXCERPTS\n' + JSON.stringify({ entryId: 'internal-id', content: 'Strategy 코드 발췌', startLine: 2, endLine: 5 }), truncated: true }])
  render(<InvestigationPanel {...props} />)
  fireEvent.click(screen.getByRole('button', { name: '자료 더 찾아보기' }))
  expect(await screen.findByText('Strategy 코드 발췌')).toBeVisible()
  expect(screen.getByText('자료 1 · 2–5행')).toBeVisible()
  expect(screen.getByText('검색 결과가 많아 일부만 확인했습니다. 질문의 범위를 좁혀 다시 찾아 주세요.')).toBeVisible()
  expect(screen.queryByText(/internal-id/)).toBeNull()
})

it('취소 버튼은 실행 중인 작업에만 명시적으로 요청한다', async () => {
  api.mockImplementation(async (_path, options) => options?.method === 'POST' ? { ...job, status: 'CANCELLED', output: null } : [{ ...job, status: 'RUNNING', output: null }])
  render(<InvestigationPanel {...props} />)
  fireEvent.click(screen.getByRole('button', { name: '자료 더 찾아보기' }))
  fireEvent.click(await screen.findByRole('button', { name: '취소' }))
  await screen.findByText('검색 취소됨')
  expect(api.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1)
  await waitFor(() => expect(screen.queryByRole('button', { name: '취소' })).toBeNull())
})

it('연결 선택이 바뀌어도 미리보기에서 고정한 제공자와 모델로만 접수한다', async () => {
  api.mockImplementation(async (path, options) => path.endsWith('/preview') ? preview : options?.method === 'POST' ? job : [])
  const view = render(<InvestigationPanel {...props} />)
  await prepare()
  view.rerender(<InvestigationPanel {...props} connection={{ provider: 'CLAUDE', state: 'AUTHENTICATED', model: 'changed' }} />)
  fireEvent.click(screen.getByRole('button', { name: '검색 시작' }))
  await screen.findByText('자료 검색 완료')
  const submitted = api.mock.calls.find(([path, options]) => path.endsWith('/jobs') && options?.method === 'POST')!
  expect(JSON.parse(submitted[1]!.body as string)).toMatchObject({ provider: 'CODEX', requestedModel: 'fixture', digest: 'digest' })
})

it('검색 준비나 AI 연결이 없으면 시작점을 잠근다', () => {
  const view = render(<InvestigationPanel {...props} disabled />)
  expect(screen.getByRole('button', { name: '자료 더 찾아보기' })).toBeDisabled()
  view.rerender(<InvestigationPanel {...props} connection={null} />)
  expect(screen.getByRole('button', { name: '자료 더 찾아보기' })).toBeDisabled()
  expect(api).not.toHaveBeenCalled()
})

it('확정된 만료 응답은 같은 실패 요청에 갇히지 않고 다시 준비할 수 있다', async () => {
  api.mockImplementation(async (path, options) => {
    if (path.endsWith('/preview')) return preview
    if (options?.method === 'POST') throw new ApiError('미리보기가 만료되었습니다.', 409)
    return []
  })
  render(<InvestigationPanel {...props} />)
  await prepare()
  fireEvent.click(screen.getByRole('button', { name: '검색 시작' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('만료')
  fireEvent.click(screen.getByRole('button', { name: '다시 확인' }))
  await screen.findByRole('button', { name: '검색 시작' })
  expect(api.mock.calls.filter(([path]) => path.endsWith('/preview'))).toHaveLength(2)
})

it('실패한 계획은 재실행하지 않고 명시적으로 새 미리보기를 준비한다', async () => {
  api.mockImplementation(async path => path.endsWith('/preview') ? preview : [{ ...job, status: 'FAILED', output: null }])
  render(<InvestigationPanel {...props} />)
  fireEvent.click(screen.getByRole('button', { name: '자료 더 찾아보기' }))
  fireEvent.click(await screen.findByRole('button', { name: '새 검색 계획 확인' }))
  await screen.findByRole('button', { name: '검색 시작' })
  expect(api.mock.calls.filter(([path, options]) => path.endsWith('/jobs') && options?.method === 'POST')).toHaveLength(0)
  expect(api.mock.calls.filter(([path]) => path.endsWith('/preview'))).toHaveLength(1)
})
