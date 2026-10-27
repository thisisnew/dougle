# 남은900개 실행 작업 — 2026-10-27

> **범위 변경(2026-10-27):** 사용자가 Slack·Datadog 기능을 제거했습니다. T0901–T0925는 EXCLUDED25이며 통과/수정으로 승격하지 않습니다. 아래 실행 당시 원시 기록은 보존합니다. 현재 유효 범위는 PARTIAL921 / FAIL29 / NOT_RUN25 / EXCLUDED25 / PASS0입니다. 남은 실패는 검색19·대용량5·연속 대화5입니다.

**900개 완료 보고가 아니다.** 이번에875개 ID에 부분 계층 검사를 연결했다(부분 검사841통과/34실패). 기존100개 경계 검사와 합쳐 **PARTIAL941 / FAIL34 / NOT_RUN25 / PASS0**이다. 인수 케이스 전체를 통과한 것으로 승격하지 않는다. 현재 수치와 원시 관측값은 [ID별 JSON](acceptance-remaining-20261027.json)을 따른다.

## 수정 후 재검증

[수정 상세와 로그](ingestion-fixes-20261027.md). 기존76개 실패 중42개 부분 검사가 통과로 바뀌었다. 폴더5·취소/상태6·Git15·로그인/접근 확인10·CSV/PDF 발췌6개다. 초기 실패76개는 커밋 `7d355ec`에 남아 있고 ledger의 baseline/repairedProbeIds로 대조한다. 남은34개는 검색19·대용량5·후속 대화5·로그 범위5다.

DNS 연결 재해석5개와 실제 이미지25개 실행을 추가했다. 이미지에서는 가림 표시 `[REDACTED]`를 인용 번호로 오인하는5개 실패를 새로 발견·수정했고 실제 모델5개 재검증도 통과했다. [최초 이미지 기록](acceptance-images-baseline-20261027.json), [재검증 병합 기록](acceptance-images-20261027.json). 실제 AI 호출 예약은 합계 **111/200회**다. 이미지 수명주기/출력 검사 통과이지 자동 의도 분류·브라우저·업무 시스템 조사 전체 통과는 아니다. 빈 질문 대신 명시적인 이미지 확인 질문을 쓴 한계도 기록했다.

## 후속225개 실행

- Notion25/OAuth25/MySQL25/readonly25/자동 동기화 UI25/업데이트 UI25 부분 검사 통과. Notion·OAuth 제공자/브라우저는 대역이며 MySQL은 독립 임시 MySQL8.4 서버와 SELECT 전용 합성 계정이다. UI는 실제 React/jsdom이나 HTTP/native IPC는 대역이다.
- Slack/로그25 중20통과/5실패. Slack 실제 수신함 서비스+대역 provider/메모리 store, Datadog 실제 HTTP 어댑터+loopback 서버를 썼다. T0911–T0915는 요청 ID로 좁히지 못해 다른 요청 이벤트를 포함한다. 회사 실서비스·AI 조사 판단은 검증하지 않았다.
- 실제 AI50개 자동 검사45통과/5실패, 호출 예약75회. 사용자 승인 상한은 **200회**이며 남은125회는 재검증에 사용할 수 있다. [합성 답변·고정 근거 기록](acceptance-live-ai-20261027.json). 의미/인용 최종 리뷰는 별도이며 자동 검사를 정확도45/50로 주장하지 않는다.
- T0780/T0785/T0790/T0795/T0800은 두 번째 일반적 후속 질문에서 NO_EVIDENCE로 중단한다. 검색 쿼리가 현재/직전의 모호한 질문으로만 구성되는 경로를 확인했다. 20턴을 끝까지 실행한 것이 아니고 계획 AI/UI를 거치지 않은 답변 파이프라인 재현이다.
- 별도 native updater smoke는 합성 패키지 서명·버전·CPU·변조 거부·rollback/사용자 fixture 불변 검사 통과. 실제 앱 교체나 새 Mac 설치 완료가 아니다.
- 하네스 수정: AI private FORGE_DATA_DIR 누락2회(호출0), MySQL JSON emoji 비교, OAuth AutoCloseable/취소 HTTP200 기대값. 제품 결함으로 합산하지 않았다. TypeScript가 새 UI 테스트의 잘못된 role 옵션/closure narrowing을 지적하여 테스트를 수정했다.
- 수정 후 프런트엔드 production build, 새 UI50개, ledger/catalog7개 모두 통과했다. bundle500kB 경고는 남아 있으며 기존 전체 회귀를 새로 모두 실행한 것으로 합산하지 않는다.

