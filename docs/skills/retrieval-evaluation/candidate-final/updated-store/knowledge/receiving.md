# 入荷 / 입하 확정
Scope: receiving
Aliases: 입고 확정; confirmReceipt
Navigation state: complete
Topic ID: receiving
Identifiers: ReceivingService; confirm; cancel; receipt; receiptCancel
About: Receiving service confirmation guards, receipt insertion, and cancellation recording.
Sections: 저장 위치 -> [C01](#c01--저장-위치); 제외 조건 -> [C02](#c02--제외-조건); 취소 기록 -> [C03](#c03--취소-기록); 사용자 정정 -> [C90](#c90--사용자-정정)
Cautions: C01 requires C02; unverified user statement -> [C90](#c90--사용자-정정); unresolved legacy design conflict -> [확인할 점](#확인할-점)
Navigation coverage: C01, C02, C03, C90, 확인할 점

## C01 — 저장 위치
Statement: confirm calls receipt.insert(r) only when cancelled, returnOrder, and archived are all false.
Provenance: observed
Evidence: [S01](../sources/S01.md), ReceivingService.java:1, ReceivingService.confirm(Receipt r), guard followed by receipt.insert(r); no revision supplied.
Observed:2026-11-05T21:56:03Z
Previous observation: 2026-01-01T00:00:00Z — confirm inserts receipt when guards permit.
Freshness: current_at_observation
Requires: C02

## C02 — 제외 조건
Statement: cancelled, returnOrder, or archived causes confirm to return before receipt.insert(r).
Provenance: observed
Evidence: [S01](../sources/S01.md), ReceivingService.java:1, ReceivingService.confirm(Receipt r), if(r.cancelled || r.returnOrder || r.archived) return; no revision supplied.
Observed:2026-11-05T21:56:03Z
Previous observation: 2026-01-01T00:00:00Z — cancelled or returnOrder causes early return; this prior guard list is superseded by the current list including archived.
Freshness: current_at_observation
Dependent claim: C01

## C03 — 취소 기록
Statement: cancel inserts receiptCancel.
Provenance: observed
Evidence: S01, ReceivingService.java, cancel
Observed:2026-01-01T00:00:00Z

## C90 — 사용자 정정
Statement: 야간에는 담당자 확인이 필요하다는 사용자 설명. 원본 확인 전.
Provenance: reported
Observed:2026-01-02T00:00:00Z

## 확인할 점
The old design says legacy_receipt; this unresolved conflict is not removed by this code update.
