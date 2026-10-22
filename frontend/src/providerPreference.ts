export type Provider = 'CODEX' | 'CLAUDE'
const key = 'forge.ai.provider'

export function preferredProvider(): Provider {
  try { return localStorage.getItem(key) === 'CLAUDE' ? 'CLAUDE' : 'CODEX' }
  catch { return 'CODEX' }
}

export function rememberProvider(provider: Provider): boolean {
  try { localStorage.setItem(key, provider); return true }
  catch { return false }
}
