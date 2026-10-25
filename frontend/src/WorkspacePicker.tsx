import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { Check, ChevronDown } from 'lucide-react'

type Workspace = { id: string; name: string }

export default function WorkspacePicker({ items, value, disabled, onChange }: {
  items: Workspace[]; value: string | null; disabled: boolean; onChange: (id: string) => void
}) {
  const [open, setOpen] = useState(false)
  const [highlighted, setHighlighted] = useState(0)
  const root = useRef<HTMLDivElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const listId = useId()
  const selected = items.find(item => item.id === value)
  const unavailable = disabled || !items.length
  const expanded = open && !unavailable
  useEffect(() => {
    if (unavailable) setOpen(false)
  }, [unavailable])
  useEffect(() => {
    if (!expanded) return
    const closeOutside = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('pointerdown', closeOutside)
    return () => document.removeEventListener('pointerdown', closeOutside)
  }, [expanded])
  useEffect(() => {
    if (expanded) document.getElementById(`${listId}-${highlighted}`)?.scrollIntoView?.({ block: 'nearest' })
  }, [expanded, highlighted, listId])
  function show() {
    setHighlighted(Math.max(0, items.findIndex(item => item.id === value)))
    setOpen(true)
  }
  function choose(index: number) {
    const item = items[index]
    if (!item || unavailable) return
    setOpen(false)
    trigger.current?.focus()
    if (item.id !== value) onChange(item.id)
  }
  function keyDown(event: KeyboardEvent<HTMLButtonElement>) {
    if (event.key === 'Escape' || event.key === 'Tab') {
      setOpen(false)
      if (event.key === 'Escape' && expanded) event.preventDefault()
      return
    }
    if (['ArrowDown', 'ArrowUp', 'Home', 'End', 'Enter', ' '].includes(event.key)) {
      event.preventDefault()
      if (!expanded) { show(); return }
      if (event.key === 'Enter' || event.key === ' ') { choose(highlighted); return }
      setHighlighted(previous => event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1
        : (previous + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length)
    } else if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
      const next = items.findIndex(item => item.name.toLocaleLowerCase().startsWith(event.key.toLocaleLowerCase()))
      if (next >= 0) { setOpen(true); setHighlighted(next) }
    }
  }
  return <div className="workspace-picker" ref={root} onBlur={event => {
    if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false)
  }}>
    <button ref={trigger} type="button" role="combobox" aria-label="Workspace 선택" aria-haspopup="listbox"
      aria-expanded={expanded} aria-controls={expanded ? listId : undefined}
      aria-activedescendant={expanded ? `${listId}-${highlighted}` : undefined}
      className="workspace-picker-trigger" disabled={unavailable} title={selected?.name}
      onKeyDown={keyDown} onClick={() => expanded ? setOpen(false) : show()}>
      <span>{selected?.name ?? 'Workspace를 만들어 주세요'}</span><ChevronDown size={16} aria-hidden="true" />
    </button>
    {expanded && <ul id={listId} className="workspace-picker-list" role="listbox" aria-label="Workspace 목록">
      {items.map((item, index) => <li key={item.id} id={`${listId}-${index}`} role="option" aria-selected={item.id === value}
        className={highlighted === index ? 'highlighted' : undefined} title={item.name}
        onPointerMove={() => setHighlighted(index)} onMouseDown={event => event.preventDefault()} onClick={() => choose(index)}>
        <span>{item.name}</span>{item.id === value && <Check size={16} aria-hidden="true" />}
      </li>)}
    </ul>}
  </div>
}
