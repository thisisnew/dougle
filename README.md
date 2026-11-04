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

## 이미 설치했다면: 사용하는 AI에 맞춰 업데이트

### Claude Code: 플러그인으로 설치한 경우

**1. 터미널에서 아래 두 명령을 순서대로 실행하세요.** Claude 대화 입력창이 아니라 일반 터미널에 입력합니다.

```sh
claude plugin marketplace update dougle-marketplace
claude plugin update dougle@dougle-marketplace
```

평소 `claude-work`로 실행한다면 위 명령의 `claude`도 `claude-work`로 바꾸세요. 같은 실행 명령을 사용해야 회사 설정을 유지할 수 있습니다.

**2. 실행 중인 Claude 대화창에서 입력하세요.**

```text
/reload-plugins
```

이 명령을 지원하지 않는 버전이면 Claude를 종료하고 평소 쓰는 명령(`claude` 또는 `claude-work`)으로 다시 실행하세요.

**3. Claude 대화창에서 `/dougle:`을 입력해 새 명령을 확인하세요.** 예: `/dougle:dougle-fetch`.

**여전히 안 보이면** 터미널에서 설치 상태를 확인하세요.

```sh
claude plugin list
```

| 확인 결과 | 대처 |
|---|---|
| `dougle`이 목록에 없음 | `claude plugin install dougle@dougle-marketplace` 실행 |
| 마켓플레이스를 찾지 못함 | `claude plugin marketplace add thisisnew/dougle` 실행 후 업데이트 재시도 |
| Scope가 `project` 또는 `local` | 설치한 프로젝트 폴더에서 업데이트 명령 끝에 `--scope project` 또는 `--scope local`을 붙여 실행 |
| 비활성 상태 | `claude plugin enable dougle@dougle-marketplace` 실행 후 리로드 |
| 설치돼 있는데 명령이 없음 | Claude 대화창에서 `/plugin`의 Errors 항목 확인. 개인 스킬과 플러그인이 중복 설치됐는지도 확인 |

위 확인·설치 명령도 회사 실행명이 다르면 `claude`를 해당 이름으로 바꾸세요. 관리자가 관리하는 설치는 관리자에게 업데이트를 요청하세요.

### Codex: 스킬 폴더로 설치한 경우

**1. Codex 대화창에 아래 요청을 그대로 입력하세요.**

```text
https://github.com/thisisnew/dougle 의 최신 main 기준으로
현재 설치된 dougle-connect, dougle-ask, dougle-reload,
dougle-publish, dougle-fetch 다섯 스킬을 갱신해 줘.

실제로 로드되는 설치 경로를 먼저 확인하고 그 위치를 업데이트해.
개인 수정본은 보존하고, ~/.dougle/의 업무 지식은 건드리지 마.
완료 후 다섯 SKILL.md와 참조 파일이 최신 저장소와 일치하는지 확인해.
```

**2. Codex를 다시 실행하고 `$dougle-fetch`를 입력해 확인하세요.**

안 보이면 Codex에 아래 요청을 보내세요.

```text
현재 세션에서 dougle-fetch가 안 보여.
실제 스킬 검색 경로에 dougle-fetch/SKILL.md가 있는지,
다른 위치의 이전 설치본과 중복되거나 형식 오류가 있는지 확인해 줘.
저장된 업무 지식은 변경하지 마.
```

### Claude Code: 개인 스킬 폴더로 설치한 경우

`~/.claude/skills/`에 직접 설치했다면 플러그인 업데이트 명령으로 갱신되지 않습니다. **위 Codex의 갱신 요청을 Claude 대화창에 입력한 뒤 새 세션을 시작하세요.** 확인할 명령은 접두사 없는 `/dougle-fetch`입니다.

### 업데이트와 리로드의 차이

| 작업 | 하는 일 |
|---|---|
| 플러그인 업데이트·스킬 파일 갱신 | GitHub의 새 스킬 파일을 받음 |
| 플러그인·스킬 리로드 | 현재 설치된 파일을 세션에 다시 반영 |
| `dougle-reload` | 연결한 회사 자료를 다시 읽어 업무 지식을 갱신 |

**새 기능을 받으려면 파일 업데이트가 먼저입니다.** 플러그인 리로드 후 스킬 리로드까지 따로 반복할 필요는 보통 없습니다.

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
