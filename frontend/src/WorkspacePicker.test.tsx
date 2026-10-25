import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import WorkspacePicker from './WorkspacePicker'

const items = [{ id: 'one', name: '첫 번째 서재' }, { id: 'two', name: '긴기존이름'.repeat(20) }]

it('앱 목록을 아래에 열고 현재 선택과 긴 이름 원문을 보존한다', async () => {
  const changed = vi.fn()
  render(<WorkspacePicker items={items} value="one" disabled={false} onChange={changed} />)
  const trigger = screen.getByRole('combobox', { name: 'Workspace 선택' })
  expect(document.querySelector('select')).toBeNull()
  await userEvent.click(trigger)
  expect(trigger).toHaveAttribute('aria-expanded', 'true')
  expect(screen.getByRole('option', { name: items[0].name })).toHaveAttribute('aria-selected', 'true')
  expect(screen.getByRole('option', { name: items[1].name })).toHaveAttribute('title', items[1].name)
  await userEvent.click(screen.getByRole('option', { name: items[1].name }))
  expect(changed).toHaveBeenCalledExactlyOnceWith('two')
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  expect(trigger).toHaveFocus()
})

it('방향키와 Enter로 선택하고 Escape와 바깥 클릭은 변경 없이 닫는다', async () => {
  const changed = vi.fn()
  render(<WorkspacePicker items={items} value="one" disabled={false} onChange={changed} />)
  const trigger = screen.getByRole('combobox')
  trigger.focus()
  await userEvent.keyboard('{ArrowDown}{End}{Enter}')
  expect(changed).toHaveBeenCalledExactlyOnceWith('two')
  await userEvent.click(trigger)
  await userEvent.keyboard('{Escape}')
  expect(trigger).toHaveAttribute('aria-expanded', 'false')
  await userEvent.click(trigger)
  fireEvent.pointerDown(document.body)
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  expect(changed).toHaveBeenCalledOnce()
})

it('현재 Workspace 재선택은 요청하지 않고 작업 중이나 빈 목록은 잠근다', async () => {
  const changed = vi.fn()
  const view = render(<WorkspacePicker items={items} value="one" disabled={false} onChange={changed} />)
  await userEvent.click(screen.getByRole('combobox'))
  await userEvent.click(screen.getByRole('option', { name: items[0].name }))
  expect(changed).not.toHaveBeenCalled()
  await userEvent.click(screen.getByRole('combobox'))
  view.rerender(<WorkspacePicker items={items} value="one" disabled onChange={changed} />)
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  expect(screen.getByRole('combobox')).toBeDisabled()
  view.rerender(<WorkspacePicker items={[]} value={null} disabled={false} onChange={changed} />)
  expect(screen.getByRole('combobox')).toBeDisabled()
})
