# 0.1.27 후보 소스 검증

고정 JAR SHA-256: `b647ff9815037ddef3780246d712a944d4534a43060f881cdcbab8c2e6f3a612`.

## 변경 범위

- 자동 SEARCH capture 후 React updater가 변경 가능한 `value`를 참조하던 문제를 불변 `previousBundle`로 수정했다. 이전 턴이 현재 턴 ID를 공유하여 현재 버튼을 중복 표시하지 않도록 한다.
- `SEARCH_CAPTURE`는 사용자 질문 말풍선 대신 추가 자료 표시로 렌더링한다. 저장된 bundle/thread/parent ID는 변경하지 않는다. 두 사용자 질문·두 capture의 재열기 회귀는 네 저장 턴, 두 질문 말풍선, 두 답변과 단일 현재 컨트롤을 검증한다.
- 검색 자료에서 명시적으로 조회 범위를 확인하는 후속 계획은 `RESOLVE_CAPTURED_SCOPE`, `rootQuestionBundleId`, `maxCalls=1`, `maxToolCalls=0` 계약을 검증한다. 재열기는 GET으로만 상태를 복구하며 추가 AI를 자동 실행하지 않는다. 기존 bounded hard deadline은 유지한다.
- 확정된 계획 사전 거부의 질문 수정, 복구된 계획 취소 후 입력 잠금 해제, 운영 자료 턴 저장 응답 유실 시 동일 요청 재조정 회귀를 포함한다. 불확실한 접수를 무조건 초기화하거나 자동 재전송하지 않는다.

## 실행 결과

| 검사 | 결과 | 로그 |
|---|---|---|
| Java broad | 1,143 실행, 1,136 통과, 7 건너뜀, 실패/오류 0 | `/tmp/dougle-027-java-final.log` |
| 최종 Node `node --test --test-concurrency=1 scripts/*.test.mjs` | 157 통과, 실패 0, 13.431초 | `/tmp/dougle-027-node-final3.log` |
| UI focused, ChatPanel/InvestigationPanel | 2파일, 131 통과 | `/tmp/dougle-ui-capture-fixed.log` |
| 최종 UI `npm test -- --maxWorkers=2` | 63파일, 686 통과 | `/tmp/dougle-ui-capture-final-full.log` |
| 최종 UI `npm run build` | 통과 | `/tmp/dougle-ui-capture-final-build.log` |
| JAR/dist 정확 대조 | 7파일, 1,663,455 bytes 일치 — 부모 검증 보고 | `scripts/verify-frontend-bundle.mjs` |

Java는 기존 fixture 문제인 `AnswerPartialIntegrationTest`, `QuestionAttachmentIntegrationTest`를 명시적으로 제외했다. 모든 backend 테스트 무조건 통과를 의미하지 않는다. Focused 수치는 전체 수치와 합산하지 않는다.

Node 최종157 통과는 fixture guard 및 알려진 pin 검증을 강화한 최신 helper를 포함한다. 이전156 통과(`/tmp/dougle-027-node-final.log`)는 변경 전 실행 이력으로만 남긴다. 이후 두 전체 실행의 실패도 보존한다: `/tmp/dougle-027-node-final2.log`는 동시 패키징 중 counter의3초 deadline에서 출력 bytes가 없는2실패, `/tmp/dougle-027-node-sequential.log`는 직렬 실행에서도 wrapper5초 후 exit null과 probe8초 timeout으로2실패였다. 무수정 격리 counter 재실행은15 통과(`/tmp/dougle-027-counter-isolated.log`)했고, 부모가 테스트 수정 없이 다시 실행한 전체 직렬 검사는 위 최종157 통과다. 실패 원인은 입증되지 않았으며 CPU 부하나 동시성으로 단정하지 않는다. 격리 통과를 전체 통과와 합산하지 않고 최종 통과로 이전 실패 기록을 대체하지 않는다.

`/tmp/dougle-ui-capture-red.log`는 이름과 달리 **107 통과**를 기록한다. 중단/수정 시점이 겹친 실행이므로 변경 전 실패 증거로 주장하지 않는다. 결함 재현은 부모의 설치된0.1.26 실제 HTTP·합성 CLI Ego UI 관찰이며 실제 모델 호출이 아니다. 수정 후 비동기 capture/React StrictMode 및 재열기 회귀와 최종 전체 실행은 위 fixed/full 로그를 따른다. 이전685 통과 결과 대신 마지막 불변 참조 수정 이후686 통과를 사용한다.

빌드에는 main JS 500 kB 초과 경고가 남는다(586.11 kB, gzip185.01 kB). 산출물 `frontend/dist/assets/index-DuWVghRA.js` SHA-256은 `55822a87b96807270e882278549627dd05b3bbb913a17b17d616478353e47b4b`, `frontend/dist/index.html`은 `ddcd51404c2bdc1b7d3f55070954e5270f9bb59daf84959634e4b837588c18d4`다.

## 한계와 별도 검증

이 문서는 소스·합성 회귀와 빌드 증거다. 새 JAR의 UI runtime, 패키징, 설치 통과는 이 기록에서 주장하지 않으며 별도 결과가 필요하다. 실제 AI, 실제 회사 MySQL/Datadog, ARM 실기기, 전체 전문가 인수 통과로 확대하지 않는다. 외부 업무 시스템 읽기 전용 정책은 유지한다.
