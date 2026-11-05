# 더글 다이어그램

README의 설명을 줄이기 위한 두 도식입니다. 기존 더글의 흰 바탕·파란 강조색을 사용합니다.

| 도식 | README 이미지 | 편집 가능한 원본 |
|---|---|---|
| 연결 → 내재화 → publish → fetch → 활용 | [SVG](knowledge-sharing.svg) | [HTML](knowledge-sharing.html) |
| 질문 → 지식 탐색 → 원본 확인 → 출처 있는 답변 | [SVG](ask-flow.svg) | [HTML](ask-flow.html) |

[diagram-design](https://github.com/cathrynlavery/diagram-design/tree/f24d98b05e2d7a5e68c2dedfe46e5b7173f50118/skills/diagram-design) 스킬을 설치해 적용했습니다. HTML은 minimal template의 프레임·로컬 스크롤·인쇄 구조를 사용하며, SVG는 스킬의 export_svg.py로 내보냈습니다. 제작 지침·템플릿의 MIT 고지는 [LICENSE](diagram-design-LICENSE)를 참고하세요.

스킬은 문서 제작 환경에만 설치하며 더글 사용자의 실행 의존성이 아닙니다. 출력물은 외부 폰트·스크립트·이미지를 요청하지 않습니다. 글꼴은 OS의 한글 산세리프 글꼴로 대체되므로 기기별 모양은 조금 다를 수 있습니다. 색상·문자 대비와 여백은 로컬 브라우저에서 확인합니다.

첫 도식은 지식 공유의 구조를 설명합니다. 실제 자동 동기화나 회사 원본 전체 업로드를 뜻하지 않습니다. 두 번째 도식의 재사용 경로는 충분한 근거와 유효한 확인 이력이 있는 경우입니다. 조회가 실패하면 최신 사실로 단정하지 않는 규칙은 유지합니다. 인증·저장 실패·설정 우선순위 등의 세부 조건은 [구동 방식](../skills/how-it-works.md)을 참고하세요.
