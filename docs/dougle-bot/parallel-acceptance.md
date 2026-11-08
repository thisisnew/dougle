# 질문별 에이전트 병렬 처리: 구현·검증 차트

진행 순서: **케이스 확정 → 본코드 구현 → 테스트 코드 작성 → 검증**. 테스트를 먼저 작성하는 TDD로 진행하지 않습니다.

```text
질문 A ── 에이전트 A ── 근거 검증 ── A 스레드 답변
질문 B ── 에이전트 B ── 근거 검증 ── B 스레드 답변
질문 C ── 빈 자리 대기 ── 에이전트 ── C 스레드 답변
                 │
                 └─ 지식 변경 제안 ── 저장 대기열 ── 순차 반영 / 충돌 보존
```

에이전트는 문의별 독립 Claude 실행입니다. 한 문의 안에서 추가 Agent 호출을 중첩하는 방식이 아닙니다. 기본 두 문의를 동시에 처리하되 같은 스레드는 세션·게시 순서를 지킵니다. 답변은 지식 저장 대기열을 기다리지 않습니다.

| ID | 케이스 | 기대 결과 |
|---|---|---|
| P01 | 다른 스레드 A·B 동시 접수 | 두 엔진 호출이 실제로 겹칩니다. 전역 엔진 잠금이 없어야 합니다. |
| P02 | A가 오래 걸리고 B는 짧음 | B가 A보다 먼저 답변할 수 있습니다. |
| P03 | 세 문의 연속 접수 | 최대 두 건 실행, 한 건 대기; 자리가 나면 시작합니다. |
| P04 | 같은 스레드 두 문의 | 앞 문의 처리·게시 종료 후 뒤 문의가 같은 세션을 이어갑니다. |
| P05 | 다른 스레드 세션 | 세션·질문·결과가 섞이지 않습니다. |
| P06 | 앞 문의 실패 또는 재시도 | 다른 스레드는 계속 진행합니다. |
| P07 | 개발자 확인 대기 | 작업 자리를 점유하지 않습니다. |
| P08 | 처리 중 개별 취소 | 해당 문의만 중단하며 뒤늦은 결과를 게시하지 않습니다. |
| P09 | 봇 중지 | 새 작업을 시작하지 않고 실행 중 작업을 종료합니다. |
| P10 | 재시작 복구 | 중복 답변 없이 실행 중 작업과 저장 대기 작업을 복구합니다. |
| P11 | 중복 Slack 이벤트·중복 선점 | 한 문의에 한 작업만 실행합니다. |
| P12 | 예산이 한 호출분만 남음 | 동시 예약이 일일 예산을 초과하지 않습니다. |
| P13 | 같은 스레드 게시 중·전송 불명 | 후속 조사가 먼저 실행돼 순서를 뒤집지 않습니다. |
| P14 | 엔진 호출별 정책 파일 | 별도 경로이며 한 호출의 종료·설정 변경이 다른 호출을 훼손하지 않습니다. |
| P15 | 엔진 제한 시간 | 실제 실행 시작 이후 계산하며 대기열 시간이 포함되지 않습니다. |
| M01 | 문의 중 지식 Write/Edit | 공통 파일을 직접 수정하지 않고 호출별 임시 영역에 기록합니다. |
| M02 | 같은 호출에서 저장한 자료 재읽기 | 자기 임시 변경 내용을 읽을 수 있습니다. 다른 문의에는 미반영 변경이 보이지 않습니다. |
| M03 | 서로 다른 지식 파일 변경 | 저장 담당자가 순차 반영합니다. |
| M04 | 같은 지식 파일 동시 변경 | 먼저 반영된 내용을 덮어쓰지 않습니다. 오래된 기준의 제안은 충돌로 보존합니다. |
| M05 | 읽은 뒤 외부에서 원본 변경 | 변경된 기준을 감지하면 제안을 자동 덮어쓰기 하지 않습니다. |
| M06 | 기존 파일을 읽지 않고 덮어쓰기 | 쓰기 전 기준 확인을 요구하며 무조건 덮어쓰지 않습니다. |
| M07 | 지식 저장이 느리거나 실패 | 답변 게시를 막지 않고 저장 상태를 별도로 기록합니다. |
| M08 | 취소·실패한 실행의 임시 변경 | 성공한 지식 반영으로 취급하지 않습니다. |
| M09 | 부분 변경·재시작 | 제안과 결과를 잃지 않으며 동일 제안을 중복 반영하지 않습니다. |
| M10 | 경로 이탈·심볼릭 링크·민감정보 | 허용 지식 경로 밖에 쓰지 않으며 민감정보 제안을 차단합니다. |
| M11 | 자료가 많은 지식 저장소 | 호출마다 전체 저장소를 복사·스캔하지 않습니다. 접근한 파일만 다룹니다. |
| R01 | 정상 도메인 질문 | 기존 근거·말투·출처 계약을 유지합니다. |
| R02 | 실제 코드 확인 | 원본 코드 조회 검증을 유지합니다. |
| R03 | 실제 운영 조회 | 운영 근거·출처 검증을 유지하며 옛 조회를 재사용하지 않습니다. |
| R04 | 이미지 질문 | 문의별 첨부와 읽기 기록이 섞이지 않습니다. |
| R05 | 근거 기록 보완 | 기존 보완 제한·불변 필드·검증 조건을 유지합니다. |
| R06 | 승인 모드·개발자 결과·직접 응대 | 기존 권한·오래된 카드·중복 게시 방지 계약을 유지합니다. |
| R07 | 기본값·기존 설정 파일 | 업데이트 후 적용될 동시 처리 수를 명확히 표시하고 기존 비밀 설정은 수정하지 않습니다. |
| R08 | 처리 시간 계측 | 대기와 실행을 분리하며 실제 엔진 중첩·먼저 완료한 문의를 확인합니다. |

