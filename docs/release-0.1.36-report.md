# Dougle 0.1.36 — 폴더 수집 복구와 웹 링크

로컬 설치·패키지 검증 완료, GitHub 공개 준비 중. 회사 Mac에서의 실제 실패 원인은 오류 로그 없이 확정하지 않는다.

## 변경

- 로컬 폴더 내부의 심볼릭 링크·특수 파일·미지원 경로를 부분 실패로 기록한다. 정상 소스코드는 계속 수집·검색하며 외부 링크를 따라가지 않고 원본을 변경하지 않는다.
- 루트 폴더 누락/권한 오류 안내를 구체화하고, 로컬 폴더의 알 수 없는 오류에 인터넷 연결을 요구하지 않는다.
- 웹 링크 등록·자동 동기화·opt-in 폴링을 복구한다. Confluence Cloud는 기존 Jira · Confluence 플러그인의 읽기 전용 연결을 사용한다.
- GitHub 연결 UI/API는 복구하지 않는다. 웹 링크에 붙인 GitHub 저장소/파일/API/raw 주소와 리다이렉트를 차단한다. 기존 보관 자료는 삭제하지 않는다.
- 파일당256MiB, 총8GiB·2만개, 경로16단계/이름120자 제한은 유지한다. 깊은 경로는 해당 하위 폴더를 따로 연결할 수 있다.

## 검증 시나리오

| 시나리오 | 검증 위치 |
| --- | --- |
| 정상 Java + 외부 심볼릭 링크 + 순환 링크 + 깊은 경로 + 긴 이름 | LocalFolderCollectorTest |
| 미지원 경로만 제외, 외부 비밀 내용 미수집·원본 불변 | LocalFolderCollectorTest, CollectorTest |
| 부분 수집 상태와 정상 문서 검색 | SourceIntegrationTest |
| 문제 항목 제거 후 PARTIAL → READY, 검색 유지 | source-recovery-smoke.mjs, 동봉 JAR/Java |
| AI 설치/인증 없는 새 HOME에서 폴더 수집·검색 | source-recovery-smoke.mjs |
| 웹 문서 등록 → 자동 수집 → 검색 | SourceIntegrationTest, 가상 HTTP 응답 |
| Notion 연결 문서 수집/검색 | PluginIntegrationTest, 가상 연결 |
| Confluence 인증 없음 → 연결 안내, 로그인 화면 미색인 | SourceIntegrationTest, 패키지 smoke |
| 웹 추가 UI·등록 후 자동 동기화·Confluence 연결 버튼 | SourceMvp.test.tsx |
| GitHub 저장소/blob/raw/API/대문자/끝점 주소 거부 | WebSourcePolicyTest, SourceIntegrationTest, UI |
| 일반 웹 → GitHub 리다이렉트는 두 번째 요청 전 차단 | PublicHttpReaderTest |
| GitHub 보관 열람 유지, sync/poll 금지, URL poll 허용 | SourceMvpBoundaryTest, PollingIntegrationTest, UI |
| 실제 브라우저 URL 입력 정렬·붙여넣기 거부·로컬 오류 안내 | 생산 UI + 가상 API, source-recovery-036.png |

실제 회사 자료/계정·Confluence SSO, ARM 실기기 실행과 Apple 공증은 미검증이다. 실제 외부 AI 호출0회. UI 가상 서버 검증을 실서비스 인증 성공으로 취급하지 않는다.

## 테스트 결과

- 전체 Java 재실행: 1,235개 중1,209통과·25생략·1실패(현재 실행에서 갱신된140 XML만 집계). 실패는 기존 ChatThreadIntegrationTest의 후속 질문 근거에 NightState가 추가된 경우다. 해당 클래스50개를 코드 변경 없이 별도 재실행해 통과했다. 전체 실행이 한 번에 모두 통과했다고 보고하지 않는다.
- 최초 전체 실행의 이전 정책 기대4건은 새 정책 검증으로 수정했다. 당시 기존 CLI 타이밍 검사4건은 구현 변경 없이 재실행 및 다음 전체 실행에서 통과했다.
- 폴더 수집·실패 opt-in75개 통과. 수집기 가상 자료 검사이며 회사 폴더 인수를 대신하지 않는다.
- UI 전체655개·opt-in50개 통과. 마지막 오류 안내 수정 후 관련53개, 추가 툴팁14개 재검증 통과.
- Node177개, Rust24개 통과. 생산 UI의 실제 브라우저 조작은 가상 API로 검증했다.

동봉 Java/JAR의 별도 HOME에서 AI 실행 경로를 없는 경로로 지정하고 소스 폴더 수집·검색, 심볼릭 링크/깊은 경로 제외, 문제 항목 제거 후 READY 복구, 원본 불변, GitHub 차단, Confluence 인증 안내 시나리오가 통과했다. 처음에는 테스트용 Workspace 이름이20자를 초과해400, 이후 검색 엔진 준비 전에 조회해 빈 결과가 나왔다. 유효한 이름과 `/search-model` READY 대기를 적용해 검증했다. 제품의 한도를 제거하거나 가상 답변으로 대체하지 않았다.

## 설치 파일

Intel DMG의 실제 React/네이티브 런타임 검사, ARM DMG의 정적 무결성·서명·CPU별 모델 검사, 양쪽 업데이트 아카이브의 서명·버전·현재 JAR 일치 검사를 통과했다. `/Applications/Dougle.app`을0.1.36으로 교체하고 실행을 확인했다. 기존 앱 및 정지된 데이터는 `~/Library/Application Support/Dougle Backups`에 백업했다.

| 파일 | 크기(bytes) | SHA-256 |
| --- | ---: | --- |
| Dougle_0.1.36_arm64.dmg | 477460621 | c97860c219863de84467097c7c4b66b1407d64d510b6e31df2fcd99558a8656d |
| Dougle_0.1.36_x86_64.dmg | 485410366 | 2a14a944d7ce82b8a71c0e1b79e6efa223e068fb38cbb1213ec6ba8e58024dd0 |

Intel JAR: `3375724929dbf90286d127fa1b5c94e01c8bedf1a0b9319816edfe57b7a874fe`.
ARM JAR: `ea50c7fe2b623b5c44c93accad8573275e5b8c2e746f8fa0006f7a70de9c4979`.

재현: `bash scripts/maven.sh test`, `cd frontend && npm test`, `node scripts/source-recovery-smoke.mjs desktop/src-tauri/resources`. 폴더75개는 `-Dtest=FolderAcceptanceTest,FolderFailureAcceptanceTest -Dforge.acceptance.collection=true -Dforge.acceptance.folderFailure=true`로 실행한다.
