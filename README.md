# Dougle · 더글

<img src="desktop/src-tauri/icons/128x128.png" alt="Dougle 앱 아이콘" width="80" />

**물어보면 함께 풀어주는 똑똑한 동료.**

Dougle은 파일·폴더·웹페이지를 모아 두고, 궁금한 내용을 AI에게 질문하는 Mac 앱입니다.
검색 준비는 앱이 맡고, 사용자는 질문하고 답변의 출처를 확인하면 됩니다.

![Dougle 대화 화면](docs/images/chat-home.png)

## 설치하기

**[Mac 설치 파일 다운로드](https://github.com/thisisnew/dougle/releases/tag/v0.1.14-preview.1)**

1. 내 Mac에 맞는 파일을 받습니다. **M 시리즈는 `arm64`**, **Intel은 `x86_64`**입니다.
2. 받은 `.dmg` 파일을 열고 앱을 **Applications(응용 프로그램)**로 옮깁니다.
3. 앱을 열고 **Workspace 만들기**로 첫 서재를 만듭니다.
4. **자료 → +**에서 파일·폴더·웹 링크·GitHub를 고릅니다. **AI 연결**에서 AI와 모델을 선택하고 로그인합니다.
5. **대화**에서 질문하세요. 참고 자료를 확인하고 전송에 동의하면 답변을 받습니다. **질문 기록**의 카드를 누르면 지난 대화를 다시 볼 수 있습니다.

연결한 자료는 **동기화 아이콘**으로 한 번에 갱신합니다. 자동 갱신은 **설정 → Workspace 관리**에서 켜고 끕니다. [자료·설정 안내](docs/library-simplification.md)

질문에만 참고할 이미지·문서는 질문창 **+**로 첨부하세요. [첨부 사용법](docs/question-attachments.md)

연결되면 오른쪽 위에 **CODEX / CLAUDE**가 표시됩니다. [짧은 사용 안내](docs/chat-first.md)

Java·Git·Codex 실행 도구는 앱에 포함되어 있습니다. GitHub 자료도 별도 프로그램 설치 없이 가져올 수 있습니다.
0.1.3부터 새 버전을 앱에서 알려 줍니다. 안내에 따라 설치하고 다시 시작하면 됩니다. [업데이트 안내](docs/automatic-updates.md)
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
