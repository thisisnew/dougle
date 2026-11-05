# Dougle · 더글

**회사 자료를 연결하고, 평소 쓰는 AI에게 업무를 물어보세요.**

더글은 코드·문서·DB에서 업무 지식을 정리하고, 출처를 찾아 답하는 Claude Code·Codex 스킬입니다.

[처음 설치](#1-설치하기) · [바로 질문](#3-질문하기) · [이미 설치했다면](#업데이트하기) · [구동 방식 자세히 보기](docs/skills/how-it-works.md)

## 1. 설치하기

**Claude Code 대화창**에 두 줄을 차례로 입력하세요.

```text
/plugin marketplace add thisisnew/dougle
/plugin install dougle@dougle-marketplace
```

**Codex 대화창**에는 아래 요청을 붙여 넣으세요.

```text
https://github.com/thisisnew/dougle 저장소에서
skills/dougle-connect, skills/dougle-ask, skills/dougle-reload,
skills/dougle-publish, skills/dougle-fetch를 참조 파일까지 설치해 줘.
```

## 2. 자료 연결하기

어느 디렉토리에서든 AI를 실행하고 요청하세요. 아래 `/path/to/project`를 실제 자료 폴더 경로로 바꾸면 됩니다. Claude 플러그인 기준입니다.

```text
/dougle:dougle-connect /path/to/project 코드를 읽고 입하 업무를 정리해 줘.
```

문서 링크나 DB 설명도 그대로 알려주세요. 같은 자료를 다시 요청하면 기존 기록을 찾아 갱신합니다.

해당 서비스에 접근할 수 있는 조회 도구와 권한이 필요합니다. 연결이 안 되면 필요한 설정을 안내합니다. [연결 문제 해결](docs/skills/connections.md)

## 3. 질문하기

```text
/dougle:dougle-ask 입하 확정할 때 어느 테이블에 데이터가 들어가?
```

같은 대화에서 **“어느 코드를 보면 돼?”**처럼 이어서 물어보세요.

답변은 **결론 → 필요한 설명 → 출처** 순서로 정리합니다. 실제로 확인한 자료만 인용하며, 추론이나 최신 확인이 안 된 내용은 구분합니다.

> 답변 형식 예시 — 아래 파일과 동작은 가상입니다.
>
> 입하 확정은 `receipt` 테이블에 기록합니다. [1]
>
> 흐름은 `confirmReceipt()`부터 보면 됩니다. [1]
>
> **출처**
> - [1] `src/ReceiptService.java` · `confirmReceipt()`

`i-have-adhd` 원본 지침이 ask에 포함되어 기본 답변에 적용됩니다. 자세한 설명이 필요하면 **“전체 흐름을 자세히 설명해 줘”**라고 요청하세요.

## 필요한 명령 찾기

| 하고 싶은 일 | Claude 플러그인 | Codex |
|---|---|---|
| 자료 연결 | `/dougle:dougle-connect` | `$dougle-connect` |
| 업무 질문 | `/dougle:dougle-ask` | `$dougle-ask` |
| 자료 즉시 갱신 | `/dougle:dougle-reload` | `$dougle-reload` |
| 팀에 지식 공유 | `/dougle:dougle-publish` | `$dougle-publish` |
| 팀 지식 가져오기 | `/dougle:dougle-fetch` | `$dougle-fetch` |

## 필요한 내용부터 읽습니다

더글은 관련 지침과 근거부터 읽고, 이미 확인한 내용을 불필요하게 다시 읽지 않습니다. **근거가 부족하면 검색을 넓히며, 예외·최신성·출처 확인은 생략하지 않습니다.**

[토큰 최적화와 정확도 검증](docs/skills/context-optimization.md)

## 오래된 자료는 질문할 때 갱신합니다

`ask`는 **관련 자료를 마지막으로 확인한 지 24시간 이상 지났으면 갱신 후 답합니다.** 현재 DB 상태나 최신 설정을 묻는 질문은 매번 확인합니다.

주기도 바꿀 수 있습니다.

```text
이 컨플루언스 문서는 일주일마다 확인해 줘.
더글의 기본 확인 간격을 12시간으로 바꿔 줘.
```

갱신에 실패하면 예전 자료를 최신 정보처럼 말하지 않습니다. 전체를 지금 갱신하려면 `/dougle:dougle-reload 전체` 또는 `$dougle-reload 전체`를 쓰세요.

## 업데이트하기

### Claude Code 플러그인

**1. 일반 터미널**에서 실행하세요.

```sh
claude plugin marketplace update dougle-marketplace
claude plugin update dougle@dougle-marketplace
```

평소 `claude-work`를 쓴다면 위 명령의 `claude`도 `claude-work`로 바꾸세요.

**2. Claude 대화창**에서 실행하세요.

```text
/reload-plugins
```

지원하지 않는 버전이면 Claude를 종료하고 평소 쓰는 실행 명령으로 다시 여세요. `/dougle:`을 입력해 스킬 목록을 확인합니다.

**새 명령이 안 보이면**, 터미널에서 상태를 확인하세요.

```sh
claude plugin list
```

| 확인 결과 | 실행할 명령·대처 |
|---|---|
| dougle이 없음 | `claude plugin install dougle@dougle-marketplace` |
| 마켓플레이스를 못 찾음 | `claude plugin marketplace add thisisnew/dougle` 후 업데이트 |
| 비활성 상태 | `claude plugin enable dougle@dougle-marketplace` 후 리로드 |
| Scope가 project 또는 local | 해당 프로젝트에서 업데이트 명령에 `--scope project` 또는 `--scope local` 추가 |
| 설치됐는데 계속 안 보임 | 대화창의 `/plugin` → Errors 확인. 개인 스킬·플러그인 중복 설치 여부 확인 |

이 명령들도 회사 실행명이 다르면 `claude` 대신 그 이름을 쓰세요. 관리자가 관리하는 설치는 관리자에게 업데이트를 요청하세요.

### Codex 또는 Claude 개인 스킬 폴더

**1. AI 대화창**에 붙여 넣으세요.

```text
https://github.com/thisisnew/dougle 의 최신 main으로
dougle-connect, dougle-ask, dougle-reload, dougle-publish, dougle-fetch를 갱신해 줘.
실제로 로드되는 설치 경로를 확인하고 SKILL.md와 참조 파일을 함께 업데이트해.
개인 수정본은 보존하고 ~/.dougle/의 업무 지식은 건드리지 마.
```

**2. AI를 다시 실행**하고 Codex는 `$dougle-fetch`, Claude 개인 설치는 `/dougle-fetch`를 확인하세요.

안 보이면 아래를 요청하세요.

```text
dougle-fetch가 안 보여. 실제 스킬 검색 경로의 SKILL.md와 참조 파일,
이전 설치본과의 중복, 형식 오류를 확인해 줘. 업무 지식은 변경하지 마.
```

**업데이트는 새 스킬 파일을 받는 것, 리로드는 받은 파일을 다시 읽는 것**입니다. `dougle-reload`는 회사 자료를 갱신하는 별도 기능입니다.

## 지식은 어디에 남나요?

**어느 디렉토리에서 실행해도 같은 `~/.dougle/` 지식을 사용합니다.** 작업 공간을 따로 만들거나 선택하지 않습니다.

업무 개념·규칙·시스템 관계·출처를 Markdown으로 저장하며, 프로젝트와 서비스는 각 자료의 정보로 구분합니다. 같은 용어가 여러 시스템에 있으면 필요한 경우에만 대상을 확인합니다.

기존 `~/.dougle/workspaces/`의 지식은 삭제하거나 옮기지 않고 공통 목차에 연결해 찾습니다. 세션을 닫아도 **저장된 지식은 남지만 전체 대화가 자동 복원되지는 않습니다.**

질문할 때 주제·동의어 목차로 관련 자료를 좁히고 필요한 근거를 읽습니다. 그래프 DB나 모델 재학습 방식은 아닙니다. [내재화 방식](docs/skills/architecture.md) · [검색 속도와 Lucene 비교](docs/skills/search-performance.md)

“현재 폴더”나 상대 경로를 연결할 때만 실행 위치를 사용합니다. 원본 자료는 읽기만 하며, 호스트의 파일 접근 권한과 회사 서비스의 읽기 권한은 그대로 적용됩니다.

[사용 예시](docs/skills/usage.md) · [팀 지식 공유](docs/skills/sharing.md) · [전체 문서](docs/README.md) · [다운로드](https://github.com/thisisnew/dougle/releases/tag/v0.5.0-skills-preview.1)
