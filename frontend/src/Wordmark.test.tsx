import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import Wordmark from './Wordmark'

it('여섯 글자의 장식을 하나의 읽을 수 있는 브랜드로 제공한다', () => {
  render(<Wordmark />)
  const wordmark = screen.getByRole('img', { name: 'Dougle' })
  expect(wordmark).toHaveTextContent('Dougle')
  expect(wordmark.querySelectorAll('[aria-hidden="true"]')).toHaveLength(6)
})

it('캐릭터는 o만 대체하고 접근성 이름과 나머지 글자는 유지한다', () => {
  render(<Wordmark mascot />)
  const wordmark = screen.getByRole('img', { name: 'Dougle' })
  expect(wordmark).toHaveTextContent('Dougle')
  expect(wordmark.children[1]).toHaveClass('wordmark-face')
  expect(wordmark.querySelectorAll('img')).toHaveLength(1)
  expect(wordmark.children[5]).toHaveTextContent('e')
})
