# 더글 스킬

더글은 평소 쓰는 AI가 업무 자료를 읽고, 정리한 지식을 다음 질문에도 쓰도록 돕습니다.

[전체 구조](../design.md) · [설치](install.md) · [사용 예시](usage.md)

| 할 일 | Claude 플러그인 | Codex |
|---|---|---|
| 자료 연결 | `/dougle:dougle-connect` | `$dougle-connect` |
| 업무 질문 | `/dougle:dougle-ask` | `$dougle-ask` |
| 팀에 공유 | `/dougle:dougle-publish` | `$dougle-publish` |
| 팀 지식 가져오기 | `/dougle:dougle-fetch` | `$dougle-fetch` |
| Slack 봇 관리 | `/dougle:dougle-bot` | `$dougle-bot` |

개인 스킬 폴더로 설치한 Claude에서는 `/dougle-ask`처럼 `/dougle:` 접두사를 빼고 사용합니다. 답변 정리를 맡는 `i-have-adhd`와 `plain-language`는 함께 설치되는 내부 스킬입니다.

지식은 `~/.dougle/`에 저장합니다. 원본 읽기는 AI에 연결된 도구로 수행하며, 질문에 필요한 변경만 확인합니다. [저장 방식](architecture.md) · [답변과 출처](answer-sources.md)

기존 채널 폴링 사용자는 [schedule 안내](schedule.md)를 참고하세요.
