# 지식 근거에서 현재 원본으로 이동

## 구현 범위

P3-4를 기능 단위로 진행하며 먼저 **출처 탐색 연결**을 구현했습니다. 지식 폴더는 아직 없습니다.
지식 문서의 근거마다 **현재 원본에서 보기**를 누르면 원본 영역으로 전환하고,
루트부터 부모 폴더까지의 경로와 해당 파일의 미리보기를 표시합니다.
**당시 근거 원본 다운로드**는 기존 불변 스냅샷을 계속 제공합니다.

- 일반 업로드는 당시 entry ID로 찾습니다. 삭제 후 같은 이름으로 올린 파일은 다른 원본입니다.
- Source 자료는 같은 Workspace의 **Source ID + 상대 경로**로 찾습니다.
  동기화로 entry ID가 교체되어도 같은 출처를 찾으며, 내용이 바뀌었다면 STALE 경고와 함께 현재 파일을 엽니다.
- 파일 이름 변경을 추정하거나 새 Source를 과거 Source로 대체하지 않습니다. 이런 경우에는 404입니다.
- 클릭 시마다 다시 조회합니다. 목록에 MISSING으로 표시되어도 이후 같은 Source 경로가 복원될 수 있습니다.
- 현재 파일에 과거 인용 행을 자동 적용하지 않습니다. 과거 근거와 현재 내용은 다를 수 있습니다.
- 외부 웹페이지나 OS 파일을 열지 않고 앱 내부에 저장된 원본 복사본만 표시합니다. 외부 호출/AI 전송은 없습니다.

## API

`GET /api/workspaces/{workspaceId}/knowledge/{id}/versions/{number}/evidence/{snapshotId}/location`

200 응답:

```json
{
  "entry": { "id": "current-entry", "workspaceId": "workspace", "parentId": "parent", "name": "notes.txt", "kind": "FILE", "area": "ORIGINAL", "size": 12, "createdAt": "2026-10-21T00:00:00Z" },
  "ancestors": [ { "id": "parent", "workspaceId": "workspace", "parentId": null, "name": "자료", "kind": "FOLDER", "area": "ORIGINAL", "size": 0, "createdAt": "2026-10-21T00:00:00Z" } ],
  "freshness": "CURRENT"
}
```

`ancestors`는 루트부터 부모까지 순서이며 루트 파일은 빈 배열입니다. freshness는 CURRENT 또는 STALE입니다.
현재 원본이 없으면 404이며 당시 복사본 다운로드는 계속 가능합니다. 다른 Workspace/카드/버전의
근거 또는 없는 버전도 404입니다. 손상된 순환/다른 영역의 부모 경로는 409로 종료합니다.
GET은 기존 Host/Origin 검사를 유지하며 변경 헤더나 외부 전송 승인은 필요하지 않습니다.

## 설계와 일관성

```mermaid
flowchart LR
    Card[선택한 지식 버전의 근거] --> API[버전별 위치 조회]
    API --> Scope[Workspace·카드·버전 근거 소속 확인]
    Scope --> Resolve[업로드 ID 또는 Source ID+경로]
    Resolve --> Current[현재 파일 + 루트부터 부모 경로 + 최신성]
    Current --> UI[원본 폴더 목록 재조회 후 파일 선택]
    Resolve --> Missing[없으면 404 / 과거 복사본 유지]
```

KnowledgeService가 기존 LibraryStore와 KnowledgeStore 포트를 사용합니다. `currentMaterial`은
최신성 판정과 탐색의 공통 해석 규칙입니다. 별도 폴더 테이블이나 빈 모듈을 만들지 않았으며 DB는 v7 그대로입니다.
버전 소속 확인·현재 파일 해석·부모 경로 조회는 한 SQLite 트랜잭션입니다.
네트워크 응답 이후 다른 작업이 원본을 변경할 수 있으므로 UI는 폴더 목록을 다시 불러온 뒤 ID가 있는 경우만 선택합니다.
파일이 사라졌으면 오류를 표시하고 잘못된 파일을 대신 선택하지 않습니다. 화면 이탈 요청은 중단하고 늦은 응답을 무시합니다.
조회 시점 뒤의 내용 변경을 잠그지는 않습니다. CURRENT는 조회 당시 로컬 복사본과의 일치이며 실시간 원격 사실 보증이 아닙니다.

## 검증과 남은 범위

가상 자료만 사용하며 실제 AI/외부 수집은 이번 검증에서 호출하지 않습니다.
백엔드 신규 7개: 루트/중첩 폴더, 동기화 ID 교체·STALE, 같은 이름 재업로드 차단,
이름 변경·Source 재등록 차단, 카드/버전 근거 소속, REST/Workspace 격리·삭제, 순환 경로 종료.
UI 신규 6개: 클릭 시 조회, 삭제 오류, 편집 중 차단, 이탈 후 늦은 응답 무시,
실제 App→KnowledgePanel→원본 경로/미리보기 연결 및 이동 중 삭제 처리.
JAR smoke에는 재시작 후 위치 보존과 Source 삭제 후 404를 추가했습니다.

남음: 독립 지식 폴더 생성/배치/이동, 안전한 Markdown 렌더링, 지식으로 돌아가기 UX,
현재 행과 과거 인용 행의 diff 매핑, Git rename 추적. P3-4 전체를 완료로 표시하지 않습니다.

## 실제 실행 결과 (2026-10-21)

| 명령 | 결과 |
|---|---|
| `bash scripts/maven.sh test -Dtest=KnowledgeIntegrationTest` | 기존 12개 + 신규 7개, 총 19개 통과 |
| `npm --prefix frontend test` | 42개 통과 |
| `npm --prefix frontend run build` | TypeScript/Vite 성공 |
| `bash scripts/maven.sh clean verify` | 116개 통과, 실제 CLI opt-in 1개 skip, 실행 JAR 빌드 성공 |
| `node scripts/smoke.mjs` | 실제 JAR HTTP/재시작 탐색·Source 삭제 후 404·과거 근거 다운로드 보존 통과 |

이번 작업에서 실제 AI 호출과 수동 브라우저 QA는 실행하지 않았습니다.
기존 deprecated API/Mockito agent/Lucene vector/native access 경고는 남아 있습니다.
미해결 테스트 실패는 없습니다. smoke의 AI 경로는 fake CLI fixture이며 실제 모델 성능 검증이 아닙니다.
측정 시각 2026-10-21T11:57:59Z, macOS Intel/Java 21: 시작 17,562ms, 재시작 18,180ms,
목록 p95 9.20ms, 검색 p95 22.64ms. 원시 값은 `performance-phase3-foundation.json`에 있습니다.
작은 가상 자료의 순차 요청 결과이며 대규모·동시 수집·LLM 성능을 보장하지 않습니다.
