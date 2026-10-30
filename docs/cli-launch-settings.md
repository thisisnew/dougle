# 설치 앱에서 CLI 경로 설정하기

0.1.40부터 AI 연결 화면의 고급 수동 경로 설정은 제거했습니다. 사용자는 자동 탐색 결과에서 도구를 선택합니다. 아래 수동 설정 API·재시작 계약은 이전 버전 설정의 호환을 위한 내부 경로입니다.

## 0.1.35 연결 선택 흐름

기본 연결 화면의 도구 선택은 경로 저장만 하지 않습니다. `POST /api/ai/launch-settings/connect/{provider}`에 탐색된 `path`와 `expectedRevision`을 보내면 해당 도구의 인증 상태를 확인합니다. 인증이 확인되면 저장과 현재 실행 환경 적용을 함께 수행하고, 재시작 없이 같은 환경으로 답변합니다. 로그인 필요 응답일 때는 선택한 환경을 적용하되 연결 완료로 표시하지 않고 기존 브라우저 로그인 절차로 이어집니다.

시간 초과·미지원·잘못된 인증 응답은 기존 설정을 바꾸지 않습니다. 탐색 목록 밖의 명령과 오래된 revision은 실행 전에 거절합니다. 도구 조회 자체는 후보를 실행하지 않습니다. 인증 확인은 실제 모델 호출이나 회사 서비스 사용 권한 검증이 아닙니다.

`CliExecutionGate`는 인증 확인·로그인·답변·계획·지식 생성 중 실행 환경 교체를 막습니다. 활성 실행 식별자가 바뀌면 기존 미리보기의 승인을 그대로 재사용하지 않습니다. 진단과 답변 실행기도 시작 시 경로를 고정하지 않고 같은 활성 환경을 사용합니다.

고급 수동 경로 저장 `PUT`은 호환성을 위해 다음 재시작 적용 방식을 유지합니다. 아래 설명은 이 고급 API 및 이전 버전의 흐름입니다. 기본 선택 화면에서는 재시작을 요구하지 않습니다.

회귀 검사: `CliLiveConnectionTest`, `CliLaunchIntegrationTest`, `ProviderConnectionPanel.test.tsx`, `InstalledCliChoices.test.tsx`, `fresh-install-smoke.mjs --profile-alias`. 가상 CLI/임시 HOME 검사와 실제 회사 인증 검증을 구분합니다.

## 0.1.17 (로컬 설치 완료, GitHub 미공개)

사용자 화면에서는 실행 경로 입력을 제거합니다. 제공자·모델 선택 후 연결하며 Codex는 기존 내장 실행 파일을 사용합니다. Claude는 `~/.local/bin`, `~/.npm-global/bin`, Homebrew의 표준 경로와 절대 PATH 항목에서 실행 가능한 `claude` 파일을 찾습니다. 검색 자체는 프로세스를 실행하거나 인증 내용을 읽지 않습니다. 설치하지 않은 Claude를 자동 설치하거나 연결 성공으로 표시하지 않습니다.

기존 저장 경로 및 내부 호환 API는 보존합니다. 아래 수동 설정 절차는 이전 버전용이며 새 UI 진입점이 아닙니다. 연결 확인은 인증 상태 확인이며 실제 모델 응답·계정 이용 한도를 검증하지 않습니다.

상단 **CLI 경로 설정**을 누릅니다. Finder에서 앱을 실행해 CLI를 찾지 못할 때 사용합니다.

1. Codex/Claude Code를 직접 설치하고 로그인합니다. 이 앱은 설치·로그인을 대신하지 않습니다.
2. 설치한 실행 파일의 **절대 경로**를 입력합니다. 예: `/usr/local/bin/codex`. 파일 뒤에 옵션을 붙이지 마세요.
3. 실행 파일과 해당 폴더를 신뢰하는지 확인하고 **경로 저장**을 누릅니다.
4. 진행 중 작업이 끝나면 앱을 완전히 종료하고 다시 엽니다.
5. 같은 화면에서 **CLI 설치 확인**을 누릅니다. 성공해도 실제 모델 사용·로그인 상태까지 확인한 것은 아닙니다.

터미널에서 `command -v codex` 또는 `command -v claude`로 설치 위치를 확인할 수 있습니다.
빈칸으로 저장하면 앱 시작 옵션의 기본 명령으로 돌아갑니다. 경로에 공백이 있으면 그대로 입력하며 따옴표로 감싸지 않습니다.
다른 PC로 옮겼다면 그 PC의 경로를 다시 설정하세요.

