# 유한 프로토콜 진단 — 성공 기준선만 확인

2026-10-26. [원본 보고서](protocol-counter-diagnostic-20261026.json), 로그 `/tmp/dougle-protocol-counter-diagnostic.log`.

## 범위와 결과

- 승인된 `restart-history-followup` 변형0 한 번만 실행했다. 상한4dispatch를 모두 사용했으며 계획2회·답변2회가 완료됐다. 재시도·추가 배치·UI hold는 없었다.
- 첫 답변은 BLUE 배지의 유효 기간을 **발급 시각부터72시간**으로 설명했다. 후속 답변은 같은 BLUE 배지를 대상으로 **자동 갱신 아님·운영 담당자의 수동 승인 필요**를 설명했다. 두 답변 모두 가상 정책의 R1에 부합하며 실제 레코드·현재 승인 상태를 만들지 않았다.
- 두 계획은 모두 STOP이고 query/output/toolStatus는 null이다. 운영 도구 제안·실행은 없었다.
- 첫 답변 뒤 정확한 checkpoint/hash에 묶인 AGENT 수동 ACK를 작성했다. 부모도 독립 대조했고 후속 답변 ACK는 부모가 작성했다. 자동 의미 승인은 없었으며 두 ACK 내용은 원본 보고서에 보존했다.
- 같은 합성 DB의 백엔드를 재시작하고 GET으로 대화·근거·작업 이력을 복원한 뒤 후속 질문을 완료했다. 보고서 상태는 `FOLLOWUP_VERIFIED`다. 실제 UI 재열기 검사는 하지 않았다.

## 카운터 해석

카운터는 **순서 없는 프로세스별2개 기록**이다. 임의 파일명 정렬은 시간순·dispatch순이 아니므로 특정 기록을 첫 답변이나 후속 답변에 대응시키지 않는다.

| 관측 | 각 프로세스의 값 또는 순서 없는 값 집합 |
| --- | --- |
| INITIALIZE/CONFIG/THREAD/TURN 응답 | 각각1 |
| application binding이 일치한 agent 시작/완료 | 각각1 |
| application binding이 일치한 agent delta | {48, 51} |
| 일치한 turn 완료 / COMPLETED 상태 | 각각1 |
| 무시된 agent 시작/delta/완료 및 turn 완료 | 모두0 |
| JSON·envelope 오류, 잘린 line, capture 한도 도달 | 모두0 |
| stdout 바이트 / line 수 | {35185, 37186} / {67, 70} |

각 기록의 application binding은 TURN_STARTED의 직접 turn ID 누락1, THREAD_STARTED의 직접 thread ID 누락1, THREAD_STATUS의 turn ID 누락2, OTHER의 thread/turn ID 누락 각각2를 기록했다. TURN_STARTED는 nested ID를 쓰는 normalized 분류에서 MATCH1이다. 별도의 pre-stream OTHER1도 각 기록에 있다. **이 성공 실행의 무시된 메타데이터는 누락된 답변/완료 이벤트가 아니다.** 다만 OTHER는 고정 분류 외 이벤트이며 원문을 저장하지 않아 세부 의미를 단정하지 않는다.

카운터 부분은 고정 필드·숫자만 보존한다. 원문 프로토콜 payload, reasoning, 인증정보, 실제 thread/turn ID를 카운터에 저장하지 않았다. 평가 보고서의 합성 질문·답변·근거·앱 작업 ID는 의미 검토용 증거로 별도 보존한다.

## 아티팩트와 정리

- 실행 전후 JAR SHA-256 일치: `e0e43e1084177ea4d28530547cc762bce339d2de2f5721d21ce70d0ef4eeb58d`.
- 실행 전후 bundled CLI SHA-256 일치: `73697f229103f463acaafd8659976caaa4892ec11e2c246c7fa97f93d523f90e`.
- 보고서 SHA-256: `2c93c9fde941a9c2a46f22ac0b69c468f8943d43970c6bfa7defa59d410b48bd`. 파일 권한0600 확인.
- 래퍼 SHA-256: `831f6a62ab9cd204ae56540021bb26665fdca557e21cb346ccbf9f1d7399ab4e`. hook SHA-256: `81dbab35cadef88a3a5d406c283b9e7ccb447cdc2dc95992daf47f1427beca1c`.
- 2026-10-26T11:33:12Z 정리 확인: runner PID92181과 백엔드 PID92182/92384가 종료됐고, 해당 wrapper/app-server 잔여 프로세스가 관측되지 않았다. 보고서의 두 runtime에도 stoppedAt이 기록됐다.
- 소유 임시 디렉터리 `/var/folders/2r/c1wp_v_j5zd0zkhlcr4h09r00000gn/T/dougle-expert-live-JiRMND` 제거를 확인했다. 합성 DB·launcher·pin manifest·임시 counter 파일은 제거됐으며 검증한 숫자 카운터는 보고서에 남았다. 기존 전용 프로필은 CLI로만 사용했고 인증내용을 읽거나 복사하지 않았다.

## 결론과 제한

**성공한 계측 기준선 한 번**이다. 래퍼의 전달·카운터 저장은 타이밍에 영향을 줄 수 있어 비계측 실행과 동일하다고 볼 수 없다. 과거 WAIT_RESPONSE 시간 초과를 재현하지 않았으며 과거 원인을 제공자 장애, 바인딩 누락 또는 앱 오류로 확정하지 않는다. 시간 초과가 해결됐다는 증거도 아니다.

E04/E05 및 중앙 인수 상태는 승격하지 않는다. UI hold·ARM 실기기·실제 업무 시스템 조회/쓰기·설치·공개는 이번 범위에 없다. 모델 ID·과금은 독립 계측하지 않았으며 dispatch 수는 제공자 과금 호출 수의 확정값이 아니다. 승인된4dispatch로 종료했고 추가 실행 승인은 없다.
