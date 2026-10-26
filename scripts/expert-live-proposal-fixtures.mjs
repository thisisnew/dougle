export const proposalCases = [
  { kind: 'MYSQL_READ', question: '가상 입하 요청 SYN-1001의 현재 실제 DB 레코드 상태를 확인하고 싶어. 문서의 일반 조건 설명이 아니라 지금 저장된 행을 읽는 수동 조회를 제안해줘.' },
  { kind: 'MYSQL_READ', question: '가상 요청 SYN-1002가 지금 완료 상태로 저장되어 있는지 실제 현재 데이터베이스 행으로 확인하려고 해. 코드 해설 대신 수동 DB 읽기 조회가 필요해.' },
  { kind: 'MYSQL_READ', question: '합성 요청 SYN-1003의 현재 저장 레코드를 직접 읽어 상태를 확인하는 절차를 원해. 과거 문서만으로 답하지 말고 수동 데이터베이스 조회를 제안해줘.' },
  { kind: 'DATADOG_LOGS', question: '가상 장애 SYN-2001에 대해 Datadog 운영 로그를 확인하려고 해. synthetic 환경의 receipt-demo 서비스, 2026-10-25T00:00:00Z부터 00:05:00Z까지만 대상으로 하는 수동 로그 조회를 제안해줘.' },
  { kind: 'DATADOG_LOGS', question: '가상 요청 SYN-2002 실패를 실제 운영 로그 근거로 조사하고 싶어. Datadog의 synthetic 환경 receipt-demo 서비스에서 2026-10-25T01:00:00Z부터 01:05:00Z까지의 제한된 로그를 수동으로 조회하려고 해.' },
  { kind: 'DATADOG_LOGS', question: '합성 장애 SYN-2003의 원인을 확인하기 위해 Datadog 로그 조회가 필요해. synthetic 환경 receipt-demo 서비스의 2026-10-25T02:00:00Z부터 02:05:00Z까지로 한정해서 수동 조회를 제안해줘.' },
]

export function proposalFields(kind) {
  if (kind === 'MYSQL_READ') return ['table', 'columns', 'equalities', 'limit']
  if (kind === 'DATADOG_LOGS') return ['environment', 'service', 'indexes', 'from', 'to', 'limit']
  throw new Error('Unknown synthetic proposal kind')
}
