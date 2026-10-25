import { afterEach, expect, it, vi } from 'vitest'
import { openNotionConnections } from './notionBrowser'

afterEach(() => { Reflect.deleteProperty(window, '__TAURI_INTERNALS__'); vi.restoreAllMocks() })
it('설치 앱은 URL 인자 없는 고정 Notion 명령만 호출한다', async () => {
  const invoke = vi.fn().mockResolvedValue(undefined)
  Object.defineProperty(window, '__TAURI_INTERNALS__', { configurable: true, value: { invoke } })
  await openNotionConnections()
  expect(invoke).toHaveBeenCalledWith('open_notion_connections')
})
it('브라우저에서는 opener 없는 공식 연결 관리 창을 연다', async () => {
  const open = vi.spyOn(window, 'open').mockReturnValue(null)
  await openNotionConnections()
  expect(open).toHaveBeenCalledWith('https://www.notion.so/profile/integrations', '_blank', 'noopener,noreferrer')
})
