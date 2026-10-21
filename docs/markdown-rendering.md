# 안전한 지식 Markdown 표시 — P3-4

이 문서의 audit 4개 경고는 당시 기록입니다. 후속 도구 갱신으로 0개가 되었습니다. [최신 결과](toolchain-report.md).

검증일: 2026-10-21. 원본/지식 폴더와 사람 검토·게시 위에 추가한 표시 기능입니다.
Phase 3 전체 완료가 아니며 API와 SQLite 스키마 v8은 그대로입니다.

## 사용 방법

1. 지식 영역에서 카드를 선택합니다. 제목, 강조, 목록, 인용, 코드, GFM 표·취소선·체크리스트를 표시합니다.
2. **Markdown 원문 보기**를 펼쳐 저장된 원문을 확인합니다. 공백·줄바꿈·HTML 문자열을 그대로 보존합니다.
3. 최신 버전의 **초안 편집**은 원문을 편집하며 새 불변 버전을 저장합니다. 이전 버전은 읽기 전용입니다.
4. 근거 원문과 당시 파일 다운로드, 현재 원본 탐색, 사람 검토/게시 기능은 기존 경로를 유지합니다.

## 설계와 보안 경계

`KnowledgePanel` → `KnowledgeMarkdown` → react-markdown 10.1.0 + remark-gfm 4.0.1.
본문은 서버 저장 값 그대로 전달합니다. 렌더링 결과를 저장하거나 승인 digest 계산에 사용하지 않습니다.

- Markdown AST를 React 요소로 표시합니다. `dangerouslySetInnerHTML`, raw HTML 플러그인, MDX는 사용하지 않습니다.
- 본문 HTML은 실행하지 않고 텍스트로 표시합니다. 코드 블록 역시 텍스트이며 실행 기능은 없습니다.
- **모든 링크는 비활성 텍스트**입니다. 외부 HTTP(S), 상대 API 경로, 프로토콜 상대 URL, 파일,
  이메일, 각주/앵커를 포함해 클릭 이동을 만들지 않습니다. 안전한 URL 변환 후 남은 주소만 텍스트에 표시합니다.
  차단된 URL을 정확히 확인할 때는 원문을 사용합니다.
- **이미지는 대체 설명만 표시**하며 `<img>`/`src`를 만들지 않습니다. 원격 추적 이미지와 로컬 API 요청을 방지합니다.
- GFM 체크박스는 비활성 읽기 전용입니다. 표와 코드는 가로 스크롤하고 CSS는 미리보기 범위에 한정합니다.
- 이 정책은 Markdown 본문에 적용합니다. 앱이 제공하는 근거 다운로드/현재 원본 이동 버튼은 별도 기존 기능입니다.
- 내용의 진실성이나 인용의 의미를 자동 검증하지 않습니다. 표시 성공은 검토/게시 승인이 아닙니다.

공식 API 참고: [react-markdown](https://github.com/remarkjs/react-markdown),
[remark-gfm](https://github.com/remarkjs/remark-gfm).

## 실제 검증

환경: macOS Intel, Corretto Java 21.0.10, Node 24.2.0, npm 11.3.0.

| 명령 | 결과 |
|---|---|
| `npm --prefix frontend test -- --maxWorkers=2` | 11파일, 60개 통과 |
| `npm --prefix frontend run build` | TypeScript/Vite 통과; JS 462.09 kB, gzip 143.76 kB |
| `bash scripts/maven.sh clean verify` | 125개 통과, 실제 CLI opt-in 1개 skip; UI 포함 JAR 빌드 성공 |
| `node scripts/smoke.mjs` | 패키지 UI·재시작·검색·지식/근거/폴더·게시 영속성·승인 차단 통과 |
| `npm audit --omit=dev --json` (frontend) | production 의존성 경고 0개 |
| `npm audit --json` (frontend) | 개발 도구 의존성 경고 4개, 아래 미해결 사항 참조 |

추가한 `KnowledgeMarkdown.test.tsx` 12개는 GFM 표시, 원문 보존,
script/img/iframe/svg/style 및 이벤트 속성 비생성, 위험/로컬/인코딩 URL 비활성,
원격 이미지·자동 링크·각주 비활성을 검사합니다.
기존 KnowledgePanel 7개 테스트에도 미리보기/원문 열기와 이전 버전의 실제 제목 표시를 확인했습니다.
사람 편집 저장, 기대 버전 전달, 충돌 시 입력 보존, 폴더 배치 회귀 테스트도 통과했습니다.

최초 테스트에서 새 표시 컴포넌트와 생성 패널의 React key가 겹쳐 2개가 실패했습니다.
표시 컴포넌트 key에 `markdown-` 접두어를 적용한 뒤 전체 60개가 통과했습니다.
실제 브라우저 시각 QA와 실제 AI 호출은 이번 변경에서 실행하지 않았습니다.

JAR smoke 측정: 2026-10-21T12:20:17.170Z, 시작 17,389 ms, 재시작 23,122 ms,
목록 p95 13.35 ms, 검색 p95 34.54 ms. [원시 결과](performance-phase3-foundation.json).
100개 가상 파일의 순차 HTTP 측정이며 Markdown 렌더링/실제 LLM 성능이 아닙니다.
AI 통합 경로는 테스트 전용 fake CLI로 검증했고 공개 원격 수집은 이번 실행에서 생략했습니다.

## 한계와 다음 작업

- 링크 확인 후 외부 열기, 이미지 승인 로딩, 각주 이동, 코드 색상 강조, Mermaid/수식은 미지원입니다.
- 입력 상한은 기존 100,000자입니다. 악의적 중첩 Markdown의 CPU/메모리 예산·worker 격리와 대형 문서 성능 검증은 남아 있습니다.
- 이번 npm audit은 Vite/Vitest/@vitest/mocker/tinypool 계열에 critical 2, high 1, moderate 1을 보고했습니다.
  새 Markdown production 패키지의 경고는 없으나 앱 전체의 무취약성을 뜻하지 않습니다.
  자동 `audit fix --force`는 하지 않았으며 개발 도구 버전 갱신·호환성 검증을 다음 우선 작업으로 기록합니다.
  개발 서버와 Vitest UI 서버를 외부에 노출하지 마세요. npm audit은 현재 설치 경로에 대한 결과입니다.
- 이후 P3-4 폴더 이름 변경/이동과 AI 갱신 제안/충돌 해결을 진행합니다.
  채팅/SSE, opt-in 폴링/Git 증분, Tauri Mac 패키징은 후속 Phase입니다.
