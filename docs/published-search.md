# 원본과 게시 지식을 함께 검색하기

이 문서는 통합 검색 도입 당시 기록입니다. 후속 [답변용 근거 묶음·v14](evidence-bundles.md) 보관도 구현했습니다. 전송/채팅은 아직 없습니다.

## 쉽게 말하면

검색창에서 **게시 지식도 검색**을 켜면 원본 자료와 사람이 게시한 지식을 함께 찾습니다.
검토만 한 문서나 수정 중인 초안은 검색되지 않습니다.

1. 자료 연결과 검색에서 검색어를 입력합니다. 도메인은 여러 개 선택할 수 있습니다.
2. 결과의 `원본` 또는 `게시 지식 v1 · 근거 CURRENT` 표시를 확인합니다.
3. 게시 지식을 누르면 **검색 당시 게시 버전**이 읽기 전용 창으로 열립니다.
4. `[E1]` 등의 출처를 펼쳐 당시 인용문과 현재 근거 상태를 확인합니다.

게시 후 새 초안을 작성해도 검색에는 기존 게시본이 나옵니다. 새 버전을 다시 검토·게시해야 바뀝니다.
게시 반영은 보통 2초 주기의 로컬 색인 큐 처리 후 보입니다. Source 자동 폴링과는 다릅니다.

## 상태를 읽는 법

| 표시 | 뜻 |
|---|---|
| CURRENT | 모든 근거가 현재 앱의 수집본과 동일 |
| STALE | 근거 중 변경된 자료가 있음 |
| MISSING | 근거 중 삭제된 자료가 있거나 근거 없음 |
| UNCHECKED | 원본 검색 미리보기이며 지식의 근거 최신성 검사 대상 아님 |

MISSING이 STALE보다 우선합니다. 오래된 게시본도 숨기지 않고 경고 상태로 보여줍니다.
CURRENT는 원격 사이트의 최신성이나 AI의 사실 검증을 뜻하지 않습니다.
게시본의 줄 번호는 **Markdown 줄**, 원본 결과는 **추출 텍스트 줄**입니다.
게시본을 연 뒤 새 버전이 게시될 수 있으므로 최신 결과가 필요하면 다시 검색하세요.

## 구현 모델과 코드 대조

```mermaid
flowchart LR
  Review[사람 검토] --> Publish[명시적 게시]
  Publish --> Queue[SQLite 검색 큐 · 같은 트랜잭션]
  Queue --> Index[Lucene · 원본과 최신 게시본]
  Index --> Check[Workspace·현재 게시 버전 확인]
  Check --> Fresh[연결 근거 최신성 조회]
  Fresh --> Result[원본/게시본 검색 결과]
```

- `DatabaseConfiguration`: v13에서 게시 추가·카드 삭제·Source 삭제/도메인 변경 트리거를 추가합니다. 기존 게시본도 큐에 넣으며 문서/검토/채택 이력을 수정하지 않습니다.
- `JdbcSearchCatalog`: 최신 게시 revision의 버전만 읽습니다. 최신 초안 번호와 혼동하지 않습니다. 지식 색인 키는 `knowledge:<cardId>`입니다.
- `LuceneSearchIndex`: 같은 색인에 ORIGINAL/KNOWLEDGE 종류, 게시 버전, 복수 도메인을 저장합니다. 기존 이름·본문·심볼 검색과 같은 Nori 규칙을 사용합니다.
- `SearchService`: 삭제되거나 이미 교체된 게시 버전은 반환하지 않습니다. 최대 30개 후보의 근거 상태를 기존 지식 조회로 계산합니다. 재수집/문서 추출/AI 호출은 없습니다.
- `SourcePanel` / `PublishedSearchPreview`: 원본 선택과 게시본 열기를 구분합니다. HTML 실행·외부 이미지/링크 자동 로딩을 막는 기존 Markdown 표시기를 재사용합니다.

