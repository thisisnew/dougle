# 지식 폴더 이름·위치 변경

2026-10-21 구현·검증. **폴더를 옮겨도 문서 내용과 게시 이력은 바뀌지 않습니다.**

## 사용 방법

1. 지식 폴더를 열고 **폴더 이름·위치 변경**을 누릅니다.
2. 새 이름과 상위 폴더를 선택합니다. 최상위로 옮기려면 **지식 루트**를 선택합니다.
3. **폴더 변경 저장**을 누릅니다. 하위 폴더와 카드는 그대로 따라갑니다.
4. 충돌하면 입력을 보존합니다. 입력을 복사한 뒤 취소 → 새로고침 → 변경 화면 다시 열기로 재확인하세요.

자신/하위 폴더로 이동, 같은 위치의 같은 이름, 전체 깊이 16 초과, 다른 Workspace로 이동은 거절합니다.
문서 편집 중에는 폴더 변경을 막습니다. 원본 폴더 이름 변경/이동은 이번 범위가 아닙니다.

## 모델과 API

SQLite **v9**에서 `knowledge_folder.revision`을 추가했습니다. 기존/새 폴더는 1부터 시작합니다.
변경마다 1 증가하며 문서 버전·카드 배치 revision과는 별개입니다.
카드·근거·검토·게시 테이블은 변경하지 않습니다. v8 데이터와 폴더 구조를 보존합니다.
업데이트 전에 앱을 종료하고 데이터 디렉터리를 백업하세요. 역방향 마이그레이션은 없습니다.

```http
PUT /api/workspaces/{workspaceId}/knowledge-organization/folders/{id}
X-Forge-Client: 1
Content-Type: application/json

{"parentId":null,"name":"새 이름","expectedRevision":1}
```

응답: `200 {"id":"...","parentId":null,"name":"새 이름","revision":2}`.
조회/생성 Folder 응답에도 revision이 추가됩니다. `parentId:null` 또는 생략은 루트입니다.
위치를 유지하려면 현재 parentId를 보내세요. 값이 같아도 성공한 저장은 revision을 증가시킵니다.

| 응답 | 의미 |
|---|---|
| 400 | 이름/필수 revision 오류, 순환 이동, 깊이 초과 |
| 403 | 변경 요청 헤더 없음 |
| 404 | Workspace/폴더/이동 대상 없음 또는 다른 공간의 ID |
| 409 | 오래된 revision 또는 같은 위치의 이름 충돌 |

`OrganizationService`가 변경 후 트리의 순환·깊이를 검사하고,
`JdbcOrganizationStore`가 기대 revision으로 조건부 UPDATE합니다.
현재 단일 SQLite 연결의 트랜잭션으로 검사와 저장을 직렬화합니다.
UI는 변경 화면을 처음 연 revision을 보내므로 새로고침이 오래된 편집을 몰래 최신 요청으로 바꾸지 않습니다.

## 실제 검증

| 명령 | 결과 |
|---|---|
| `bash scripts/maven.sh test -Dtest=OrganizationIntegrationTest,MigrationTest` | 21개 통과 |
| `npm --prefix frontend test -- --maxWorkers=2` | 63개 통과 |
| `npm --prefix frontend run build` | TypeScript/Vite 성공 |
| `bash scripts/maven.sh clean verify` | 130개 통과, 실제 CLI opt-in 1개 skip, JAR 성공 |
| `node scripts/smoke.mjs` | 이름·부모·revision 변경 후 재시작 보존 포함 통과 |

하위 카드·근거·게시본 보존, 오래된 요청/동시 변경 충돌, 순환·Workspace 격리,
하위 트리 깊이 초과 거절/16단계 허용, 이름 충돌 롤백, v8→v9 보존과 재실행을 검사했습니다.
UI는 요청 revision·변경 후 경로·충돌 입력 보존·취소·편집 중 비활성화를 확인합니다.

측정: 2026-10-21T12:45:27.312Z, 시작 5,857 ms, 재시작 6,457 ms,
목록 p95 4.63 ms, 검색 p95 16.02 ms. [원시 결과](performance-phase3-foundation.json).
작은 가상 데이터의 순차 HTTP 측정입니다. AI 경로는 fake CLI이며 실제 AI 호출·브라우저 시각 QA·설치형 검증은 하지 않았습니다.

## 남은 작업

드래그·일괄 이동, 대규모 트리 성능, AI 갱신 제안·충돌 해결은 후속입니다.
전체 폴더 목록을 메모리에서 검사하므로 대규모 트리에는 별도 개선이 필요합니다.
채팅/SSE·자동 동기화·설치형 앱도 아직 남아 있습니다.
