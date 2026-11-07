# 문의 처리·데이터 모델

[서버 설계 입구](README.md) · 다음: [자료 접근·운영 조회](access.md)

이 문서의 상태·필드·연산은 서버 목표 계약입니다. 현재 SQLite 스키마를 그대로 실행하는 마이그레이션 SQL이 아닙니다. 서버 구현자는 아래 제약을 DDL과 트랜잭션 테스트로 옮깁니다.

## 문의 식별과 접수

대화 키는 `(workspace_id, channel_id, thread_ts)`입니다. Slack 시각 문자열은 부동소수점으로 바꾸지 않습니다. 원글 멘션이면 원글 `ts`가 `thread_ts`입니다. 같은 스레드의 새 멘션은 새 문의가 되고 대화는 이어갑니다. 같은 사람이 다른 원글에서 질문하면 별도 대화입니다.

접수 순서는 다음과 같습니다.

1. Slack 앱·워크스페이스, 이벤트 형식, 봇 작성 여부를 검사합니다.
2. 허용 채널·실행 상태·중지 구간을 확인합니다. 거절한 질문은 본문을 저장하지 않고 최소 식별 정보와 이유만 남깁니다.
3. 짧은 DB 트랜잭션에서 이벤트 중복 키, 스레드, 문의, 작업을 함께 저장합니다.
4. 커밋 후 수신 확인(ack)을 보냅니다. 원본 읽기·AI·DM 전송은 ack 경로에서 실행하지 않습니다.
5. 반응 이모지는 별도 부수 효과로 처리합니다. 실패해도 문의 처리를 취소하지 않습니다.

중복 키는 Slack `event_id`를 우선 사용하고 `(workspace_id, channel_id, trigger_ts, event_kind)`에도 유일 제약을 둡니다. Socket Mode `envelope_id`는 수신 확인에 사용하며 문의의 유일 ID를 대신하지 않습니다. DB 저장 실패 시 성공 ack를 하지 않습니다. 사용 중인 Bolt/Socket Mode 어댑터가 핸들러 전에 ack하는지 반드시 확인하고 필요하면 수신 경계를 구현합니다. 테스트는 실제 선택한 어댑터의 ack 순서를 검증합니다.

정상 접수 목표는 1초 이내이며 Slack의 ack 제한 안에 끝내야 합니다. DB 지연이 길어지면 수신기를 비정상으로 표시하고 운영자에게 알립니다. ack만 먼저 보내고 메모리에 작업을 두면 재시작 시 잃을 수 있으므로 허용하지 않습니다.

대기열이 설정 상한에 도달하면 이벤트에 `overloaded` 결정을 저장하고 ack합니다. AI 작업은 만들지 않으며 “현재 문의가 많아 접수하지 못했습니다. 잠시 후 다시 질문해 주세요.”라는 안내를 문의별 최대 한 번 예약합니다. 이 최소 기록·안내도 저장할 수 없으면 성공 ack를 보내지 않습니다. 과부하 안내는 자동 모드·허용 채널에서만 전송하고 검토 모드에서는 내부 경보로 처리합니다.

## 문의 상태

| 상태 | 뜻 | 다음 상태·조건 |
|---|---|---|
| `queued` | 접수 또는 보충 후 조사 대기 | 소유권·예산 확보 후 `researching` |
| `researching` | 근거 조사·답변 작성 | `ready`, `awaiting_approval`, `needs_developer`, `failed` |
| `ready` | 자동 게시 가능한 검증 결과 보유 | 전송 선점 후 `posting` |
| `awaiting_approval` | 명시적 검토 모드의 승인 대기 | 최신 답변 승인 시 `ready`, 거절 시 `rejected` |
| `needs_developer` | 필요한 결과 또는 내부 조치 대기 | 유효 결과 입력 시 `queued`, 직접 응대 시 `human_owned` |
| `failed` | 조사 실행을 완료하지 못함 | 내부 도움 요청, 유효 보충 후 `queued`, 직접 응대 시 `human_owned` |
| `posting` | 외부 전송을 시작할 수 있는 상태 | 전체 확인 시 `posted`, 확정 실패 시 보류·내부 요청, 불명 시 `post_unknown` |
| `post_unknown` | 일부 또는 전체 전송 결과 불명 | 외부 기록으로 전송 증명 시 `posted`; 불충분하면 유지 |
| `posted` | 모든 답변 조각의 게시 확인 | 종료 |
| `human_owned` | 담당 개발자가 직접 응대 | 해당 문의의 자동 작업 종료 |
| `rejected`, `cancelled` | 검토 거절 또는 명시적 취소 | 종료 |

