# Phase 3 P3-1 — 지식/근거 기반 구현 검증

검증일: 2026-10-21 (Asia/Seoul). macOS Intel, Java 21, Spring Boot 4.1.1,
Node 24.2.0. 테스트는 가상 fixture만 사용했습니다. 실제 회사 자료·외부 LLM 호출은 없습니다.

## 완료한 기능

- `knowledge` 도메인/유스케이스/저장 포트/JDBC/REST 추가
- SQLite v3: 카드, 불변 Markdown 버전, 근거 스냅샷, 버전별 인용 범위
- 수동 DRAFT 생성, 목록/현재·과거 버전 열람, 새 버전 편집, 독립 삭제
- 근거 원본 BLOB·추출 텍스트·해시·출처 종류/위치·revision 보존 및 다운로드
- Source 재동기화의 entry 교체/삭제에도 지식과 근거 유지
- 마지막 로컬 복사본 기준 CURRENT/STALE/MISSING 조회
- expectedVersion 비교로 동시 편집 충돌 409, 실패 시 트랜잭션 롤백
- React 초안 작성 폼, 지식 목록/버전/근거, 편집·삭제 확인, HTML 비실행

모든 생성 결과는 `DRAFT`, `manuallyEdited=true`입니다. AI 생성/사실 검증/게시를
구현한 것으로 표시하지 않습니다. P3-1은 완료, P3-4 UI는 일부 구현입니다.

## 실제 검증 결과

| 검증 | 명령/방법 | 결과 |
|---|---|---|
| 변경 전 기준 | Maven test / npm test | 백엔드 35개, UI 11개 통과 |
| 지식/마이그레이션 초기 집중 테스트 | Maven KnowledgeIntegrationTest,MigrationTest | 당시 12개 통과 |
| 최종 전체 백엔드 및 JAR | `bash scripts/maven.sh clean verify` | 48개 통과, 실패/오류/skip 0 |
| 최종 UI | `cd frontend && npm test` | 17개 통과 |
| 프런트엔드 빌드 | `cd frontend && npm run build` | TypeScript/Vite 성공 |
| 실제 JAR 통합 | `node scripts/smoke.mjs` | 지식/근거/재시작/검색/충돌 검증 통과 |

신규 백엔드: 지식 통합 12개 + v2→v3 마이그레이션 1개.
신규 UI: 초안/근거/버전/충돌/삭제 취소/저장 실패 6개.
기존 v1 마이그레이션 테스트도 v3까지의 업그레이드와 반복 초기화를 검증합니다.

### 주요 회귀 방지 시나리오

- 원본 삭제 후 지식 본문/당시 바이트 보존, MISSING 표시
- Source의 다른 파일 변경으로 entry ID가 바뀌어도 동일 내용은 CURRENT
- 실제 근거 파일 변경 시 STALE, 과거 인용/revision은 불변
- 다른 Workspace의 초안 생성 근거·버전·삭제·근거 다운로드는 404
- 잘못된 근거가 섞인 생성/편집은 스냅샷·최신 버전 포인터까지 롤백
- 동시 편집 두 요청 중 하나만 새 버전 생성, 나머지 409
- 같은 스냅샷 공유, 지식 삭제 시 다른 지식이 참조하는 스냅샷 보존
- Workspace 삭제 시 지식/근거까지 삭제, 지식만 삭제 시 원본 유지
- 실제 JAR 재시작 후 초안 보존, 이전 버전 비덮어쓰기, 원본 Source 삭제 후 근거 바이트 동일

브라우저 검증은 별도 가상 DB에서 Workspace 생성 → 가상 Markdown 업로드 →
추출 텍스트 행 지정 → 수동 초안 저장 → 지식 문서 열람 → 편집/새 버전 저장 →
이전 버전 열람 흐름을 확인했습니다. 네트워크/AI의 가짜 성공 UI는 추가하지 않았습니다.

## 성능/빌드 기록

실측 원시 결과: [performance-phase3-foundation.json](performance-phase3-foundation.json).
100개 가상 UTF-8 파일을 사용한 기동·업로드·목록·검색의 순차 HTTP 측정이며,
지식 대량 생성 처리량이나 LLM 성능 측정은 아닙니다. 이전 보고서의 측정 파일은 보존합니다.
최종 UI 번들: JS 283.20 kB / gzip 88.87 kB, CSS 19.64 kB / gzip 5.36 kB.

## 한계와 다음 작업

- 다음은 P3-2 Ollama Curator, 이후 P3-3 검증·게시. 실제 모델 연동은 아직 없음.
- 근거 존재/해시 일치는 사실성 검증이 아니며, 현재 모든 초안은 미검증.
- 지식 폴더, 안전한 Markdown 렌더링, 지식 검색, 관계 검색, AI 갱신 제안은 미구현.
- API 근거 1~10개, 1~500 추출 행, 원본 총 50 MiB. UI 생성은 파일 1개 근거만 지원.
- UI 편집은 기존 근거를 재사용하며 근거 교체는 API로만 지원.
- 전체 원본 이력이 아닌 인용된 원본의 당시 복사본만 보관. 지식 버전/총 DB 용량 정책은 미구현.
- 원본 삭제만으로 근거 복사본은 삭제되지 않음. 민감 자료 제거 시 지식 보존 정책도 확인 필요.
- 최신성은 조회 시 마지막 로컬 복사본과 비교하며 외부 Source 실시간 상태는 알 수 없음.
- 스키마 v3에서 구버전으로의 다운그레이드는 지원하지 않음. 이전 전 DB 백업 필요.
- 기존 Mockito agent/Lucene native-vector/deprecated API 경고는 남음. 미해결 자동 테스트 실패는 없음.
- 자동 폴링·SSE 채팅·Tauri 설치 패키지와 Windows 실행 검증은 여전히 후속 범위.
