# 0.1.20 질문 한 번으로 답변·스트리밍·이력 삭제

## 최종 배포 후보 — 2026-10-25

- 검색 엔진은 자동 준비하며 설정 메뉴/상태 모달은 표시하지 않습니다. 대화 입력창에서 준비·실패 시에만 안내합니다.

- 다국어 검색 엔진을 Intel/Apple Silicon 설치본에 기본 포함하고 자동 준비합니다. Notion 본문 해제·문맥 확보·검색 조각 평가를 보완했습니다. 기존 Notion 자료는 다시 동기화해야 합니다.
- 질문 기록의 체크박스 선택/전체 선택/일괄 삭제와 체크박스 정렬을 반영했습니다. 답변 스트리밍·완료 후 인용·수집 진행/실패 안내·설정 내 플러그인을 포함합니다.
- 최신 검증은 검색 설정 제거 후 UI 49파일·395개 통과, Java 462개 통과/opt-in 6개 제외입니다. 기존 이름 제한 fixture 두 클래스는 제외했습니다. 마지막 정렬 수정 후 질문 기록 9개와 UI 빌드를 다시 확인했습니다.
- 승인된 실제 Notion 문서의 40개 질문 평가 결과는 최종 40/40이며 해당 평가셋 한정입니다. 원문/답변/인증 정보는 공개하지 않습니다. [검증 상세](notion-search-verification.md).
- Intel 설치본 교체·재실행·JAR 일치·서명 검사 완료. ARM 실기기 및 Apple 공증은 검증하지 않았습니다. 아래는 개발 당시의 중간 기록입니다.

## 2026-10-25 수집·검색 개선과 문서 브랜드 정리

- DB v25: 파일별 분석 체크포인트·수집 실패 경로 보관, 부분 실패 시 이전 성공 자료 보존. 파일 256 MiB/자료 8 GiB·20,000개 한도를 분리하고 디스크 스냅샷·스트림 추출·문단/선언 분할·실제 진행/취소를 적용했습니다.
- HTML 본문 우선 추출·인코딩 감지와 Git 산출물 제외/읽기 실패 격리, 선택형 로컬 다국어 임베딩·키워드 결합·재순위화, 답변 완료 후 실제 인용 출처만 표시합니다. 검색 흐름은 Java 코드로 고정하며 답변 AI에 임의 셸 검색을 맡기지 않습니다.
- UI 전체 49파일·391개 통과. Java 최종 55클래스·455개 중 453개 통과/opt-in 2개 제외, 실패·오류 0개입니다. 기존 Workspace 이름 제한을 위반하는 두 클래스의 10개 오류는 별도로 남겨 재실행에서 제외했습니다. 전체 Java가 무조건 통과했다고 보고하지 않습니다.
- 13.2 MiB 업로드의 마지막 문장 검색, 실제 Intel 로컬 모델의 한국어↔영문 코드·바꿔 쓴 질문·무관한 질문 제외·Workspace 격리를 검사했습니다. 가상 브라우저에서는 업로드·첨부·완료 인용·이력·검색 설정·자료 목록을 확인했습니다. 가상 CLI 1회, 실제 답변 AI 0회입니다.
- 문서 112개를 점검하고 현재 이름·사용법·화면을 Dougle로 갱신했습니다. 로컬 문서 링크 누락 0개, 호환 경로·과거 검증 기록은 유지합니다. 실행 검사에 남은 이전 CLUE 제목 기대값도 갱신했습니다.
- 최종 UI/JAR 빌드 및 양 CPU DMG/업데이트 아카이브의 서명·CPU·버전·현재 JAR·설치 복사본 정적 검증 완료. `DOUGLE_PACKAGES_VERIFIED`: `/tmp/dougle-upgrade-final-packages.log`. 로컬 manifest만 갱신하고 공개 릴리스·피드는 변경하지 않았습니다.
- Notion UTF-8 수정은 유지되며 실제 사용자 계정 승인 성공은 미확인입니다. ARM 실기기 추론·상한 크기 성능·Apple 공증도 확인한 것으로 보고하지 않습니다. [상세 경계와 검사](ingestion-retrieval-upgrade.md).
- 최종 체크섬 6개 OK, Intel 백업 설치·재실행 완료(`2026-10-25T07-43-59.154Z-before-dougle`). 설치 JAR/네이티브/아이콘 일치·codesign·UI 200/인증 없는 API 403 확인. 패키지 smoke는 기존 20자 초과 Workspace fixture에서 중단되어 전체 통과가 아닙니다. 실제 자료·인증 내용을 검사에 사용하지 않았습니다.

## 2026-10-25 Notion 토큰 요청 UTF-8 수정