## 안전한 동작

- 조회·저장·재시작만으로 CLI나 AI를 실행하지 않습니다. 설치 확인 버튼만 해당 CLI의 `--version`을 실행합니다.
- 절대 경로·실행 가능한 일반 파일만 받습니다. 실제 설치의 심볼릭 링크는 허용하지만 인증 파일을 읽지는 않습니다.
- 셸을 실행하지 않고 실행 경로를 하나의 프로세스 인자로 전달합니다.
- 실행 파일의 폴더를 자식 프로세스 PATH 앞에 추가합니다. 같은 폴더의 Node 등 shebang 의존성을 찾기 위해서입니다. 폴더 전체를 신뢰해야 합니다.
- PATH의 다른 폴더에만 있는 의존성까지 자동 검색하지 않습니다. 셸 초기화 파일을 읽거나 실행하지 않습니다.
- API 키·로그인 데이터는 저장하지 않습니다. SQLite에는 경로와 설정 revision만 보관합니다.
- 다른 창이 먼저 저장했다면 409로 막습니다. 다시 불러온 뒤 확인하세요.
- 저장은 **다음 앱 실행에만 적용**합니다. 현재 실행 중인 작업의 CLI는 바꾸지 않습니다.
- 활성 CLI 경로를 승인 digest에 묶습니다. 재시작 후 경로가 달라지면 이전 승인으로 새 작업을 시작할 수 없습니다.

경로 식별자는 실행 파일 내용의 해시나 디지털 서명 검증이 아닙니다. 같은 경로의 바이너리 교체까지 탐지하지 않습니다.
CLI 자체의 네트워크 행동을 OS 차원에서 격리하는 기능도 아닙니다. 신뢰할 수 있는 공식 설치만 사용하세요.

## 모델·코드 대조

- SQLite v21 `cli_launch_settings`: 앱 전체 설정 1행, `codex_path`, `claude_path`, `revision`.
- `CliLaunchConfiguration`: 시작 시 활성값 고정, 저장된 값과 비교해 재시작 필요 표시.
- `CliLaunchStore`/`JdbcCliLaunchStore`: 경로 검사와 예상 revision 갱신.
- `CliCuratorModel`/`CliDiagnosticsAdapter`: 같은 활성 명령을 사용합니다. Codex 스트림 경로도 동일합니다.
- `CuratorSettings.executionBinding`: 지식 초안·답변 미리보기 digest에 활성 명령 식별자 포함.
- `CliProcessPath`: 신뢰한 절대 실행 파일 폴더만 자식 PATH 앞에 추가.
- `CliLaunchPanel`: 명시적 저장·재확인·현재/다음 실행 구분, 재시작 대기 중 진단 비활성화.

## API

`GET /api/ai/launch-settings` → `{saved:{codexPath,claudePath,revision},activeCodexCommand,activeClaudeCommand,restartRequired}`

`PUT /api/ai/launch-settings`

```json
{"codexPath":"/usr/local/bin/codex","claudePath":"","expectedRevision":0,"acknowledged":true}
```

400 잘못된 경로/확인 누락, 409 설정 충돌. 기존 로컬 변경 헤더·설치형 세션 인증을 유지합니다.

## 업데이트 주의

v20→v21 이관은 기존 자료를 보존하고 빈 실행 경로 설정을 추가합니다.
승인 binding이 달라지므로 **이전 미리보기·승인은 다시 확인**하세요. 이전에 고정한 대화 문맥도 새 묶음으로 다시 선택해야 할 수 있습니다.
완료된 답변·지식·이력은 지우지 않습니다. 이전 v18/v20 앱으로 최신 DB를 열지 마세요.

## 검증 명령

```sh
bash scripts/maven.sh test -Dtest=CliLaunchIntegrationTest,CliProcessPathTest,MigrationTest
cd frontend && npm test -- --run src/CliLaunchPanel.test.tsx src/App.test.tsx src/CliStatusPanel.test.tsx
# 실행 JAR 빌드 후: 재시작 적용·이전 승인 차단·가상 --version 실행
node scripts/cli-settings-smoke.mjs
```

가상 실행 파일로 검증합니다. 실제 외부 AI 전송이나 실제 로그인 검증이 아닙니다.
실제 JAR에서 저장 전/후 binding 유지 → 재시작 후 변경 → 이전 승인 409 → 명시적 fake --version 진단을 확인했습니다.
브라우저에서도 경로 설정·가상 버전 확인·기본값 복원 저장·재시작 안내를 확인했습니다.
