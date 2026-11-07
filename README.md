# Dougle · 더글

**내 업무 자료를 AI의 기억으로 만들고, 팀과 함께 쓰세요.**

자료를 연결하면 더글이 업무 지식으로 정리해 둡니다. 질문할 때 다시 읽고, 필요한 원본을 확인해 답합니다.

[작동 원리 그림으로 보기](docs/design.md) · [설치 도움말](docs/skills/install.md) · [전체 문서](docs/README.md) · [사용 시나리오](docs/scenarios/README.md)

## 1. 설치하기

**Claude Code 대화창**에 입력하세요.

```text
/plugin marketplace add thisisnew/dougle
/plugin install dougle@dougle-marketplace
```

<details>
<summary>Codex에서 설치하기</summary>

```text
https://github.com/thisisnew/dougle 저장소의 skills/에 있는
모든 스킬을 참조 파일까지 설치해 줘.
```

</details>

## 2. 연결하고 질문하기

`연결할_URL`을 원하는 자료 링크로 바꿔 주세요. 코드 폴더나 DB도 자연어로 요청할 수 있습니다.

```text
/dougle:dougle-connect 연결할_URL 이 자료를 읽고 업무 지식으로 정리해 줘.
/dougle:dougle-ask 회송과 폐기는 어떻게 달라?
```

**질문에 필요한 자료의 변경을 확인하고, 바뀐 지식만 갱신해 답합니다.** 전체 자료를 다시 읽을 때까지 기다리지 않습니다.

**짧은 설명과 출처**를 함께 답합니다. 저장한 지식은 세션을 닫아도 남습니다. 원본을 읽으려면 해당 서비스의 조회 도구와 권한이 필요합니다.

## 3. 팀과 지식 나누기

**publish는 공유하기, fetch는 가져와 내 지식과 합치기입니다.**

```text
/dougle:dougle-publish 팀_지식_저장소_URL에 입하 업무 지식을 공유해 줘.
/dougle:dougle-fetch 팀_지식_저장소_URL의 지식을 가져와 합쳐 줘.
```

선택한 지식을 기존 팀 지식과 합쳐 바로 반영합니다. PR은 만들지 않고, 서로 다른 주장은 둘 다 남깁니다. 민감정보 검사를 통과해야 게시하며, 가져올 때는 기존 지식·정정·충돌을 보존합니다. [자세히](docs/skills/sharing.md) · [자료가 많을 때의 처리 방식](docs/skills/large-corpus.md)

## Slack에서 쓰기

```text
/dougle:dougle-bot
```

처음 설정을 안내합니다. 이후 `@dougle 질문`에 **근거를 확인해 바로 답합니다.** 운영 조회·개발자 도움·내부 오류가 있을 때만 담당자 DM으로 확인을 요청합니다. [봇 설정·사용법](dougle-bot/README.md)

<details>
<summary>다른 명령 보기</summary>

| 하고 싶은 일 | Claude 플러그인 | Codex |
|---|---|---|
| 자료 연결 | `/dougle:dougle-connect` | `$dougle-connect` |
| 업무 질문 | `/dougle:dougle-ask` | `$dougle-ask` |
| 지식 공유 | `/dougle:dougle-publish` | `$dougle-publish` |
| 지식 가져오기 | `/dougle:dougle-fetch` | `$dougle-fetch` |
| Slack 봇 관리 | `/dougle:dougle-bot` | `$dougle-bot` |
| Slack 주기 확인 | `/dougle:dougle-schedule` | `$dougle-schedule` |

[schedule](docs/skills/schedule.md)은 기존 Slack MCP와 호스트 타이머로 문의를 확인하는 별도 방식입니다.

</details>

## 업데이트하기

**Claude Code:** 터미널에서 업데이트한 뒤 대화창에서 리로드하세요.

```sh
claude plugin marketplace update dougle-marketplace
claude plugin update dougle@dougle-marketplace
```

```text
/reload-plugins
```

리로드를 지원하지 않으면 Claude를 다시 실행하세요.

**Codex·개인 스킬 설치:** 아래를 요청한 뒤 새 세션을 여세요.

```text
https://github.com/thisisnew/dougle 최신 main으로 설치된 skills/의 모든 스킬을
참조 파일까지 갱신해 줘. 개인 수정본과 ~/.dougle/ 지식은 보존해.
제거된 dougle-reload 설치본은 출처와 개인 수정 여부를 확인하고,
더글 배포본으로 확인된 경우에만 스킬 검색 경로 밖에 백업해 줘.
```

[업데이트 문제 해결](docs/skills/install.md#새-버전으로-업데이트하기) · [다운로드](https://github.com/thisisnew/dougle/releases/tag/v0.8.13-skills-preview.1)
