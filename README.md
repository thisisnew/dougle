# Dougle · 더글

**평소 사용하는 AI에게 회사 업무 맥락을 연결하는 스킬 모음.**

Claude Code나 Codex를 프로젝트 폴더에서 실행하고, 더글에 자료 연결이나 업무 질문을 요청하세요. 별도 앱·웹 화면·AI 선택 메뉴는 없습니다. 같은 PC의 같은 작업 공간에서는 연결 자료와 업무 기억을 두 AI가 공유합니다. 모델 자체를 재학습하거나 전체 대화 기록을 자동 이전하는 기능은 아닙니다.

## 설치

지원 대상: Apple Silicon(M 시리즈) Mac, 이미 사용하는 Claude Code 또는 Codex, **Python 3.9 이상과 SQLite FTS5**. Python은 이 패키지에 포함되지 않습니다. 회사 정책에 맞는 Python을 먼저 준비하세요. 기존 더글 앱·Java·Node·검색 모델·별도 AI API 키는 필요 없습니다.

[스킬 배포 파일](https://github.com/thisisnew/dougle/releases/tag/v0.2.0-skills-preview.1)을 내려받아 압축을 풀고 그 폴더에서:

```sh
python3 scripts/install-skills.py
```

Codex의 `~/.agents/skills`와 Claude Code의 `~/.claude/skills`에 두 스킬을 설치합니다. 새 AI 세션을 열어 사용하세요. 기존 회사용 실행 명령과 로그인은 그대로 사용합니다. [설치·업데이트·삭제](docs/skills/install.md)

## 사용

Codex에서는 `$dougle-connect`, `$dougle-ask`를 선택하거나 스킬 이름으로 요청하세요. Claude Code에서는 `/dougle-connect`, `/dougle-ask`를 사용합니다. 개념적인 `dougle/connect` 묶음을 양쪽의 표준 스킬 이름으로 배포합니다.

```text
dougle-connect로 현재 프로젝트와 이 컨플루언스 문서를 연결해.
https://company.atlassian.net/wiki/spaces/TEAM/pages/123

dougle-ask로 입하 확정 시 어떤 테이블에 데이터가 들어가는지 설명해.

그건 이전 방식이야. 지금은 입하 확정과 검수를 별도로 처리해. 기억해 줘.
```

자료 종류를 메뉴에서 고르지 않습니다. AI가 요청을 해석하고 공통 실행 도구로 읽기·검색·기억 저장을 수행합니다. 연결 후 선택지가 필요하면 `1 계속 연결하기`, `0 이전으로`만 제공합니다. 내부 업무 지식을 열람·관리하는 별도 UI는 없습니다. 답변에는 근거와 필요한 한계를 표시합니다.

## 현재 구현 범위

| 자료/기능 | 지원 방식 |
|---|---|
| 로컬 코드·문서 | UTF-8 텍스트를 분할·검색. 변경·삭제는 다음 검색에 반영 |
| 컨플루언스·웹 | **현재 AI에 이미 연결된 읽기 도구**로 가져온 실제 본문을 출처와 함께 저장 |
| 회사 DB | 기존 읽기 전용 도구로 조회한 구조를 저장. 인증·실시간 조회는 해당 도구가 담당 |
| 로컬 SQLite | 읽기 전용 스키마 수집과 제한된 테이블 조회 |
| 업무 기억 | 근거 기반 사실·추론·사용자 정정을 구분. 원본 변경 시 관련 기억을 검색에서 제외 |
| 공유 | 동일 PC·동일 작업 공간에서 Claude/Codex가 같은 저장소 사용 |

**회사 SSO·컨플루언스 OAuth·MySQL/PostgreSQL 드라이버가 새로 내장된 것은 아닙니다.** 접근 도구가 없으면 연결은 대기 상태이며 로그인·도구 설정을 안내합니다. 외부 자료는 읽은 시점의 스냅샷입니다. PDF/OCR·임베딩 검색·AWS/Kafka 직접 연결·기존 앱 DB 이전은 이번 버전에 포함하지 않습니다. [연결 계약과 한계](docs/skills/connections.md)

원본 코드·문서·DB는 수정하지 않습니다. 더글의 로컬 저장소만 갱신합니다. 단, 스킬이 호스트 AI의 다른 도구까지 전역으로 차단하는 것은 아닙니다. [읽기 전용과 보관 정책](docs/skills/architecture.md)

## 개발과 검증

```sh
python3 scripts/build-skills.py --check
python3 -m unittest discover -s tests/skills -v
```

[검증 결과와 미검증 항목](docs/skills/verification.md) · [개발 안내](docs/skills/development.md) · [문서 목록](docs/README.md)

`backend/`, `frontend/`, `desktop/`은 이전 0.1.x 앱의 보존 코드입니다. 현재 스킬을 설치·실행할 때 사용하지 않습니다. 앱 자동 업데이트 피드는 스킬 배포와 별개이며 변경하지 않습니다. [이전 앱 기록](docs/skills/legacy-index.md)
