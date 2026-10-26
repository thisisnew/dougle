import { useEffect, useRef, useState } from 'react'
import { request } from './api'

export type QuestionAttachment = { id: string; name: string; mediaType: string; size: number; characters: number; truncated: boolean }
export const attachmentAccept = '.png,.jpg,.jpeg,.pdf,.docx,.txt,.md,.csv,.json,.java,.py,.js,.ts,.tsx,.jsx,.xml,.yaml,.yml,.log,.sql,.html,.css'

export function useQuestionAttachments(workspaceId: string) {
  const [items, setItems] = useState<QuestionAttachment[]>([])
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState('')
  const current = useRef<QuestionAttachment[]>([])
  const pending = useRef<AbortController | null>(null)
  const mounted = useRef(true)
  const base = `/workspaces/${encodeURIComponent(workspaceId)}/attachments`

  function discard(values: QuestionAttachment[]) {
    for (const item of values) void request(`${base}/${encodeURIComponent(item.id)}`, { method: 'DELETE' }).catch(() => {})
  }
  function reset(keep = false) {
    pending.current?.abort(); pending.current = null
    if (!keep) discard(current.current)
    current.current = []; setItems([]); setUploading(false); setError('')
  }
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; pending.current?.abort(); discard(current.current); current.current = [] }
  }, [base])

  async function add(files: File[]) {
    if (pending.current || !files.length) return
    if (current.current.length + files.length > 4) { setError('한 번에 최대 4개까지 첨부할 수 있어요.'); return }
    if (files.some(file => file.size === 0 || file.size > 5 * 1024 * 1024)) { setError('빈 파일은 제외하고, 각각 5 MiB 이하로 첨부해 주세요.'); return }
    if (current.current.reduce((sum, item) => sum + item.size, 0) + files.reduce((sum, file) => sum + file.size, 0) > 10 * 1024 * 1024) {
      setError('첨부 파일의 합계는 10 MiB 이하여야 해요.'); return
    }
    const controller = new AbortController()
    pending.current = controller; setUploading(true); setError('')
    try {
      for (const file of files) {
        const body = new FormData(); body.append('file', file)
        const item = await request<QuestionAttachment>(base, { method: 'POST', body, signal: controller.signal })
        if (controller.signal.aborted || !mounted.current || pending.current !== controller) { discard([item]); return }
        if (current.current.reduce((sum, value) => sum + value.size, 0) + item.size > 10 * 1024 * 1024) {
          discard([item]); throw new Error('이미지 처리 후 첨부 합계가 10 MiB를 초과했어요. 크기를 줄여 주세요.')
        }
        current.current = [...current.current, item]; setItems(current.current)
      }
    } catch (reason) { if (!controller.signal.aborted && mounted.current) setError((reason as Error).message) }
    finally { if (pending.current === controller) { pending.current = null; if (mounted.current) setUploading(false) } }
  }
  async function remove(id: string) {
    if (pending.current) return
    const controller = new AbortController()
    pending.current = controller; setUploading(true)
    try {
      await request(`${base}/${encodeURIComponent(id)}`, { method: 'DELETE', signal: controller.signal })
      if (!mounted.current || controller.signal.aborted || pending.current !== controller) return
      current.current = current.current.filter(item => item.id !== id); setItems(current.current); setError('')
    } catch (reason) { if (mounted.current && !controller.signal.aborted) setError((reason as Error).message) }
    finally { if (pending.current === controller) { pending.current = null; if (mounted.current) setUploading(false) } }
  }
  function adopt(item: QuestionAttachment) {
    if (pending.current || !mounted.current) return false
    if (!item.id || item.mediaType !== 'text/plain' || !Number.isSafeInteger(item.size) || item.size <= 0 || item.size > 5 * 1024 * 1024) {
      setError('조회 결과 첨부 정보를 확인하지 못했습니다.'); return false
    }
    if (current.current.some(value => value.id === item.id)) return true
    if (current.current.length >= 4 || current.current.reduce((sum, value) => sum + value.size, 0) + item.size > 10 * 1024 * 1024) {
      setError('첨부 한도를 초과했습니다. 기존 첨부를 제거한 뒤 다시 시도하세요.'); return false
    }
    current.current = [...current.current, item]; setItems(current.current); setError('')
    return true
  }
  function cancel() { pending.current?.abort(); pending.current = null; setUploading(false) }
  return { items, uploading, error, add, adopt, remove, reset, cancel }
}
