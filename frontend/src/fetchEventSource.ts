import { apiFetch } from './desktopTransport'

export function fetchEventSource(path: string) {
  const controller = new AbortController()
  const listeners = new Map<string, (event: MessageEvent) => void>()
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined
  const source = {
    onerror: null as (() => void) | null,
    addEventListener(name: string, handler: (event: MessageEvent) => void) { listeners.set(name, handler) },
    close() { controller.abort(); void reader?.cancel().catch(() => {}) },
  }
  async function read() {
    try {
      const response = await apiFetch(path, { signal: controller.signal, headers: { Accept: 'text/event-stream' } })
      if (controller.signal.aborted) { await response.body?.cancel(); return }
      if (!response.ok || response.headers.get('Content-Type')?.split(';')[0].trim().toLowerCase() !== 'text/event-stream'
        || !response.body) {
        await response.body?.cancel()
        throw new Error('SSE response unavailable')
      }
      reader = response.body.getReader()
      const decoder = new TextDecoder()
      let line = ''
      let carriageReturn = false
      let name = ''
      let data: string[] = []
      let size = 0
      function consumeLine() {
        if (line === '') {
          if (data.length) listeners.get(name || 'message')?.(new MessageEvent(name || 'message', { data: data.join('\n') }))
          name = ''; data = []; size = 0
        } else if (!line.startsWith(':')) {
          const colon = line.indexOf(':')
          const field = colon < 0 ? line : line.slice(0, colon)
          const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
          if (field === 'event') name = value
          if (field === 'data') data.push(value)
        }
        line = ''
      }
      while (!controller.signal.aborted) {
        const chunk = await reader.read()
        if (chunk.done) break
        for (const character of decoder.decode(chunk.value, { stream: true })) {
          if (controller.signal.aborted) return
          if (carriageReturn && character === '\n') { carriageReturn = false; continue }
          carriageReturn = character === '\r'
          if (++size > 4 * 1024 * 1024) throw new Error('SSE event too large')
          if (character === '\r' || character === '\n') consumeLine()
          else line += character
        }
      }
      if (!controller.signal.aborted) source.onerror?.()
    } catch {
      if (!controller.signal.aborted) source.onerror?.()
    } finally {
      await reader?.cancel().catch(() => {})
      reader?.releaseLock()
    }
  }
  void read()
  return source
}