도메인은 **게시본이 인용한 현재 Source들의 도메인 합집합**입니다. 하나라도 선택 도메인과 같으면 검색됩니다.
단일 업로드나 삭제된 Source에는 도메인이 없으며 전체 검색에서는 계속 찾을 수 있습니다.
Source 도메인은 과거 스냅샷으로 고정되지 않습니다. Source 삭제/분류 변경은 큐 반영 후 필터에 적용됩니다.

## API

`GET /api/workspaces/{workspaceId}/search?query=...&includeKnowledge=true&domains=...`

기본값은 `includeKnowledge=false`로 이전 API의 원본 전용 동작을 유지합니다. UI에서는 기본 켬입니다.
추가 필드: `kind`, `cardId`, `version`, `domains`, `freshness`.
원본은 `entryId`가 있고 cardId/version은 null입니다. 게시 지식은 entryId=null이며 cardId/version을 사용합니다.
게시 지식의 sourceId/path는 빈 문자열입니다. 출처는 해당 버전의 evidence를 조회합니다.

## 한계와 다음 단계

- P4-1 일부 구현입니다. 영속 검색 청크/인용 스냅샷, 심볼·지식 관계 검색은 아직 없습니다.
- Lucene 점수순 최대 30개입니다. 사후 유효성 검사에서 제외되면 더 적게 반환하며 페이지 이동은 없습니다.
- 검색 미리보기는 확정 인용이 아닙니다. 답변 근거 묶음과 SSE 채팅·승인은 후속 작업입니다.
- 근거 최신성 조회는 저장된 원본 바이트/추출 텍스트 해시를 비교합니다. 대규모 근거의 IO/해시 비용 최적화는 남았습니다.
- Mac 설치 패키지는 아직 없습니다. v13으로 올린 뒤 구버전 앱으로 되돌릴 수 없으므로 먼저 데이터를 백업하세요.

## 검증 기록

초기 표적 테스트에서 단일 SQLite 연결을 점유한 채 도메인 조회가 다른 연결을 기다렸습니다.
해당 테스트 JVM만 종료하고, 카탈로그 조회가 읽기 트랜잭션의 같은 연결을 사용하도록 수정했습니다.
이후 표적 테스트 25개가 통과했습니다. 추가 회귀 테스트를 포함한 최종 결과는 다음과 같습니다.

| 검증 | 결과 |
|---|---|
| `bash scripts/maven.sh clean verify` | 181개 통과, 실제 CLI opt-in 1개 skip, 실행 JAR 빌드 성공 |
| `npm --prefix frontend test -- --maxWorkers=2` | 95개 통과 |
| `npm --prefix frontend run build` | TypeScript/Vite 빌드 성공 |
| `node scripts/smoke.mjs` | 게시 색인 반영·재시작·후속 초안 제외·Source 삭제 후 MISSING 및 도메인 제거 통과 |
| 실제 브라우저 | 원본+게시본 2건 검색 → 게시 v1과 출처 열람 → 후속 초안 무결과 → 원본 삭제 후 게시본 MISSING 표시 통과 |

`PublishedSearchIntegrationTest` 7개는 게시 전 제외, 검토와 게시 구분, 원본 전용 API 호환,
초안 보호/재게시, 오래된 색인 버전 차단, Workspace 격리, 카드 삭제, 트랜잭션 rollback,
복수 도메인/Source 삭제/분류 변경, 근거 STALE/MISSING을 검사합니다.
`MigrationTest` 13개는 이전 스키마부터 v13까지 기존 자료·이력 보존과 재열기를 확인합니다.

2026-10-21 macOS Intel/Java 21: 시작 6,330ms, 재시작 5,782ms,
가상 원본 파일 100개 검색 30회 p95 12.77ms. [원시 측정](performance-phase3-foundation.json).
이 수치는 원본 검색 기준이며 대용량 통합 검색 성능 보장이 아닙니다. 측정 중 별도 격리 QA 서버도 실행 중이었습니다.
모든 데이터는 가상 자료이며 AI smoke는 fake CLI를 사용했습니다. 실제 외부 AI 요청은 하지 않았습니다.
