import type { Provider } from './providerPreference'

export type ModelChoice = { mode: 'server' | 'cli' | 'custom'; model: string }
export const modelPresets: Record<Provider, { id: string; label: string }[]> = {
  CODEX: [
    { id: 'gpt-6.1-sol', label: 'GPT-6.1 Sol · 문서와 코드 분석' },
    { id: 'gpt-6-luna', label: 'GPT-6 Luna · 빠른 요약과 정리' },
    { id: 'gpt-6-astra', label: 'GPT-6 Astra · 복잡한 추론' },
  ],
  CLAUDE: [
    { id: 'sonnet', label: 'Sonnet · 일상 작업' },
    { id: 'opus', label: 'Opus · 복잡한 추론' },
    { id: 'haiku', label: 'Haiku · 빠르고 가벼운 작업' },
  ],
}
export function loadModel(provider: Provider): ModelChoice {
  try {
    const value = JSON.parse(localStorage.getItem(`forge.ai.model.${provider}`) || 'null')
    if (value && ['server', 'cli', 'custom'].includes(value.mode) && typeof value.model === 'string'
      && (value.mode !== 'custom' || /^[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,127}$/.test(value.model))) return value
  } catch {}
  return { mode: 'server', model: '' }
}
export function saveModel(provider: Provider, choice: ModelChoice): boolean {
  try { localStorage.setItem(`forge.ai.model.${provider}`, JSON.stringify(choice)); return true } catch { return false }
}
export function requestedModel(choice: ModelChoice): string | null {
  return choice.mode === 'server' ? null : choice.mode === 'cli' ? '' : choice.model
}
