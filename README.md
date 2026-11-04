# Dougle · 더글

**회사 자료를 연결하고, 평소 쓰는 AI에게 업무를 물어보세요.**

더글은 Claude Code·Codex에서 쓰는 스킬 모음입니다. AI가 코드·문서·DB를 읽고, 출처와 업무 지식을 파일에 정리해 다음 질문에도 참고합니다.

## 1. 설치하기

**Claude Code**에 아래 두 줄을 차례로 입력하세요.

```text
/plugin marketplace add thisisnew/dougle
/plugin install dougle@dougle-marketplace
```

**Codex**를 쓴다면 아래 요청을 붙여 넣으세요.

```text
https://github.com/thisisnew/dougle 저장소에서
skills/dougle-connect, skills/dougle-ask, skills/dougle-reload,
skills/dougle-publish, skills/dougle-fetch를 스킬로 설치해 줘.
```

더글용 Python·Node·서버는 필요 없습니다. [다른 설치 방법과 업데이트 →](docs/skills/install.md)

## 2. 자료 연결하기

프로젝트 폴더에서 AI를 실행하고 요청하세요. 아래는 Claude 플러그인 기준입니다.

```text
/dougle:dougle-connect 현재 프로젝트 코드를 읽고 입하 업무를 정리해 줘.
```

문서 URL이나 DB 설명도 자유롭게 덧붙일 수 있습니다. 이미 등록한 자료인지 확인할 필요는 없습니다. 더글이 기존 기록을 찾아 재사용하거나 갱신합니다.

**자료를 읽으려면 AI에 해당 서비스의 조회 도구와 접근 권한이 있어야 합니다.** 연결이 안 되면 필요한 설정을 안내합니다. [연결이 안 될 때 →](docs/skills/connections.md)

## 3. 질문하기

```text
/dougle:dougle-ask 입하 확정할 때 어느 테이블에 데이터가 들어가?
```

같은 대화에서는 “그럼 어느 코드를 보면 돼?”처럼 이어서 물어보세요. 답변은 근거를 바탕으로 하며, 확인된 사실과 해석을 구분합니다.

## 필요할 때 꺼내 쓰기

| 하고 싶은 일 | Claude 플러그인 | Codex |
|---|---|---|
| 자료 연결 | `/dougle:dougle-connect` | `$dougle-connect` |
| 업무 질문 | `/dougle:dougle-ask` | `$dougle-ask` |
| 바뀐 자료 갱신 | `/dougle:dougle-reload` | `$dougle-reload` |
| 팀에 지식 공유 | `/dougle:dougle-publish` | `$dougle-publish` |
| 팀 지식 가져오기 | `/dougle:dougle-fetch` | `$dougle-fetch` |

[복사해서 쓰는 예시 →](docs/skills/usage.md) · [자료 갱신 →](docs/skills/reload.md) · [팀 공유 →](docs/skills/sharing.md)

## 기억은 어디에 남나요?

`~/.dougle/workspaces/`에 Markdown 파일로 저장합니다. **저장된 지식은 터미널이나 대화 세션을 닫아도 남습니다.** 같은 PC의 같은 작업 공간을 쓰는 AI가 다시 참고할 수 있습니다. 전체 채팅이 자동으로 옮겨지는 것은 아닙니다.

원본 코드·문서·DB는 읽기만 합니다. 다만 스킬 자체가 쓰기 권한을 차단하는 보안 장치는 아니므로 회사 시스템에는 읽기 전용 권한을 사용하세요.

현재 버전은 **0.4.0 Preview**입니다. 설치·파일 구조는 검증했지만 회사별 연결과 AI 답변·지식 병합 품질을 모두 검증한 상태는 아닙니다. [검증 범위 →](docs/skills/verification.md)

[전체 문서 →](docs/README.md) · [스킬 다운로드 →](https://github.com/thisisnew/dougle/releases/tag/v0.4.0-skills-preview.1)
