import { apiFetch } from './desktopTransport'

export type Workspace = { id: string; name: string; createdAt: string }
export type Area = 'ORIGINAL' | 'KNOWLEDGE'
export type Entry = {
  id: string; workspaceId: string; parentId: string | null; name: string
  kind: 'FILE' | 'FOLDER'; area: Area; size: number; createdAt: string
}
export type Session = { workspaces: Workspace[]; activeWorkspaceId: string | null }
export type Preview = { text: string | null; supported: boolean; reason: string | null }
export type EvidenceLocation = { entry: Entry; ancestors: Entry[]; freshness: 'CURRENT' | 'STALE' }

export class ApiError extends Error {
  readonly code?: string
  readonly expectedThreadId?: string
  constructor(message: string, readonly status: number, code?: unknown, expectedThreadId?: unknown) {
    super(message)
    this.name = 'ApiError'
    if (typeof code === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(code)) this.code = code
    if (status === 409 && this.code === 'SLACK_THREAD_MISSING' && typeof expectedThreadId === 'string'
      && /^[A-Za-z0-9_-]{1,128}$/.test(expectedThreadId)) this.expectedThreadId = expectedThreadId
  }
}

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  headers.set('X-Forge-Client', '1')
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  const response = await apiFetch(path, { ...options, headers })
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}))
    throw new ApiError(typeof problem?.detail === 'string' && problem.detail ? problem.detail : `요청을 완료하지 못했습니다. (${response.status})`,
      response.status, problem?.code, problem?.expectedThreadId)
  }
  return response.status === 204 ? undefined as T : response.json()
}

export function entryPath(workspaceId: string, entryId?: string) {
  return `/workspaces/${encodeURIComponent(workspaceId)}/entries${entryId ? `/${encodeURIComponent(entryId)}` : ''}`
}
