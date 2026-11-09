# 설치하기

쓰고 있는 AI에 맞는 방법 하나를 선택하세요. [작동 구조](../design.md)를 먼저 읽어도 좋습니다.

## Claude Code

1. Claude Code에 아래 명령을 입력합니다.

   ```text
   /plugin marketplace add thisisnew/dougle
   ```

2. 더글 플러그인을 설치합니다.

   ```text
   /plugin install dougle@dougle-marketplace
   ```

3. 어느 디렉토리에서든 자료를 연결합니다. 아래 `연결할_URL`을 읽고 싶은 자료 링크로 바꾸세요.

   ```text
   /dougle:dougle-connect 연결할_URL을 읽고 업무 지식으로 정리해 줘.
   ```

명령을 찾을 수 없다면 설치 상태를 확인하고 새 세션에서 다시 시도하세요.

## Codex

1. Codex에 아래 요청을 붙여 넣습니다.

   ```text
   https://github.com/thisisnew/dougle 저장소에서
   skills/ 안의 모든 스킬과 참조 파일을 함께 설치해 줘.
   ```

2. 설치 후 새 세션에서 자료를 연결합니다. `연결할_URL`을 읽고 싶은 자료 링크로 바꾸세요.

   ```text
   $dougle-connect 연결할_URL을 읽고 업무 지식으로 정리해 줘.
   ```

현재 프리뷰 **스킬 0.8.26 / 봇 0.1.23**은 선택형 압축 정책, 정확한 BigQuery 전체 건수의 자동 안내, 일부 명시적 정책 문장의 결정적 답변을 포함합니다. 기본 답변 정책은 유지하며 압축 정책은 [봇 설정](../../dougle-bot/README.md)에서 선택합니다. [합성 평가와 적용 범위](../dougle-bot/token-consistency-codex-pilot.md)

봇 업데이트 시 표식 없는 이전 `max_parallel = 1`은 메모리에서만 4로 적용됩니다. 명시적으로 1을 유지하려면 `[engine]`의 `max_parallel = 1`과 `parallelism_version = 1`을 함께 설정하세요. 기존 설정 파일을 읽는 것만으로 파일을 변경하지 않습니다. [설정 안내](../../dougle-bot/README.md)를 참고하세요.

