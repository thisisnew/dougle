import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it } from 'vitest'
import KnowledgeMarkdown from './KnowledgeMarkdown'

it('제목·목록·코드·인용·GFM 표와 읽기 전용 체크박스를 표시한다', () => {
  const { container } = render(<KnowledgeMarkdown markdown={'# 관측\n\n**중요** ~~취소~~\n\n- 항목\n- [x] 확인\n\n> 근거 [E1]\n\n```java\nint count = 1;\n```\n\n| 이름 | 값 |\n| --- | --- |\n| 관측 | 1 |'} />)
  expect(screen.getByRole('heading', { name: '관측' })).toBeInTheDocument()
  expect(screen.getByRole('table')).toHaveTextContent('관측')
  expect(screen.getByRole('checkbox')).toBeDisabled()
  expect(screen.getByRole('checkbox')).toBeChecked()
  expect(container.querySelector('blockquote')).toHaveTextContent('근거 [E1]')
  expect(container.querySelector('pre code')).toHaveTextContent('int count = 1;')
  expect(container.querySelector('strong')).toHaveTextContent('중요')
  expect(container.querySelector('del')).toHaveTextContent('취소')
})

it('원문 공백과 HTML을 보존하며 실행 가능한 요소를 만들지 않는다', async () => {
  const markdown = '# 제목\n\n<script>alert(1)</script>\n\n<img src="https://example.com/pixel" onerror="alert(1)">\n\n<iframe src="/api/session"></iframe>\n\n<svg onload="alert(1)"></svg>\n\n<style>body{display:none}</style>\n  끝  \n'
  const { container } = render(<KnowledgeMarkdown markdown={markdown} />)
  await userEvent.click(screen.getByText('Markdown 원문 보기'))
  expect(screen.getByLabelText('지식 Markdown').textContent).toBe(markdown)
  expect(container.querySelector('script, img, iframe, svg, style, [onerror], [onload]')).toBeNull()
})

it.each(['https://example.com', '/api/session', '//example.com', 'javascript:alert%281%29', 'java&#x73;cript:alert%281%29', 'data:text/html,test', 'file:///tmp/test', 'mailto:test@example.com', '#section'])('링크를 비활성 텍스트로 표시한다: %s', destination => {
  const { container } = render(<KnowledgeMarkdown markdown={`[표시 이름](${destination})`} />)
  expect(within(screen.getByLabelText('지식 Markdown 미리보기')).getByText('표시 이름')).toBeInTheDocument()
  expect(container.querySelector('a, [href], [src]')).toBeNull()
})

it('원격·로컬 이미지와 자동 링크도 외부 요청 요소를 만들지 않는다', () => {
  const { container } = render(<KnowledgeMarkdown markdown={'![추적](https://example.com/pixel)\n\n![로컬](/api/session)\n\nhttps://example.com\n\n각주[^1]\n\n[^1]: 설명'} />)
  expect(screen.getByText('[이미지 로딩 차단: 추적]')).toBeInTheDocument()
  expect(container.querySelector('a, img, [href], [src]')).toBeNull()
})