- 사용자 추가 화면으로 실패를 토큰 교환 단계의 HTTP 400 `invalid_request` 또는 `unsupported_grant_type`으로 좁혔습니다.
- 공개 token endpoint에 가상 client/code만 사용한 동일 요청 3개로 재현: Content-Type에 ISO-8859-1을 쓰면 `invalid_request`/폼 데이터 파싱 오류 400, charset 미지정 또는 UTF-8이면 폼 파싱을 통과하여 가상 client에 대한 `invalid_client` 401입니다. 실제 사용자 코드/토큰/문서·외부 AI는 사용하지 않았습니다.
- BrowserPluginHttp의 StringEntity에 UTF-8을 명시하여 인증 코드 교환·refresh 모두 수정합니다. PKCE/state/redirect/resource/암호화 저장/읽기 도구 허용 목록은 유지합니다. 테스트는 실제 entity Content-Type과 특수문자/한글 form round-trip 및 JSON Unicode를 검사합니다.
- 실제 계정의 최종 승인 성공은 아직 미검증입니다. 이전 resource 보완만으로 해결되지 않았으며 이번에는 HTTP 요청 인코딩의 재현된 원인을 수정합니다.
- 관련 Java 4개 클래스 22개 통과 및 JAR 빌드 성공. 이후 `FORGE_VERIFY_NOTION_FORM=true`로 BrowserPluginHttpTest 3개 통과: 실제 수정된 Apache HTTP 전송 경로에서도 가상 client에 대한 예상 401을 받아 폼 파싱 거부 400이 해소됨을 확인했습니다. 3개 중 2개는 앞선 검사와 겹치므로 합산하지 않습니다. 공개 통신 검사는 기본 테스트에서 환경 변수 없이는 건너뜁니다. 로그 `/tmp/dougle-notion-utf8-tests.log`, `/tmp/dougle-notion-utf8-wire-test.log`, `/tmp/dougle-notion-utf8-build.log`.
- UI 변경이 없으므로 이전 UI 빌드/화면을 재사용합니다. 실제 계정 인증·문서 열람·외부 AI는 검사에 사용하지 않았습니다.
- Intel 설치/재실행 완료, 백업 `2026-10-25T05-49-39.708Z-before-dougle`. 현재 JAR/서명된 네이티브 일치·codesign·UI 200 및 인증 없는 session/plugins API 403 확인. 양 CPU DMG/업데이트 서명·버전·CPU·정적 설치 복사본 검증 완료. 공개 피드/릴리스는 유지하고 로컬 manifest만 갱신했습니다. 로그 `/tmp/dougle-notion-utf8-install.log`, `/tmp/dougle-notion-utf8-packages.log`. 실제 사용자 승인 성공은 여전히 별도 확인입니다.

## 2026-10-25 출처 표시 · 공식 서비스 로고 · 인증 실패 진단

- 참고 자료는 작업 성공과 화면의 최종 글자 표시가 모두 끝난 뒤에 표시합니다. 완료 이력은 지연 없이 보여 주고 대기/생성/실패/중단에는 출처를 먼저 제안하지 않습니다. 기존 검색 후보/저장/전송 계약은 변경하지 않았습니다.
- 플러그인 카드 설명 3개와 임시 아이콘을 제거했습니다. Notion·Jira·Confluence·MySQL의 공식 사이트 자산을 로컬 동봉합니다. Jira와 Confluence의 공통 인증 카드는 유지합니다. 자산 출처는 `frontend/src/assets/plugins/README.md`에 있습니다.
- 사용자 Notion 승인 실패를 실제 앱 화면에서 확인했습니다. 기존 메시지는 HTTP 실패를 일반화하여 단계/상태가 없었습니다. 승인과 토큰 교환/갱신에 같은 resource를 보내고, 콜백에도 단계·안전한 오류/HTTP 코드를 표시합니다. HTML escaping, 토큰/코드/원문 오류 미노출 및 실패 시 미연결을 유지합니다. 실제 재승인 성공/정확한 원인은 아직 확인되지 않았으며 이 변경만으로 해결되었다고 간주하지 않습니다.
- Java 관련 3개 클래스 20개 통과. UI 첫 실행의 자산 URL 단언은 Vite SVG 인라인 처리를 반영하여 수정했습니다. 후속 병렬 검사에서 App 3개가 5초 타임아웃하여 단일 worker/15초 한도로 재검사합니다. 최종 검사/설치 결과는 후속 기록을 따릅니다.
- 최종 단일 worker/15초 재검사: UI 5개 파일 86개 통과, TypeScript/Vite·JAR 빌드 통과. 기존 메인 청크 500KB 경고는 유지합니다. 로그: `/tmp/dougle-reference-ui-tests-final.log`, `/tmp/dougle-reference-ui-build.log`, `/tmp/dougle-reference-backend-tests.log`, `/tmp/dougle-reference-jar.log`. 이전/단독 검사 수를 더하지 않습니다.
- 13:35–13:36 KST Intel 설치 갱신/재실행 완료. 백업 `2026-10-25T04-35-31.965Z-before-dougle`; 인증 미변경·닫힌 DB 백업·현재 JAR/서명 확인, `/` 200·인증 없는 session/plugins API 403. 실제 Notion 재시도 결과를 요청했으며 성공 여부는 미확인입니다.
- 가상 브라우저 TaskSpace 90에서 공식 아이콘 4개의 로드와 설명 제거/정렬을 확인하고 [플러그인 화면](screenshots/plugins-catalog.png)을 갱신했습니다. TaskSpace 종료, 자체 fixture PID 80234 종료 및 가상 AI 호출 0회 확인. 참고 자료 표시의 스트림/완료/실패/중단/이력 분기는 UI 테스트로 확인했습니다.
- 양 CPU DMG·업데이트 아카이브의 서명/버전/CPU/현재 JAR/정적 설치 복사본 검증 완료(`DOUGLE_PACKAGES_VERIFIED`). 로컬 `release/latest.json`만 갱신했고 공개 0.1.16 피드/릴리스는 변경하지 않았습니다. ARM 실기기 실행·Apple 공증·실제 Notion 승인 성공을 검증한 것은 아닙니다. 설치 네이티브도 서명된 Intel 번들과 일치합니다.
- 최종 SHA256SUMS 재검사 6개 모두 OK(`/tmp/dougle-reference-checksums.log`, 13:44 KST).

