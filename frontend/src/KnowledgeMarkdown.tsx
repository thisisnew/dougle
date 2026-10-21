import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

export default function KnowledgeMarkdown({ markdown }: { markdown: string }) {
  return <section aria-label="지식 문서 보기">
    <p className="source-hint">읽기용 표시입니다. HTML은 실행하지 않으며 링크 이동·이미지 로딩은 차단합니다. 원문은 아래에서 확인하세요.</p>
    <div className="markdown-preview" aria-label="지식 Markdown 미리보기">
      <Markdown remarkPlugins={[remarkGfm]} components={{
        a: ({ children, href }) => <span className="markdown-link">{children}{href && <small> (이동 차단: {href})</small>}</span>,
        img: ({ alt }) => <span className="markdown-image">[이미지 로딩 차단: {alt || '설명 없음'}]</span>,
        table: ({ children }) => <div className="markdown-table"><table>{children}</table></div>,
      }}>{markdown}</Markdown>
    </div>
    <details><summary>Markdown 원문 보기</summary><pre aria-label="지식 Markdown">{markdown}</pre></details>
  </section>
}
