# 수집·검색·해석 기준선 (2026-10-27)

## 범위와 결론

현재 설치 Intel 0.1.27 JAR `b647ff9815037ddef3780246d712a944d4534a43060f881cdcbab8c2e6f3a612`의 복사본과 동봉 검색 모델을 격리된 임시 HOME/DB에서 실행했다. 사용자 DB와 원본은 변경하지 않았다. 외부 소스는 조회만 수행했다. 제품 코드·패키지·설치본은 변경하지 않았다. **검색 품질 인수는 실패이며 전체 테스트 완료 선언이 아니다.**

- Java: 기본 배치1143실행/1138통과/5skip. 실제 검색 모델 opt-in 포함. 기존 제외 `AnswerPartialIntegrationTest`, `QuestionAttachmentIntegrationTest`는 별도 실행10오류: fixture의 Workspace 이름이20자 제한을 넘어서 본문 실행 전 실패했다. 숨기거나 통과로 계산하지 않는다.
- UI:63파일686통과. Node:159통과(신규 평가기 테스트2개 포함).
- 실제 설치 JAR 검색:54질문 행, 자동 체크40통과/7실패. 체크는 그룹과 개별 질문이 섞여 있어 **정확도40/47로 해석하지 않는다**.
- 실제 AI: 독립 질문6회/추가 재시도0회. 생성 완료6건, 정규식5건 통과. 별도 원문·답변 수동 대조도5건 적합/1건 유용성 실패. 대화 문맥·모든 모델 품질 검사는 아니다.
- 7개 실패 체크 중 `design-pattern:query-1`은 질문이 모호하여 제품 결함 판정 제외. 나머지6개 체크는4종류의 재현 사례이며 중복된 파일/폴더 실패가 포함된다.

측정값·질문·반환 경로·행 범위·원시 보고서 해시는 [JSON](retrieval-baseline-027-20261027.json)에 보존한다. 실제 Notion 원문과 AI 답변 전체는 공개하지 않는다. 원시 자료는 로컬 전용 `/tmp/dougle-search-acceptance-027.bUdkdm`에 보존했고 런타임 임시 DB/프로세스는 정리했다.

## 재현된 품질 문제

1. **의미 변형 누락:** 파일과 폴더 모두 `선택 알고리즘을 외부에서 전달하는 코드는 어디야?`에 생성자 주입 예제를 찾지 못했다. 같은 자료의 전략 패턴 명시 질문은 찾았다.
2. **무관한 근거:** 달 중력 질문에 State 코드/배지 정책이 반환됐다. 검색 정밀도 문제이며 AI가 그 답을 지어냈다는 뜻은 아니다.
3. **자료가 많아지면 필요한 구현 누락:** 동일 WinningStrategy 소스를 파일/폴더/raw URL로 각각 넣으면3질문 모두 근거를 찾았다. 전체 GitHub 저장소에서는 `직전 판에서 이겼으면 같은 손을 다시 내는 코드는?`에 Main/Hand만 반환하고 WinningStrategy를 누락했다. 단일 파일 대 전체 저장소이므로 원인이 URL 수집이라고 단정할 수 없다. 나머지2질문은 해당 구현을 찾았다.
4. **맞는 문서의 틀린 문단:** 최신 Notion 본문10질문 중 답변 구절9건 검색. 워터폴 시초의 변형 질문은 로이스가 적힌 본문 대신 참고도서 목록을 선택했다. 별도 AI 평가에서도 모든 팀에 한 방법론을 강제하느냐는 질문에 참고도서만 전달하여, 본문에 답이 있는데 답변을 유보했다.

실제 AI는 잘못된 전제 정정, 배포에 관한 반론, 백로그 권고 해석을 자료 범위 안에서 수행했다. 없는 장애 원인/실패율은 답하지 않았다. **현재 관찰로는 사실성 제약을 푸는 것보다 올바른 근거를 전달하는 개선이 먼저다.** 6건에서 근거 없는 단정은 관찰하지 않았으나 환각률0%를 입증한 것은 아니다.

## 실제 소스와 판정 주의