## 2026-10-25 브라우저 연결 · 설정 하위 플러그인
- Notion/Jira·Confluence Cloud의 기본 브라우저 승인, PKCE/state/issuer/콜백 Host 검증, 취소/5분 만료/늦은 응답 무시, 토큰 암호화 저장·직렬 refresh 회전을 구현했습니다. 고객용 토큰 입력 UI와 사이드바 플러그인 탭을 제거하고 설정 하위로 이동했습니다.
- 공식 원격 MCP를 공통 인증/읽기 어댑터로 사용합니다. 별도 인증 중계 서버/도메인/앱 비밀키 동봉은 없습니다. 공개 인증 메타데이터의 고정 HTTPS endpoint/DCR/S256 제공을 조회했습니다. 실제 사용자 로그인·회사 관리자 정책·원격 문서 수집 성공을 검증한 것은 아닙니다.
- Notion 개별 페이지와 Atlassian 개별 이슈/문서의 링크 수집을 기존 Source/검색/출처 흐름에 연결했습니다. 계정별 자료 선택 검색창·공간 전체 수집·복수 계정은 미구현입니다. MySQL UI/스키마 수집은 유지합니다. [설계·제약](plugins.md).
- Java 관련 79개 통과 후 후속 3개 파일/18개 회귀 통과(중복 합산 금지). 가상 OAuth/MCP와 실제 loopback 콜백으로 state/PKCE/거절/취소 중 교환/만료/병렬 refresh/invalid_grant/암호문 크기/쓰기 도구 차단/사이트 권한·출처를 검사했습니다.
- UI 전체 47개 파일/377개 통과 후 후속 2개 파일/43개 재검사 통과(중복 합산 금지). 연결 후 자료 갱신 실패 시 화면 잠금 해제도 회귀에 추가했습니다. TypeScript/Vite 빌드의 기존 500 kB 청크 경고는 비차단 상태입니다.
- 가상 브라우저에서 설정 → 플러그인 → Notion/Atlassian/MySQL 모달 → 설정 복귀를 확인했습니다. 토큰 입력/독립 사이드바 탭이 없고 필드/버튼 정렬이 유지됩니다. [설정](screenshots/settings-plugins.png), [Notion](screenshots/notion-browser-connection.png), [Atlassian](screenshots/atlassian-browser-connection.png), [목록](screenshots/plugins-catalog.png). TaskSpace 89 종료·fixture 정리·가상 AI 호출 0회입니다. 브라우저에서 실제 외부 승인을 대신 누르지 않았습니다.
- 13:05 KST Intel 로컬 설치/재실행 완료. 백업 `2026-10-25T04-04-57.322Z-before-dougle`, 설치 JAR·서명된 네이티브 일치/codesign 검증, 시작 대기 후 UI 200·미인증 session/plugins API 403 확인. 최초 시작 직후 listener가 아직 없어 재시도한 뒤 정상 응답을 확인했습니다.
- Intel/ARM64 DMG·업데이트 아카이브의 서명/버전/CPU/현재 JAR·설치 복사본 정적 검증과 체크섬 6개 검증 완료. 로컬 release/latest.json만 갱신했고 공개 피드/릴리스는 변경하지 않았습니다. 네이티브 코드는 변경하지 않아 직전 검증된 양 CPU 실행 파일을 재사용했습니다. ARM 실기기·Apple 공증·실제 계정 승인 성공은 미검증입니다.

