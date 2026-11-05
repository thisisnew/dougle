# 더글 다이어그램

기존 더글의 흰 바탕·파란 강조색을 유지합니다.

| 도식 | README 이미지 | 편집 원본 |
|---|---|---|
| 자료를 지식으로 정리 → 팀에 공유 → 동료가 가져와 질문 | [PNG](knowledge-sharing.png) | [HTML 원본](knowledge-sharing.src.html) · [렌더링 HTML](knowledge-sharing.html) |
| 질문 → 근거 확인 → 출처 있는 답변 | [PNG](ask-flow.png) | [원본](ask-flow.src.html) · [HTML](ask-flow.html) |
| 원본 → 지식 저장 → 질문에 활용 | [PNG](knowledge-memory.png) | [원본](knowledge-memory.src.html) · [HTML](knowledge-memory.html) |
| 스킬 실행 → 지식·원본 확인 → 답변·기억 반영 | [PNG](skill-execution.png) | [원본](skill-execution.src.html) · [HTML](skill-execution.html) |

## 지식 공유 그림

[visual-explainer](https://github.com/nicobailon/visual-explainer) 0.15.0 스킬을 설치하고 적용했습니다. 한글 문장 단위 줄바꿈, 큰 제목, 한 방향의 세 단계 흐름으로 구성했습니다. 650px 이하의 HTML 화면은 세로로 배치됩니다. README는 글꼴 차이를 막기 위해 Apple SD Gothic Neo로 렌더링한 2배 해상도 PNG를 사용합니다. README 이미지 자체는 화면에 맞춰 축소되며 반응형 HTML처럼 재배치되지는 않습니다.

제작 환경에서 다음 명령으로 HTML을 렌더링합니다.

```sh
node ~/.agents/skills/visual-explainer/kit/render.mjs docs/diagrams/knowledge-sharing.src.html
```

PNG는 Chrome의 1000px viewport, deviceScaleFactor 2에서 글꼴 로딩 후 main 영역을 내보낸 것입니다. 렌더러의 390px·1280px 검사와 밝은/어두운 화면 시트를 확인했습니다. HTML에는 스킬의 CSS·스크립트가 포함됩니다. README PNG에는 실행 코드나 외부 요청이 없습니다. 제작 도구는 더글 사용자의 실행 의존성이 아닙니다. [MIT 고지](visual-explainer-LICENSE).

## 전체 도식의 표현

현재 스킬 문서의 네 도식 모두 visual-explainer 방식의 HTML·PNG로 통일했습니다. 질문·내재화·실행 흐름은 위에서 아래로 읽는 세 단계이며, 조건과 예외를 해당 단계 안에 적었습니다. 기존 diagram-design SVG와 현재 스킬 문서의 Mermaid 블록은 교체했습니다.

과거 데스크톱 앱 문서의 Mermaid 설계 기록과 실제 UI 스크린샷은 당시 동작의 기록이므로 보존합니다. 현재 스킬 버전의 그림이 아닙니다.

지식 공유는 선택한 정제 지식의 명시적 publish/fetch이며 원본 전체 업로드나 자동 동기화가 아닙니다. 질문 흐름의 근거 재사용은 충분한 근거와 유효한 확인 이력이 있는 경우입니다. 세부 조건은 [구동 방식](../skills/how-it-works.md)을 참고하세요.
