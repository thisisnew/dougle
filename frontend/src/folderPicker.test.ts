import { afterEach, expect, it, vi } from 'vitest'
import { hasFolderPicker, selectFolder } from './folderPicker'

afterEach(() => { Reflect.deleteProperty(window, '__TAURI_INTERNALS__') })

it('브라우저에서는 경로 직접 입력이나 파일 선택으로 우회하지 않는다', async () => {
  expect(hasFolderPicker()).toBe(false)
  await expect(selectFolder()).rejects.toThrow('설치한 CLUE 앱')
})

it('인자 없는 폴더 전용 명령만 호출하고 취소를 보존한다', async () => {
  const invoke = vi.fn().mockResolvedValueOnce('/synthetic').mockResolvedValueOnce(null)
  Object.defineProperty(window, '__TAURI_INTERNALS__', { configurable: true, value: { invoke } })
  expect(hasFolderPicker()).toBe(true)
  await expect(selectFolder()).resolves.toBe('/synthetic')
  await expect(selectFolder()).resolves.toBeNull()
  expect(invoke).toHaveBeenNthCalledWith(1, 'select_source_folder')
})