## 2026-10-25 실제 플러그인 연결 수정
- 준비 중 모달을 실제 Notion 토큰/MySQL 호스트·포트·DB·ID·비밀번호·TLS 입력 및 연결 테스트/저장/해제로 교체했습니다. Notion 페이지·중첩 블록·페이지네이션 수집, MySQL 구조 Source 수집/검색을 구현했습니다. 자격 증명은 로컬 AES-GCM 파일에 보관하며 API·자료 위치·명령행에 비밀을 반환하지 않습니다. [범위/보안](plugins.md).
- Java 관련 66개 통과: PluginConnectionsTest 7, NotionHttpTest 1, PluginIntegrationTest 4, PluginProviderTest 9, CollectorTest 13, SourceIntegrationTest 32. 모의 Notion/JDBC로 검증하며 실제 외부 서비스 접속 성공으로 보고하지 않습니다.
- UI 전체 47개 파일/374개 통과 후, 페이지 권한 안내·자료 새로고침 연결·테스트 타입 수정까지 관련 5개 파일/91개 재통과. 이 두 수치는 중복이므로 합산하지 않습니다. TypeScript/Vite/JAR 빌드 완료. Vite 메인 청크 500 kB 경고는 비차단 상태입니다.
- 네이티브 단위 7개·Intel/ARM64 release 빌드 완료. 고정된 Notion 관리 주소만 여는 인자 없는 IPC를 추가했으며 main 창/현재 앱 origin 검증을 유지합니다. ARM 실기기는 미검증입니다.
- 가상 브라우저: MySQL 입력 정렬·실제 JDBC의 localhost:1 접속 거절/재시도, 잘못된 Notion 토큰의 네트워크 전 검증/연결됨 미표시, 토큰 발급 안내를 확인했습니다. 실제 AI/사용자 자료/토큰은 사용하지 않았습니다. [MySQL 화면](screenshots/mysql-plugin-connection.png), [Notion 안내](screenshots/notion-plugin-token-guide.png), [목록](screenshots/plugins-catalog.png).
- 격리 Intel 실행: main 창 40회 관측, 전체 330회 관측에서 정상 시작 status 창/중복 창 없음. HTTP 200/403·자식 프로세스 종료 확인. 사용자 데이터와 실제 AI를 사용하지 않았습니다.
- **12:25 KST 설치 완료:** `/Applications/Knowledge Forge.app`, 백업 `2026-10-25T03-24-57.800Z-before-dougle`. 설치 JAR/서명된 네이티브 일치·codesign·재실행 `/` 200, 미인증 `/api/session` 및 `/api/plugins` 403 확인. 앱 데이터/기존 AI 인증은 유지했습니다.
- 양 CPU DMG·업데이트 아카이브 갱신 완료. 서명/버전/아키텍처/현재 JAR/설치 복사본 검사와 SHA256SUMS 6개 항목 재검증 통과, 로컬 latest.json 갱신 완료. Intel DMG 첫 검사의 마운트 해제 일시 오류는 재검사로 통과했습니다. 공개 릴리스·피드는 0.1.16 유지하며 이번 수정은 로컬 미공개 0.1.20입니다. 실제 Notion 계정/MySQL 서버 접속·OAuth·공증은 검증/완료한 것으로 보고하지 않습니다.

