import { beforeEach, expect, it } from 'vitest'
import { loadModel, saveModel } from './modelPreference'

beforeEach(() => localStorage.clear())

it.each(['server', 'cli', 'custom'])('이전 %s 설정도 화면에 표시 가능한 모델로 정리한다', mode => {
  localStorage.setItem('forge.ai.model.CODEX', JSON.stringify({ mode, model: 'unknown-model' }))
  expect(loadModel('CODEX')).toEqual({ mode: 'custom', model: 'gpt-6.1-sol' })
})

it('선택한 모델은 제공자별로 보존한다', () => {
  saveModel('CODEX', { mode: 'custom', model: 'gpt-6-luna' })
  expect(loadModel('CODEX').model).toBe('gpt-6-luna')
  expect(loadModel('CLAUDE').model).toBe('sonnet')
})