- `thisisnew/design-pattern`: `b675f8f1342f1bddaa96c613875841a407306bcd`, 수집 READY. FactoryMethod/Singleton 예제를 확인. 이 저장소에서 Strategy를 요구했던 b2의 정답 가정은 잘못됐으며 무효로 남긴다.
- `thisisnew/study-gof-23-design-pattern`: `fd98bd00d958e23f8b3f05c379b2747da92e8d68`, READY. 전략 관련5질문에서 해당 경로를 찾았다. 일부 질문에 다른 패턴도 섞이므로 문서 적중이 높은 정밀도를 뜻하지 않는다.
- `객체를 만드는 코드`를 FactoryMethod만 정답으로 삼은 b3 체크는 너무 좁다. Singleton Instance도 질문에 부합할 수 있어 확정 실패에서 제외했다. 다음 배치에서는 질문을 구체화하거나 허용 정답 집합을 독립적으로 검토한다.
- 공개 Agile manifesto URL2질문은 본문을 찾았다. Notion 미연결 오류 안내도 확인했다. Notion 본문은 별도 도구로 새로 읽은 Markdown을 업로드한 것이므로 **앱 OAuth/Notion 수집 성공을 뜻하지 않는다**.
- 파일명만 같은 Main.java를 정답으로 인정하지 않도록 경로 기반 평가를 사용했다. `precisionReturned`는 미리 지정한 파일/경로와의 일치율이지 독립적으로 판정한 문단 정밀도가 아니다. ProbStrategy처럼 다른 유효 답이 빠질 수 있다.
- 첫 실행은 `FORGE_DATA_DIR` 누락으로 시작 실패(0질문)했다. 평가기를 고친 뒤 b2, b3를 실행했다. 앞선 결과를 지우거나 모두 합쳐 독립 사례 수로 보고하지 않는다.

## 촘촘한40개 인수 범위

PARTIAL은 해당 배치/회귀의 일부 증거만 있다는 뜻이며 인수 통과가 아니다. FAIL은 재현된 품질 문제, NOT_RUN은 해당 실제 시나리오 미실행, BLOCKED는 외부 환경이 필요하다는 뜻이다. 아래 항목은 기존 전문가50개와 별도이며 점수를 합산하지 않는다.

| ID | 확인할 내용 | 현재 판정/증거·남은 점 |
|---|---|---|
| F01 | TXT/MD/코드 수집과 핵심 검색 | PARTIAL: 네 합성 파일 수집, 표현 변형 일부 실패 |
| F02 | PDF 첫·중간·마지막 본문 검색 | NOT_RUN: 이번 실제 JAR 평가에 PDF 없음 |
| F03 | 스캔 PDF/OCR와 미지원 안내 | NOT_RUN |
| F04 | 큰 파일 분할 후 마지막 부분 검색 | PARTIAL: StreamingCollectionTest 통과, 실제 대형 문서 검색 미실행 |
| F05 | 한글·공백·인코딩·손상 파일 | PARTIAL: 한글 파일명 통과, 인코딩/손상 실제 배치 미실행 |
| F06 | 중복 추가와 중복 근거 방지 | PARTIAL: 기존 회귀 범위, 실제 중복 자료 평가 필요 |
| F07 | 파일 내용 갱신과 오래된 근거 구분 | PARTIAL: 폴더 원본 변경36시간 반영, 업로드 교체 별도 필요 |
| F08 | 삭제 후 새 검색과 과거 인용 보존 | PARTIAL: 폴더 삭제 코드 검색 제외, 기존 답변 보존 실제 평가 필요 |
| D01 | 재귀 디렉토리·동일 파일명 경로 | PARTIAL: 합성 하위 폴더 수집/경로 기록 |
| D02 | 수백/수천 파일 누락 없는 수집 | PARTIAL: CollectorTest의 대량 fixture, 실제 대량 검색 미실행 |
| D03 | 큰 파일과 작은 파일 혼합 | PARTIAL: StreamingCollectionTest sibling 보존 통과 |
| D04 | 제외 규칙·심볼릭 링크·원본 readonly | PARTIAL: CollectorTest 통과, 실제 사용자 폴더 미실행 |
| D05 | 일부 실패와 나머지 검색·오류 원인 | PARTIAL: SourceIntegrationTest, 실제 UI 부분 실패 미실행 |
| D06 | 추가/수정/이름 변경/삭제 재동기화 | PARTIAL: 이름 변경·수정·삭제 실제 통과, 신규 추가 분리 평가 필요 |
| D07 | 동시 동기화·취소·전환 차단 | PARTIAL: 기존 서버/UI 회귀, 실제 수집 중 GUI 미실행 |
| D08 | 폴더 이동·권한 해제·재연결 | NOT_RUN: 실제 OS 권한 시나리오 필요 |
| G01 | 사용자 지정 두 공개 저장소 수집 | PARTIAL: 두 개 READY/커밋 기록, 전체 지원 파일 독립 대조 필요 |
| G02 | 소스 본문을 검색하고 경로로 구분 | FAIL: 전체 저장소에서 WinningStrategy 근거 누락 |
| G03 | 브랜치/커밋·최신성 표시 | PARTIAL: revision 및 commit-pinned raw 대조, 브랜치 전환 미실행 |
| G04 | 대형 저장소·한도·진행 표시 | NOT_RUN |
| G05 | LFS/서브모듈/바이너리 제외 안내 | NOT_RUN |
| G06 | 비공개/404/권한/네트워크 실패 | PARTIAL: 기존 안전성 회귀, 실제 인증 저장소 미실행 |
| G07 | upstream 변경·삭제 반영 | NOT_RUN: 외부 쓰기 없이 별도 고정 fixture 필요 |
| G08 | 실패 재시도·기존 색인 보존 | PARTIAL: SourceIntegrationTest, 실제 GitHub 장애 재시도 미실행 |
| W01 | 공개 HTML 본문/메뉴 제거 | PARTIAL: Agile manifesto2질문, 다양한 사이트 미실행 |
| W02 | 동적 렌더링 페이지 | NOT_RUN |
| W03 | 인증 페이지·연결 안내·만료 | PARTIAL: 미연결 Notion 오류, 인증/만료 실제 미실행 |
| W04 | 실제 Notion 앱 연결→수집→검색 | BLOCKED: 이번은 별도 본문 읽기+업로드만; 앱 인증 세션 필요 |
| W05 | 긴 페이지의 전 구간 근거 검색 | FAIL: Notion snapshot의 답변 구절9/10 |
| W06 | URL 변형·중복·리다이렉트 | PARTIAL: 기존 CollectorTest, 실제 변형 묶음 미실행 |
| W07 | 링크 수집 범위와 내부 주소 차단 | PARTIAL: CollectorTest 경계 회귀, 실제 브라우저 범위 미실행 |
| W08 | 원격 변경/실패 시 기존 내용 | PARTIAL: SourceIntegrationTest 보존, 실제 변경 페이지 미실행 |
| S01 | 동일 자료 네 경로 비교 | PARTIAL: 단일 코드12질문11적중; GitHub만 코퍼스가 커 공정한 동등 비교 아님 |
| S02 | 한국어 질문/영문 코드·용어 | PARTIAL: 명시적 전략 질문 적중, 의미 변형 실패 |
| S03 | 구어체·동의어·경쟁 문서 | FAIL: 파일/폴더 의미 변형과 무관 결과 |
| S04 | 구현 의도·호출 관계 | FAIL: 주입/승리 동작 구현 검색 누락 |
| S05 | 조각 경계·여러 문단 연결 | FAIL: 올바른 문서에서 참고도서 문단 선택 |
| S06 | 출처·행 범위·답변 주장 대응 | PARTIAL: 실제 AI6건 수동 대조, 전체 자료/복합 인용 미완료 |
| S07 | 없는 정보·잘못된 전제·과잉 거부 | FAIL: 실제 AI5/6 적합, 있는 내용1건 불필요한 답변 유보 |
| S08 | 이어 질문·정정·재시작·격리 | PARTIAL: 기존 회귀/이전0.1.26 실AI 증거, 이번 독립6건으로 대화 품질 판정하지 않음 |