## 2026-10-25 후속 검증
- 플러그인 첫 단계와 정지 아이콘을 포함한 Intel/ARM64 DMG·업데이트 아카이브도 갱신했습니다. 양 CPU 서명/버전·아키텍처·JAR 일치·DMG 무결성 통과. 로컬 업데이트 매니페스트만 변경했고 공개 피드는 유지했습니다. 실제 OAuth/MySQL 접속·ARM 실기기·Apple 공증은 이번 검증에 포함하지 않습니다.
- 플러그인 UI까지 Intel 설치 완료: 백업 `2026-10-25T02-59-25.896Z-before-dougle`. 설치 JAR 일치·codesign·재실행 UI 200/미인증 API 403 확인. 기존 인증은 유지했습니다. 최신 소스에는 이전 정지 아이콘 변경도 포함됩니다. Notion/MySQL의 실제 연결은 여전히 준비 상태입니다.
- 플러그인 첫 단계: 사이드바 Notion/MySQL 목록, 준비 상태 모달, 실패 Notion 자료의 느낌표 안내와 설정 이동을 추가했습니다. 서버는 Notion 앱 HTML을 수집하지 않고 연결 필요 사유를 자료에 저장합니다. 실제 OAuth 로그인·Notion 본문 수집·MySQL 연결은 미구현이며 준비 상태로 표시합니다. [남은 구현](plugins.md).
- 플러그인 검사: UI 전체 46개 파일/367개 통과, 이후 탐색 잠금 보강을 포함한 SourcePanel 36개 재검사 통과(중복 합산하지 않음). 서버 PluginProvider/SourceIntegration 41개와 UI/JAR 빌드 통과. 가상 Notion 주소 등록→느낌표 hover 안내→클릭→Notion 설정 이동 및 Workspace 없는 플러그인 접근을 브라우저에서 확인했습니다. 실제 OAuth/DB/AI 실행은 하지 않았습니다. [목록](screenshots/plugins-catalog.png), [연결 안내](screenshots/notion-plugin-required.png), [설정 이동](screenshots/notion-plugin-setup.png).
- 11:34 KST 후속: 대화의 ‘답변 중단’ 텍스트를 정지 아이콘으로 교체했습니다. 접근성 이름/중단 API는 유지합니다. 관련 UI 34개·UI/JAR 빌드 통과 후 서명한 Intel 앱 번들에서 로컬 설치본을 교체했습니다. 백업 `2026-10-25T02-34-12.589Z-before-dougle`. 이번 아이콘 변경은 아래 이전 DMG/업데이트 아카이브에는 아직 포함되지 않습니다.
- 최종 Intel/ARM64 DMG와 업데이트 아카이브 생성 완료. 양 CPU 아키텍처·아카이브 서명/버전·앱 codesign·현재 JAR 일치·DMG 무결성 검사를 통과했습니다. 로컬 `release/latest.json`만 갱신했고 공개 피드는 변경하지 않았습니다. ARM 실기기 실행·Apple 공증·실제 외부 AI는 이번 검증에 포함하지 않습니다.
- **최신 Intel 설치 완료:** 기록 전환 깜빡임/업데이트 모달 정렬까지 포함한 0.1.20으로 교체·재실행했습니다. 백업 `~/Library/Application Support/KnowledgeForgeBackups/2026-10-25T01-42-02.562Z-before-dougle`. 설치 JAR 원본 일치·codesign·UI 200/미인증 API 403·최신 JS/CSS 자산을 확인했습니다. 앱과 닫힌 DB만 백업하고 인증은 변경하지 않았습니다. 아래 이전 설치는 중간 기록입니다.
- 최종 브라우저: 빠른 조회에는 임시 문구가 없고 지연 네트워크에서는 스켈레톤이 나타난 뒤 제거됩니다. 업데이트 모달의 버전/라벨은 각각 21px 한 줄, 전체 정보 행 폭 380px로 확인했습니다. [기록 로딩](screenshots/history-delayed-loading.png), [업데이트 모달](screenshots/app-update-dialog.png), [동기화 잠금](screenshots/material-sync-progress.png), [오류 툴팁](screenshots/material-error-tooltip.png). 이번 두 격리 브라우저 서버의 AI 실행은 각각 0회입니다.
- 최종 추가 수정까지 UI **45개 파일/355개 테스트**, TypeScript/Vite 및 JAR 빌드 통과. 이전 354개 결과와 합산하지 않습니다. Java 관련 47개는 동일 서버 코드에서 통과했습니다. 브라우저 빠른 기록 조회에서 임시 문구 없음/스켈레톤 초기 opacity 0/완료 후 제거를 확인했습니다.
- 브라우저 후속에서 대화→질문 기록 전환 시 임시 로딩 문구가 약 196ms 노출되는 것을 재현했습니다. 즉시 로딩 문구 대신 300ms 이후에만 나타나는 목록 스켈레톤으로 변경합니다. 앱 업데이트 모달은 공통 dl의 75px 열을 상속하던 문제를 별도 단일 열로 고쳐 버전 줄바꿈을 방지합니다. 이 두 수정으로 이전 패키징은 중단하고 재검증/재패키징합니다.
- 서버 Source/Library 통합 테스트 47개 통과. 격리 브라우저에서 폴더 자동 수집, 업로드, 종류별 아이콘, +/− 상태, 용량 실패의 원인·해결 툴팁과 상단 오류 없음, 동기화 중 설정 클릭 차단 및 종료 후 해제를 확인했습니다. 설정의 두 행은 동일한 720×86px/왼쪽 정렬입니다. 업데이트 화면의 current 상태는 native IPC 대역이며 공개 새 버전 설치의 증거가 아닙니다.
- 최신 설치 준비: UI 전체 45개 파일/354개 테스트 통과. 자료 종류별 아이콘 통일, 추가 영역 +/− 토글, 실제 오류 툴팁과 해결 방법, 앱 버전/업데이트 확인 모달을 포함합니다. 아래 이전 설치 기록과 구분하며 최종 설치 결과는 검증 후 추가합니다.
- 첫 자동 동기화는 등록 요청의 `sync:true`로 서버에서 등록→수집→색인 갱신까지 처리합니다. 실패 자료는 목록에 유지하고 원인/해결 방법은 느낌표의 hover·focus·클릭 툴팁으로만 표시합니다. 자료 화면에서 관측한 서버 SYNCING 상태도 탐색 잠금에 반영하며 사이드바에 inert를 적용했습니다.
- Workspace 이름 20자 제한에 맞게 SourceIntegrationTest의 기존 가상 Workspace 이름 4개를 줄였습니다. 제한 정책을 완화하거나 실제 사용자 이름을 변경하지 않았습니다.
- 최신 수정: 같은 화면에서 연속 질문을 누적하고 기존 답변 패널을 유지합니다. 폴더·URL·GitHub 등록 후 자동 동기화, 파일 업로드 분석/색인 경로 유지, 동기화 중 탭/설정/Workspace 전환 잠금을 적용했습니다. 실제 Source별 작업 단계·완료/실패 최근 내역과 최근 성공 시간을 표시하며 가짜 진행률/남은 시간은 만들지 않습니다. 폴더 내부 파일별 실시간 로그는 아직 제공하지 않습니다. 네이티브 오류창의 ‘진행하던 AI 요청은 자동으로 다시 보내지 않습니다.’ 문구를 제거했습니다. 최신 검증/설치 결과는 후속 항목으로 기록합니다.
- **대화 제목/기록 탭/로고 시작 화면 최종 Intel 설치 완료:** 백업 `~/Library/Application Support/KnowledgeForgeBackups/2026-10-25T00-45-15.290Z-before-dougle`. 설치 JAR 일치·codesign·재실행 HTTP 200/비인증 session 403을 다시 확인했습니다. 아래 00-34/00-25 기록은 중간 설치입니다.
- 최신 브라우저 재검증: Workspace 0개에서 기록 메뉴 클릭→빈 기록 화면, Workspace 생성→가상 질문 보관→기록 행 클릭→대화 열기까지 확인했습니다. [빈 기록 접근](screenshots/history-without-workspace.png), [로고 시작 화면](screenshots/empty-workspace-minimal.png), [대화 제목](screenshots/dougle-face-wordmark.png). 실제 AI 호출은 없습니다.
- 대화 제목/빈 기록 접근/로고 시작 화면까지 최종 UI **334개**와 UI/JAR 빌드 통과. 빈 화면에 로고를 추가하면서 기존 ‘모든 로고 없음’ 회귀 기대를 ‘사이드바에는 없음, 중앙에는 공통 로고’로 수정한 뒤 전체 재검증했습니다. 아래 332/331개는 이전 묶음 결과이며 합산하지 않습니다.
- 추가 피드백: 대화 화면에도 h1 제목을 표시하고 브랜드 로고는 h2로 구분했습니다. Workspace가 없을 때 기록 탭을 비활성화하던 조건과 환영 화면 우선 분기를 수정했습니다. 빈 기록 화면은 열리되 null Workspace로 API를 호출하지 않습니다. 빈 시작 화면은 큰 테두리를 제거하고 공통 Dougle 로고와 만들기 버튼을 중앙에 배치합니다. 설명 문구는 복구하지 않습니다.
- **마지막 추가분 Intel 설치 완료:** 설정 이동/빈 화면/자료 설명 삭제를 포함한 설치본으로 다시 교체했습니다. 백업 `~/Library/Application Support/KnowledgeForgeBackups/2026-10-25T00-34-38.724Z-before-dougle`, 설치 JAR 일치·codesign·재실행 HTTP 200/비인증 session 403 확인. 이전 00-25 설치는 중간 설치 기록입니다.
- 설정 이동/최종 문구 삭제까지 UI 전체 **332개** 및 TypeScript/Vite/JAR 빌드 통과. [하단 설정과 업데이트](screenshots/settings-update.png), [빈 시작 화면](screenshots/empty-workspace-minimal.png), [설명 없는 자료 제목](screenshots/materials-minimal-heading.png)을 확인했습니다. 설정 버튼 높이는 34px입니다. 업데이트 화면 스크린샷의 `current`는 격리 브라우저 시각 검사용 IPC 대역이며 실제 신규 버전 설치 검증이 아닙니다.
- 최종 추가 요청: 사이드바 하단은 설정 버튼으로 교체하고 업데이트 확인은 설정 화면 내부로 이동했습니다. 숨겨진 상태에서도 자동 확인을 유지하고 새 버전은 설정 버튼에 점으로 표시합니다. 빈 시작 화면은 Workspace 만들기 버튼만 남겼고 빈 관리 목록 문구는 ‘Workspace가 없습니다’입니다. 이 추가 변경의 설치 파일은 아래 이전 설치본 이후 다시 갱신합니다.
- Intel 최신 설치 완료: `/Applications/Knowledge Forge.app`, 백업 `~/Library/Application Support/KnowledgeForgeBackups/2026-10-25T00-25-19.291Z-before-dougle`. DB는 종료 후 내용 열람 없이 복사했고 인증은 변경하지 않았습니다. 설치 JAR/아이콘 원본 일치, codesign 검증, 재실행 HTTP 200/비인증 session 403 확인.
- 네이티브 최신 Intel 시작 검사: main 창 40개 표본/전체 223개 표본에서 중복·시작 status 창 없음, 격리 backend 시작/종료 통과. 양 CPU release 빌드 완료. ARM 실기기·외부 AI·새 공개 버전으로의 실제 업데이트 설치는 미검증입니다.
- 최종 소스 검증: UI 45개 파일/331개 테스트, TypeScript/Vite 빌드, LibraryIntegrationTest 16개, 네이티브 binary 단위 7개 통과. 최초 업데이트 오류 표시 회귀 1건은 idle 폴링이 IPC 오류를 덮는 문제였으며 수정 후 전체 331개 재통과했습니다.
- 격리 브라우저: 가상 Workspace 2개·서로 다른 파일로 왕복 전환/자료 및 질문 이력 분리, 지연 네트워크 스켈레톤, 20자 입력, 목록 말줄임, 관리창/공통 로고/오류 아이콘/AI 연결 문구를 확인했습니다. 실제 AI와 사용자 DB를 사용하지 않았습니다. [전환 중 화면](screenshots/workspace-switch-loading.png).
- Workspace 전환 동안 이전 화면/질문 입력을 내리고 스켈레톤을 표시합니다. 활성화 PUT·세션 검증·새 자료 조회 후 해제하며 중복 선택을 막습니다. 실패 시 재선택할 수 있고 이전 조회 응답은 새 화면에 반영하지 않습니다. 메인 좌측 상단의 중복 Workspace 이름은 제거했습니다.
- 자료 추출 ERROR/UNSUPPORTED와 상위 폴더 오류는 메타데이터 조회로 표시합니다. 수집 오류와 함께 붉은 이름/느낌표·간결한 원인 툴팁을 제공합니다. 가상 빈 파일로 실제 표시, 다른 Workspace 자료 분리 및 왕복 전환을 확인했습니다.
- 하단 설정 버튼은 34px 최소 높이/7px 세로 여백입니다. 확인 IPC의 긴 대기를 제거하고 결과는 설정의 업데이트 항목에 ‘최신 버전입니다’ 또는 재시도로 표시합니다. 확인 결과 팝업을 기다리며 버튼이 계속 대기하는 구조를 제거했습니다. 네트워크 제한시간·서명·버전·CPU 검증·설치 확인은 유지합니다.
- GitHub 공개 릴리스/피드 직접 확인: 최신 공개 0.1.16, 설치 작업본 0.1.20. 새 업데이트 없음이 정상이며 이번 검사는 신규 버전 다운로드/설치 성공 증거가 아닙니다.
- 화면: [공통 얼굴 로고](screenshots/dougle-face-wordmark.png), [Workspace 목록](screenshots/workspace-picker.png), [관리창](screenshots/workspace-manager.png), [이름 제한](screenshots/workspace-name-limit.png), [자료 오류](screenshots/material-analysis-warning.png).