## 실행 범위

| ID | 개수 | 실제 실행 | 아직 검증하지 않은 것 |
|---|---:|---|---|
| T0101–T0200 |100|Tika/Java 분석기, 합성 TXT/MD/Java/CSV/HTML/PDF, 9/10/20/100MiB, 1000페이지, 인코딩, Lucene 키워드 검색·색인 재열기|업로드 UI/DB, 앱 재시작, PDF 읽기 순서, NFC/NFD 충돌, 실제 의미 검색/답변|
| T0201–T0225 |25|실제 Spring/SQLite·고정 근거, 같은 이름 충돌, MockMVC multipart 동시 제출, 별도 사본/삭제/Workspace 격리|UI, 완료 답변 표시, 재시작|
| T0226–T0275 |50|실제 폴더 수집기, 1/100/50/501/5000파일 manifest, 추가/변경/삭제·취소·접근 복구, 해시/mtime/권한 불변|폴더 선택기, SourceService/색인/화면; symlink는 전체 거부되어 후속 조작 미검증|
| T0276–T0300 |25|실제 권한 거부·희소256MiB+1 파일·손상 PDF·수집 중 파일 삭제, 정상 형제 분석/키워드 검색|SourceService 전체 실패 상태·persisted retry·hover·동시 클릭|
| T0301–T0400 |100|공개 저장소2개 고정 커밋 fetch, 실제 Git 수집기/blob, 자체 fixture 변경·이동·LFS·서브모듈·인코딩·오류 복구|API/clone 전송은 대역·로컬 저장소; UI 자동 등록·의미 검색·동시 클릭·owner identity|
| T0401–T0450 |50|실제 URL 수집기·본문 분석·키워드 검색, 모의 HTTP200/ETag/304, 호출 범위|실제 네트워크/DNS/TLS·브라우저, 구어체 의미 질문, 페이지네이션 완전성|
| T0451–T0490, T0496–T0500 |45|실제 HTTP reader/URL collector, 오류 전달·리다이렉트 홉 제한·내부 주소/userinfo 차단·25초 실제 취소 타이머5회|HTTP 전송/인증서 오류는 대역, 실제 느린 소켓·DNS rebinding·UI 해결 안내·hover는 미검증|
| T0576–T0600 |25|실제 SourceService/SQLite·collector/analyzer, 분석 진입 latch, 취소/재시도·중복 sync/poll·metadata 변경 차단·서비스 객체 재생성|화면 이동 잠금·hover·분할 내부 단계 순서·실제 프로세스 재시작|
| T0601–T0750 |150|실제 앱 동봉 다국어 모델·Lucene, 고정 합성 코퍼스·순서 역전·재색인·Workspace 분리|UI/수집/근거 조립/실제 AI; 경쟁50개는 정교한 유사 키워드가 아닌 온실 문서; 네 수집 경로 비교·과거 인용·답변 거절은 별도|
| T0801–T0850 |50|실제 대화/Workspace 서비스·SQLite, 1/20턴·동일 제목·상태 혼합, 선택 ID 삭제·조회 격리·20/21자 경계|체크박스/모달/선택 취소/늦은 HTTP 응답/앱 재시작; 답변 작업은 합성 데이터|

`checkStatus=PASS`는 표의 제한된 검사에 대한 결과다. 전체 인수 상태는 **PARTIAL**로 유지한다. 실패는 해당 범위의 관측이며, 실제 AI가 잘못 답했다고 확대하지 않는다. 임시 파일/DB·원문 fixture만 사용하고 사용자 자료/인증정보를 복사하지 않았다. 검색 모델 캐시는 동일 입력의 추론 결과만 재사용하며 임베딩/관련도 대역으로 교체하지 않았다.

## 최초 확인한 실패 이력

