# Dougle · 더글

**평소 쓰는 AI가 회사 자료를 읽고, 업무 지식으로 정리하고, 근거를 찾아 답하도록 하는 스킬 모음.**

더글은 실행 프로그램이 아닙니다. Python·Node·Java·SQLite·별도 서버나 모델을 설치하지 않습니다. 스킬에 자료 정리 절차를 담고, 현재 AI가 가진 파일·검색·서비스 조회 도구를 사용합니다.

## 설치

Claude Code에서:

```text
/plugin marketplace add thisisnew/dougle
/plugin install dougle@dougle-marketplace
```

사용 명령은 `/dougle:dougle-connect`, `/dougle:dougle-ask`, `/dougle:dougle-reload`입니다.

Codex에서는 이 저장소의 `skills/dougle-connect`, `skills/dougle-ask`, `skills/dougle-reload`를 스킬로 설치해 달라고 요청하세요. 또는 [배포 ZIP](https://github.com/thisisnew/dougle/releases/tag/v0.3.1-skills-preview.1)의 `skills` 안 세 폴더를 `~/.agents/skills/`에 복사하세요. `$dougle-connect`, `$dougle-ask`, `$dougle-reload` 또는 이름으로 요청하면 됩니다. [상세 설치 안내](docs/skills/install.md)

## 사용

| 작업 | Claude 플러그인 | Codex |
|---|---|---|
| 자료 연결 | `/dougle:dougle-connect` | `$dougle-connect` |
| 질문 | `/dougle:dougle-ask` | `$dougle-ask` |
| 자료 갱신 | `/dougle:dougle-reload` | `$dougle-reload` |

[명령별 예시](docs/skills/usage.md)

```text
더글로 현재 프로젝트랑 이 컨플루언스·노션 문서를 연결해.

이 DB도 같이 참고해. 연결이 안 되면 필요한 설정을 알려줘.

입하 확정할 때 어떤 테이블에 데이터가 들어가?

그건 예전 방식이야. 지금은 검수를 별도로 처리해. 기억해 줘.

dougle-reload 컨플루언스 입하 설계 문서
dougle-reload 전체
```

- **connect:** 자연어로 자료 요청 → 기존 등록 여부 확인 → 호스트 도구로 읽기 → 출처와 업무 개념 정리.
- **reload:** 연결한 자료를 명시적으로 다시 읽고 변경된 업무 지식 갱신. 예: `dougle-reload 전체`, `dougle-reload 컨플루언스 입하 설계`.
- **ask:** 관련 기억으로 찾을 곳을 좁히고 원본을 재확인 → 근거 기반 답변 → 필요한 지식·정정 반영.
- 같은 자료는 재사용/갱신합니다. 사용자가 중복 여부를 먼저 확인할 필요 없습니다.
- 연결할 수 없으면 호스트 AI가 도구 부재·로그인·권한·일시 오류를 구분해 안내합니다. 실제 읽기 전에는 연결 완료로 표현하지 않습니다.
- 서비스 종류를 고정하지 않습니다. 사용 가능한 **인증된 읽기 도구**가 있어야 해당 서비스를 읽을 수 있습니다.

[자료 갱신 방법](docs/skills/reload.md)

## 업무 기억

AI가 `~/.dougle/workspaces/` 아래 Markdown에 출처·업무 개념·규칙·문서/코드/API/테이블 관계를 정리합니다. 두 AI가 같은 작업 공간을 사용하면 같은 파일을 읽습니다. 별도 지식 열람 UI는 없습니다. 원본 변경과 사용자 정정의 처리 절차도 스킬에 포함됩니다.

더글 전용 런타임이 강제하는 DB 제약이나 자동 색인/감시 기능은 없습니다. 중복 검사·근거 갱신은 호스트 AI가 수행하는 절차입니다. 동시에 여러 AI가 같은 기억을 쓰는 것은 피해야 합니다. 스킬이 호스트 전체를 샌드박싱하지 않으므로 회사 시스템은 읽기 전용 계정·권한을 사용하세요.

## 문서와 검증

[설치](docs/skills/install.md) · [연결·중복 처리](docs/skills/connections.md) · [자료 정리 방식](docs/skills/architecture.md) · [검증 범위](docs/skills/verification.md) · [개발](docs/skills/development.md)

0.2.0의 Python/SQLite 구현은 철회했습니다. 기존 저장 데이터는 삭제하거나 자동 이전하지 않습니다. 이전 앱 코드는 보존된 이력이며 현재 스킬 설치에 사용하지 않습니다. 새 버전의 검증을 과거 앱/실행기 테스트 수치로 대신하지 않습니다.