## 현재 정책
사용자 요청에 따라 질문 전송이 관련 자료를 선택한 AI로 보내는 실행 동의입니다. 대화에서는 준비 버튼/동의 체크박스를 표시하지 않습니다. 업로드·동기화·이력 열기·모델 변경만으로 AI를 호출하지 않습니다.

## 구현
- 최신 묶음: 자료 관리의 바깥 테두리/그림자를 제거하고 목록 테두리만 남겼습니다. Workspace 관리는 이름/삭제와 자동 갱신/주기를 두 줄로 나눠 정렬했습니다. 선택기는 OS 기본 select 대신 아래로 펼치는 앱 목록이며 선택 표시·키보드·Esc/바깥 클릭 닫기·긴 이름 한 줄 말줄임을 지원합니다. 신규 이름은 UI/API 모두 최대 20자, 기존 이름은 보존합니다. 생성 창의 설명 문구는 제거했습니다.
- 공통 로고의 `o`에 투명 캐릭터 얼굴을 원형으로 배치하고 오른쪽 캐릭터는 제거했습니다. Dock/시작·오류 안내창도 같은 얼굴을 사용하며 접근성 이름 Dougle은 유지합니다.
- AI 연결 선택지에서 ‘앱에 포함’·‘별도 도구 필요’를 제거하고 Codex/OpenAI, Claude/Anthropic만 표시합니다. 이 수정은 표시 문구만 변경하며 모델 설치/실행 경로를 바꾼 것으로 보고하지 않습니다.
- 최신 문구/삭제 창: 근거 없는 질문은 ‘질문과 관련된 내용을 찾지 못했어요.’만 표시합니다. 자료 확인 버튼은 제거했습니다. 공통 삭제 창의 대상명은 최대 두 줄에서 말줄임하고 원문은 툴팁/접근성 설명에 보존합니다. 공백 없는 긴 문자열도 모달 폭을 넘지 않습니다.
- 후속 이력 화면 정리: 사이드바의 질문 목록과 새 질문 +를 제거했습니다. 질문 기록 탭은 카드 대신 제목·날짜·삭제 버튼의 세로 목록을 표시하며 검색창 내부 테두리는 제거했습니다. 대화 메뉴는 새 질문을 시작하고 기록의 행을 누르면 해당 저장 답변을 엽니다.
- ChatPanel의 명시적 submit에서 근거 저장→미리보기 검증→CAS 승인→고정 requestId 답변 접수를 진행합니다. 요청 ID를 접수 전에 기억해 응답 유실 후에는 같은 요청으로만 확인합니다. 새로고침/이력 재진입에서는 작업 조회만 합니다.
- 질문 직후 ‘확인해보겠습니다…’를 표시합니다. 기존 Codex/Claude 스트리밍 경로의 실제 partial SSE를 사용하고, 받은 글을 짧은 간격으로 점진 표시합니다. 답변을 미리 만들어 보이거나 성능 개선이라고 주장하지 않습니다. 저장된 답변과 모션 감소 설정은 즉시 표시합니다.
- 대화의 연결 상태/SSE 상세·요청 모델·완료 표제·질문 관리·삭제를 제거합니다. 오류 및 중단 기능은 유지합니다. 출처는 답변 아래에 표시합니다.
- 질문/답변은 기존 DB 저장 경로를 그대로 사용합니다. 질문 기록 카드의 휴지통과 공통 확인창으로 삭제합니다. 실패 시 대상을 보존하고 재시도하며 원본 자료는 건드리지 않습니다.
- legacy QuickAnswerPanel/API의 명시적 승인 흐름은 호환성을 위해 남기되 ChatPanel에서 사용하지 않습니다. 이전 편집형 지식이 포함된 미완료 질문의 원본 재질문 보호도 유지합니다.