## 통과 기준

P01·P02는 두 호출의 시작/종료 장벽을 사용하는 테스트로 확인합니다. 작업자 수만 세거나 대기를 실행 시간으로 옮긴 것은 병렬 처리 성공으로 인정하지 않습니다. 전체 회귀 검사와 wheel-소스 일치, 독립 검토를 통과해야 배포합니다.

자동 테스트는 임시 자료·가짜 Slack·가짜 엔진으로 실행합니다. 실제 Claude·회사 연동 성능은 별도 결과로 기록하며 자동 테스트 통과와 혼동하지 않습니다.

## 실제 테스트 대응과 검증 범위

main 보고 기준 패키지 제외 전체 **1,220 passed / 40.43초**입니다. 아래는 현재 테스트 소스와의 대응이며, 이 문서 작업에서 테스트를 재실행하지 않았습니다. main 보고로 Bacon의 손상 manifest 기아 수정·소스 동결과 관련 검사 58건 통과를 확인했습니다. 최종 전체 검사는 실행 중이며 건수·결과는 main이 갱신합니다. [검증 초안](parallel-verification.json)에 남은 확인을 기록했습니다.

| 사례 | 실제 테스트 파일·대표 함수 | 확인 범위 |
|---|---|---|
| P01–P03, P15, R08 | [test_inquiry_concurrency.py](../../dougle-bot/tests/test_inquiry_concurrency.py): `test_p01_p02_p03_real_overlap_short_answer_first_third_waits_without_reservation`, `test_p01_p02_actual_claude_cli_run_overlaps_using_only_synthetic_local_processes` | 장벽으로 두 호출 중첩·B 우선 게시·C 대기를 확인합니다. 가상 시계의 대기 120초와 엔진 제한 시간을 분리합니다. 실제 모델 성능 측정은 아닙니다. |
| P04–P05, P13 | [test_inquiry_concurrency.py](../../dougle-bot/tests/test_inquiry_concurrency.py): `test_p04_p05_p13_same_thread_waits_for_post_and_resumes_own_session`, `test_p10_p13_uncertain_send_holds_thread_across_restart_without_duplicate` | 게시·전송 불명 상태에서 같은 스레드를 보류하고 세션을 분리합니다. |
| P06–P07 | [test_inquiry_concurrency.py](../../dougle-bot/tests/test_inquiry_concurrency.py): `test_p06_p07_failure_or_developer_wait_releases_slot_for_other_inquiry`, `test_p06_retry_keeps_own_session_and_does_not_block_other_thread` | 실패·개발자 대기·재시도 중 다른 문의가 진행합니다. |
| P08–P10 | [test_inquiry_concurrency.py](../../dougle-bot/tests/test_inquiry_concurrency.py): `test_p08_p09_two_active_engines_cleanup_before_claim_release`, `test_p10_restart_clears_leases_but_preserves_atomic_completed_job` | 취소·중지 정리 후 선점 해제와 재시작 복구를 확인합니다. |
| P11–P12 | [test_inquiry_concurrency.py](../../dougle-bot/tests/test_inquiry_concurrency.py): `test_p03_p11_atomic_global_claim_limit_across_connections`, `test_p12_simultaneous_budget_claims_cannot_overspend`; [test_boundaries.py](../../dougle-bot/tests/test_boundaries.py): `test_parallel_duplicate_events_have_exactly_one_job` | 임시 DB의 복수 연결 선점·예산 경합과 중복 이벤트를 확인합니다. |
| P14 | [test_engine_call_isolation.py](../../dougle-bot/tests/test_engine_call_isolation.py): `test_two_cli_calls_overlap_and_finishing_one_preserves_the_other_policy` | 호출별 정책 수명과 종료 격리를 확인합니다. |
| M01–M02, M06 | [test_memory_staging.py](../../dougle-bot/tests/test_memory_staging.py): `test_lazy_snapshot_read_own_changes_and_validation_gate`, `test_edit_uses_snapshot_and_never_shared_target`, `test_existing_write_requires_exact_read_and_new_write_captures_absence` | 자기 변경 재읽기·공통 원본 보존·기준 읽기를 확인합니다. |
| M03–M05 | [test_memory_staging.py](../../dougle-bot/tests/test_memory_staging.py): `test_parallel_sessions_and_serial_saves_preserve_conflicting_proposals`, `test_all_read_dependencies_checked_before_any_write` | 순차 반영과 비교 시점에 존재하는 변경을 감지합니다. 외부 작성자와의 원자적 CAS를 보장하지 않습니다. |
| M07–M08 | [test_memory_worker.py](../../dougle-bot/tests/test_memory_worker.py): `test_validated_answer_enqueues_memory_without_committing_on_answer_path`, `test_dispatch_filesystem_failure_does_not_change_answer_and_remains_retryable`, `test_failed_inquiry_never_accepts_its_staged_knowledge`, `test_cancel_after_validation_before_dispatch_discards_proposal` | 답변과 저장 실패를 분리하고 취소·실패 제안을 반영하지 않습니다. |
| M09 | [test_memory_staging.py](../../dougle-bot/tests/test_memory_staging.py): `test_partial_commit_crash_recovers_or_preserves_conflict`, `test_acceptance_crash_marker_does_not_promote_pending_but_replay_recovers`; [test_inquiry_concurrency.py](../../dougle-bot/tests/test_inquiry_concurrency.py): `test_memory_ready_and_job_done_are_one_transaction_and_batch_ids_deduplicate` | 부분 반영·승인 재생·중복 제거를 확인합니다. 손상 manifest 기아 수정은 아래 추가 회귀와 대응합니다. |
| M10–M11 | [test_memory_staging.py](../../dougle-bot/tests/test_memory_staging.py): `test_symlinks_traversal_foreign_sessions_and_internal_metadata_denied`, `test_sensitive_content_denied_before_staging_and_checked_again_in_background`, `test_finish_seals_without_file_reads_hashes_diffs_or_directory_scan`, `test_drainer_only_scans_ready_markers_not_historical_or_held_batches` | 경로·민감정보 경계와 불필요한 전체 탐색 회피를 확인합니다. 대규모 실사용 성능 결과는 아닙니다. |
| R01–R03 | [test_answer_style.py](../../dougle-bot/tests/test_answer_style.py), [test_research_regressions.py](../../dougle-bot/tests/test_research_regressions.py) | 기존 표현·근거 대응·운영 요구 회귀 검사입니다. 실제 회사 답변의 정확성은 미검증입니다. |
| R04–R06 | [test_images.py](../../dougle-bot/tests/test_images.py), [test_verification_repair_worker.py](../../dougle-bot/tests/test_verification_repair_worker.py), [test_runtime.py](../../dougle-bot/tests/test_runtime.py), [test_handoff_notice.py](../../dougle-bot/tests/test_handoff_notice.py), [test_processing_claims.py](../../dougle-bot/tests/test_processing_claims.py) | 첨부·보완 불변 조건·승인·안내·게시 선점의 기존 회귀 검사와 대응합니다. 모든 조합의 동시 실행을 입증하지는 않습니다. |
| R07 | [test_inquiry_concurrency.py](../../dougle-bot/tests/test_inquiry_concurrency.py): `test_r07_legacy_saved_one_upgrades_in_memory_without_changing_file`, `test_r07_limit_is_shared_by_connections_and_explicit_config_is_not_migrated`, `test_r07_status_old_schema_with_migrate_false_never_creates_coordination` | 이전 설정의 메모리 내 적용·명시적 설정 유지·읽기 전용 상태 조회를 확인합니다. |

