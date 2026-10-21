# Phase 1–2 및 Phase 3 기반 API 명세

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
표시 정책은 [렌더링 정책](markdown-rendering.md)을 따릅니다. 현재 DB는 v16이며 답변 작업 테이블이 추가되었습니다. 기존 근거 묶음·게시 색인 큐·FULL/MANUAL 채택 기록도 보존합니다.

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
`https://github.com/owner/repository` 형태의 공개 기본 브랜치만 지원합니다.
Source 이름은 같은 Workspace에서 고유하며 도메인은 0~80자입니다.
Source 등록은 수집을 시작하지 않습니다.

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
`includeKnowledge=true`이면 원본과 최신 게시 지식을 함께 검색합니다. 기본 false(원본만), UI 기본 true입니다.
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
