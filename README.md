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

### 이미 설치했다면: 업데이트 → 리로드

1. Claude 플러그인 관리에서 **dougle을 업데이트**합니다.
2. **플러그인을 리로드**합니다. 새 명령이 안 보이면 새 세션을 시작하세요.

리로드만으로 새 버전을 다운로드하지는 않습니다. Codex나 폴더 설치는 스킬 파일을 최신 버전으로 갱신한 뒤 새 세션에서 확인하세요.

[업데이트 방법과 자료 갱신의 차이 →](docs/skills/install.md#새-버전으로-업데이트하기)

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

같은 대화에서는 “그럼 어느 코드를 보면 돼?”처럼 이어서 물어보세요. 답변은 **결론부터 짧게**, 근거는 해당 설명 옆에 보여줍니다. 확인된 사실과 해석은 구분합니다. 더 깊은 설명이 필요하면 “자세히 설명해 줘”라고 요청하세요.

## 필요할 때 꺼내 쓰기

| 하고 싶은 일 | Claude 플러그인 | Codex |
|---|---|---|
| 자료 연결 | `/dougle:dougle-connect` | `$dougle-connect` |
| 업무 질문 | `/dougle:dougle-ask` | `$dougle-ask` |
| 바뀐 자료 갱신 | `/dougle:dougle-reload` | `$dougle-reload` |
| 팀에 지식 공유 | `/dougle:dougle-publish` | `$dougle-publish` |
| 팀 지식 가져오기 | `/dougle:dougle-fetch` | `$dougle-fetch` |

[복사해서 쓰는 예시 →](docs/skills/usage.md) · [자료 갱신 →](docs/skills/reload.md) · [팀 공유 →](docs/skills/sharing.md)

## 지식은 어떻게 내재화하나요?

**업무 개념별 Markdown에 규칙·시스템 관계·출처를 정리하고, 질문할 때 다시 읽습니다.** 모델 재학습이나 그래프 DB 방식은 아닙니다. API → 코드 → 테이블의 관계도 근거와 함께 기록하며, 답변할 때 필요한 원본을 확인합니다.

[저장 구조·관계 기록·검색 흐름 보기 →](docs/skills/architecture.md)

## 기억은 어디에 남나요?

`~/.dougle/workspaces/`에 Markdown 파일로 저장합니다. **저장된 지식은 터미널이나 대화 세션을 닫아도 남습니다.** 같은 PC의 같은 작업 공간을 쓰는 AI가 다시 참고할 수 있습니다. 전체 채팅이 자동으로 옮겨지는 것은 아닙니다.

원본 코드·문서·DB는 읽기만 합니다. 다만 스킬 자체가 쓰기 권한을 차단하는 보안 장치는 아니므로 회사 시스템에는 읽기 전용 권한을 사용하세요.

[전체 문서 →](docs/README.md) · [스킬 다운로드 →](https://github.com/thisisnew/dougle/releases/tag/v0.4.1-skills-preview.1)
