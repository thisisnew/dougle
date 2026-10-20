# Phase 1–2 API 명세

기본 URL: `http://127.0.0.1:8787/api`

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

Hit 필드: `entryId, name, sourceId, domain, path, snippet, score`.
직접 업로드한 파일의 sourceId/domain은 빈 문자열입니다.
검색어는 1~256자, 도메인은 최대 20개이며 선택하지 않으면 Workspace 전체를 검색합니다.
결과는 기존 인덱스에서만 조회하며 검색을 위해 네트워크 수집/원문 재분석을 실행하지 않습니다.
