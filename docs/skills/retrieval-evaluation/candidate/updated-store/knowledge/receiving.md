# 入荷 / 입하 확정
Scope: receiving
Aliases: 입고 확정; confirmReceipt
Topic: receiving (knowledge/receiving.md)
Identifiers: ReceivingService; confirm(Receipt); cancel(Receipt); receipt; receiptCancel
About: Receiving confirmation guards, insertion calls, and cancellation recording.
Sections: insertion -> [C01](#c01--저장-위치); exclusions -> [C02](#c02--제외-조건); cancellation -> [C03](#c03--취소-기록)
Cautions: reported night-time requirement -> [C90](#c90--사용자-정정); unresolved design conflict and coverage -> [확인할 점](#확인할-점)

## C01 — 저장 위치
Statement: confirm inserts receipt when guards permit.
Provenance: observed
Evidence: S01, ReceivingService.java, confirm
Observed:2026-01-01T00:00:00Z
Requires: C02
Freshness: current_at_observation (rechecked 2026-11-05T21:53:10Z)
Recheck evidence: [S01](../sources/S01.md), ReceivingService.java:1, ReceivingService.confirm(Receipt), receipt.insert(r) after the C02 guard; no revision supplied. Original observation above retained.

## C02 — 제외 조건
Statement: cancelled, returnOrder, or archived causes early return from confirm before receipt.insert(r).
Provenance: observed
Evidence: S01, ReceivingService.java, confirm
Observed:2026-11-05T21:53:10Z
Freshness: current_at_observation
Precise evidence: [S01](../sources/S01.md), ReceivingService.java:1, ReceivingService.confirm(Receipt), if(r.cancelled || r.returnOrder || r.archived) return; no revision supplied.
Dependents: C01 requires this guard; this scope does not establish guards on cancel.
Prior observation (2026-01-01T00:00:00Z, observed, S01/confirm): "cancelled or returnOrder causes early return." Superseded as the complete exclusion list; archived is now also excluded.

## C03 — 취소 기록
Statement: cancel inserts receiptCancel.
Provenance: observed
Evidence: S01, ReceivingService.java, cancel
Observed:2026-01-01T00:00:00Z
Freshness: current_at_observation (rechecked 2026-11-05T21:53:10Z)
Recheck evidence: [S01](../sources/S01.md), ReceivingService.java:1, ReceivingService.cancel(Receipt), receiptCancel.insert(r.id); no revision supplied. Original observation above retained.

## C90 — 사용자 정정
Statement: 야간에는 담당자 확인이 필요하다는 사용자 설명. 원본 확인 전.
Provenance: reported
Observed:2026-01-02T00:00:00Z

## 확인할 점
The old design says legacy_receipt; this unresolved conflict is not removed by this code update.
Refresh coverage: only the supplied ReceivingService.java, confirm and cancel. C90 remains reported and unverified; no design document, callers, persistence implementation, or database schema was read. The code establishes insertion calls, not physical database table mappings. The legacy_receipt conflict remains unresolved against C01.
