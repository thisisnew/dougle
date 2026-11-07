# 더글 그림 원본

GitHub 문서에는 한글 글꼴과 배치를 고정한 PNG를 사용합니다. 작은 화면에서는 README의 개요 그림을 세로형으로 바꿉니다.

| 설명 | 문서용 이미지 | 편집 데이터 | 탐색용 HTML |
|---|---|---|---|
| 각자 연결하고 함께 쓰기 | [PNG](archify/overview.png) · [SVG](archify/overview.svg) | [JSON](archify/overview.json) | [HTML](archify/overview.html) |
| 작은 화면의 개요 | [PNG](archify/overview-mobile.png) · [SVG](archify/overview-mobile.svg) | [JSON](archify/overview-mobile.json) | [HTML](archify/overview-mobile.html) |
| 목차·토픽·소스·원본 | [PNG](archify/memory.png) · [SVG](archify/memory.svg) | [JSON](archify/memory.json) | [HTML](archify/memory.html) |
| Slack 질문과 개발자 확인 | [PNG](archify/bot.png) · [SVG](archify/bot.svg) | [JSON](archify/bot.json) | [HTML](archify/bot.html) |

HTML은 내려받아 브라우저에서 열면 확대·구성 요소 탐색이 가능합니다. GitHub 파일 화면에서는 실행되지 않습니다. [전체 구조 설명](../design.md)

## 제작과 확인

[archify](https://github.com/tt-a1i/archify)의 JSON 설계와 렌더러로 연결 구조를 만들었습니다. [humanizer-ko](https://github.com/IsaacEryn/humanizer-ko)를 문서 편집에 적용했습니다. 두 스킬은 문서 제작용이며 더글의 사용자 명령이나 답변 실행 경로에 추가하지 않았습니다.

1. JSON의 구성 요소·연결·근거 파일을 수정합니다.
2. archify `finalize architecture`로 HTML과 검사 기록을 만듭니다.
3. HTML의 내보내기 메뉴에서 라이트 SVG를 저장합니다.
4. [brand-export.py](brand-export.py)로 더글 색상과 큰 한글 글꼴을 적용합니다.
5. SVG를 브라우저에서 2배 크기 PNG로 렌더링하고 문서 너비에서 확인합니다.

```sh
node /path/to/archify/bin/archify.mjs finalize architecture \
  docs/diagrams/archify/overview.json docs/diagrams/archify/overview.html \
  --repo-root . --quality showcase --json
python3 docs/diagrams/brand-export.py exported.svg \
  docs/diagrams/archify/overview.svg docs/diagrams/archify/overview.json
```

HTML 검사는 archify 산출물에 적용됩니다. 글꼴·색상을 바꾼 문서용 SVG·PNG는 별도 시각 검토 대상입니다. [검토 기록](review.json) · [그림 스타일](style-guide.md)

archify 생성물의 소프트웨어 고지와 글꼴 라이선스는 HTML에 포함됩니다. [Apache-2.0](archify-LICENSE) · [JetBrains Mono OFL](JetBrainsMono-OFL.txt)
