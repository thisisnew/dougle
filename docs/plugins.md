# 플러그인 연결

2026-10-27 사용자 결정: Slack 문의·알림 수신과 Datadog 연결·로그 조회는 제거했습니다. 설정에는 Notion·Jira/Confluence·MySQL 연결만 제공합니다. 기존 대화·자료·DB 마이그레이션은 보존하며 예전 연결 API나 AI 제안으로 제거된 기능을 실행할 수 없습니다.

2026-10-25 HTTP 400 원인 재현: Notion token endpoint는 `application/x-www-form-urlencoded; charset=ISO-8859-1` 요청을 `invalid_request`로 거부했습니다. 동일 가상 요청을 UTF-8/charset 미지정으로 보내면 폼 파싱을 통과해 예상한 `invalid_client` 응답을 반환합니다. OAuth 폼을 포함한 HTTP 요청 본문은 UTF-8로 명시합니다. 실제 계정 재승인 성공과는 구분합니다.

카드에는 서비스 이름·공식 로고·연결 상태만 표시합니다. 로고는 앱에 동봉하며 원격 이미지를 호출하지 않습니다.

2026-10-25 사용자 Notion 승인 실패는 원인 확인 중입니다. 승인 대상 `resource`를 토큰 교환/갱신에도 보내며, 실패 시 브라우저와 앱에 인증 승인 확인/자료 서비스 연결/연결 저장 단계 및 안전한 오류를 표시합니다. 제공자 원문이나 토큰은 출력하지 않습니다. 현재 설치/검사 결과와 실제 계정 미검증 경계는 [0.1.20 보고서](release-0.1.20-report.md)를 따릅니다.

