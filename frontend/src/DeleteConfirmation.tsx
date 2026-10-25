import * as Dialog from '@radix-ui/react-dialog'
import { useRef } from 'react'
import { Button } from './components/ui/button'

export default function DeleteConfirmation({ name, busy, error, onCancel, onConfirm }: { name: string; busy: boolean; error: string; onCancel: () => void; onConfirm: () => void }) {
  const cancel = useRef<HTMLButtonElement>(null)
  return <Dialog.Root open onOpenChange={open => { if (!open && !busy) onCancel() }}><Dialog.Portal><Dialog.Overlay className="dialog-overlay" /><Dialog.Content className="dialog-content delete-confirmation" onOpenAutoFocus={event => { event.preventDefault(); cancel.current?.focus() }} onEscapeKeyDown={event => { if (busy) event.preventDefault() }} onPointerDownOutside={event => { if (busy) event.preventDefault() }}>
    <Dialog.Title>삭제하시겠습니까?</Dialog.Title>
    <Dialog.Description className="delete-target" title={name}>{name}</Dialog.Description>
    {error && <p className="modal-error" role="alert">{error}</p>}
    <div className="dialog-actions"><Button ref={cancel} variant="outline" disabled={busy} onClick={onCancel}>취소</Button><Button variant="destructive" disabled={busy} onClick={onConfirm}>{busy ? '삭제 중…' : '삭제'}</Button></div>
  </Dialog.Content></Dialog.Portal></Dialog.Root>
}
