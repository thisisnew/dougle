export type Workspace = { id: string; name: string; createdAt: string }
export type Area = 'ORIGINAL' | 'KNOWLEDGE'
export type Entry = {
  id: string; workspaceId: string; parentId: string | null; name: string
  kind: 'FILE' | 'FOLDER'; area: Area; size: number; createdAt: string
}
export type Session = { workspaces: Workspace[]; activeWorkspaceId: string | null }
export type Preview = { text: string | null; supported: boolean; reason: string | null }
export type EvidenceLocation = { entry: Entry; ancestors: Entry[]; freshness: 'CURRENT' | 'STALE' }

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  headers.set('X-Forge-Client', '1')
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  const response = await fetch(`/api${path}`, { ...options, headers })
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}))
    throw new Error(problem.detail || `요청을 완료하지 못했습니다. (${response.status})`)
  }
  return response.status === 204 ? undefined as T : response.json()
}

export function entryPath(workspaceId: string, entryId?: string) {
  return `/workspaces/${encodeURIComponent(workspaceId)}/entries${entryId ? `/${encodeURIComponent(entryId)}` : ''}`
}
