import { useEffect, useRef } from 'react'
import type { ConnectionIndicator } from './connectionIndicator'

const preferenceKey = 'dougle.ai-setup.presented.v1'

export function useFirstRunAiSetup(connection: ConnectionIndicator | null, ready: boolean, open: (value: boolean) => void) {
  const presented = useRef(false)
  useEffect(() => {
    if (!ready || presented.current || !connection || !['AUTHENTICATED', 'LOGIN_REQUIRED', 'TOOL_UNAVAILABLE'].includes(connection.state)) return
    presented.current = true
    try {
      if (localStorage.getItem(preferenceKey) === 'true') return
      localStorage.setItem(preferenceKey, 'true')
    } catch {}
    if (connection.state !== 'AUTHENTICATED') open(true)
  }, [connection, ready, open])
}
