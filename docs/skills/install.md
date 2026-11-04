# 설치와 사용

## 요구 사항

지원 대상은 M 시리즈 Mac입니다. Claude Code 또는 Codex는 회사에서 평소 쓰는 방식으로 설치·로그인되어 있어야 합니다. 더글은 AI 프로세스를 새로 실행하지 않으므로 `claude-work` 같은 별칭을 탐지하거나 변경하지 않습니다.

Python 3.9+와 SQLite FTS5가 필요합니다. Python이 없는 새 Mac에서는 회사가 허용한 배포판을 준비해야 합니다. Python·Java·Node·모델은 배포 파일에 포함하지 않습니다. 설치 프로그램이 FTS5를 확인합니다.

## 설치

[0.2.0 스킬 패키지](https://github.com/thisisnew/dougle/releases/tag/v0.2.0-skills-preview.1)의 `Dougle_Skills_0.2.0.zip`을 풀고 해당 폴더에서 실행합니다.

```sh
python3 scripts/install-skills.py
```

두 호스트에 함께 설치합니다. 하나만 설치하려면 `--agent codex` 또는 `--agent claude`를 붙입니다. 프로젝트 파일이나 AI 권한 설정, AGENTS.md/CLAUDE.md를 수정하지 않습니다. 새 AI 세션에서 스킬 이름을 선택하거나 이름을 포함해 요청하세요.

| 호스트 | 설치 위치 | 호출 |
|---|---|---|
| Codex | `~/.agents/skills/dougle-connect`, `dougle-ask` | `$dougle-connect`, `$dougle-ask` 또는 스킬 이름으로 요청 |
| Claude Code | `~/.claude/skills/dougle-connect`, `dougle-ask` | `/dougle-connect`, `/dougle-ask` |

기본 스킬 형식과 설치 위치는 [OpenAI 공식 문서](https://learn.chatgpt.com/docs/build-skills)와 [Claude Code 공식 문서](https://code.claude.com/docs/en/skills)를 따릅니다. 회사의 관리 정책이 개인 스킬을 제한하면 관리자 설정을 따라야 합니다. 두 스킬은 독립적으로 복사할 수 있도록 실행 코드와 참조 문서를 각각 포함합니다.

## 프로젝트와 공유

평소처럼 프로젝트 폴더에서 AI를 실행하세요. 스킬은 그 프로젝트의 절대 경로를 `--workspace`로 전달합니다. 같은 실제 경로는 같은 작업 공간이고, 다른 경로는 별도 공간입니다. 다른 하위 폴더나 Git worktree를 자동 합치지 않습니다. 다른 AI로 이어갈 때 같은 프로젝트 루트를 지정하세요.

저장 위치: `~/Library/Application Support/Dougle Skills/knowledge.sqlite3`. `DOUGLE_HOME`으로 별도 위치를 지정할 수 있지만 두 AI가 같은 값을 사용해야 합니다. 비밀정보를 포함할 수 있는 자료 사본과 업무 기억이 들어 있으므로 Git에 넣지 마세요. 파일 권한은 디렉토리700/DB600이며 별도 암호화는 없습니다.

## 업데이트와 제거

새 패키지를 받아 같은 설치 명령을 실행합니다. 저장소는 유지됩니다. 설치한 스킬을 직접 수정했거나 같은 이름의 다른 스킬이 있으면 덮어쓰지 않고 중단합니다. 직접 수정본을 백업/이동한 뒤 재설치하세요.

```sh
python3 scripts/install-skills.py --uninstall
```

설치된 두 스킬만 제거하고 자료·기억은 남깁니다. 저장소 삭제는 별도 사용자 선택입니다. 이전 데스크톱 앱이나 해당 DB는 이 설치 프로그램이 건드리지 않습니다.

## 문제가 있을 때

- 스킬 없음: 새 세션·설치 위치·회사 정책을 확인합니다.
- Python/FTS5 없음: 회사 승인 Python 환경을 준비합니다. AI 인증 문제가 아닙니다.
- 컨플루언스/DB 접근 없음: 해당 AI의 기존 읽기 도구와 로그인을 연결합니다. 더글 설치 성공과 회사 자료 접근 성공은 별개입니다.
- 자료 일부 제외: 결과의 사유를 확인하고 범위를 좁힙니다. PDF·비 UTF-8 파일은 현재 직접 지원하지 않습니다.
