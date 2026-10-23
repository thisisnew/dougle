import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import SourceMetadataEditor from './SourceMetadataEditor'

const source = { name: '가상 자료', domain: '연구', pollingRevision: 3, status: 'READY' }

it('명시적 저장에 편집 시작 버전을 보내고 성공한 경우에만 닫는다', async () => {
  const save = vi.fn().mockResolvedValueOnce(false).mockResolvedValueOnce(true)
  const close = vi.fn()
  render(<SourceMetadataEditor source={source} busy={false} onSave={save} onClose={close} />)
  await userEvent.clear(screen.getByLabelText('자료 이름 수정'))
  await userEvent.type(screen.getByLabelText('자료 이름 수정'), '수정 이름')
  expect(save).not.toHaveBeenCalled()
  await userEvent.click(screen.getByRole('button', { name: '변경 저장' }))
  expect(save).toHaveBeenCalledWith({ name: '수정 이름', domain: '연구', expectedRevision: 3 })
  expect(close).not.toHaveBeenCalled()
  expect(screen.getByLabelText('자료 이름 수정')).toHaveValue('수정 이름')
  await userEvent.click(screen.getByRole('button', { name: '변경 저장' }))
  expect(close).toHaveBeenCalledOnce()
})

it('다른 설정 변경을 감지해 저장을 막되 입력 내용은 지우지 않는다', async () => {
  const save = vi.fn()
  const close = vi.fn()
  const view = render(<SourceMetadataEditor source={source} busy={false} onSave={save} onClose={close} />)
  await userEvent.type(screen.getByLabelText('자료 이름 수정'), ' 메모')
  view.rerender(<SourceMetadataEditor source={{ ...source, name: '다른 이름', pollingRevision: 4 }} busy={false} onSave={save} onClose={close} />)
  expect(screen.getByLabelText('자료 이름 수정')).toHaveValue('가상 자료 메모')
  expect(screen.getByRole('button', { name: '변경 저장' })).toBeDisabled()
  expect(screen.getByRole('status')).toHaveTextContent('다른 설정이 저장되었습니다')
  await userEvent.click(screen.getByRole('button', { name: '편집 취소' }))
  expect(close).toHaveBeenCalledOnce()
  expect(save).not.toHaveBeenCalled()
})

it('수집 중과 빈 이름은 저장할 수 없다', async () => {
  const save = vi.fn()
  const view = render(<SourceMetadataEditor source={{ ...source, status: 'SYNCING' }} busy={false} onSave={save} onClose={vi.fn()} />)
  expect(screen.getByRole('button', { name: '변경 저장' })).toBeDisabled()
  view.rerender(<SourceMetadataEditor source={source} busy={false} onSave={save} onClose={vi.fn()} />)
  await userEvent.clear(screen.getByLabelText('자료 이름 수정'))
  expect(screen.getByRole('button', { name: '변경 저장' })).toBeDisabled()
  expect(save).not.toHaveBeenCalled()
})