`needs_developer`와 `failed`는 작업자·스레드 실행 소유권을 계속 잡고 있지 않습니다. 후속 질문은 처리할 수 있습니다. 보충 결과는 해당 문의에 연결하고 최신 스레드의 정정·개발자 답변을 다시 읽습니다. 이미 사람이 해결한 내용을 새 답변으로 덮지 않습니다. `human_owned`는 해당 문의의 종료이며 앞으로 같은 스레드에서 명시적으로 새 멘션하는 것까지 영구 차단하지 않습니다.

확인할 수 없는 개념 질문, 범위 밖 질문, 필수 입력 누락은 곧바로 내부 오류로 처리하지 않습니다. 허용 범위 안에서 설명·추가 질문을 게시할 수 있습니다. 코드·현재값 조사가 필요한데 근거가 빠진 경우는 내부 확인으로 넘깁니다. 기존 [응답 정책](../dougle-bot/internal-handoff.md)을 보존합니다.

## 작업 선점과 병렬 실행

작업 상태는 `queued`, `running`, `succeeded`, `failed`, `cancelled`입니다. 재시도 시 작업 ID를 보존하고 `attempt`와 `lease_epoch`를 올립니다. 문의 세대 번호 `generation`은 개발자 보충·직접 응대·취소처럼 기존 결과를 무효화하는 사건마다 증가합니다.

작업자는 다음 짧은 트랜잭션으로 작업을 가져옵니다.

```text
BEGIN
  처리 가능한 가장 오래된 작업을 FOR UPDATE SKIP LOCKED로 선택
  스레드 소유권 행을 잠그고 현재 활성 작업이 없는지 확인
  작업의 inquiry.generation 및 실행 모드 확인
  해당 작업의 예산을 원자적으로 예약
  thread와 job에 owner_id, lease_epoch, lease_until 기록
  job = running, inquiry = researching
COMMIT
  트랜잭션 밖에서 AI·자료 조회 실행
```

