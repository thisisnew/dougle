import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import SourcePollingSettings from './SourcePollingSettings'

const source = { name: '가상 자료', pollingEnabled: false, pollIntervalSeconds: 3600, pollingRevision: 3, nextPollAt: null, consecutiveFailures: 0 }

it('체크만으로는 저장하지 않고 명시적 저장에 revision을 보낸다', async () => {
  const save = vi.fn()
  render(<SourcePollingSettings source={source} busy={false} onSave={save} />)
  await userEvent.click(screen.getByRole('checkbox'))
  expect(save).not.toHaveBeenCalled()
  const interval = screen.getByRole('spinbutton')
  await userEvent.clear(interval)
  await userEvent.type(interval, '120')
  await userEvent.click(screen.getByRole('button', { name: '갱신 설정 저장' }))
  expect(save).toHaveBeenCalledWith({ enabled: true, intervalSeconds: 120, expectedRevision: 3 })
})

it('범위 밖 주기를 막고 저장된 설정이 바뀌면 새 revision을 표시한다', async () => {
  const save = vi.fn()
  const view = render(<SourcePollingSettings source={source} busy={false} onSave={save} />)
  await userEvent.clear(screen.getByRole('spinbutton'))
  await userEvent.type(screen.getByRole('spinbutton'), '59')
  expect(screen.getByRole('button')).toBeDisabled()
  view.rerender(<SourcePollingSettings source={{ ...source, pollingEnabled: true, pollIntervalSeconds: 120, pollingRevision: 4, consecutiveFailures: 2, nextPollAt: 1800000000000 }} busy={false} onSave={save} />)
  expect(screen.getByRole('checkbox')).toBeChecked()
  expect(screen.getByRole('spinbutton')).toHaveValue(120)
  expect(screen.getByText(/연속 실패 2회/)).toBeInTheDocument()
  await userEvent.click(screen.getByRole('checkbox'))
  await userEvent.click(screen.getByRole('button'))
  expect(save).toHaveBeenCalledWith({ enabled: false, intervalSeconds: 120, expectedRevision: 4 })
})
