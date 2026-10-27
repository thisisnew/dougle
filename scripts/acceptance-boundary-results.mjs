import assert from 'node:assert/strict'
import { buildCatalog } from './acceptance-catalog.mjs'

export function routingBoundaryLedger(checks) {
  assert.equal(checks.length, 100)
  assert.equal(new Set(checks.map(check => check.id)).size, 100)
  const catalog = buildCatalog()
  const expected = catalog.cases.slice(0, 100).map(item => item.id)
  assert.deepEqual(checks.map(check => check.id).sort(), expected)
  assert(checks.every(check => ['PASS', 'FAIL'].includes(check.status)))
  return catalog.cases.map(item => {
    const check = checks.find(candidate => candidate.id === item.id)
    return {
      id: item.id,
      status: !check ? 'NOT_RUN' : check.status === 'FAIL' ? 'FAIL' : 'PARTIAL',
      scope: !check ? 'NOT_EXECUTED' : Number(item.id.slice(1)) % 5 === 4 ? 'MOCKMVC_REGISTRATION_AND_PERSISTENCE' : 'REACT_COMPONENT_WITH_MOCKED_HTTP',
      fullAcceptancePassed: false,
      remaining: check ? ['실제 수집기와 UI를 연결한 end-to-end 검증', '허용 주소의 원문 manifest와 검색 구절 대조', '설치본 브라우저 동작 확인'] : ['해당 케이스의 실행 환경·fixture·runner 준비 및 실행'],
    }
  })
}
