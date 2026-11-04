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

3. 프로젝트 폴더에서 첫 자료를 연결합니다.

   ```text
   /dougle:dougle-connect 현재 프로젝트를 읽고 업무 지식을 정리해 줘.
   ```

명령을 찾을 수 없다면 설치 상태를 확인하고 새 세션에서 다시 시도하세요.

## Codex

1. Codex에 아래 요청을 붙여 넣습니다.

   ```text
   https://github.com/thisisnew/dougle 저장소에서
   skills/dougle-connect, skills/dougle-ask, skills/dougle-reload,
   skills/dougle-publish, skills/dougle-fetch를 스킬로 설치해 줘.
   ```

2. 설치 후 새 세션에서 자료를 연결합니다.

   ```text
   $dougle-connect 현재 프로젝트를 읽고 업무 지식을 정리해 줘.
   ```

직접 설치하려면 [배포 ZIP](https://github.com/thisisnew/dougle/releases/tag/v0.4.1-skills-preview.1)을 풀고 `Dougle/skills/` 안의 다섯 폴더를 `~/.agents/skills/`에 복사하세요.

## 설치 후 확인하기

**`dougle-connect`가 실행되어 요청한 자료를 읽고, 읽은 범위를 알려주면 첫 연결이 된 것입니다.** 회사 서비스에 접근할 수 없다면 [연결 안내](connections.md)를 참고하세요. 스킬 설치와 서비스 로그인은 별개입니다.

다음에는 [업무 질문 예시](usage.md)를 사용하세요.

## 새 버전으로 업데이트하기

**Claude 플러그인은 업데이트 → 리로드 순서입니다.** 매번 삭제하고 재설치할 필요는 없습니다.

1. Claude의 플러그인 관리에서 설치된 **dougle을 업데이트**합니다. 새 스킬 파일을 받는 단계입니다.
2. **플러그인을 리로드**해 현재 세션에 반영합니다. 지원하는 버전에서는 `/reload-plugins`를 사용합니다.
3. 새 명령이 여전히 보이지 않으면 새 세션에서 확인합니다.

플러그인 리로드 후 스킬 리로드를 따로 반복할 필요는 보통 없습니다. **리로드만으로 GitHub의 새 버전을 받는 것은 아닙니다.**

| 작업 | 바뀌는 것 |
|---|---|
| 플러그인 업데이트·스킬 재설치 | PC에 설치된 더글 스킬 파일 |
| 플러그인·스킬 리로드 | 현재 AI 세션이 읽는 설치 파일 |
| `dougle-reload` | 연결한 회사 자료를 다시 읽어 정리한 업무 지식 |

Codex나 개인 폴더로 설치했다면 다섯 스킬 폴더를 최신 버전으로 갱신한 뒤 새 세션에서 확인하세요. 직접 수정한 스킬은 교체 전에 변경 내용을 보존하세요.

[Claude 공식 업데이트 안내](https://code.claude.com/docs/en/discover-plugins#keep-plugins-updated)

## 제거하기

Claude 플러그인은 플러그인 관리에서 제거합니다. 폴더로 설치했다면 설치한 다섯 스킬 폴더를 제거합니다.

**설치한 스킬과 저장된 지식은 별개입니다.** 스킬을 제거해도 `~/.dougle/`의 지식은 자동 삭제되지 않습니다.

<details>
<summary>Claude에 폴더로 직접 설치하려면</summary>

다섯 스킬 폴더를 `~/.claude/skills/`에 복사하세요. 이때 명령은 `/dougle-connect`, `/dougle-ask`, `/dougle-reload`, `/dougle-publish`, `/dougle-fetch`입니다. 플러그인 설치의 `/dougle:` 접두사는 붙이지 않습니다.

명령이 중복되지 않도록 개인 폴더 설치와 플러그인 설치 중 하나만 사용하세요.

</details>

<details>
<summary>예전 앱이나 0.2.0~0.3.x를 사용했다면</summary>

0.4.1은 connect·ask·reload·publish·fetch 다섯 스킬을 함께 설치합니다. 예전 두세 개만 갱신하면 새 기능이 빠집니다. 기존 스킬을 직접 수정했다면 변경 내용을 먼저 확인하세요. 예전 Python 실행 파일은 새 스킬 폴더에 섞지 않습니다.

0.2.0 SQLite 데이터는 `~/Library/Application Support/Dougle Skills/`에 남아 있으며 자동으로 읽거나 삭제하지 않습니다. 원본 자료를 다시 연결하면 새 Markdown 지식을 만듭니다.

</details>

<details>
<summary>플러그인 호환 범위</summary>

Claude 배포는 marketplace/plugin 구조입니다. Cursor 공개 디렉토리 등록이나 `/add-plugin dougle` 지원을 뜻하지 않습니다.

ZIP에는 Codex 플러그인 매니페스트도 있지만 버전별 플러그인 UI까지 검증한 것은 아닙니다. Codex는 위의 표준 스킬 폴더 설치를 기준으로 안내합니다.

</details>
