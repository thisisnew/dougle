import { useEffect, useState, type ReactNode } from 'react'

export default function StreamingAnswer({ text, animate, children }: { text: string; animate: boolean; children?: ReactNode }) {
  const [visible, setVisible] = useState(animate ? '' : text)
  useEffect(() => {
    if (!animate || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) { setVisible(text); return }
    setVisible(previous => text.startsWith(previous) ? previous : '')
    const timer = window.setInterval(() => {
      setVisible(previous => {
        if (previous === text) { window.clearInterval(timer); return previous }
        const prefix = text.startsWith(previous) ? previous : ''
        const remaining = Array.from(text.slice(prefix.length))
        return prefix + remaining.slice(0, Math.max(1, Math.ceil(remaining.length / 24))).join('')
      })
    }, 16)
    return () => window.clearInterval(timer)
  }, [text, animate])
  return <><pre className="chat-answer-text" aria-label="AI 답변">{text.startsWith(visible) ? visible : ''}</pre>{visible === text && children}</>
}
