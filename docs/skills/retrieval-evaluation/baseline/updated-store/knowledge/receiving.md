# 入荷 / 입하 확정
Scope: receiving
Aliases: 입고 확정; confirmReceipt

## C01 — 저장 위치
Freshness: current_at_observation
Statement: confirm inserts receipt when guards permit.
Provenance: observed
Evidence: [S01](../sources/S01.md), ReceivingService.java:1, ReceivingService.confirm(Receipt), receipt.insert(r) after the guard in C02
Observed:2026-01-01T00:00:00Z
Rechecked: 2026-11-05T21:52:54Z
Requires: C02

## C02 — 제외 조건
Freshness: current_at_observation
Statement: cancelled, returnOrder, or archived causes early return from confirm; receipt.insert(r) runs only when all three are false.
Provenance: observed
Evidence: [S01](../sources/S01.md), ReceivingService.java:1, ReceivingService.confirm(Receipt), if(r.cancelled || r.returnOrder || r.archived) return
Observed:2026-11-05T21:52:54Z
History: The prior observed statement (2026-01-01T00:00:00Z), "cancelled or returnOrder causes early return", is superseded as an incomplete list of exclusions. The refreshed source adds archived; the prior conditions still apply.

## C03 — 취소 기록
Freshness: current_at_observation
Statement: cancel inserts receiptCancel.
Provenance: observed
Evidence: [S01](../sources/S01.md), ReceivingService.java:1, ReceivingService.cancel(Receipt), receiptCancel.insert(r.id)
Observed:2026-01-01T00:00:00Z
Rechecked: 2026-11-05T21:52:54Z

## C90 — 사용자 정정
Statement: 야간에는 담당자 확인이 필요하다는 사용자 설명. 원본 확인 전.
Provenance: reported
Observed:2026-01-02T00:00:00Z

## 확인할 점
The old design says legacy_receipt; this unresolved conflict is not removed by this code update.
