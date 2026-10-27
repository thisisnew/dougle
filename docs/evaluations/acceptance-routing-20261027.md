# 1,000개 인수의 첫 경계 검사 — 2026-10-27

## 현재 판정

**T0001–T0100의 경계 검사100개를 실행했다. 최종100개 통과지만 전체 인수는 PARTIAL100 / NOT_RUN900 / PASS0이다.** 실제 소스 수집·manifest·검색·설치 앱 동작까지 확인하지 않았기 때문에 인수100개 완료로 승격하지 않는다. [각 ID와 실행 이력 JSON](acceptance-routing-20261027.json).

검사 대상은 소스 커밋 `b9daacfa4350037ca10df59509e26cf1c0216c37`이다. 현재 설치 앱0.1.27은 변경하지 않았다. 이번 변경으로 검색 정확도가 개선됐다고 주장하지 않는다. 앞선 검색 기준선 실패는 여전히 별도 미해결이다.

| 구간 | 검사 방식 | 최종 경계 결과 | 미검증 범위 |
|---|---|---|---|
| R01 T0001–T0025 | 입력5종×UI4동선/API1동선 | 25/25 | 실제 저장소 원문 manifest·검색 |
| R02 T0026–T0050 | 일반 웹/파일/이슈/PR/raw 링크 | 25/25 | 실제 각 페이지 본문·수집 범위 |
| R03 T0051–T0075 | tree/query/fragment/대문자/443 | 25/25 | 설치본 브라우저와 원격 수집 연결 |
| R04 T0076–T0100 | userinfo·내부주소/file/javascript/SSH/개행 | 25/25 | native 브라우저 E2E 및 추가 주소 변형 |
| T0101–T1000 | 이번 매트릭스로 실행하지 않음 | NOT_RUN900 | 케이스별 fixture/runner와 실제 실행 필요 |

UI80개는 실제 React 컴포넌트/jsdom/userEvent지만 HTTP는 mock이다. 정상 입력의 request body·자동 동기화1회·탭 전환·Enter·오류 뒤 재입력·멀티라인 제출 차단을 검사한다. 모의 서버가 READY를 반환한 것을 실제 수집 성공으로 보지 않는다.

API20개는 실제 Spring MVC/서버 검증/SQLite 영속화 경계다. 각 케이스에서 URL/GITHUB 두 kind를 독립 Workspace로 요청하고 결과 종류·정규화 주소·등록 수를 대조한다. `sync=false`, RemoteReader/GitRunner mock의 상호작용0회를 확인한다. 실제 외부 원격 수집이나 설치본 세션 인증 검사는 아니다.

## 발견·수정·재검사

1. 첫 API 실행은 테스트에서 `X-Forge-Client`를 빠뜨려20개 모두403이었다. **HARNESS_ERROR**로 보존하고 제품 결함 수에서 제외했다. 보안 필터를 끄지 않고 정상 헤더를 추가했다.
2. 유효한 수정 전 기준선은 UI64통과/16실패, API16통과/4실패였다. 실패는20개 조작 조합에 반복된4가지 주소 변형이다: `?tab=readme-ov-file`, `#readme`, `GITHUB.com`, `:443`.
3. 서버와 UI 양쪽에서 해당 저장소 루트 변형을 `https://github.com/owner/repo`로 정규화했다. 개별 blob/issues/pull/raw/tree 링크는 웹 범위를 유지한다. 임의 query/userinfo/비표준 포트를 조용히 제거해 저장소 수집으로 확대하지 않는다.
4. 초기 부정 입력 UI 검사는 mock 서버 거부만 확인해 제출 시도를 놓쳤다. 이를 보강하자 T0096/97/98/100에서 개행이 제거된 주소로 등록을 시도하는 문제가 드러났다(UI76/80). 이 이전 통과를 완전한 보안 검증으로 취급하지 않는다.
5. clipboard의 내부 제어문자/줄바꿈을 붙여넣기 전에 차단하고 기존 주소도 비워 잘못된 이전 주소 제출을 막았다. 수정 후 경계 UI80/API20 통과. 일반 회귀에도 멀티라인 paste 테스트를 추가했다.

정답을 구현에 맞춰 수정하거나 실패 케이스를 삭제하지 않았다. 정규화 전 결과, 테스트 헤더 오류, 부정 입력 검사의 누락과 보강 결과를 JSON에 함께 보존한다.

## 회귀 결과와 남은 실패

- 대상 Java72개 통과: SourceAddress·SourceIntegration·Collector·opt-in Routing20 포함.
- 전체 Java **1157실행/1141통과/10오류/6skip**. 이번에는 기존 제외 클래스도 실행했다. 오류10개는 `AnswerPartialIntegrationTest`와 `QuestionAttachmentIntegrationTest`가 준비하는 Workspace 이름이20자 제한을 넘는 문제다. 제품 검증을 진행하지 못한 fixture 오류이며 전체 Java 통과라고 보고하지 않는다.
- 실제 동봉 검색 모델 opt-in을 활성화한 Java 회귀다. 회사 자료·실제 AI 정확도 평가를 대신하지 않는다.
- 전체 UI790개 통과(붙여넣기 guard 추가 직전). 최종 변경 후 관련 UI142개 통과, 그중80개가 주소 매트릭스다. TypeScript/프로덕션 빌드 통과, 기존500kB chunk 경고 유지. 앞선790과 이후142를 합산하지 않는다.
- Node 첫 배치166개 중1실패: synthetic descendant 종료 검사에서 출력 준비 대기 실패. 해당 파일만 다시 실행한15개 중 다른 signal/stat 검사1실패. 이후 직렬 전체166개 통과. **초기 실패는 보존하며 부하 원인이라고 확정하지 않는다.**
- 추가 실제 AI 호출0회, 회사 시스템 쓰기0회. 공개 릴리스/피드 승격·재설치 없음.

## 재실행

```sh
bash scripts/maven.sh test -Dtest=RoutingAcceptanceTest -Dforge.acceptance.routing=true
(cd frontend && VITE_ROUTING_ACCEPTANCE=1 npx vitest run src/RoutingAcceptance.test.tsx)
node --test scripts/acceptance-catalog.test.mjs scripts/routing-acceptance-fixtures.test.mjs scripts/acceptance-boundary-results.test.mjs
FORGE_VERIFY_SEARCH_MODEL_DIR='/Applications/Knowledge Forge.app/Contents/Resources/resources' bash scripts/maven.sh test
```

경계 매트릭스는 opt-in이며 기본 회귀에서 생략된다. 실제 source/UI는 동일하게 사용하되 테스트의 원격 응답/목록은 합성이다. 전체 매트릭스 파일은 `docs/fixtures/routing-acceptance.json`, 원시 실행 로그는 `/tmp/dougle-routing-*`와 `/tmp/dougle-1000-*`다. 테스트가 만든 임시 SQLite에는 합성 자료만 있으며 사용자 프로필/인증을 읽지 않았다.

다음은 T0101 이후 수집/검색 인수 runner를 구현해 manifest와 답변 구절까지 대조하는 작업이다. 이번 경계 통과를 그 단계의 완료로 취급하지 않는다. ARM·회사 계정 등 실제 환경이 필요한 케이스도 준비 없이 PASS로 변경하지 않는다.
