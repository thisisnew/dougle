# Knowledge Forge

<img src="desktop/src-tauri/icons/128x128.png" alt="꼬마 탐정 아이콘" width="80" />

**흩어진 자료에서, 필요한 답을.**

파일·폴더·웹페이지를 모아 두고, 궁금한 내용을 AI에게 질문하는 Mac 앱입니다.
검색 준비는 앱이 맡고, 사용자는 질문하고 답변의 출처를 확인하면 됩니다.

![Knowledge Forge 대화 화면](docs/images/chat-home.png)

## 설치하기

**[Mac 설치 파일 다운로드](https://github.com/thisisnew/knowledge-forge/releases/tag/v0.1.11-preview.1)**

1. 내 Mac에 맞는 파일을 받습니다. **M 시리즈는 `arm64`**, **Intel은 `x86_64`**입니다.
2. 받은 `.dmg` 파일을 열고 **Knowledge Forge**를 **Applications(응용 프로그램)**로 옮깁니다.
3. 앱을 열고 **Workspace 만들기**로 첫 서재를 만듭니다.
4. **자료** 탭에 파일을 넣고, **AI 연결**에서 사용할 AI와 모델을 골라 연결합니다. 필요하면 안내에 따라 로그인하세요.
5. **대화**에서 질문하세요. 참고 자료를 확인하고 전송에 동의하면 답변을 받습니다. 지난 질문은 왼쪽 목록에서 다시 볼 수 있습니다.

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
