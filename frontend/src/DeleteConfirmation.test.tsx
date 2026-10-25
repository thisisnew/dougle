import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import DeleteConfirmation from './DeleteConfirmation'

it('취소에 초점을 두고 대상과 삭제 실패를 같은 창에 표시한다', async () => {
  const cancel = vi.fn()
  const confirm = vi.fn()
  render(<DeleteConfirmation name="synthetic.txt" busy={false} error="삭제 실패" onCancel={cancel} onConfirm={confirm} />)
  expect(screen.getByRole('dialog', { name: '삭제하시겠습니까?' })).toHaveTextContent('synthetic.txt')
  expect(screen.getByRole('button', { name: '취소' })).toHaveFocus()
  expect(screen.getByRole('alert')).toHaveTextContent('삭제 실패')
  await userEvent.click(screen.getByRole('button', { name: '삭제' }))
  expect(confirm).toHaveBeenCalledOnce()
  await userEvent.keyboard('{Escape}')
  expect(cancel).toHaveBeenCalledOnce()
})

it('긴 대상은 말줄임 영역에 두고 원문은 툴팁으로 보존한다', () => {
  const name = '공백없는아주긴가상질문😀'.repeat(100)
  render(<DeleteConfirmation name={name} busy={false} error="" onCancel={vi.fn()} onConfirm={vi.fn()} />)
  const target = screen.getByTitle(name)
  expect(target).toHaveClass('delete-target')
  expect(target).toHaveTextContent(name)
  expect(screen.getByRole('button', { name: '삭제' })).toBeVisible()
})

it('삭제 중에는 중복 요청과 Esc 닫기를 막는다', async () => {
  const cancel = vi.fn()
  const confirm = vi.fn()
  render(<DeleteConfirmation name="synthetic" busy error="" onCancel={cancel} onConfirm={confirm} />)
  expect(screen.getByRole('button', { name: '취소' })).toBeDisabled()
  await userEvent.click(screen.getByRole('button', { name: '삭제 중…' }))
  await userEvent.keyboard('{Escape}')
  expect(confirm).not.toHaveBeenCalled()
  expect(cancel).not.toHaveBeenCalled()
})