## 재실행과 다음 순서

```sh
FORGE_VERIFY_SEARCH_MODEL_DIR='/Applications/Knowledge Forge.app/Contents/Resources/resources' \
  bash scripts/maven.sh test '-Dtest=!AnswerPartialIntegrationTest,!QuestionAttachmentIntegrationTest'
bash scripts/maven.sh test '-Dtest=AnswerPartialIntegrationTest,QuestionAttachmentIntegrationTest'
(cd frontend && npm test -- --maxWorkers=2)
node --test --test-concurrency=1 scripts/*.test.mjs
node scripts/retrieval-acceptance.mjs /ABSOLUTE/PRIVATE/report.json /ABSOLUTE/PRIVATE/notion-page.md
```

마지막 명령은 macOS/지정 설치 JAR 해시 전용이며 공개 GitHub·웹에 읽기 요청한다. Notion 인자는 선택 사항이다. 보고서는 새 비공개 디렉토리에 저장한다. AI 명령은 비활성화하며 실제 AI6건은 별도 승인된 유한 배치로 실행했다. AI 원시 보고서에는 원문이 있으므로 커밋하지 않는다.

다음 작업은 (1) 평가 정답을 경로/문단/허용 해석으로 분리, (2) 고정 실패 질문의 후보 검색·재정렬·문단 선택 추적, (3) 무관 근거 제거와 과잉 거부를 함께 평가, (4) 사용하지 않은 질문으로 개선 검증, (5) 미실행 대형/다양한 형식·동적 URL·실제 Notion/회사 서비스·ARM 실기기 인수다. 검색 품질 실패가 남아 있으므로 공개 릴리스/업데이트 피드는 계속 보류한다.
