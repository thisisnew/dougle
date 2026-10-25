import { act, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import StreamingAnswer from './StreamingAnswer'

afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

it('참고 자료는 글자 표시가 모두 끝난 뒤 나타나고 새 텍스트를 받으면 다시 숨긴다', () => {
  vi.useFakeTimers()
  const view = render(<StreamingAnswer text="완료 답변" animate><section>참고 자료</section></StreamingAnswer>)
  expect(screen.queryByText('참고 자료')).not.toBeInTheDocument()
  act(() => vi.advanceTimersByTime(200))
  expect(screen.getByText('참고 자료')).toBeVisible()
  view.rerender(<StreamingAnswer text="완료 답변 추가" animate><section>참고 자료</section></StreamingAnswer>)
  expect(screen.queryByText('참고 자료')).not.toBeInTheDocument()
  act(() => vi.advanceTimersByTime(200))
  expect(screen.getByText('참고 자료')).toBeVisible()
})

it('새 글을 조금씩 표시하고 한글과 이모지를 보존한다', () => {
  vi.useFakeTimers()
  const view = render(<StreamingAnswer text="가나다🙂마바사" animate />)
  expect(screen.getByLabelText('AI 답변')).toHaveTextContent('')
  act(() => vi.advanceTimersByTime(16))
  expect(screen.getByLabelText('AI 답변').textContent).toBe('가')
  act(() => vi.advanceTimersByTime(160))
  expect(screen.getByLabelText('AI 답변').textContent).toBe('가나다🙂마바사')
  view.rerender(<StreamingAnswer text="가나다🙂마바사 새 내용" animate />)
  act(() => vi.advanceTimersByTime(160))
  expect(screen.getByLabelText('AI 답변').textContent).toBe('가나다🙂마바사 새 내용')
  view.rerender(<StreamingAnswer text="" animate />)
  expect(screen.getByLabelText('AI 답변').textContent).toBe('')
  view.unmount()
  expect(vi.getTimerCount()).toBe(0)
})

it('저장된 답변과 모션 감소 설정은 지연 없이 표시한다', () => {
  const view = render(<StreamingAnswer text="저장된 답변" animate={false} />)
  expect(screen.getByLabelText('AI 답변')).toHaveTextContent('저장된 답변')
  vi.stubGlobal('matchMedia', () => ({ matches: true }))
  view.rerender(<StreamingAnswer text="새 답변" animate />)
  expect(screen.getByLabelText('AI 답변')).toHaveTextContent('새 답변')
})
