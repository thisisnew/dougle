export const documents = {
  'synthetic-badge-policy.md': `# 가상 방문 배지 정책
가상 BLUE 방문 배지의 유효 기간은 발급 시각부터 72시간이다.
BLUE 배지는 자동 갱신되지 않는다. 갱신은 운영 담당자의 수동 승인이 필요하다.
이 문서는 합성 정책 정의이며 현재 실제 배지 레코드나 운영 시스템은 없다.
`,
  'synthetic-glossary.md': `# 가상 Workspace 용어집
입고와 입하는 이 Workspace에서 동일한 수신 업무를 뜻한다.
입하 검수완료, 입고 검수 끝, 수신 검사 완료는 모두 RECEIVING_CHECKED 상태를 뜻한다.
RECEIVING_CHECKED는 수신 품목의 검사가 끝났다는 뜻이며 출하 확정을 뜻하지 않는다.
출하 확정과 발송 확정은 SHIPPING_CONFIRMED 상태를 뜻한다.
SHIPPING_CONFIRMED는 발송 승인을 확정한 것이며 수신 검사가 끝났다는 뜻이 아니다.
두 상태는 서로 다른 업무이며 동의어가 아니다. 모두 가상 정책 정의다.
`,
}

export const scenarios = [
  { id: 'restart-history-followup', restartBeforeTurn: 1, questions: [
    ['가상 BLUE 방문 배지의 유효 기간은?', '그럼 그 배지는 자동으로 갱신돼?'],
    ['BLUE 방문 배지는 발급 뒤 몇 시간 유효해?', '그 배지를 갱신하려면 누구의 승인이 필요해?'],
    ['가상 방문 배지 BLUE의 만료 기준을 설명해줘.', '그럼 갱신 방식도 알려줘.'],
  ], expected: [/72/, /수동[\s\S]*승인|승인[\s\S]*수동/], forbidden: [null, /자동으로 갱신(?:됩니다|돼요)/],
    expectedDecisions: ['STOP', 'STOP'], manualCriteria: 'E04 backend-only: restart same synthetic DB after completed answer, preserve thread/evidence/jobs/order, reopen via GET, then answer manual operator approval without losing context. UI reopen not exercised.' },
  { id: 'workspace-glossary-states', questions: [
    ['이 가상 Workspace에서 입고 검수 끝은 어떤 상태야?', '그럼 출하 확정과 같은 상태라고 해도 돼?'],
    ['용어집의 수신 검사 완료에 해당하는 상태 코드는?', '그 상태를 발송 확정과 동의어로 써도 돼?'],
    ['입하 검수완료와 입고 검수 끝은 이 Workspace에서 같은 뜻이야? 상태 코드도 알려줘.', '그럼 SHIPPING_CONFIRMED와도 같은 업무 상태야?'],
  ], expected: [/RECEIVING_CHECKED/, /RECEIVING_CHECKED[\s\S]*SHIPPING_CONFIRMED|SHIPPING_CONFIRMED[\s\S]*RECEIVING_CHECKED/],
    forbidden: [null, null], expectedDecisions: ['STOP', 'STOP'],
    manualCriteria: 'E12: resolve explicit Workspace synonyms to RECEIVING_CHECKED, then distinguish SHIPPING_CONFIRMED as shipping approval; do not merge similarly named completion states. Policy only, no operational proposal.' },
]