공개 후 직접 설치하려면 [0.8.26 배포 ZIP](https://github.com/thisisnew/dougle/releases/tag/v0.8.26-skills-preview.1)을 풀고 `Dougle/skills/` 안의 모든 스킬 폴더를 `~/.agents/skills/`에 복사하세요.

설치할 때 `SKILL.md`만 복사하지 말고 각 스킬의 `references/`, `scripts/`, `assets/`도 함께 포함하세요. publish의 번들 셸 검사가 빠지면 게시할 수 없습니다. 내부 답변 스킬인 `i-have-adhd/`, `plain-language/` 폴더의 SKILL.md와 LICENSE.md도 포함하세요. 기존 개인 수정본은 백업하고 변경 내용을 비교하세요.

Slack 봇을 사용할 때는 `/dougle:dougle-bot`에서 최초 설정을 진행합니다. 지식 스킬만 사용할 때는 봇 런타임을 설치하지 않습니다. [봇 설치·실행 안내](../../dougle-bot/README.md)

## 첫 연결 확인하기

**`dougle-connect`가 실행되어 요청한 자료를 읽고, 읽은 범위를 알려주면 첫 연결이 된 것입니다.** 회사 서비스에 접근할 수 없다면 [연결 안내](connections.md)를 참고하세요. 스킬 설치와 서비스 로그인은 별개입니다.

다음에는 [업무 질문 예시](usage.md)를 사용하세요.

## 새 버전으로 업데이트하기

### Claude Code: 플러그인으로 설치한 경우

터미널에서 새 파일을 받습니다.

```sh
claude plugin marketplace update dougle-marketplace
claude plugin update dougle@dougle-marketplace
```

실행 중인 Claude 대화창에서 적용합니다.

```text
/reload-plugins
```

리로드를 지원하지 않으면 Claude를 다시 실행하세요. 플러그인 업데이트는 새 파일을 받는 작업이고 리로드는 현재 세션에 적용하는 작업입니다.

<details>
<summary>업데이트 후에도 명령이 보이지 않을 때</summary>

터미널에서 `claude plugin list`로 설치 상태를 확인합니다.

| 확인 결과 | 대처 |
|---|---|
| dougle 없음 | `claude plugin install dougle@dougle-marketplace` |
| 마켓플레이스 없음 | `claude plugin marketplace add thisisnew/dougle` 후 업데이트 |
| project/local 범위 | 설치한 프로젝트에서 업데이트 명령에 `--scope project` 또는 `--scope local` 추가 |
| 비활성 | `claude plugin enable dougle@dougle-marketplace` 후 리로드 |
| 설치됐으나 오류 | `/plugin`의 Errors와 개인 폴더 중복 설치 확인 |

관리자가 관리하는 설치는 관리자에게 업데이트를 요청하세요.

</details>

### Codex: 스킬 폴더로 설치한 경우

Codex 대화창에서 갱신을 요청합니다.

```text
https://github.com/thisisnew/dougle 의 최신 main 기준으로
현재 설치된 dougle-connect, dougle-ask,
dougle-publish, dougle-fetch, dougle-schedule, dougle-bot, i-have-adhd, plain-language를 갱신해 줘.

실제로 로드되는 설치 경로를 먼저 확인하고 그 위치를 업데이트해.
개인 수정본은 보존하고, ~/.dougle/의 업무 지식은 건드리지 마.
제거된 dougle-reload는 출처와 개인 수정 여부를 확인하고,
더글 배포본으로 확인된 경우에만 스킬 검색 경로 밖에 백업해 줘.
완료 후 모든 SKILL.md와 참조 파일이 최신 저장소와 일치하는지 확인해.
```

새 세션에서 `$dougle-fetch`를 입력해 확인하세요.

<details>
<summary>스킬이 보이지 않을 때</summary>

Codex에 아래 요청을 보내세요.

```text
현재 세션에서 dougle-fetch가 안 보여.
실제 스킬 검색 경로에 dougle-fetch/SKILL.md가 있는지,
다른 위치의 이전 설치본과 중복되거나 형식 오류가 있는지 확인해 줘.
저장된 업무 지식은 변경하지 마.
```

</details>

### Claude Code: 개인 스킬 폴더로 설치한 경우

`~/.claude/skills/`에 직접 설치했다면 플러그인 업데이트 명령으로 갱신되지 않습니다. **위 Codex의 갱신 요청을 Claude 대화창에 입력한 뒤 새 세션을 시작하세요.** 확인할 명령은 접두사 없는 `/dougle-fetch`입니다.

0.8.8부터 별도 자료 갱신 명령은 없습니다. 질문하면 필요한 자료만 확인합니다. 업데이트 후에도 `dougle-reload`가 보이면 개인 폴더에 남은 이전 설치본인지 확인하세요. 백업 폴더는 스킬 검색 경로 밖에 두고 새 세션을 시작하세요. 기존 업무 지식과 명시적인 확인 주기는 보존합니다.

## 제거하기

Claude 플러그인은 플러그인 관리에서 제거합니다. 폴더로 설치했다면 설치한 더글 스킬 폴더를 제거합니다. 공유 답변 스킬을 다른 용도로도 쓴다면 보존하세요. 봇을 사용 중이면 먼저 중지하세요.

**설치한 스킬과 저장된 지식은 별개입니다.** 스킬을 제거해도 `~/.dougle/`의 지식은 자동 삭제되지 않습니다.

<details>
<summary>Claude에 폴더로 직접 설치하려면</summary>

배포물의 모든 스킬 폴더를 `~/.claude/skills/`에 복사하세요. 이때 명령은 `/dougle-connect`, `/dougle-ask`, `/dougle-publish`, `/dougle-fetch`, `/dougle-schedule`, `/dougle-bot`입니다. 답변 정리용 두 스킬은 내부 호출용입니다. 플러그인 설치의 `/dougle:` 접두사는 붙이지 않습니다.

명령이 중복되지 않도록 개인 폴더 설치와 플러그인 설치 중 하나만 사용하세요.

</details>

<details>
<summary>플러그인 호환 범위</summary>

Claude 배포는 marketplace/plugin 구조입니다. Cursor 공개 디렉토리 등록이나 `/add-plugin dougle` 지원을 뜻하지 않습니다.

ZIP에는 Codex 플러그인 매니페스트도 있지만 버전별 플러그인 UI까지 검증한 것은 아닙니다. Codex는 위의 표준 스킬 폴더 설치를 기준으로 안내합니다.

</details>
