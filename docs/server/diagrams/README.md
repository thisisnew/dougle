# 서버 목표 구조 그림

이 폴더의 그림은 구현 전 TO-BE 명세입니다. 현재 로컬 봇의 코드 구조를 증명하는 그림이 아닙니다. 자세한 조건과 책임은 [서버 개발 명세](../README.md)를 따릅니다.

| 그림 | 문서 | 원본·내보내기 |
|---|---|---|
| 로컬 지식 공유와 서버 답변 | [개요](../README.md) | [JSON](overview.json) · [HTML](overview.html) · [SVG](overview.svg) · [PNG](overview.png) |
| 질문 처리와 게시 | [구조](../architecture.md) | [JSON](runtime.json) · [HTML](runtime.html) · [SVG](runtime.svg) · [PNG](runtime.png) |
| 지식 버전과 수정 제안 | [갱신](../knowledge.md) | [JSON](knowledge.json) · [HTML](knowledge.html) · [SVG](knowledge.svg) · [PNG](knowledge.png) |

archify 3.2.0으로 구조를 작성하고 native finalize의 검증·산출물·브라우저 검사를 수행합니다. 한국어 문장은 humanizer-ko 1.5.0의 파일·임베디드 편집 방식으로 다듬었습니다. 두 스킬은 문서 제작에만 사용하며 제품 명령으로 추가하지 않습니다.

GitHub에는 [공통 변환기](../../diagrams/brand-export.py)로 한글 글꼴·색상을 적용한 SVG와 2배 PNG를 표시합니다. native HTML의 자동 검사는 변환된 SVG·PNG의 시각 검토를 대신하지 않습니다. HTML은 내려받아 브라우저로 열면 탐색할 수 있습니다.

작은 화면에서는 본문의 “그림 확대” 링크로 SVG를 열어 보실 수 있습니다. [검토 기록](../review.json)에 자동 검사와 시각 검토 범위를 구분했습니다.

```sh
node /path/to/archify/bin/archify.mjs finalize architecture \
  docs/server/diagrams/overview.json docs/server/diagrams/overview.html \
  --quality showcase --out-dir /path/to/new-review-directory --json
# HTML의 내보내기에서 SVG · 라이트를 저장한 뒤:
python3 docs/diagrams/brand-export.py exported.svg \
  docs/server/diagrams/overview.svg docs/server/diagrams/overview.json
```

설계 그림이므로 현재 소스 커밋의 실행 증거로 표시하지 않습니다. 현재 구현 분석의 기준 커밋은 [구조 문서](../architecture.md)에 따로 명시했습니다. [Apache-2.0 고지](../../diagrams/archify-LICENSE)와 [내장 글꼴 고지](../../diagrams/JetBrainsMono-OFL.txt)를 보존합니다.
