# 관련 자료 — 연결 이유를 보고 찾아가기

검색 결과 옆의 **관련 자료**를 누르세요. 같은 Workspace에서 연결된 원본·게시 지식을 최대 30개 보여줍니다.
검색에서 선택한 여러 도메인 조건을 그대로 적용합니다. 읽기 전용이며 AI를 호출하지 않습니다.

## 어떤 연결인가요?

| 출발 | 보여주는 자료 | 연결 이유 |
|---|---|---|
| 원본 파일 | 다른 원본 파일 | Java 선언 심볼의 이름과 종류가 같음 |
| 원본 파일 | 게시 지식 | 그 원본 위치를 근거로 인용함 |
| 게시 지식 | 현재 원본 파일 | 게시본이 인용했던 원본 위치 |
| 게시 지식 | 다른 게시 지식 | 같은 원본 위치를 근거로 인용함 |

**같은 심볼 이름은 함수 호출·상속·의존 관계의 증거가 아닙니다.** 같은 원본 위치도 다른 시점의 내용을 인용할 수 있습니다.
화면에 연결 이유를 그대로 표시하며 의미적 유사성이나 AI 추론으로 저장하지 않습니다.

- 초안과 검토만 된 지식은 제외합니다. 가장 최근에 실제 게시한 버전만 대상입니다.
- 이후 새 버전이 게시됐으면 이전 검색 대상은 다시 검색해야 합니다.
- 게시 지식에는 CURRENT/STALE/MISSING을 표시합니다. 사실 검증·원격 최신성 보증은 아닙니다.
- 원본 연결은 현재 로컬 복사본입니다. 게시 당시 근거는 게시본의 출처에서 따로 확인하세요.
- 원본이 삭제돼도 다른 게시본과의 공통 출처 연결은 남을 수 있습니다. 그때 MISSING으로 표시합니다.
- 더 많은 관계가 있으면 30개 제한을 표시합니다. 도메인을 좁혀 다시 검색할 수 있습니다.

## 모델과 코드 대조

새 테이블이나 벡터 DB를 추가하지 않습니다. 현재 스키마 v20을 사용합니다.

`Lucene 검색 결과 → RelationPanel → RelationController → RelationSearch → RelationCatalog → SQLite`

- `symbol`: JavaParser가 저장한 선언 이름·종류를 정확히 비교합니다.
- `knowledge_publication`/`knowledge_version`: 실제 최신 게시본만 선택합니다.
- `knowledge_evidence`/`evidence_snapshot.origin_key`: Source ID+경로 또는 업로드 ID를 따라 연결합니다.
- `JdbcRelationCatalog`: Workspace·도메인 조건을 적용한 뒤 최대 31개를 읽어 초과 여부를 계산합니다.
- `RelationSearch`: 같은 읽기 트랜잭션에서 존재·게시 버전·현재 근거 상태를 확인합니다.

관계는 조회할 때 저장된 인덱스/출처에서 계산합니다. 원본 재수집·재분석·LLM 전송은 하지 않습니다.
검색어가 없는 관계 그래프 전체 순회, 함수 호출 그래프, 관계 결과의 자동 답변 근거 편입은 후속입니다.

## API

`GET /api/workspaces/{workspaceId}/relations?kind=ORIGINAL&id={entryId}&domains={domain}`

게시 지식은 `kind=KNOWLEDGE&id={cardId}&version={publishedVersion}`입니다.
`domains`는 최대 20개를 반복 지정합니다. 생략하면 전체 도메인입니다.
응답은 `{items:[{target:{kind,id,title,version,path,domains,relation,detail},freshness}],truncated}`입니다.
잘못된 입력 400, 다른 Workspace/삭제/미게시/변경된 게시 버전 404입니다.

## 검증

2026-10-22 전체 빌드: 백엔드 **342개 통과 + 실제 AI opt-in 1개 skip**, UI **192개 통과**.
가상 자료의 실제 JAR 관계 API smoke와 기존 전체 smoke가 통과했습니다. 실제 AI 호출은 없습니다.
브라우저에서 원본 검색 → 관련 자료 3개(게시본 2개/동일 심볼 원본 1개) → 게시 v1 열기·근거 CURRENT 표시를 확인했습니다.

```sh
bash scripts/maven.sh test -Dtest=RelationSearchIntegrationTest
cd frontend && npm test -- --run src/RelationPanel.test.tsx src/SourcePanel.test.tsx
# 실행 JAR 빌드 후, 실제 HTTP 경로·가상 자료 확인
node scripts/relations-smoke.mjs
```

가상 Java/문서로 Workspace 격리, 동일 선언 심볼, 공통 출처, 초안 제외, 다중 도메인,
변경/삭제 근거 상태, 30개 제한과 게시 버전 변경을 검사합니다.
UI는 원본/게시본 분기, 읽기 전용 요청, 안전한 제목 표시, 요청 취소/오류와 명시적 열기를 검사합니다.

초기 추가 테스트에서 기존 메서드명/인자 수, jsdom dialog 설정, 버튼 선택자 모호성이 실패했습니다.
테스트를 실제 계약과 새 화면에 맞춰 수정했습니다. 제품 오류를 숨기기 위한 예외 무시는 추가하지 않았습니다.