- 검색19개: 생성자 주입을 묻는 구어체에서 `Player.java` 누락, 경쟁 문서가 있을 때 코드/입하 매핑 검색 누락, 달 중력·피자 질문에 무관한 근거 반환. 동일 fixture를 다른 조작으로 검사한 수이며 독립 결함19종이라는 뜻은 아니다.
- 파일11개: 100MiB 텍스트는 추출 상한32,000,000자 이후의 중간/끝 표식 누락5개. PARTIAL 안내는 있으므로 무표시 유실과 구분하지만, 전체 분할 검색은 아니다. CSV 중간 행과1000페이지 PDF 마지막 페이지는 추출되어도 키워드 검색에서 필요한 구절이 빠지는6개.
- 웹10개: 로그인/CAPTCHA의 HTTP200 본문이 READY로 분석된다. 실제 인증/브라우저가 아닌 모의 응답을 통과시킨 수집·분석 계층의 재현이다.
- Git15개: LFS 포인터를 원문으로 포함5개, 서브모듈이 있으면 정상 형제까지 수집 중단5개, 잘못된 UTF-8 Java가 READY로 분석5개. Maven 결과는 검증 실패10/예외5이며 ledger에서는 모두 FAIL이다.
- 폴더5개: 탐색 중 파일이 사라지면 `toRealPath()`의 `NoSuchFileException`으로 전체 수집이 중단된다. 정상 형제 부분 수집 요구를 만족하지 못한다. 권한/용량/손상 PDF/전체 접근 불가의 수집·분석 부분 검사는20통과했으나 SourceService 전체 실패 표시까지 통과한 것은 아니다.
- 동기화6개: T0578 큰 텍스트 분석 중 취소 후 재시도에서 정상 파일이 손상 오류/PARTIAL로 남는다. 분석기 취소가 Tika 예외로 감싸져 ERROR 체크포인트로 저장되는 경로를 조사해야 한다. T0591–T0595는 손상 PDF 하나만 있어 검색 가능한 파일이0개인데 PARTIAL로 표시된다. 동시 sync409·메타데이터 변경 거부·이전 성공 시각 보존은 해당 범위에서 확인했으며 UI 탭 잠금까지 검증한 결과는 아니다.
- 폴더에서 `.git`/`node_modules`는 제외하지만 `build`는 현재 제외 목록에 없다. symlink 안전성은 전체 수집 거부로 확보되며 정상 형제 파일의 부분 수집까지 보장한 결과가 아니다.

이 결함들을 수정한 릴리스가 아니다. 제품 코드는 변경하지 않았으며 테스트의 Workspace 이름과 하네스만 수정했다.

## 하네스 오류를 제품 오류와 분리

1. 처음 검색 fixture JSON이 도구 출력에서 잘려 `Warning` 문자열이 들어갔다. 실행0개, 생성기로 파일을 다시 만들고 재실행했다.
2. 처음 파일 검사에서 `BeforeAll`이 인스턴스 TempDir보다 먼저 실행됐다. 실행0개, static TempDir로 고쳤다.
3. 식별자 경계 검사 보강 중 `BLUE72입니다`의 한국어 조사까지 거부해25개가 잘못 실패했다. ASCII 식별자 경계로 수정하고 `PAGE1`/`PAGE1000`과 한국어 조사 전용 회귀를 추가했다.
4. 처음 자료 중복 검사17개는 서비스의 `DuplicateKeyException`을 잘못된 예외로 판단했다. 실제 API는 이를409로 바꾸므로 MockMVC multipart 응답과 동시 제출로 검사 위치를 변경했다. 이17개를 제품 결함으로 세지 않는다.
5. 기존 통합검사10개는20자보다 긴 Workspace fixture 때문에 실행 준비에 실패했다. 테스트 이름만 짧게 했고 제품의 길이 제한은 유지했다.
6. Maven 후 셸 래퍼가 zsh 예약 변수 `status`에 대입해 Node 실행이 시작되지 않았다. 별도 Node 실행 결과만 집계한다.

첫 실패 로그는 `/tmp/dougle-search-150-harness-error.log`, `/tmp/dougle-files-100-harness-error.log`, `/tmp/dougle-files-100-initial.log.gz`, `/tmp/dougle-file-marker-harness-errors.json`, `/tmp/dougle-library-workflow-harness-errors.json`에 보존했다. 합성 자료만 포함하며 이 임시 경로는 장기 보관을 보장하지 않는다.

## 재현

전체 Java 실행은 **1526개: 통과1481 / 실패40 / 오류0 / skip5**였다. 실제 CLI·OAuth·브라우저·공개 저장소/문서 평가 opt-in 일부가 건너뛰어졌으며 이를 통과로 합산하지 않는다. 기존 fixture10개 준비 오류는 이름 수정 뒤 사라졌다. 이후 보강한 파일 검사는101개(인수 부분 검사100 + 표식 oracle 회귀1) 중90통과/11실패다. 이 재검사는 전체 실행과 중복 합산하지 않는다.