M05의 한계: 저장 작업자끼리는 공유 잠금으로 직렬화하지만 그 잠금을 따르지 않는 외부 작성자는 기준 비교 후 파일 교체 전에도 수정할 수 있습니다(TOCTOU). 이 구간의 변경 보존은 보장하지 않으며 전역 원자적 CAS로 표현하지 않습니다.

M07·M09 추가 회귀는 [test_memory_dispatch_independent_review.py](../../dougle-bot/tests/test_memory_dispatch_independent_review.py)의 `test_bad_dispatch_manifest_does_not_starve_later_healthy_batch`, `test_transient_failures_rotate_past_page_limit_and_can_recover`, `test_save_loop_backs_off_when_every_dispatch_is_retryable`에 대응합니다. 손상 manifest 격리, 페이지 한도를 넘는 재시도 순환과 복구, 재시도만 남은 경우의 backoff를 확인합니다. main이 Bacon 수정 완료·동결과 관련 검사 58건 통과를 보고했습니다.

main 보고 기준 0.1.18 wheel 빌드·소스 일치·사본 반영·격리 설치(max_parallel=2 확인)가 완료되었습니다. 사례 대응 문서는 동결하며 최종 전체 검사(세션 34403)의 건수·결과는 main이 기록합니다. 회사 서비스와 실제 모델 검증은 수행하지 않았습니다.