## 검증·설치
- 최신 문구/삭제 창: UI 전체 313개 및 TypeScript/Vite 빌드 통과. 실제 브라우저의 공백 없는 긴 가상 질문에서 2줄/43.19px 높이, 원문 높이 151px, 모달 폭 초과 없음과 원문 툴팁을 확인했습니다. 근거 없음 안내 한 문장 및 자료 확인 버튼 없음도 확인했습니다. [긴 질문 삭제 창](screenshots/long-question-delete.png). 같은 미공개 0.1.20 재설치·JAR 일치·양 CPU 체크섬 확인 완료. 백업 `~/Library/Application Support/KnowledgeForgeBackups/2026-10-24T23-55-22.753Z-before-dougle`. AI 연결 설명 제거 후 설치본을 추가 갱신합니다.
- 최신 이력 UI: 전체 UI 312개·TypeScript/Vite 빌드 통과. 가상 브라우저에서 검색창 내부 테두리 0px·세로 목록 2행·사이드바 질문 영역 없음, 검색 필터→기록 열기→대화 메뉴의 새 질문 전환을 확인했습니다(AI 0회). [이력 리스트 화면](screenshots/question-history-list.png). 같은 미공개 0.1.20의 양 CPU 패키지/서명을 검증하고 재설치했습니다. 설치 JAR 일치 확인. 최신 백업: `~/Library/Application Support/KnowledgeForgeBackups/2026-10-24T23-38-51.850Z-before-dougle`.
- 후속 수정: 등록된 자료가 없으면 동기화 버튼과 실행 경로를 모두 차단합니다. 연결 자료 또는 업로드 자료가 있으면 활성화하고, 하위 폴더 탐색 중에도 상위 등록 자료의 존재를 반영합니다. 마지막 자료 삭제 후 다시 비활성화됩니다. 최종 UI 전체 311개 및 TypeScript/Vite 빌드 통과(아래 310개와 중복 합산하지 않음). 같은 미공개 0.1.20 설치 파일을 갱신합니다.
- 후속 브라우저 검사: 빈 Workspace에서 비활성→가상 PDF 업로드 후 활성→마지막 파일 삭제 후 비활성을 확인했습니다. [빈 자료 화면](screenshots/empty-materials-sync-disabled.png). 외부 AI 호출 없이 검사했습니다.
- 후속 설치 완료: UI/JAR 및 양 CPU 패키지를 갱신·검증하고 같은 미공개 0.1.20을 재설치했습니다. 설치 JAR 일치 확인. 교체 전 백업은 `~/Library/Application Support/KnowledgeForgeBackups/2026-10-24T23-22-48.775Z-before-dougle`이며 인증은 유지했습니다. 네이티브 코드는 변경하지 않았습니다.
- UI 전체 310개(43파일) 및 TypeScript/Vite 빌드 통과. Java 승인/작업/이벤트/partial 관련 36개 통과, JAR 빌드 통과. `git diff --check` 통과.
- 가상 PDF와 가상 Codex 전용 프로필로 질문 전송→즉시 진행 안내→점진 답변→하단 출처를 확인했습니다. 이력 열기·브라우저 새로고침 뒤 저장 답변을 다시 읽고, 공통 확인창으로 삭제한 뒤 목록/사이드바 제거 및 원본 PDF 보존을 확인했습니다. 전체 가상 AI 실행은 1회이며 이력 열기/삭제는 추가 실행하지 않았습니다. 실제 외부 AI 검사가 아닙니다.
- 화면: [자동 답변과 출처](screenshots/automatic-answer-history.png), [이력 삭제](screenshots/question-history-delete.png).
- Intel/Apple Silicon 네이티브 빌드 통과. Intel 격리 시작 검사에서 메인 창 40회, 전체 198회 표본에 추가 시작 창이 없었으며 HTTP 200/403·자식 프로세스 종료를 확인했습니다.
- 양 CPU DMG/업데이트 아카이브의 아키텍처·서명·체크섬 검증 및 최종 6개 SHA256 검사를 통과했습니다. 로컬 `release/latest.json`은 0.1.20이며 공개 피드는 바꾸지 않았습니다.
- `/Applications/Knowledge Forge.app`을 0.1.20으로 교체·재실행하고 설치 JAR 일치 및 UI 200/미인증 API 403을 확인했습니다. 교체 전 앱/닫힌 DB 백업: `~/Library/Application Support/KnowledgeForgeBackups/2026-10-24T23-05-31.754Z-before-dougle`. 인증 내용은 읽거나 변경하지 않았습니다.
- DB v24 유지. 실제 사용자 자료·실제 AI 호출은 테스트에 사용하지 않았습니다. ARM 실기기·Apple 공증은 미검증입니다. GitHub 공개·커밋·푸시는 하지 않았으며 공개본은 0.1.16입니다.
