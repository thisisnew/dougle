# API

## 0.1.36 자료 연결

`POST /api/workspaces/{workspaceId}/sources`는 LOCAL_FOLDER, URL, MYSQL을 허용한다. URL은 공개 HTTPS(443, 사용자정보·fragment 없음)이며 GitHub 주소와 파일 링크를 거부한다. `sync:true` 또는 별도 `/sources/{id}/sync`로 수집한다. UI 등록은 곧바로 동기화를 요청한다. Confluence Cloud 인증이 없으면 Source ERROR와 연결 안내를 반환하고 로그인 페이지를 색인하지 않는다. GitHub 보관 자료의 수동 동기화와 폴링 활성화는 400, 스케줄러는 제외한다. GitHub 인증 API는 제거된 상태다.

로컬 폴더의 개별 읽기/미지원 경로 실패는 `PARTIAL`과 실패 요약을 반환하고 정상 파일을 검색에 반영한다. 루트 접근 실패는 `ERROR`로 이전 자료를 보존한다. 파일당256MiB·총8GiB·2만개 및 경로 제한은 유지한다. 실제 회사 Mac 권한 오류와 실제 Confluence SSO는 가상 테스트 결과로 보증하지 않는다.

## 0.1.32 회사 연결 작업본 (이전 이력)

- `GET /api/plugins/github`: 로컬 인증 저장 여부 `{configured}`만 반환한다. 토큰이나 현재 원격 인증 성공을 반환하지 않는다.
- `POST /api/plugins/github`: `{token, repository}`. Fine-grained 토큰으로 github.com 저장소의 commits 읽기 검증에 성공한 뒤 암호화 저장한다. 실패 시 이전 토큰은 유지한다.
- `DELETE /api/plugins/github`: 로컬 인증만 제거하며 보관 자료를 삭제하지 않는다.
- `POST /api/plugins/github/open`: 고정된 GitHub 읽기 토큰 발급 페이지를 기본 브라우저로 연다. 로그인 완료나 조직 승인을 뜻하지 않는다.
- `/api/ai/launch-settings`는 Codex/Claude 모두 실행 명령 이름 또는 전체 경로를 받는다. 셸 별칭·함수·옵션은 실행하지 않으며 기존 신뢰 확인·revision·재시작 적용을 유지한다.

## 0.1.26 후보의 대화 조회

일반 텍스트 질문은 계획과 로컬 검색을 거쳐 답변하며, MySQL/Datadog 제안은 수동 입력 양식이 아니라 서버에 묶인 범위 확인·읽기 동의로 이어집니다. `investigation-plans/jobs/{id}/clarification`, `scope/cancel`과 `conversation-operational-reads` 경로의 계약은 [범위 확인](conversation-operational-scope.md), [조회·영수증 복구](conversation-operational-read.md)를 따릅니다. 검색 결과 capture는 당시 스냅샷을 검증하며 재검색하지 않습니다. 아래 수동 양식/V4 설명은 이전 단계 이력입니다. 팀 설정/아카이브 화면은 제거했고 기존 내부 API만 호환 목적으로 유지합니다. 전체 자료 export 지원이 아닙니다.

## 최우선 정책: 외부 업무 시스템 읽기 전용

사용자 승인·과거 승인·재시작 여부와 무관하게 Slack 게시, 외부 DB 변경, 브라우저 업무 변경은 금지합니다. 로컬 Workspace/대화/근거/색인/설정 저장은 유지합니다. HTTP 메서드가 아니라 외부 효과를 기준으로 판단하며, UI 제거와 별개로 직접 API·dispatcher/transport 경계에서도 차단해야 합니다. [현재 정책과 아직 필요한 검증](external-readonly-policy.md)을 따릅니다. 아래의 과거 아티팩트 동작 설명은 차단 구현 완료나 새 릴리스 허가가 아닙니다.

## 전문가 작업본 (DB v28, 미배포)

기존 데스크톱 세션·Origin·Workspace 소유권 검증을 유지합니다. 이 경로를 추가했다는 사실은 실제 회사 서비스 연결이나 전체 인수 완료를 의미하지 않습니다.

