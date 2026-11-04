# 설치 — 0.3.0

더글 자체 실행 의존성은 없습니다. 이미 쓰는 호스트 AI와 해당 호스트의 스킬/플러그인 기능만 필요합니다. 호스트 도구 자체의 설치 요구 사항과 회사 정책은 그대로 적용됩니다.

## Claude Code

```text
/plugin marketplace add thisisnew/dougle
/plugin install dougle@dougle-marketplace
```

저장소의 `.claude-plugin/marketplace.json`은 `plugins/dougle`을 가리킵니다. 플러그인은 스킬과 Markdown 참조, 매니페스트만 포함합니다. 명령은 `/dougle:dougle-connect`, `/dougle:dougle-ask`입니다. 업데이트는 호스트의 플러그인 관리 화면에서 진행하세요.

이 방식은 [pstack 배포 사례](https://github.com/cursor/plugins/tree/main/pstack)와 같은 marketplace/plugin 구조이며 [Claude 공식 설치 규격](https://code.claude.com/docs/en/plugin-marketplaces)을 따릅니다. Cursor 공개 디렉토리에 등록된 제품이라는 뜻은 아니며 `/add-plugin dougle`을 보장하지 않습니다.

## Codex

Codex에 다음처럼 요청할 수 있습니다.

> thisisnew/dougle 저장소의 skills/dougle-connect와 skills/dougle-ask를 설치해 줘.

또는 [배포 파일](https://github.com/thisisnew/dougle/releases/tag/v0.3.0-skills-preview.1)을 풀고 `skills` 안 두 폴더를 `~/.agents/skills/`에 복사하세요. `$dougle-connect`, `$dougle-ask`로 선택하거나 이름으로 요청합니다. 새 세션이 필요할 수 있습니다. [공식 스킬 위치 안내](https://learn.chatgpt.com/docs/build-skills)

플러그인 ZIP에는 `.codex-plugin/plugin.json`도 포함하지만 Codex 버전별 UI/마켓플레이스 명령을 동일하다고 가정하지 않습니다. 기본 호환 경로는 두 표준 스킬 폴더 설치입니다.

## 기존 버전

0.2.0 개인 스킬 폴더를 사용했다면 사용자 수정본을 먼저 확인하고 해당 두 폴더를 새 Markdown 전용 폴더로 교체하세요. Python 실행 파일을 새 폴더에 남겨두지 마세요. Claude에서는 개인 스킬과 플러그인을 동시에 설치하면 중복 명령이 생길 수 있으므로 사용하는 설치 방식 하나를 선택하세요.

예전 SQLite 데이터는 `~/Library/Application Support/Dougle Skills/`에 남아 있습니다. 새 스킬은 이를 자동으로 읽거나 삭제하지 않습니다. 원본 자료를 다시 연결하면 새 Markdown 기억을 만듭니다. 자료 연결 정보/본문을 무단으로 다른 곳에 복사하지 않습니다.

제거는 호스트 플러그인 제거 기능 또는 설치한 두 스킬 폴더 제거로 수행합니다. `~/.dougle/`의 기억은 별도이며 자동 삭제하지 않습니다.

두 폴더를 Claude의 `~/.claude/skills/`에 직접 복사하는 방식도 가능합니다. 이 경우 명령은 플러그인 접두사 없이 `/dougle-connect`, `/dougle-ask`입니다. 이번 개발 PC의 개인 스킬은 이 방식으로 새 지침으로 교체했습니다.