모든 연산의 잠금 순서는 스레드, 문의, 작업, 예산, 전송 기록 순서로 통일합니다. 위 후보 선택은 후보 ID 탐색 단계이며 실제 변경 트랜잭션에서는 이 잠금 순서를 지킵니다. 선택한 후보가 바뀌었으면 다시 찾습니다. `SKIP LOCKED`만으로 같은 스레드의 동시 실행이 막히는 것은 아닙니다. [PostgreSQL 설명](https://www.postgresql.org/docs/current/sql-select.html)

초기 소유권 기간은 60초, 갱신은 10초마다입니다. DB 시각으로 판정하며 갱신에 실패하면 도구 호출을 중단합니다. 완료 저장에는 `owner_id + lease_epoch + generation + running 상태`가 모두 일치해야 합니다. 오래된 작업자가 나중에 돌아와도 결과·갱신 제안·게시 예약을 저장할 수 없습니다.

복구기는 만료 작업만 회수하고 이전 실행 취소를 요청합니다. 다른 실행기가 살아 있다는 이유만으로 모든 `running` 작업을 대기 상태로 초기화하지 않습니다. 격리 프로세스의 강제 종료와 외부 조회 취소를 시도하고 실제 종료 여부를 기록합니다. 취소할 수 없는 읽기 조회는 제한 시간까지 실행될 수 있으며 남은 비용·연결 수를 계속 계산합니다.

같은 스레드에서는 접수 순서와 명시적 의존성을 지킵니다. 최종 전송 결과가 불명인 이전 문의가 있으면 후속 자동 게시는 보류하고 먼저 상태를 조정합니다. 다른 채널·스레드는 공정하게 선택해 한 사용자의 긴 문의가 모두를 막지 않게 합니다.

## 결과 저장의 원자성

엔진 결과를 받은 뒤 아래 항목을 같은 트랜잭션으로 저장합니다.

- 현재 작업·문의 세대·소유권 검사
- 답변 revision, 원본 확인 기록, 검증 결과
- 작업 완료, 실제 비용·미확정 비용 처리
- 다음 문의 상태
- 자동 게시 또는 개발자 DM의 outbox 기록
- 감사 이벤트와 필요한 지식 수정 제안

외부 Slack 호출은 이 트랜잭션 안에서 하지 않습니다. 검증 실패를 답변 저장 실패와 혼동하지 않고 원인별로 기록합니다. 모델이 자기 출력에 `verified=true`를 적어도 통과 근거가 되지 않습니다.

## 예산과 대기

일일 합계는 `확정 비용 + 진행 중 예약 + 미확정 비용 보류액`입니다. DB 행 잠금으로 예약을 계산하고 상한을 넘는 새 작업을 시작하지 않습니다. 기존 SQLite의 예약 개념을 서버에서도 유지합니다.

엔진이 반환한 실제 비용으로 정산합니다. 비정상 종료로 비용을 모르면 예약을 바로 0으로 반환하지 않습니다. 제공자 기록으로 대조하거나 상한까지 사용한 것으로 잠정 계산합니다. 호출당 한도를 넘을 수 있는 실행기는 초과 위험을 별도 제한해야 합니다. 토큰 비용과 BigQuery 조회 비용은 서로 다른 예산으로 기록합니다.

대기 중에는 현재 단계와 대기 시간을 보여줍니다. 허위 진행률·근거 없는 완료 예상 시각을 만들지 않습니다. 대기·도구·AI·최종 게시 각각의 제한 시간을 적용하고 무한 재시도하지 않습니다. 기본 조사 전체 제한은 900초를 출발점으로 삼되 질문 유형별 실측 후 조정합니다.

초기 재시도 정책은 읽기의 일시 오류 최대 2회, 형식 오류 보완 최대 1회입니다. 최초 실행과 재시도는 같은 전체 기한·예산에 포함합니다. 권한 거부·정책 차단은 자동 재시도하지 않습니다. 대기 한도는 초기 15분으로 두고 초과하면 내부 확인으로 전환합니다. 운영자용 이유 코드는 `queue_timeout`, `budget_exhausted`, `source_unavailable`, `engine_timeout`, `verification_failed`처럼 구분합니다. 승인자에게 일반적인 “재시도” 버튼을 추가하지 않습니다.

## 대화와 세션

서버는 사용자 메시지, 실제 게시된 답변, 개발자 보충, 미해결 항목을 자체 기록합니다. 엔진 세션 ID는 이 문맥의 부가 참조입니다. 세션이 없으면 보관한 대화와 허용 범위의 Slack 스레드로 재구성하고 신규 조사를 수행합니다.

저장할 식별자는 `session_id`, `engine_version`, `skill_bundle_version`, `snapshot_id`, `policy_version`, `context_digest`입니다. 모델·스킬 계약이나 권한 범위가 달라지면 기존 세션을 재사용하지 않습니다. 지식 버전이 달라졌을 때는 새 세션에 허용된 대화만 복원하는 방식을 첫 구현의 기본값으로 둡니다. 이전 세션이 이미 읽은 비공개 자료는 새 필터만 추가해서 지울 수 없습니다.

고객의 수정·삭제, 개발자의 실제 답변, 첨부 변경을 감지하면 `context_digest`를 바꿉니다. 게시 직전에 현재 문맥을 확인하고 결과의 전제가 바뀌었으면 재검증합니다. 자체 진행 안내·반응 이모지만 바뀐 경우에는 재조사를 유발하지 않습니다.

## 개발자 행동과 고객 안내

버튼·모달은 워크스페이스, 담당자, 문의, 최신 카드, 현재 revision 또는 job, 문의 세대에 묶습니다. 서버가 역할을 검증하며 Slack payload의 사용자 입력만 믿지 않습니다. 같은 제출은 한 번만 적용합니다.

| 행동 | 원자적으로 저장할 내용 | 고객 안내 |
|---|---|---|
| 도움 요청 | 내부 요청과 DM 전송 예약 | DM 전송 확인 후 대기 안내 한 번 |
| 결과 입력 | 보충 결과, `generation+1`, 새 작업, 감사 기록 | “개발자로부터 확인 내용을 받았습니다. 내용을 확인하고 정리해 답변드리겠습니다.” |
| 직접 응대 | `human_owned`, `generation+1`, 작업 취소, 미전송 최종 답변 폐기 | “담당 개발자가 직접 답변드릴 예정입니다.” |
| 환경 복구 보고 | 복구 사실, 새 작업 | 실제 조회를 다시 수행한 뒤 답변 |

결과 입력에 조회 시각 필드를 강제하지 않습니다. `submitted_at`은 서버 접수 시각입니다. 보고된 실제 관측 시각이 있으면 별도 보관하고 최신성 판단에 사용합니다. 입력 시각만으로 현재값이라고 판단하지 않습니다.

안내는 자동 모드에서만 게시하며 `(inquiry_id, notice_kind, action_event_id)`로 중복을 막습니다. 최초 대기 안내는 문의당 한 번입니다. 결과 입력 안내는 수락한 보충 사건당 한 번입니다. 안내 실패·불명은 자동 재전송하지 않으며 최종 조사·답변을 막지 않습니다. 최종 답변은 앞선 안내의 전송 시도가 끝난 뒤 진행하되 실패 안내를 기다리며 정지하지 않습니다.

직접 응대와 게시 선점은 같은 문의 행에서 직렬화합니다. 직접 응대가 먼저면 게시기가 취소된 답변을 보내지 않습니다. 이미 외부 전송이 시작됐으면 회수할 수 없으므로 “봇 답변 전송이 시작되어 중단을 보장할 수 없습니다”라는 내부 상태를 반환하고 완료 결과를 조정합니다. 버튼 성공을 거짓으로 표시하지 않습니다.

검토 모드에서는 승인 전 고객 안내·최종 답변을 자동 게시하지 않습니다. 기존 승인 대기 문의는 서버 설정이 자동 모드로 바뀌어도 자동 게시 대상으로 변환하지 않습니다.

## 게시와 결과 불명

Outbox는 DB에 저장한 전송 대기 기록입니다. 원격 전송과 DB 기록을 하나의 트랜잭션으로 묶을 수 없으므로 정확히 한 번 전송을 보장한다고 표현하지 않습니다.

1. 게시기가 `pending` 전송을 선점합니다. 문의 상태·세대, 검증, 대상·현재 공개 권한, 문맥, 실행 모드를 다시 확인합니다.
2. 전송 의도와 내용 해시를 기록한 뒤 `sending`으로 바꿉니다. 전송 ID는 재시작해도 유지합니다.
3. 답변은 의미 단위로 나누고 조각 순서를 고정합니다. 마지막까지 확인된 뒤 문의를 `posted`로 바꿉니다.
4. Slack 성공 응답의 `channel`, `ts`를 저장합니다. 지원되는 메시지 metadata 등에 전송 ID를 넣을 수 있는지는 실제 API로 검증합니다. ID 자체가 Slack 중복 방지를 보장하지 않습니다.
5. 확정 거절·429는 분류에 따라 제한적으로 재시도합니다. 429는 `Retry-After`를 따릅니다. 요청이 전달됐을 수 있는 timeout·연결 단절·5xx는 `unknown`으로 둡니다.
6. 불명 전송은 Slack의 읽기 가능한 기록과 일치 여부를 대조합니다. 확실한 증거가 있을 때만 `sent`로 고칩니다. 검색에 안 보인다는 이유로 재전송하지 않습니다.

일부 조각만 성공하면 전체 답변을 다시 보내지 않습니다. 불명 조각은 보류하고 담당자에게 링크·상태를 알립니다. `sending` 소유권이 만료됐다는 이유로 다른 게시기가 다시 호출해서도 안 됩니다. 먼저 `unknown`으로 전환합니다.

DB를 과거 시점으로 복원하면 이미 보낸 메시지가 DB에서 사라질 수 있습니다. 서버 전송 의도는 별도 복구 가능한 append 기록에도 남기고, 복원 후 원장을 대조하기 전에는 게시기를 열지 않습니다. 첫 배포에서는 기존 로컬 원장을 보존하면서 서버용 원장·DB 백업의 대응 절차를 구현합니다. 원장 누락·불일치를 무시하는 설정은 제공하지 않습니다.

## 논리 데이터 모델

모든 시간은 UTC `timestamptz`, 비용은 `numeric`, Slack 식별자는 `text`, 신규 내부 ID는 UUID를 사용합니다. 아래 주요 필드 외에도 `created_at`, `updated_at`과 필요한 감사 필드를 둡니다.

| 테이블 | 주요 필드 | 제약 |
|---|---|---|
| `workspaces` | id, mode, policy_version, epoch | 워크스페이스마다 1행 |
| `pause_intervals` | workspace_id, channel_id?, from_ts, to_ts | 이벤트 생성 시각 기준 중지 구간 판정 |
| `channels` | workspace_id, channel_id, enabled, domain_scope, support_route_id | 복합 PK |
| `processed_events` | workspace_id, event_id, event_kind, event_ts, decision | workspace+event 유일 |
| `threads` | id, workspace_id, channel_id, thread_ts, context_version, active_job_id, owner_id, lease_epoch, lease_until | workspace+channel+thread 유일 |
| `inquiries` | id, workspace_id, thread_id, trigger_ts, requester_id, status, generation, reply_mode, snapshot_id, current_revision | workspace+thread+trigger 유일 |
| `jobs` | id, inquiry_id, generation, kind, status, attempt, owner_id, lease_epoch, lease_until, available_at, deadline | 문의당 queued/running 작업 최대 1개 |
| `conversation_events` | id, thread_id, external_id, kind, body_ref, content_hash, happened_at, deleted_at | thread+external_id+version 유일 |
| `drafts` | id, inquiry_id, revision, body, structured_sources, verification, context_digest, snapshot_id | inquiry+revision 유일 |
| `evidence_receipts` | id, run_id, source_id, kind, revision, coverage, observed_at, success, result_digest, policy_version | 실제 도구 성공과 연결 |
| `developer_requests` | id, inquiry_id, generation, route_id, missing_evidence, status, card_ref | 열린 요청과 최신 카드 연결 |
| `developer_results` | id, request_id, actor_id, submission_key, summary_ref, observed_at?, submitted_at, evidence_kinds | submission_key 유일 |
| `engine_runs` | id, job_id, attempt, session_ref, skill_versions, reserved_cost, actual_cost?, accounting_state | job+attempt 유일 |
| `budget_accounts` | workspace_id, budget_scope, utc_day, limit, reserved, spent, uncertain | scope+day 유일, 음수 금지 |
| `outbox` | id, workspace_id, channel_id, thread_ts, inquiry_id?, event_id?, generation?, kind, revision?, action_event_id?, part, payload_ref, digest, state, attempt, lease, slack_ts | 논리 전송 키 유일 |
| `audit` | id, workspace_id, inquiry_id?, actor, action, reason_code, correlation_id, at | 원문·시크릿 없는 이력 |
| `runtime_leases` | role_key, owner_id, epoch, lease_until | 역할별 활성 소유자 1개 |

지원 경로 설정은 `support_routes(route_id, workspace_id, primary_user_ids, fallback_user_ids, allowed_domains, enabled)`로 관리합니다. 첫 버전은 경로당 실제 책임자 1명을 선택하고 선택된 사람을 요청에 고정합니다. 담당자 변경 시 열린 요청의 권한과 카드 수신자를 함께 갱신합니다. 자료 연결·시크릿 참조·허용 범위는 [자료 접근 명세](access.md)의 원본 연결 목록에 따라 별도 설정으로 보관합니다.

테이블 간 FK는 문의·스레드·워크스페이스가 서로 다른 조합으로 연결되지 않도록 복합 키 또는 동등한 DB 제약을 둡니다. `current_revision`은 그 문의의 답변만 가리켜야 합니다. outbox의 답변 revision과 문의 세대는 전송 시에도 재검사합니다. NULL을 포함한 유일 키는 일반 UNIQUE만으로 중복을 막지 못할 수 있으므로 전송 종류별 부분 유일 인덱스 또는 정규화한 비NULL `dedupe_key`를 둡니다.

과부하 안내 outbox는 문의 대신 `processed_events`의 사건을 참조하며 `(workspace_id, event_id, kind)`로 유일하게 만듭니다. 고객 답변·개발자 안내에는 `inquiry_id`와 `generation`을 필수로 요구합니다. 종류별 필수 필드와 목적지는 CHECK·FK로 검사하고 과부하 안내를 근거 검사 우회용 답변으로 사용할 수 없게 합니다.

작업 조회에는 `(status, available_at, created_at)`, 문의에는 `(workspace_id, status, updated_at)`, 전송에는 `(state, available_at)`, 감사에는 `(inquiry_id, at)` 인덱스를 둡니다. outbox의 `available_at`은 재시도·순서 제어 필드로 추가합니다. 완료 기록은 보관 정책에 따라 정리하며 살아 있는 FK 참조와 중복 방지 기간을 먼저 확인합니다.
