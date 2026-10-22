declare global {
  interface Window { readonly __FORGE_DESKTOP_SESSION__?: string }
}

export function desktopSession(): string | undefined {
  const token = window.__FORGE_DESKTOP_SESSION__
  if (token === undefined) return undefined
  if (!/^[a-f\d]{64}$/i.test(token) || window.location.protocol !== 'http:'
    || !['127.0.0.1', '[::1]', 'localhost'].includes(window.location.hostname)) {
    throw new Error('데스크톱 세션을 확인할 수 없습니다.')
  }
  return token
}

export function apiFetch(path: string, options: RequestInit = {}): Promise<Response> {
  const url = new URL(`/api${path}`, window.location.origin)
  if (!path.startsWith('/') || path.startsWith('//') || /[\\\r\n\t#]/.test(path)
    || url.origin !== window.location.origin || !url.pathname.startsWith('/api/')) {
    throw new Error('앱 API 경로가 아닙니다.')
  }
  const headers = new Headers(options.headers)
  headers.delete('X-Forge-Session')
  const token = desktopSession()
  if (token) headers.set('X-Forge-Session', token)
  headers.set('X-Forge-Client', '1')
  return fetch(`/api${path}`, { ...options, headers, redirect: 'error', mode: 'same-origin' })
}