리소스 값은 [MCP 인증 규격](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization#resource-parameter-implementation)에 따라 승인/토큰 요청에 동일하게 전달합니다. [Notion 클라이언트 문서](https://developers.notion.com/guides/mcp/build-mcp-client)의 PKCE·public client·세션 헤더 경로를 사용합니다. 이것이 이번 실계정 실패 원인이라는 뜻은 아닙니다.

## Notion · Jira · Confluence Cloud
1. 설정 → 플러그인에서 서비스를 선택하고 연결을 누릅니다. 독립 사이드바 플러그인 탭은 없습니다.
2. 기본 브라우저에서 로그인·승인합니다. 고객에게 토큰 발급이나 인증 코드 복사를 요구하지 않습니다.
3. Dougle이 승인을 확인하면 연결됨으로 바뀝니다. 대기는 취소할 수 있고 5분 뒤 만료됩니다.
4. 자료 화면에서 사용할 Notion 페이지/Jira 이슈/Confluence 페이지의 전체 링크를 추가합니다. 연결 직후에는 현재 Workspace에 이미 등록된 해당 서비스 링크를 재동기화합니다.

공식 원격 MCP의 동적 클라이언트 등록·OAuth PKCE를 사용하는 공통 연결 엔진입니다. 별도 중계 서버/도메인/client secret 배포 없이 공식 서버에 직접 연결하며 OAuth state, issuer, 고정 HTTPS origin, 로컬 콜백 Host/경로를 검증합니다. 고객의 승인이나 회사 관리자 정책을 우회하지 않습니다. [Notion 규격](https://developers.notion.com/guides/mcp/build-mcp-client), [Atlassian 규격](https://developer.atlassian.com/cloud/rovo-mcp/guides/configuring-oauth-2-1/).

Notion은 notion-fetch만 호출합니다. Atlassian은 승인된 사이트 목록의 URL과 등록 링크의 호스트를 대조한 뒤 getJiraIssue 또는 getConfluenceContent/getConfluencePage만 호출합니다. 도구 스키마의 필수 입력을 확인하며 모르는 규격을 임의 추측하지 않습니다. 쓰기/삭제/범용 실행 도구는 실행 허용 목록에 없습니다. 제공자 승인 화면이 광범위한 권한을 요청할 수 있으므로 앱의 읽기 전용 실행 정책과 OAuth 권한 범위는 별개입니다. Atlassian 인증 요청에는 Jira/Confluence 읽기 scope만 지정합니다.

연결 확인은 MCP initialize/tools/list를 검사합니다. Atlassian은 Jira 또는 Confluence 중 하나 이상의 읽기 기능과 유효한 회사 사이트 목록을 확인합니다. token 응답에 scope가 있으면 하나 이상의 해당 읽기 scope가 필요하며, 생략되면 기능 확인을 사용합니다. 한 제품만 승인해도 연결할 수 있고 다른 제품을 읽을 때는 해당 제품의 권한 부족을 표시합니다. 각 문서의 접근 성공을 보장하지 않습니다. 응답은 요청당 최대 30초/10 MiB, 본문 200만 문자, 기능 목록 최대 20페이지입니다. 원격 리다이렉트·쿠키·자동 HTTP 재시도는 하지 않습니다. 자료 수집이 AI 모델을 자동 실행하지 않습니다.

현재는 앱 전체에 서비스별 연결 한 개입니다. Jira·Confluence는 Atlassian 연결을 공유하지만 각 사이트·문서의 권한을 따릅니다. 여러 계정 선택, 문서 검색/선택 창, 프로젝트/공간 전체 수집, 첨부 다운로드는 미구현입니다. 고객이 등록한 개별 링크만 현재 Workspace에 수집합니다. 사내 설치형 Jira/Confluence는 이 커넥터의 범위가 아닙니다.

이전 Notion 내부 토큰은 호환용 API/수집기로만 유지합니다. UI에서는 입력하지 않으며 브라우저 연결 성공 시 이전 내부 토큰을 제거합니다. 기존 연결이 없는 사용자에게 토큰 방식으로 되돌아가도록 요구하지 않습니다.

## MySQL
- 호스트·포트(기본 3306)·DB명·사용자 ID·비밀번호·TLS 입력, 연결 테스트, 연결 저장, 명시적 연결 해제를 제공합니다.
- 실제 Connector/J 9.7.0으로 접속하여 지정 DB 조회 권한과 스키마 조회를 검증한 뒤에만 저장합니다. 테스트만 누르면 저장하지 않습니다.
- TLS 인증서·호스트 검증이 기본입니다. 암호화만 옵션은 인증서 신원을 확인하지 않습니다. 암호화 해제는 localhost/127.0.0.1에만 허용합니다.
- ‘DB 구조를 자료에 추가’를 누르면 현재 Workspace에 MYSQL Source를 등록·수집·색인합니다. 같은 DB Source가 있으면 재동기화합니다. 테이블/열 이름·타입·NULL 여부·키 표시·열 설명을 조회하며 행 데이터는 읽지 않습니다.
- 읽기 전용 DB 계정을 사용하세요. JDBC readOnly 플래그는 서버 계정 권한을 대체하지 않습니다. 구현은 information_schema의 고정 SELECT 두 개만 실행하고 DB명은 바인딩합니다. 임의 SQL/다중 쿼리/LOCAL INFILE/URL infile/역직렬화/공개키 자동 수신은 사용하지 않습니다.
- 연결 5초·소켓 15초·쿼리 10초, 최대 5,000열·200만 문자 제한입니다. 임의 JDBC URL/추가 연결 옵션은 받지 않습니다.
- 현재 앱 전체에 MySQL 연결 1개를 저장합니다. MySQL 연결 대상을 바꾸면 기존의 다른 DB Source는 해당 연결을 다시 설정해야 동기화할 수 있습니다. 연결을 해제해도 이미 보관한 자료는 유지됩니다.
- 일반 DB IDE 전체를 대체하지 않습니다. SQL 편집/실행, 행 데이터, SSH 터널, 사용자 CA/클라이언트 인증서 파일, 여러 DB 연결 목록, IPv6 호스트 입력은 아직 지원하지 않습니다.

### 명시적 MySQL 행 조회의 시간 제한 (현재 작업본)

스키마 수집과 별개인 `MysqlInvestigationService`의 승인된 행 조회는 검증과 실행에 같은 10초 협력적 시간 예산을 사용합니다. HTTP 응답이 반드시 10초 이내에 끝난다는 보장은 아닙니다. 일반 호출의 Workspace·연결 검증은 단계별 최대 2초 대기, 실행 작업자 2개·대기열 2개로 제한하며, 만료 시 작업을 취소하고 늦은 결과는 사용하지 않습니다. 작업자가 인터럽트를 무시하면 즉시 종료되지 않을 수 있지만 추가 작업은 제한된 용량에서 거부합니다. 전역 Hikari 설정은 변경하지 않습니다.

로컬 첨부 저장 트랜잭션 안에서는 이미 점유한 SQLite 연결을 재사용하기 위해 Workspace 검증을 호출 스레드에서 실행합니다. 이 인라인 DB 조회에는 위 작업자 대기 제한을 적용하지 않으며, 전후 시간·취소 검사로 진행 중인 DB 호출을 강제 중단할 수 없습니다. 트랜잭션/드라이버의 제한과 JDBC 정리·rollback·close 지연 때문에 전체 응답은 예산보다 늦을 수 있습니다. 연결 변경 epoch와 취소 플래그 검사는 DB·비밀 저장소를 읽지 않는 가벼운 검사이며, Workspace 존재 여부는 실행 전후에 별도 재검증합니다. 이 검사만으로 삭제와 결과 반환 사이의 모든 경쟁을 원자적으로 차단한다고 주장하지 않습니다.

삭제 경쟁의 의미: 실행 후 Workspace 재검증보다 먼저 삭제가 완료되면 조회 결과는 실패로 처리하고 행을 반환하지 않습니다. 성공한 재검증 시점에 Workspace가 존재했다는 사실은 그 뒤의 동시 삭제보다 HTTP 응답 전달이 먼저 끝난다는 보장이 아닙니다. 검증과 응답 전송을 원자적 연산으로 취급하거나 이미 전달한 결과를 네트워크에서 회수한다고 주장하지 않습니다. 로컬 첨부 저장도 트랜잭션 커밋 이후 Workspace가 삭제될 수 있으므로, 성공 영수증은 커밋 시점의 저장을 뜻하며 이후의 계속된 존재를 보장하지 않습니다.

회귀 확인 위치: `MysqlValidationDeadlineTest.postReadWorkspaceRevalidationStillDiscardsDeletedWorkspaceOutput`은 실행 후 재검증 전에 삭제된 Workspace의 행 폐기를, `captureTransactionReusesBoundConnectionRatherThanDeadlockingAnotherWorker`는 저장 트랜잭션의 연결 재사용을 검사합니다. `OperationalCaptureServiceTest`의 저장·rollback 검사는 첨부 저장 경계를 다룹니다. 이는 응답 전달과 삭제 순서의 보장 검사가 아니며, 새 deadline/epoch 검사의 실행 결과는 별도 통합 실행 보고를 따릅니다.

이 설명은 현재 소스의 제한이며 실제 외부 DB 검증이나 배포 완료를 뜻하지 않습니다.

### 조회 결과를 로컬 근거로 저장할 때의 범위 (현재 작업본)

명시적으로 승인한 조회 결과의 로컬 첨부 미리보기는 서버가 보관한 원래 승인 쿼리의 불변 범위를 함께 표시합니다. MySQL은 DB명·테이블·선택 열·동등 조건 값·행 제한을 포함하되 호스트·사용자명·자격 증명은 포함하지 않습니다. 별도 환경 이름이나 시간 구간·DB 세션 시간대는 승인 계약에 없으므로 미기록임을 명시하고 조건 값만으로 추정하지 않습니다. 클라이언트가 캡처 요청에 제출하는 범위 주장은 받지 않습니다.

알려진 연결 비밀번호/API 키/application 키의 원문·JSON 이스케이프 표현과 일반적인 URL·인증 정보는 마스킹합니다. 마스킹은 모든 민감 정보를 탐지하는 보장이 아니므로 저장 전 정확한 미리보기를 검토해야 합니다. 범위 헤더에 먼저 최대 1,400자를 배정하고 행 본문을 포함한 전체 첨부는 최대 3,000자입니다. 범위 자체가 잘리면 `[TRUNCATED_SCOPE]`와 전체 일부 보관 표시를 남기며, 누락·마스킹된 조건을 추정하지 말라는 안내를 보존합니다. 조회 완료 시각과 조회 대상 시간 범위는 별도입니다.

범위 텍스트와 범위 잘림 상태는 결과 내용과 함께 저장 동의 digest에 포함되고 저장 전후 재검증됩니다. 범위는 JSON 기반 표시 텍스트이며 마스킹·잘림 이후 유효한 JSON이 아닌 조각일 수 있음을 명시합니다. 미리보기와 실제 첨부의 UTF-8 바이트·본문·해시 일치를 검사하며, 결과 또는 범위가 바뀌면 저장을 거부하고 트랜잭션을 rollback합니다. 기존 연결 철회·TTL 검사를 우회하지 않으며 저장은 외부 AI 호출이나 자동 첨부 전송이 아닙니다. 헤더의 “저장 시 추가 조회 없음”은 저장 동작만 설명하며, 이후 사용자가 첨부를 답변에 전송할 수 없다는 뜻이 아닙니다. 후속 답변은 별도 사용자 질문·승인과 불변 근거 묶음을 사용합니다. 기존 HTTP DTO는 유지하며 새 범위는 미리보기 `text` 안에 표시합니다. 새 회귀 검사는 모의 제공자와 실제 로컬 첨부/SQLite 경계를 사용하며 실행 결과는 별도 통합 실행 보고를 따릅니다.

## API·보안
- GET /api/plugins: 저장된 연결 상태, 비밀번호가 제거된 MySQL 설정. 실시간 서버 가용성 보장은 아닙니다.
- POST /api/plugins/browser/{notion|atlassian}: 비동기 브라우저 인증 시작. GET/DELETE /api/plugins/browser/{provider}/{id}: 상태 확인/취소. 응답에는 인증 코드·토큰·state·PKCE verifier를 노출하지 않습니다.
- POST /api/plugins/notion/test, /mysql/test: 검증만, 204.
- POST /api/plugins/notion, /mysql: 검증 후 저장, 비밀 없는 상태 반환.
- DELETE /api/plugins/notion, /atlassian, /mysql: 진행 중 인증을 취소하고 저장된 자격 증명을 삭제합니다. 제공자 측 앱 승인 자체를 철회하는 기능은 아닙니다.
- 기존 데스크톱 세션·Origin·X-Forge-Client 필터를 유지합니다. 연결 및 수집 중 화면 전환/중복 요청을 잠급니다. 연결된 카드를 클릭하는 것만으로 해제하지 않습니다.
- 자격 증명은 data-dir/plugin-secrets에 AES-256-GCM 암호문으로 보관합니다. 디렉터리 0700, 키/암호문 0600, 임시 파일과 원자적 교체, 심볼릭 링크 거부를 적용합니다. API 응답·자료 위치·로그·localStorage·명령행 인자에 비밀을 넣지 않습니다.
- OS Keychain은 아닙니다. 같은 사용자 계정으로 로컬 키와 암호문을 읽을 수 있는 공격자에게는 보호되지 않습니다. 앱 데이터 백업/이동 시 이 폴더도 민감 정보로 취급해야 합니다.
- macOS 기본 브라우저는 백엔드에서 검증한 공식 인증 URL을 /usr/bin/open 인자로 전달해 엽니다. 셸/임의 URL을 받지 않습니다. access/refresh token 및 PKCE verifier는 명령행에 넣지 않습니다. 기존 Notion 내부 연결 관리 native IPC는 UI에서 호출하지 않습니다.
- 콜백은 인증 대기 동안만 127.0.0.1에 바인딩합니다. 클라이언트 등록과 콜백 포트를 암호화 보관해 재사용하며 포트 충돌 시 새 포트와 redirect로 동적 등록합니다. 취소/만료 이후 늦은 응답은 저장하지 않습니다. refresh는 서비스별 직렬 실행·회전 토큰 원자 저장이며 invalid_grant는 반복 재시도하지 않습니다.

## 검증 범위
화면: [설정의 플러그인 진입](screenshots/settings-plugins.png), [Notion 브라우저 연결](screenshots/notion-browser-connection.png), [Atlassian 브라우저 연결](screenshots/atlassian-browser-connection.png), [MySQL](screenshots/mysql-plugin-connection.png). 기존 토큰 안내 스크린샷은 이전 버전입니다.

브라우저 연결은 가상 OAuth/MCP 응답과 실제 로컬 콜백 HTTP로 검증합니다. 실제 사용자 Notion/Atlassian 로그인·회사 정책·문서 권한은 별도 확인이 필요합니다. MySQL 검사는 모의 JDBC 외에 새 임시 서버의 읽기 전용 계정 시나리오를 포함하며, 실제 회사 DB 접속 성공을 뜻하지 않습니다. 현재 체크리스트와 재현 방법은 [다른 Mac 사용 흐름 검증](portability-verification.md)을 따릅니다. [0.1.20 보고서](release-0.1.20-report.md)는 당시의 이력입니다.
