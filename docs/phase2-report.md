# Phase 2 검증 결과

검증일: 2026-10-21 (Asia/Seoul).
환경: macOS Intel, Java 21 (Corretto 21.0.10), Node 24.2.0,
Maven 3.9.11, Spring Boot 4.1.1.

## 구현 범위

로컬 폴더, 공개 HTTPS URL, 공개 GitHub 기본 브랜치를 읽기 전용으로 수집합니다.
Tika 3.2.3 문서 추출, JavaParser 3.27.1 심볼/행 번호,
Lucene 10.3.1 + Nori 다중 도메인 검색과 원본 미리보기를 연결했습니다.
검색은 저장된 인덱스만 사용하며 자료를 재수집하거나 LLM에 전송하지 않습니다.

## 실행 결과

| 검증 | 명령 또는 방법 | 결과 |
|---|---|---|
| 백엔드 | `bash scripts/maven.sh clean verify` | 35개 통과, 실패/오류/skip 0, 실행 JAR 생성 |
| 프런트엔드 | `cd frontend && npm test` | 11개 통과 |
| TypeScript/Vite | `cd frontend && npm run build` | 성공 |
| 패키지 통합/성능 | `node scripts/smoke.mjs --live-public` | 통과 |
| 실제 공개 URL | `https://example.org` 등록/동기화 | READY, 콘텐츠 SHA-256 기록 |
| 실제 공개 GitHub | 이 프로젝트 공개 저장소 등록/동기화 | READY, REST/clone SHA 일치 |
| 브라우저 | Source 등록 → 수동 동기화 → 심볼 검색 → 원본/분석 열기 | 통과 |

백엔드 구성: 기존 Library 11개, Source 통합 11개, Collector 7개,
Analyzer 3개, 스키마 마이그레이션 1개, HTTP 리다이렉트 2개.
프런트엔드 구성: 기존 App/API 7개, SourcePanel 4개.

자동 테스트는 가상 자료와 mock 응답을 사용합니다. 선택적 공개 네트워크 검증만
example.org와 이 프로젝트 자체 저장소를 읽습니다. 회사 코드나 인증 토큰은 사용하지 않았습니다.
실제 GitHub 검증 대상 revision은 `510d7dd4d354e2db9a165121f1f7015e517ae4fd`입니다.
네트워크 검증에는 인터넷 연결과 Git CLI가 필요하며 외부 서비스 상태에 영향을 받습니다.

### 확인한 동작

- 실패 시 이전 원본·revision·검색 결과 보존, 변경 없는 재동기화 시 entry ID 유지
- 중복 동기화 거부, 수집 도중 Source 삭제 후 재생성 방지
- 관리되는 원본의 직접 변경 차단, Source 삭제 후 검색 결과 제거
- Workspace/도메인 격리, 도메인 여러 개 OR 검색, 한글 Nori 검색
- Java 클래스/메서드 및 행 번호, HTML/PDF 추출, 잘못된 Java의 원문 보존
- 사설 IP/리다이렉트/심볼릭 링크/경로·용량 제한
- Git 체크아웃 없이 커밋 blob 읽기, 가상 Workflow 원본 미변경
- SQLite v1→v2 반복 초기화, 기존 원본 바이트와 활성 Workspace 보존
- JAR 재시작 후 원본/검색 복구, 삭제 영속성, 원본과 지식 영역 독립
- 브라우저에서 `measureTemperature` 검색 후 `Greenhouse.java`와 메서드 4행 표시

## 성능 측정

원시 결과: [performance-phase2.json](performance-phase2.json).
100개의 가상 UTF-8 파일(각 약 2.5 KB)과 하위 fixture를 사용하는 순차 HTTP 측정입니다.

| 항목 | 결과 |
|---|---|
| JAR 시작 / 동일 DB 재시작 | 6,533 / 6,679 ms |
| 가상 로컬 폴더 수집·분석·인덱싱 | 630.68 ms |
| 파일 업로드 100회 p50 / p95 | 124.31 / 172.71 ms |
| 루트 101개 조회, warm-up 후 50회 p50 / p95 | 9.73 / 16.09 ms |
| 100개 파일 검색 30회 p50 / p95 | 12.67 / 34.20 ms |
| 프런트엔드 JS / gzip | 275.29 / 86.86 kB |
| 프런트엔드 CSS / gzip | 18.30 / 5.12 kB |

업로드에는 Phase 1과 달리 분석과 인덱싱이 포함됩니다.
단일 로컬 프로세스의 소규모 데이터 결과이며 대규모 저장소, 동시 수집,
LLM 성능이나 운영 SLA를 보장하지 않습니다.

## 발견 및 해결

1. 실제 Git clone 중 임시 pack 파일이 사라져 용량 감시가 실패했습니다.
   파일 트리 순회에서 일시적인 파일 소멸을 처리하도록 수정하고,
   전체 백엔드 테스트·재빌드·실제 공개 GitHub 수집을 재실행해 통과했습니다.
2. 샌드박스에서 Mockito 동적 agent 연결이 제한되어 테스트가 실패했습니다.
   승인된 실행 환경에서 같은 전체 테스트 명령을 재실행해 35개 모두 통과했습니다.
3. Tika의 외부 OCR 및 임베디드 문서 재귀 분석은 명시적으로 비활성화했습니다.
   OCR 없는 가상 PDF 텍스트 추출 테스트를 통과했습니다.

미해결 자동 테스트 실패는 없습니다. Mockito agent 및 Lucene native/vector 관련
경고가 남으며, 향후 명시적 테스트 agent와 런타임 옵션 정리가 필요합니다.

## 남은 범위

- 비공개 GitHub/자격 증명, 사용자 수집 취소, 격리 파서, 대용량 ArtifactStore
- Source당 500개/50 MiB, 파일당 10 MiB, 경로 16단계, 추출 500,000자 제한
- 현재 스냅샷만 보관: Git 증분 갱신과 과거 원본 이력은 미구현
- 빈 폴더, OCR, Git submodule 수집 미지원
- 지식 생성·근거 검증·게시, Ollama/SSE 채팅, Source별 자동 폴링 미구현
- Mac 설치형 패키지, Windows/Apple Silicon 실행, 대규모 동시성 검증 미완료

현재 UI 포함 JAR는 개발용 실행물이며 `.app`/`.dmg`는 아닙니다.
다음 핵심 단계는 Phase 3의 근거 연결 지식 초안과 검증 후 게시입니다.
