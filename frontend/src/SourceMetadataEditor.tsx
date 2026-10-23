import { useState } from 'react'
import { Button } from './components/ui/button'

type Metadata = { name: string; domain: string; expectedRevision: number }

export default function SourceMetadataEditor({ source, busy, onSave, onClose }: {
  source: { name: string; domain: string; pollingRevision: number; status: string }
  busy: boolean; onSave: (metadata: Metadata) => Promise<boolean>; onClose: () => void
}) {
  const [name, setName] = useState(source.name)
  const [domain, setDomain] = useState(source.domain)
  const [expectedRevision] = useState(source.pollingRevision)
  const stale = expectedRevision !== source.pollingRevision
  return <form className="source-form" aria-label="자료 이름과 분류 편집" onSubmit={async event => {
    event.preventDefault()
    if (!busy && !stale && source.status !== 'SYNCING' && name.trim() && await onSave({ name, domain, expectedRevision })) onClose()
  }}>
    <label>이름<input aria-label="자료 이름 수정" required maxLength={120} value={name} disabled={busy} onChange={event => setName(event.target.value)} /></label>
    <label>도메인 (분류)<input aria-label="자료 도메인 수정" maxLength={80} value={domain} disabled={busy} onChange={event => setDomain(event.target.value)} /></label>
    <small>원본과 지식은 유지됩니다. 수집 위치는 바뀌지 않습니다.</small>
    {stale && <p role="status">다른 설정이 저장되었습니다. 편집을 닫고 다시 열어 주세요. 입력한 내용은 아직 저장되지 않았습니다.</p>}
    <Button type="button" size="sm" variant="outline" disabled={busy} onClick={onClose}>편집 취소</Button>
    <Button type="submit" size="sm" disabled={busy || stale || source.status === 'SYNCING' || !name.trim()}>변경 저장</Button>
  </form>
}
