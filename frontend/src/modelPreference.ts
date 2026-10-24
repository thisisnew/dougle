import type { Provider } from './providerPreference'

export type ModelChoice = { mode: 'server' | 'cli' | 'custom'; model: string }
export const modelPresets: Record<Provider, { id: string; label: string }[]> = {
  CODEX: [
    { id: 'gpt-6.1-sol', label: 'GPT-6.1 Sol' },
    { id: 'gpt-6-luna', label: 'GPT-6 Luna' },
    { id: 'gpt-6-astra', label: 'GPT-6 Astra' },
  ],
  CLAUDE: [
    { id: 'sonnet', label: 'Sonnet' },
    { id: 'opus', label: 'Opus' },
    { id: 'haiku', label: 'Haiku' },
  ],
}
export function loadModel(provider: Provider): ModelChoice {
  try {
    const value = JSON.parse(localStorage.getItem(`forge.ai.model.${provider}`) || 'null')
    if (value?.mode === 'custom' && modelPresets[provider].some(item => item.id === value.model)) return value
  } catch {}
  return { mode: 'custom', model: modelPresets[provider][0].id }
}
export function saveModel(provider: Provider, choice: ModelChoice): boolean {
  try { localStorage.setItem(`forge.ai.model.${provider}`, JSON.stringify(choice)); return true } catch { return false }
}
export function requestedModel(choice: ModelChoice): string | null {
  return choice.mode === 'server' ? null : choice.mode === 'cli' ? '' : choice.model
}