- `GET /api/workspaces/{workspaceId}/investigation-plans/jobs/{id}/tool-proposal`: 완료된 수동 조회 제안만 반환합니다. `jobId/workspaceId/bundleId/kind/requiredFields/executionAllowed`이며 마지막 값은 항상 false입니다. `kind`는 `MYSQL_READ`; 조회 범위나 자격 증명을 생성하지 않습니다. 미완료·SEARCH/STOP 작업은409, 소유권/근거 없음은404입니다. 조회만으로 AI·운영 도구·연결 상태 조회를 실행하지 않습니다.
- 계획의 V4 binding은 SEARCH/STOP 외에 위 수동 제안을 허용합니다. 제안은 검색 결과가 아니므로 계획 작업의 `capture`로 답변 근거를 만들 수 없습니다. 이전 binding의 동의는 새 미리보기가 필요합니다.
- `/api/workspaces/{workspaceId}/mysql-investigations`: `POST /preview`, `POST /{id}/execute`, `GET /{id}`, `POST /{id}/cancel`. 대상/조건을 직접 지정하고 별도 일회 동의 후 조회합니다. 결과는 자동 AI 전송하지 않습니다. [권한·길이·시간 제한](plugins.md)을 따릅니다.
- `POST /api/workspaces/{workspaceId}/operational-captures/preview`: `{provider: "MYSQL", resultId}`로 서버에 보관된 완료 결과와 승인 범위를 정확한 저장용 텍스트로 미리 봅니다. 클라이언트가 결과 행이나 범위를 덮어쓸 수 없습니다.
- `POST /api/workspaces/{workspaceId}/operational-captures/{id}/capture`: `{requestId,digest,approved:true}`로 미리보기와 동일한 로컬 질문 첨부를 저장합니다. `GET /{id}`는 영수증 확인만 합니다. 결과·범위·연결·TTL을 재검증하며 저장 실패를 자동 재실행하지 않습니다. 이후 AI 전송은 사용자의 별도 질문 동작입니다.
- Slack 및 Datadog API는 제거했습니다. /api/slack/**, /api/plugins/datadog/**, /api/workspaces/{workspaceId}/datadog-investigations/**는 더 이상 등록되지 않습니다. 이전 Datadog 계획·캡처의 실행도 거부합니다. 저장된 기존 대화·자료는 삭제하지 않습니다.

## 수집 진행·로컬 검색 준비 (DB v25)

- `GET /api/workspaces/{workspaceId}/sources/{sourceId}/progress`: 실제 stage/file/completed/total/cancellable. 전체량 미확정은 total=0이며 예상 시간을 생성하지 않습니다.
- `POST /api/workspaces/{workspaceId}/sources/{sourceId}/cancel`: 발행 전 수집 중단 요청. 완료 분석은 다음 동기화에 재사용합니다. 진행 관측 종료는 취소가 아닙니다.
- `GET /api/search-model`: 로컬 검색 준비 상태·다운로드 바이트·오류. 조회만으로 다운로드하지 않습니다.
- `POST /api/search-model/prepare`: 명시적 모델 다운로드/검증 후 검색 색인 준비. 자료를 다운로드 서버에 보내지 않습니다.
- `POST /api/search-model/cancel`: 다운로드 단계에서만 중단 요청.

기존 세션/Origin 검증과 Workspace 범위 검증을 유지합니다. 원본 파일 업로드는 256 MiB, 폴더/GitHub는 파일별 256 MiB·20,000개·총 8 GiB 한도입니다. [자세한 처리 경계](ingestion-retrieval-upgrade.md).

0.1.14: `GET /api/workspaces/{id}/source-polling` → `{enabled,revision,mixed}`. 같은 경로 PATCH `{enabled,expectedRevision}`는 Workspace 정책과 기존 Source 활성 여부를 원자적으로 갱신합니다. 신규 Source는 저장한 정책을 상속합니다. 누락/잘못된 입력 400, 없는 Workspace 404, revision 충돌 409. 기존 개별 폴링 API는 호환 유지합니다.
 명세

0.1.12 네이티브 명령 `select_source_folder()`는 인자를 받지 않고 폴더 경로 또는 취소 시 null을 반환합니다. 현재 앱의 정확한 loopback origin과 main 창만 허용하며 파일 선택/쓰기/임의 실행 권한은 없습니다. `POST .../sources`의 LOCAL_FOLDER는 등록 시 실제 디렉터리를 검증합니다. [설정·자료 추가](folder-settings.md).

질문 전용 파일은 `POST /api/workspaces/{workspaceId}/attachments`(multipart file), `DELETE .../attachments/{id}`로 관리합니다. `POST .../evidence-bundles`에 선택적 `attachmentIds`(최대 4개)를 전달합니다. [입출력·오류·지원 형식](question-attachments.md).

## AI 연결 / 로그인 (0.1.2부터)

0.1.7 Mac 앱은 전용 Codex 프로필을 사용합니다. 경로·인증 파일은 API로 노출하지 않으며 기존 로그인 API를 그대로 사용합니다. 인증 조회는 명령당 최대 30초, 로그인은 최대 3분입니다. 전용 프로필 변경은 기존 전송 승인을 무효화합니다. [사용법·구현](codex-managed-login.md).

0.1.3 앱 업데이트는 네이티브 시작/메뉴 기능으로, 백엔드 REST API나 WebView IPC를 추가하지 않습니다. [업데이트 계약](automatic-updates.md).

- `POST /api/ai/providers/{CODEX|CLAUDE}/connection`: 명시적 인증 상태 확인. `{provider,state,checkedAt,remoteConnection:"NOT_CHECKED"}`. 자료 전송/추론 없음.
- `POST /api/ai/logins`: `{provider:"CODEX",acknowledged:true}` → `{id,provider,state}`. 이미 인증됐다면 로그인 프로세스를 다시 실행하지 않음.
- `GET /api/ai/logins/{id}`: 메모리 로그인 작업 관측. 앱 재시작/다음 로그인으로 교체된 요청은 404.
- `DELETE /api/ai/logins/{id}`: 대기 중 작업만 CANCELLED. 자격 증명 삭제/로그아웃 아님.
- 상태: PENDING/AUTHENTICATED/FAILED/CANCELLED/TIMED_OUT. 동의 누락 400, 중복 진행/실행 환경 미확인 409.
- 모든 기존 설치형 세션 인증·변경 헤더 방어 적용. 토큰·이메일·인증 URL·CLI 원문은 응답하지 않음. [모델/한계](provider-connection.md).

CLI 실행 경로: `GET/PUT /api/ai/launch-settings`는 현재/저장된 설정과 재시작 필요 여부를 반환합니다. 저장에 경로·예상 revision·신뢰 확인이 필요합니다. [계약](cli-launch-settings.md).

관련 자료: `GET /api/workspaces/{workspaceId}/relations?kind=ORIGINAL|KNOWLEDGE&id=...&version=...&domains=...`는 저장된 심볼/출처 연결을 읽습니다. [입출력·게시본 제한](relations.md).

동기화 API는 [Git 변경 blob 수집](git-incremental.md)과 [URL 조건부 요청](url-conditional.md)을 내부적으로 적용합니다. 별도 승인/API/요청 필드는 없으며 기존 Source 반환 계약을 유지합니다.

자동 갱신: `PATCH /api/workspaces/{workspaceId}/sources/{id}/polling`, 본문 `{enabled, intervalSeconds, expectedRevision}`. 주기 60~86400초, 기본 OFF, 충돌 409. Source 응답에 `nextPollAt`(epoch ms/null), `consecutiveFailures`, `pollingRevision` 추가. [상세 계약](source-polling.md).

문맥 확인: `POST /api/workspaces/{workspaceId}/conversations/{id}/preview`는 읽기 전용입니다. `POST .../conversations/{id}/requests`로 확인한 문맥을 고정한 후에만 기존 묶음의 승인/작업 API가 해당 문맥 해시를 받습니다. 저장과 승인은 별개입니다. [입출력·삭제 정책](context-requests.md).

설치형 모드는 API 요청에 `X-Forge-Session`을 추가합니다. `GET /api/desktop/ready`도 인증이 필요합니다. 개발 모드는 기존 계약을 유지합니다. [인증 계약](desktop-backend.md).

v17 대화 보관함: `/api/workspaces/{workspaceId}/conversations` GET/POST, `/{id}` GET/DELETE, `/{id}/turns` POST. 완료 답변의 로컬 연결만 관리합니다. [입출력·충돌·삭제 계약](conversations.md).

질문별 이력 화면은 기존 `GET /api/workspaces/{workspaceId}/answer-jobs`, `/evidence-bundles` 목록과 각 `/{id}` 상세를 재사용합니다. 새 API·승인·생성 호출은 없습니다. [조회·삭제 정책](answer-history.md).

Codex 답변에 선택적 App Server 경로를 추가했습니다. 새 HTTP API/DB 스키마는 없으며 시작 옵션 `forge.ai.codex-stream-home`으로 전용 로그인 폴더를 지정합니다. 기존 SSE `partial`을 재사용합니다. 기본값은 빈 값(기존 exec)입니다. [설정·오류·격리](codex-streaming.md).

SSE에 `partial` 이벤트를 추가했습니다. Workspace/job/bundle ID와 `text`, `unverified:true`인 임시 스냅샷입니다. 근거·작업 이벤트 뒤에만 전달하며 DB에 저장하지 않습니다. [계약](partial-answer.md).

답변 작업: `POST /api/workspaces/{workspaceId}/evidence-bundles/{bundleId}/answer-jobs` → 202.
조회 `GET /api/workspaces/{workspaceId}/answer-jobs` 및 `/{id}`, 중단 `POST .../answer-jobs/{id}/cancel`.
승인 일회 소비·재시도 멱등·실제 CLI 답변을 지원합니다. [전체 계약](answer-jobs.md).
관측 SSE: `GET /api/workspaces/{workspaceId}/answer-jobs/{id}/events` → 근거, 작업 상태, 완료.
토큰 delta는 없으며 GET/재연결로 AI를 호출하지 않습니다. [이벤트·오류·재연결 계약](answer-events.md).
React 화면도 이 계약에 연결했습니다. [UI의 재연결/종료 정책](answer-events-ui.md). API·스키마 변경은 없습니다.

답변 승인: `GET/POST /api/workspaces/{workspaceId}/evidence-bundles/{bundleId}/answer-approval`,
철회: `DELETE .../answer-approval/{approvalId}`. 10분 유효·명시 동의·기대 ID 충돌 보호, 실제 전송 없음. [전체 계약](answer-approval.md).

P4 준비: `GET /api/workspaces/{workspaceId}/evidence-bundles/{id}/validation`,
`POST /api/workspaces/{workspaceId}/evidence-bundles/{id}/answer-preview`.
읽기 전용 재검증과 제공자/모델별 요청 미리보기이며 전송/승인이 아닙니다. [전체 계약·오류](bundle-validation.md).

근거 통합: `GET /api/workspaces/{workspaceId}/generation-jobs/{jobId}/acceptance/merge-preview`.
기존 merge POST의 선택 필드 `combineEvidence: true`로 반영합니다. [번호 변환·제한](evidence-merge.md).

수동 부분 반영: `POST /api/workspaces/{workspaceId}/generation-jobs/{jobId}/acceptance/merge`.
[본문·버전·근거 계약](proposal-merge.md). 응답 채택 기록의 `mode`는 FULL 또는 MANUAL입니다.

제안 전체 채택: `GET/POST /api/workspaces/{workspaceId}/generation-jobs/{jobId}/acceptance`.
[계약·제한](proposal-acceptance.md). 명시적 확인 후 새 DRAFT로만 반영하며 게시하지 않습니다.

제안 거절·재검토: `GET/POST /api/workspaces/{workspaceId}/generation-jobs/{jobId}/decisions`.
[요청·응답·충돌 계약](proposal-decisions.md). 문서 반영이나 게시 API가 아닙니다.

추가: `GET /workspaces/{workspaceId}/generation-jobs/{jobId}/comparison` → `{base,current,generated}`.
완료 작업의 생성 당시/현재/AI 최초 문서를 읽기 전용으로 비교합니다. [계약·오류·버전 선택](generation-comparison.md).

기본 URL: `http://127.0.0.1:8787/api`

지식 API는 수동/승인 기반 CLI AI DRAFT와 사람 검토·명시적 게시를 지원합니다. 자동 의미 검증은 없습니다.
Markdown 응답은 저장된 원문이며 HTML이 아닙니다. UI만 안전한 미리보기를 제공합니다.
표시 정책은 [렌더링 정책](markdown-rendering.md)을 따릅니다. 현재 DB는 v17이며 답변 작업에 더해 보관함/순서 연결 테이블이 추가되었습니다. 기존 근거 묶음·게시 색인 큐·FULL/MANUAL 채택 기록도 보존합니다.

폴더 이름/부모 변경: `PUT /workspaces/{workspaceId}/knowledge-organization/folders/{id}`.
요청 `{parentId, name, expectedRevision}` → 변경한 Folder `{id,parentId,name,revision}`.
Folder 조회/생성에도 revision을 반환합니다. [오류·충돌·전체 계약](folder-update.md).

모든 변경 요청은 `X-Forge-Client: 1` 헤더가 필요합니다.
JSON 요청은 `Content-Type: application/json`을 사용합니다.
브라우저는 동일 출처를 사용하며 임의 외부 Origin/Host는 403입니다.
ID는 UUID, 날짜는 ISO-8601 UTC 문자열입니다.

| 메서드 | 경로 | 요청 | 성공 |
|---|---|---|---|
| GET | `/session` | 없음 | 200 Session |
| POST | `/workspaces` | `{ "name": "연구 노트" }` | 201 Workspace, 자동 활성화 |
| PUT | `/workspaces/{id}/active` | 없음 | 204 |
| DELETE | `/workspaces/{id}` | 없음 | 204, 내부 자료 cascade 삭제 |
| GET | `/workspaces/{id}/entries` | `area=ORIGINAL\|KNOWLEDGE`, 선택 `parentId` | 200 Entry 배열 |
| POST | `/workspaces/{id}/folders` | `{ "name": "참고", "parentId": null }` | 201 Entry |
| POST | `/workspaces/{id}/files` | multipart `file`, 선택 `parentId` | 201 Entry |
| GET | `/workspaces/{id}/entries/{entryId}/preview` | 없음 | 200 Preview |
| GET | `/workspaces/{id}/entries/{entryId}/content` | 없음 | 200 바이트, attachment |
| DELETE | `/workspaces/{id}/entries/{entryId}` | 없음 | 204, 폴더는 하위 자료 포함 |

`parentId` 생략은 루트입니다. 빈 문자열은 유효한 ID가 아닙니다.
기본 `area`는 ORIGINAL입니다. 목록은 현재 폴더의 직계 자식만 반환하며
폴더 우선, 이름 순으로 정렬됩니다. 현재 페이지네이션은 없습니다.
이름은 trim 후 1~120자, 경로 구분자·제어 문자 및 `.`/`..` 금지입니다.
동일 이름 비교의 대소문자 처리는 SQLite 기본 NOCASE(ASCII)에 따릅니다.

## 응답 모델

```json
{
  "workspaces": [
    { "id": "uuid", "name": "연구 노트", "createdAt": "2026-10-21T00:00:00Z" }
  ],
  "activeWorkspaceId": "uuid"
}
```

Workspace가 없으면 `workspaces: []`, `activeWorkspaceId: null`입니다.

```json
{
  "id": "entry-uuid",
  "workspaceId": "workspace-uuid",
  "parentId": null,
  "name": "notes.md",
  "kind": "FILE",
  "area": "ORIGINAL",
  "size": 123,
  "createdAt": "2026-10-21T00:00:00Z"
}
```

종류는 FILE/FOLDER, 크기는 바이트입니다.

```json
{ "text": "# 가상 자료", "supported": true, "reason": null }
```

미리보기 미지원은 HTTP 오류가 아닙니다.
`{ "text": null, "supported": false, "reason": "..." }`로 반환합니다.
1 MiB 초과, 유효하지 않은 UTF-8, 바이너리 제어 문자는 미지원입니다.

## 오류

애플리케이션 오류는 `application/problem+json`의 `status`, `detail`로 반환합니다.
잘못된 JSON, 필수 multipart 필드 누락 등 Spring 기본 검증 오류는 기본 400 응답입니다.

| 상태 | 의미 |
|---|---|
| 400 | 잘못된 이름/영역/부모 종류, 폴더 미리보기·다운로드 |
| 403 | 외부 Origin/Host 또는 변경 헤더 누락 |
| 404 | Workspace/자료가 없거나 다른 Workspace 소속 |
| 409 | 중복 Workspace 이름 또는 같은 폴더의 자료 이름 |
| 413 | 파일 10 MiB 초과 / multipart 요청 11 MiB 초과 |
| 500 | 예기치 않은 저장소 오류; 내부 정보는 응답에 노출하지 않음 |

```bash
curl -X POST http://127.0.0.1:8787/api/workspaces \
  -H 'Content-Type: application/json' -H 'X-Forge-Client: 1' \
  -d '{"name":"가상 실험"}'

curl -X POST http://127.0.0.1:8787/api/workspaces/WORKSPACE_ID/files \
  -H 'X-Forge-Client: 1' -F 'file=@fixtures/research-notes.md'
```

## Phase 2 Source / 분석 / 검색

다음 경로는 `/api/workspaces/{workspaceId}` 기준입니다.

| 메서드 | 경로 | 요청 / 응답 |
|---|---|---|
| GET | `/sources` | Source 배열 |
| POST | `/sources` | name, kind, location, 선택 domain → 201 Source |
| PATCH | `/sources/{sourceId}` | name, domain, expectedRevision → 200 Source; 이름·분류만 수정, 수집 중/충돌 409 |
| POST | `/sources/{sourceId}/sync` | 수동 수집 → 200 Source, 상태 READY 또는 ERROR |
| DELETE | `/sources/{sourceId}` | Source와 보관된 원본만 삭제 → 204 |
| GET | `/entries/{entryId}` | 단일 Entry 메타데이터 |
| GET | `/entries/{entryId}/analysis` | 저장된 분석 결과. 재분석하지 않음 |
| POST | `/entries/{entryId}/analyze` | 보관된 원본 재분석 → Analysis |
| GET | `/search?query=...&domains=...&domains=...` | 검색 Hit 배열, 최대 30건 |

```json
{
  "name": "가상 프로젝트",
  "kind": "LOCAL_FOLDER",
  "location": "/absolute/path/to/fixtures/synthetic-project",
  "domain": "실험"
}
```

kind는 LOCAL_FOLDER / URL / GITHUB입니다. URL은 공개 HTTPS 443, GITHUB는
`https://github.com/owner/repository` 형태의 기본 브랜치를 지원합니다. 비공개 저장소는 `/api/plugins/github`에서 조회 검증을 마친 인증이 필요합니다.
Source 이름은 같은 Workspace에서 고유하며 도메인은 0~80자입니다.
Source 등록은 수집을 시작하지 않습니다.
화면에서는 등록 성공 후 별도 동기화 요청을 보냅니다. AI 로그인은 수집의 선행 조건이 아닙니다.
이름·분류 편집도 수집을 시작하지 않습니다. `expectedRevision`은 현재 `pollingRevision`이며 폴링 설정과 공통 충돌 버전입니다. 위치·종류는 수정하지 않습니다. [계약·보존 정책](source-metadata.md).

Source 필드: `id, workspaceId, name, kind, location, domain, status, revision,
error, syncedAt, rootEntryId, pollingEnabled, pollIntervalSeconds`.
상태는 NEW/SYNCING/READY/ERROR입니다. pollingEnabled는 현재 항상 false,
pollIntervalSeconds는 예약된 설정 기본값 3600이며 실제 스케줄은 없습니다.
revision은 로컬/URL 스냅샷 SHA-256 또는 Git commit SHA입니다.

동기화의 수집 오류는 HTTP 200의 `status: "ERROR"`와 `error`로 표현합니다.
이전 `revision`, `syncedAt`, 원본/검색 데이터는 유지됩니다.
동시 동기화는 409, 없는/다른 Workspace의 Source는 404입니다.
Source 관리 Entry의 개별 삭제나 하위 업로드/폴더 생성은 409입니다.
Source 전체 삭제와 Workspace 삭제는 가능합니다.

```json
{
  "text": "package synthetic; ...",
  "mediaType": "text/x-java-source",
  "status": "READY",
  "error": null,
  "symbols": [
    { "name": "measureTemperature", "kind": "CALLABLE", "line": 4, "signature": "measureTemperature()" }
  ]
}
```

분석 상태: READY, PARTIAL(Java 구문 실패로 원문만 검색), UNSUPPORTED(추출 텍스트 없음),
ERROR(추출 실패/크기 초과), PENDING(Phase 1 기존 파일).
원본 바이트는 분석 실패에도 보존됩니다. OCR/임베디드 문서 재귀 추출은 비활성입니다.

Hit 필드: `entryId, name, sourceId, domain, path, snippet, score, startLine, endLine, bodyMatch, kind, cardId, version, domains, freshness, textHash`.
`textHash`는 색인 본문 UTF-8 SHA-256입니다. [답변용 근거 묶음 API](evidence-bundles.md)는 이 값으로 현재 본문과 다시 대조한 뒤 로컬에 인용문을 보관합니다. AI 전송은 없습니다.
`includeKnowledge=true`이면 원본과 최신 게시 지식을 함께 검색합니다. 검색 API 기본 false(원본만)입니다. 0.1.11 기본 대화 UI는 편집형 지식 문서를 제외하도록 근거 생성에 `includeKnowledge=false`를 명시합니다. 기존 API의 명시적 지식 검색은 호환용으로 유지합니다. [사용자 경로](internal-search.md).
`kind=ORIGINAL`은 entryId를, `kind=KNOWLEDGE`는 cardId/version을 사용하며 나머지 ID는 null입니다.
게시 지식의 도메인은 현재 근거 Source 도메인들의 합집합이며 sourceId/path는 빈 문자열입니다.
`freshness`는 게시 지식에서 CURRENT/STALE/MISSING, 원본에서는 UNCHECKED입니다. [정책·제한](published-search.md).
행 범위는 원본의 추출 텍스트 또는 게시 지식의 Markdown 기준(1-based, 빈 본문은 0)입니다. 일부 행만 포함할 수 있습니다.
`bodyMatch=true`는 본문 검색 단어 주변, false는 이름·심볼 일치의 앞부분 미리보기입니다.
최대 320 UTF-16 코드 단위이며 확정 인용/최신성 보증은 아닙니다. [설명](search-evidence.md).
직접 업로드한 파일의 sourceId/domain은 빈 문자열입니다.
검색어는 1~256자, 도메인은 최대 20개이며 선택하지 않으면 Workspace 전체를 검색합니다.
결과는 기존 인덱스에서만 조회하며 검색을 위해 네트워크 수집/원문 재분석을 실행하지 않습니다.

## 지식 초안·버전·근거 (Phase 3 P3-1)

접두사: `/workspaces/{workspaceId}/knowledge`. 모든 접근은 Workspace 범위입니다.

| 메서드 | 경로 | 결과 |
|---|---|---|
| GET | 접두사 | 카드 요약 배열, 최신 제목/버전 |
| POST | 접두사 | 수동 초안 생성, 201 Document |
| GET | `/{id}` | 최신 Document |
| GET | `/{id}/versions/{number}` | 지정 버전 Document (1부터 시작) |
| POST | `/{id}/versions` | 새로운 수동 버전, 201 Document |
| DELETE | `/{id}` | 모든 버전 삭제 및 단독 참조 스냅샷 정리, 204 |
| GET | `/{id}/evidence/{snapshotId}/content` | 당시 원본 바이트, octet-stream 첨부 파일 |

초안 생성 요청 예:

```json
{
  "title": "가상 실험 메모",
  "markdown": "검토가 필요한 수동 초안입니다.",
  "citations": [{ "entryId": "원본-file-UUID", "startLine": 1, "endLine": 3 }]
}
```

제목은 공백만인 값과 제어문자를 허용하지 않는 1~120자, 본문은 공백만인 값을 제외한 1~100,000자입니다.
근거는 1~10개, 각 범위는 1~500행, 원본 크기 합계는 50 MiB 이하입니다.
`startLine/endLine`은 **저장된 추출 텍스트의 1-based 포함 범위**입니다.
원본 코드 행/PDF 페이지와 같다고 보장하지 않습니다. READY/PARTIAL 분석 텍스트가 필요합니다.
인용 문구·해시·revision은 클라이언트가 제공하지 않고 서버가 보관 원본에서 가져옵니다.

새 버전 요청 예:

```json
{ "expectedVersion": 1, "title": "수정한 메모", "markdown": "사람이 수정한 새 초안" }
```

`expectedVersion`이 최신 버전과 다르면 409이며 기존 버전은 바뀌지 않습니다.
`citations` 생략/null은 이전 버전의 근거 스냅샷을 그대로 재사용합니다.
배열을 제공하면 현재 원본에서 새로 캡처합니다. 빈 배열은 400입니다.
UI에서는 근거 유지 편집만 지원하며 근거 교체는 현재 API로 가능합니다.

Document 응답 구조:

```text
card: { id, workspaceId, title, latestVersion, createdAt }
version: { number, title, markdown, status, manuallyEdited, createdAt, evidence: [...] }
evidence[]: { snapshotId, entryId, sourceId, sourceName, sourceKind, sourceLocation, sourceRevision,
              path, name, contentHash, startLine, endLine, quote, freshness, capturedAt }
```

수동 생성/편집은 `status=DRAFT`, `manuallyEdited=true`입니다. AI 생성은 false입니다. 의미적 검증이나 자동 게시는 없습니다.
이전 버전 조회에서도 `card`는 최신 요약이고 `version`이 요청한 과거 버전입니다.
직접 업로드한 파일은 sourceId/sourceName/sourceKind/sourceLocation이 null이며 sourceRevision은 원본 SHA-256입니다.
entryId는 캡처 당시 식별자이므로 동기화/삭제 뒤 존재하지 않을 수 있습니다.

근거 `freshness`는 조회 시 계산합니다:
- CURRENT: 같은 Source+상대 경로(수동 업로드는 entry ID)의 원본/추출 텍스트 해시가 일치
- STALE: 현재 파일은 있으나 원본 또는 추출 텍스트가 변경됨
- MISSING: 현재 파일/Source가 삭제됨. 당시 인용과 바이트는 계속 조회 가능

CURRENT는 마지막 로컬 수집본 기준이며 외부 Source의 실시간 최신성/사실성을 보증하지 않습니다.
원본을 삭제해도 지식은 남습니다. 지식 삭제는 원본에 영향을 주지 않습니다.
Workspace 삭제는 해당 지식·버전·근거 복사본을 모두 삭제합니다.
다른 Workspace의 파일/지식/스냅샷 접근은 404입니다. 유효하지 않은 범위/본문은 400,
근거 원본 용량 초과는 413입니다. 생성/수정 실패는 스냅샷을 포함해 트랜잭션 전체를 롤백합니다.

## CLI AI 초안

아래 동기 API는 호환용입니다. 새 UI는 다음 절의 비동기 작업 API를 사용합니다.

접두사 `/workspaces/{workspaceId}/knowledge/{id}/generation`, 변경 헤더 필수.

미리보기·동기 생성·비동기 작업 요청에 `requestedModel`을 선택적으로 전달합니다.
생략/null은 서버 시작 설정, 빈 문자열은 CLI 기본 선택, 식별자는 건별 지정입니다.
미리보기 응답은 해석된 모델을 반환하며 UI는 이를 작업 접수 때 명시적으로 보냅니다.
승인 digest에 포함되므로 모델 변경 후에는 새 미리보기로 재승인해야 합니다. 형식 오류는 400입니다.
생성된 초안 Markdown은 요청 모델/실제 모델 미확인/승인 digest를 기록합니다.

- `POST /preview` 요청 `{ "provider": "CODEX" }` 또는 `CLAUDE`.
  응답 `{ provider, expectedVersion, digest, prompt, requestedModel }`. 외부 호출 없음.
  최신 카드의 CURRENT 근거 인용문만 사용하며 파일명/경로/기존 Markdown은 전송하지 않습니다.
- `POST` 요청 `{ "provider": "CODEX", "expectedVersion": 1, "digest": "미리보기 SHA-256", "approved": true }`.
  201 Document: **다른 ID의 새 AI DRAFT**, 기존 카드/버전은 변경하지 않음.
  결과 근거 E1…E10은 원본 카드의 근거 순서와 같으며 동일 스냅샷을 참조합니다.
- 400 잘못된 Provider/요청, 403 승인 거부, 404 Workspace/카드 없음,
  409 오래된 승인/근거 변경/동시 생성, 413 전송 프롬프트 16,000자 초과,
  502 CLI/출력 검증 실패, 503 인증 필요/중단, 504 120초 제한. 오류에 CLI stderr/인증 정보는 포함하지 않음.
- 모델 출력은 title(120자), claims(1~20), 각 text(2,000자)/evidenceIds를 검사합니다.
  ID 존재는 의미적 사실 검증이 아닙니다. 모든 결과는 미검증으로만 저장합니다.
- 동기 API이며 전역 한 작업만 허용합니다. 화면 종료/HTTP 연결 해제는 작업 취소가 아닙니다.
  자동 재시도하지 마세요. 재요청은 별도 초안을 생성할 수 있습니다. 영속 작업 ID/취소는 다음 비동기 API에서 지원합니다.

## AI 생성 작업 (v6)

- `POST /workspaces/{workspaceId}/knowledge/{id}/generation/jobs`
  요청 `{ "requestId": "UUID", "provider": "CODEX", "expectedVersion": 1, "digest": "미리보기 SHA-256", "approved": true }`.
  202 Job. 원본 프롬프트를 클라이언트에서 받지 않습니다. 승인/내용/크기 검사는 접수 때 실행합니다.
- `GET /workspaces/{workspaceId}/generation-jobs`: 최근 20개 Job (생성 시각 내림차순).
- `GET /workspaces/{workspaceId}/generation-jobs/{jobId}`: 개별 Job.
- `POST /workspaces/{workspaceId}/generation-jobs/{jobId}/cancel`: 200 Job.
  CANCELLING은 중단 요청 접수이며, CANCELLED는 worker의 정리가 끝난 상태입니다.
  이미 완료/실패/취소된 작업에는 상태 변경 없이 현재 Job을 반환합니다.

```text
Job: { id, workspaceId, sourceCardId, provider, expectedVersion, digest, status,
       resultCardId, error, createdAt, updatedAt, requestedModel,
       cliVersion, cliVersionStatus, cliCheckedAt }
status: QUEUED | RUNNING | CANCELLING | CANCELLED | SUCCEEDED | FAILED | INTERRUPTED
```

변경 헤더가 필수이며 Workspace 격리(다른 공간 작업은 404)를 적용합니다.
동일 Workspace/requestId의 같은 요청은 **기존 작업만 반환**합니다. 다른 내용은 409입니다.
명시한 요청 모델이 기존 작업과 달라도 409입니다. Job의 requestedModel은 승인 당시 모델이며,
빈 문자열은 CLI 기본 선택, null은 v4에서 생성한 기존 작업의 미기록 값입니다. 실제 실행 모델을 의미하지 않습니다.
v5 이력은 실패/취소/재시작 후에도 유지합니다. [건별 모델 선택 계약](model-selection.md).
v6의 cliVersion/cliCheckedAt은 실행 전 관측한 버전/UTC 시각이며 미기록은 null입니다.
cliVersionStatus는 NOT_CHECKED/AVAILABLE/UNAVAILABLE/UNRECOGNIZED/BUSY입니다.
승인된 비동기 작업만 관측하며 재전송으로 갱신하지 않습니다. 실제 실행 바이너리 증명은 아닙니다.
진단 실패는 기록 후 생성하지만 관측 DB 저장 실패는 모델 호출 전 FAILED가 됩니다. [상세](cli-observation.md).
작업 오류는 status=FAILED/error로 조회하며 HTTP 접수 성공을 생성 성공으로 오인하지 마세요.
같은 ID로 실패/취소/중단을 재실행하지 않습니다. 새 시도에는 새 미리보기·승인·ID가 필요합니다.
실행 중이거나 중단 정리 중인 다른 작업이 있으면 409, 잘못된 ID는 400, 미승인은 403입니다.
재시작 후 미완료 작업은 INTERRUPTED이며 자동 외부 재전송은 없습니다. 상세 [작업 설계](generation-jobs.md).

## 로컬 CLI 설치 진단

`POST /api/ai/providers/{CODEX|CLAUDE}/probe`, `X-Forge-Client: 1` 필수, body 없음.
200 `{ "provider":"CODEX", "status":"AVAILABLE", "version":"0.1.0", "requestedModel":"", "authentication":"UNKNOWN", "remoteConnection":"NOT_CHECKED" }`.
버전 예시는 가상 값입니다. `status`는 AVAILABLE/UNRECOGNIZED/UNAVAILABLE이며 마지막 두 경우 version은 null입니다.
진단 응답의 requestedModel은 서버 기본 모델이며, 생성 미리보기에서 해석한 건별 모델과는 별개입니다.
잘못된 Provider 400, 변경 헤더 누락 403, 다른 진단 진행 중 409.
설정된 CLI의 `--version`만 실행하며 설치 확인은 로그인·원격 연결·모델 지원 확인이 아닙니다.
5초 실행 제한, 원문 출력 비노출. [설정·보안 경계](cli-settings.md).

## 사람 검토·게시 API (v7 추가)

`/api/workspaces/{workspaceId}/knowledge/{id}` 아래에 GET `/publication`, POST `/review`,
POST `/publication`, GET `/published`를 제공합니다. 상세 요청/응답·오류·사용 순서는
[검토/게시 계약](publication.md#rest-계약)을 따릅니다. 기존 Document의 `version.status`는
불변 생성 상태 DRAFT이며 실제 사람 검토/게시 상태는 별도 `/publication` 응답으로 확인합니다.
자동 의미 검증은 아직 제공하지 않습니다.

## 근거의 현재 원본 위치

`GET /api/workspaces/{workspaceId}/knowledge/{id}/versions/{number}/evidence/{snapshotId}/location`
→ `{entry,ancestors,freshness}`. ancestors는 루트부터 부모까지의 Entry 배열입니다.
소속 버전의 근거만 조회 가능하며 Source ID+경로 또는 업로드 entry ID로 현재 파일을 해석합니다.
CURRENT/STALE은 당시 스냅샷과 마지막 로컬 수집본의 비교입니다. 없는 근거/원본/다른 Workspace는 404,
손상된 폴더 경로는 409입니다. [응답 예제·일관성·한계](evidence-navigation.md).

## 독립 지식 폴더·카드 배치 (v8)

`/api/workspaces/{workspaceId}/knowledge-organization` 아래 GET catalog,
POST `/folders` 생성, DELETE `/folders/{id}` 빈 폴더 삭제,
PUT `/cards/{id}` 카드 배치를 제공합니다. 이동 body는 `{folderId,expectedRevision}`이며 루트는 null입니다.
위치 revision은 문서 버전과 독립이고 최초 0입니다. 기존 `/knowledge` API는 전체 목록을 유지합니다.
[전체 요청·응답·오류 계약](knowledge-folders.md#api)을 확인하세요.