자료 workflow는 테스트의 API 검사 위치 수정 후 **75/75** 통과했다. 제품의 예외 처리는 변경하지 않았다. React/jsdom은 routing opt-in 포함 **791/791**, Node 도구는 최종 재검사도 **171/171** 통과했다. 이는 위900개 명세를 모두 실행했다는 뜻이 아니다. 원시 로그는 `/tmp/dougle-remaining-java.log`, `/tmp/dougle-file-final.log`, `/tmp/dougle-library-final.log`, `/tmp/dougle-remaining-ui-final.json`, `/tmp/dougle-remaining-node-final.log`다.

후속 Git100은85통과/10실패/5예외, 웹 경계45는45통과, 폴더 부분 실패25는20통과/5예외다. 각각 `/tmp/dougle-git-100.log`, `/tmp/dougle-web-boundary-45.log`, `/tmp/dougle-folder-failure-25.log`에 기록했다. 웹 timeout은 대역이 실제 `HttpGet` 취소를 관측할 때까지25초씩 기다렸다. 실제 TCP 소켓의 중단 보장으로 확대하지 않는다. 기존 전체 Java1526에 이170개를 추가한 전체 재검사는 아직 실행하지 않았다.

동기화 상태25개는19통과/6실패/예외0/skip0이다(`/tmp/dougle-sync-progress-25.log`). 실제 분석기 호출을 latch로 잠시 멈춰 동시 요청/취소를 제어했다. Node 전체는170개 추가 후에도171/171 통과(`/tmp/dougle-remaining-node-git-web.log`), 동기화 결과 추가 후 ledger/catalog 검사7개 통과다.

```sh
node scripts/search-acceptance-fixtures.mjs --write
FORGE_VERIFY_SEARCH_MODEL_DIR='/Applications/Knowledge Forge.app/Contents/Resources/resources' \
  bash scripts/maven.sh -Dtest=SearchAcceptanceTest -Dforge.acceptance.search=true test
bash scripts/maven.sh -Dtest=FileAcceptanceTest,FolderAcceptanceTest,WebAcceptanceTest \
  -Dforge.acceptance.collection=true test
bash scripts/maven.sh -Dtest=LibraryWorkflowAcceptanceTest -Dforge.acceptance.workflow=true test
FORGE_ACCEPTANCE_GIT_HOME='/Applications/Knowledge Forge.app/Contents/Resources/resources/git' \
  bash scripts/maven.sh -Dtest=GitAcceptanceTest -Dforge.acceptance.git=true test
bash scripts/maven.sh -Dtest=WebBoundaryAcceptanceTest -Dforge.acceptance.webBoundary=true test
bash scripts/maven.sh -Dtest=FolderFailureAcceptanceTest -Dforge.acceptance.folderFailure=true test
bash scripts/maven.sh -Dtest=SyncProgressAcceptanceTest -Dforge.acceptance.syncProgress=true test
node scripts/acceptance-execution-report.mjs --write
VITE_ROUTING_ACCEPTANCE=1 npm --prefix frontend test
node --test --test-concurrency=1 scripts/*.test.mjs
```

현재 알려진 실패를 숨기지 않으므로 일부 Maven 명령은0이 아닌 상태로 끝난다. 실행기는 opt-in이며 기본 단위 검사가 통과했다고 이 인수 검사를 통과한 것으로 해석하지 않는다. 생성기는17개 계층 배치와 실제 AI50개·이미지25개 결과 수·ID를 검사하고 부분 검사를 전체 PASS로 승격하지 않는다. 이전 실행과 대상 재검증을 합친 ledger이며 하나의 동일 빌드로 전부 재실행한 결과가 아니다. 실제 AI의 JAR 해시는 각각 보존한다.

## 실행 증거가 아직 없는25개

- T0951–T0975: 새 Mac25. Intel 호스트의 교차 빌드를 ARM 실기기 실행으로 대체하지 않는다.

실제 계정/ARM 실기기 외에도 아직 작성하지 않은 실행기가 있다. 이를 모두 환경 탓으로 BLOCKED 처리하거나 기존 단위 검사를 ID에 자동 대입하지 않는다. 현재 호스트는Intel이며 승인된 회사 테스트 tenant/새 Mac 실기기 인수는 이번 실행에 포함되지 않았다.

Intel0.1.28 로컬 설치와 최종DMG/설치JAR 일치·서명·HTTP200/비인증403·DB v29 검증을 완료했다. [설치 기록](local-install-0.1.28-20261027.json). 이것은 위 인수 전체 통과가 아니며 공개 릴리스·업데이트 피드는 보류한다.
