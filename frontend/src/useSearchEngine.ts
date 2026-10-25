import { useEffect, useState } from 'react'
import { request } from './api'

export function useSearchEngine(enabled: boolean) {
  const [state, setState] = useState('STARTING')
  useEffect(() => {
    if (!enabled) return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    async function poll() {
      try {
        const value = await request<{ state: string }>('/search-model', { signal: controller.signal })
        if (!controller.signal.aborted) setState(typeof value?.state === 'string' ? value.state : 'ERROR')
      } catch { if (!controller.signal.aborted) setState('ERROR') }
      finally { if (!controller.signal.aborted) timer = setTimeout(() => void poll(), 2000) }
    }
    void poll()
    return () => { controller.abort(); clearTimeout(timer) }
  }, [enabled])
  return state
}
