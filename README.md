# Dougle · 더글

**물어보면 함께 풀어주는 똑똑한 동료.**

Dougle은 파일·폴더·웹 문서를 모아 두고, 궁금한 내용을 AI에게 질문하는 Mac 앱입니다.
검색 준비는 앱이 맡고, 사용자는 질문하고 답변의 출처를 확인하면 됩니다.

![Dougle 대화 화면](docs/screenshots/dougle-chat.png)

## 설치하기

최신 설치본은 **0.1.38 Preview**입니다. 파일·폴더·검색·AI 별칭·Jira/Confluence 연결 검사를 보강하고 발견한 판독·검색·연결 문제를 수정했습니다. Java 7,916개와 새 HOME 6종 검사가 통과했으며, 실제 회사 SSO·ARM 실기기 실행은 별도 인수가 필요합니다. 웹 링크·Confluence는 지원하며 GitHub 주소는 계속 차단합니다. [시나리오 검증과 한계](docs/release-0.1.38-report.md) · [설치 안내](docs/macos-install.md).

**[Mac 설치 파일 다운로드](https://github.com/thisisnew/dougle/releases/tag/v0.1.38-preview.1)**

1. 내 Mac에 맞는 파일을 받습니다. **M 시리즈는 `arm64`**, **Intel은 `x86_64`**입니다.
2. 받은 `.dmg` 파일을 열고 앱을 **Applications(응용 프로그램)**로 옮깁니다.
3. 앱을 열고 **Workspace 만들기**로 첫 서재를 만듭니다.
4. **자료 → +**에서 파일 또는 폴더를 고릅니다. PC에 내려받은 코드 저장소도 폴더로 추가합니다. **AI 연결**에서 AI와 모델을 선택하고 로그인합니다.
5. **대화**에서 질문을 보내면 관련 자료로 답변을 시작하고, 출처를 아래에 표시합니다. 질문과 답변은 자동 저장되며 **질문 기록**에서 다시 보거나 삭제할 수 있습니다.

연결한 자료는 **동기화 아이콘**으로 한 번에 갱신합니다. 자동 갱신은 **설정 → Workspace 관리**에서 켜고 끕니다. [자료·설정 안내](docs/library-simplification.md)

**설정 → 플러그인**에서 MySQL 접속 설정·연결 테스트·DB 구조 수집을 제공합니다. Notion·Jira/Confluence는 브라우저 승인 후 **자료 → + → 웹 링크**로 페이지·이슈를 등록합니다. 회사의 앱 승인 정책과 해당 자료의 읽기 권한이 필요합니다. 다국어 검색 엔진은 설치 파일에 기본 포함됩니다. [연결 방법과 지원 범위](docs/plugins.md)

질문에만 참고할 이미지·문서는 질문창 **+**로 첨부하세요. [첨부 사용법](docs/question-attachments.md)

연결되면 오른쪽 위에 **CODEX / CLAUDE**가 표시됩니다. [짧은 사용 안내](docs/chat-first.md)

Java·Codex 실행 도구와 검색 엔진은 앱에 포함되어 있습니다. **자료 → + → 웹 링크**에서 HTTPS 문서를 추가할 수 있습니다. Confluence Cloud는 설정의 Jira · Confluence 플러그인을 연결해야 합니다. GitHub 주소는 지원하지 않습니다. 코드 저장소는 PC에 내려받아 폴더로 추가하고, 원격 변경은 직접 pull한 뒤 동기화하세요. 로컬 폴더 수집에는 AI·인터넷 연결이 필요하지 않습니다.
자동 업데이트 피드는 현재0.1.16에 유지되어 있으므로 최신 Preview는 위 설치 파일로 직접 설치하세요. [업데이트 안내](docs/automatic-updates.md)
앱이 열리지 않거나 기존 앱을 업데이트하려면 [자세한 설치 안내](docs/macos-install.md)를 확인하세요.

## 기술 스택

| 구분 | 사용 기술 |
|---|---|
| 데스크톱 앱 | Tauri 2 |
| 화면 | React · TypeScript · Vite · Tailwind CSS · shadcn/ui |
| 서버 | Java 21 · Spring Boot 4.1 |
| 저장·검색 | SQLite · Apache Lucene 10 · Nori |
| 자료 분석 | Apache Tika · JavaParser |
| AI 연결 | Codex · Claude Code |

## 더 알아보기

[사용 방법](docs/chat-first.md) · [검색 원리](docs/internal-search.md) · [설계도](docs/design-map.md) · [개발 환경](docs/development.md) · [전체 문서](docs/README.md)

현재 개발 중인 실무 전문가 기능은 연속 대화와 범위를 확인한 읽기 전용 DB 조회를 포함합니다. Slack 문의·Datadog 연결 기능은 제거했습니다. 설치 파일의 지원 범위와 개발 소스는 다를 수 있습니다. [구현·검증 상태](docs/expert-implementation.md)와 [50개 인수 기준](docs/expert-adoption.md)에서 실제 검증과 남은 항목을 구분합니다.
