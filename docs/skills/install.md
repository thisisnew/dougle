# 설치하기

**쓰고 있는 AI에 맞는 방법 하나만 선택하세요.**

## Claude Code

1. Claude Code에 아래 명령을 입력합니다.

   ```text
   /plugin marketplace add thisisnew/dougle
   ```

2. 더글 플러그인을 설치합니다.

   ```text
   /plugin install dougle@dougle-marketplace
   ```

3. 어느 디렉토리에서든 자료를 연결합니다. 아래 `/path/to/project`를 실제 자료 경로로 바꾸세요.

   ```text
   /dougle:dougle-connect /path/to/project를 읽고 업무 지식을 정리해 줘.
   ```

명령을 찾을 수 없다면 설치 상태를 확인하고 새 세션에서 다시 시도하세요.

## Codex

1. Codex에 아래 요청을 붙여 넣습니다.

   ```text
   https://github.com/thisisnew/dougle 저장소에서
   skills/dougle-connect, skills/dougle-ask, skills/dougle-reload,
   skills/dougle-publish, skills/dougle-fetch, skills/dougle-schedule, skills/i-have-adhd를 스킬로 설치해 줘.
   ```

2. 설치 후 새 세션에서 자료를 연결합니다. `/path/to/project`를 실제 자료 경로로 바꾸세요.

   ```text
   $dougle-connect /path/to/project를 읽고 업무 지식을 정리해 줘.
   ```

직접 설치하려면 [배포 ZIP](https://github.com/thisisnew/dougle/releases/tag/v0.7.0-skills-preview.1)을 풀고 `Dougle/skills/` 안의 일곱 폴더를 `~/.agents/skills/`에 복사하세요.

설치할 때 `SKILL.md`만 복사하지 말고 각 스킬의 `references/`도 함께 포함하세요. 독립된 `i-have-adhd/` 폴더의 SKILL.md와 LICENSE.md도 포함하세요. 기존 개인 수정본은 백업하고 변경 내용을 비교하세요.

## 설치 후 확인하기

**`dougle-connect`가 실행되어 요청한 자료를 읽고, 읽은 범위를 알려주면 첫 연결이 된 것입니다.** 회사 서비스에 접근할 수 없다면 [연결 안내](connections.md)를 참고하세요. 스킬 설치와 서비스 로그인은 별개입니다.

다음에는 [업무 질문 예시](usage.md)를 사용하세요.

## 새 버전으로 업데이트하기

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
dougle-publish, dougle-fetch, dougle-schedule, i-have-adhd 일곱 스킬을 갱신해 줘.

실제로 로드되는 설치 경로를 먼저 확인하고 그 위치를 업데이트해.
개인 수정본은 보존하고, ~/.dougle/의 업무 지식은 건드리지 마.
완료 후 일곱 SKILL.md와 참조 파일이 최신 저장소와 일치하는지 확인해.
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

[Claude 공식 플러그인 안내](https://code.claude.com/docs/en/discover-plugins)

## 제거하기

Claude 플러그인은 플러그인 관리에서 제거합니다. 폴더로 설치했다면 더글 업무 스킬 여섯 폴더와 함께 설치한 답변 스킬 폴더를 제거합니다.

**설치한 스킬과 저장된 지식은 별개입니다.** 스킬을 제거해도 `~/.dougle/`의 지식은 자동 삭제되지 않습니다.

<details>
<summary>Claude에 폴더로 직접 설치하려면</summary>

일곱 스킬 폴더를 `~/.claude/skills/`에 복사하세요. 이때 명령은 `/dougle-connect`, `/dougle-ask`, `/dougle-reload`, `/dougle-publish`, `/dougle-fetch`, `/dougle-schedule`입니다. 플러그인 설치의 `/dougle:` 접두사는 붙이지 않습니다.

명령이 중복되지 않도록 개인 폴더 설치와 플러그인 설치 중 하나만 사용하세요.

</details>

<details>
<summary>예전 앱이나 0.2.0~0.3.x를 사용했다면</summary>

0.7.0은 connect·ask·reload·publish·fetch·schedule과 답변 정리용 i-have-adhd를 함께 설치합니다. 예전 두세 개만 갱신하면 새 기능이 빠집니다. 기존 스킬을 직접 수정했다면 변경 내용을 먼저 확인하세요. 예전 Python 실행 파일은 새 스킬 폴더에 섞지 않습니다.

0.2.0 SQLite 데이터는 `~/Library/Application Support/Dougle Skills/`에 남아 있으며 자동으로 읽거나 삭제하지 않습니다. 원본 자료를 다시 연결하면 새 Markdown 지식을 만듭니다.

</details>

<details>
<summary>플러그인 호환 범위</summary>

Claude 배포는 marketplace/plugin 구조입니다. Cursor 공개 디렉토리 등록이나 `/add-plugin dougle` 지원을 뜻하지 않습니다.

ZIP에는 Codex 플러그인 매니페스트도 있지만 버전별 플러그인 UI까지 검증한 것은 아닙니다. Codex는 위의 표준 스킬 폴더 설치를 기준으로 안내합니다.

</details>
