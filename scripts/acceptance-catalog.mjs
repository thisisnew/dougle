import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { flows, suites } from './acceptance-catalog-data.mjs'

export const safetyRules = [
  '외부 업무 시스템은 항상 readonly. 테스트를 위해 실제 DB/Slack/문서/원본을 변경하지 않는다.',
  '쓰기·삭제·권한 변경·장애 주입은 소유한 합성 fixture/임시 앱/모의 transport에서만 수행한다.',
  '사용자 DB/자격 증명을 복사하거나 원문을 공개 결과 파일에 넣지 않는다.',
  '실제 AI/회사 계정/브라우저/ARM 검사는 환경·승인·예산을 확인하고 없으면 BLOCKED로 기록한다.',
  '생성기는 명세만 출력한다. 네트워크·AI·앱 조작·설치·테스트 실행을 하지 않는다.',
]

export function buildCatalog() {
  assert.equal(suites.length, 40)
  const cases = []
  for (const group of suites) {
    assert.equal(group.fixtures.length, 5, group.id)
    assert.equal(flows[group.flow]?.length, 5, group.id)
    for (const [fixtureIndex, fixture] of group.fixtures.entries()) {
      for (const [flowIndex, [action, step, expectation]] of flows[group.flow].entries()) {
        const id = 'T' + String(cases.length + 1).padStart(4, '0')
        const semantic = ['retrieval', 'answer', 'conversation'].includes(group.flow)
        cases.push({
          id,
          key: `${group.id}-F${fixtureIndex + 1}-A${flowIndex + 1}`,
          suite: group.id,
          area: group.title,
          title: `${fixture.input} — ${action}`,
          priority: group.priority,
          requirement: group.requirement,
          environment: group.environment,
          fixture: fixture.input,
          preconditions: [
            '독립 임시 Workspace/프로필을 준비하고 앱 버전·JAR/fixture 해시를 기록한다.',
            'fixture manifest와 허용 정답/거부 조건을 실행 전에 고정한다. 실제 외부 변경은 모의 transport로 대체한다.',
            ...(semantic ? ['정답 파일뿐 아니라 답변 가능한 구절·허용 해석·금지 추론을 독립적으로 검토한다.'] : []),
          ],
          steps: [
            `입력 fixture를 준비한다: ${fixture.input}`,
            step,
            'UI 상태·네트워크/도구 호출·자료 manifest·필요한 검색 근거를 수집하고 아래 기대 결과를 각각 대조한다.',
          ],
          expected: [fixture.oracle, expectation, '원본/외부 업무 데이터 변경0회이며 성공/부분 실패/실패 표시가 실제 결과와 일치한다.'],
          branchRule: '입력 oracle이 거부/차단/미지원이면 수집 성공을 요구하지 않고 거부·무부작용을 확인한다. 성공 전제가 필요한 후속 흐름은 임의로 통과시키지 말고 해당 케이스를 BLOCKED 또는 FAIL로 기록한다.',
          evidenceRequired: ['실행 앱/fixture 해시와 시각', '실제 입력·조작·상태 변화', '기대 결과별 관측값', '외부 읽기/쓰기 요청 수', ...(semantic ? ['반환 경로·행 범위·정답 구절 적중/허용 해석 검토'] : [])],
          cleanup: '자신이 만든 임시 프로필/서버/프로세스만 종료·정리한다. 사용자 자료·인증·공개 피드는 변경하지 않는다.',
          status: 'NOT_RUN',
          automation: 'SPEC_ONLY',
          result: null,
        })
      }
    }
  }
  return {
    schemaVersion: 1,
    title: 'Dougle 사용자 동선·수집·검색 인수 1000개',
    specificationDate: '2026-10-27',
    count: cases.length,
    design: '40개 영역 × 영역별 구체 입력5종 × 적용 조작5종. 파라미터화된1000개이며 독립 설계1000종/실행1000회라고 주장하지 않는다.',
    execution: 'NOT_RUN은 실행 전 명세의 초기값이다. 현재 실행 상태는 별도 결과 보고서에서 관리하며 기존 단위 통과를 자동 승계하지 않는다.',
    safetyRules,
    cases,
  }
}

export function validateCatalog(catalog) {
  assert.equal(catalog.count, 1000)
  assert.equal(catalog.cases.length, 1000)
  for (const field of ['id', 'key', 'title']) assert.equal(new Set(catalog.cases.map(item => item[field])).size, 1000, field)
  for (const [index, item] of catalog.cases.entries()) {
    assert.equal(item.id, 'T' + String(index + 1).padStart(4, '0'))
    for (const field of ['fixture', 'requirement', 'environment', 'branchRule', 'cleanup']) assert(item[field]?.trim(), `${item.id}:${field}`)
    for (const field of ['preconditions', 'steps', 'expected', 'evidenceRequired']) {
      assert(item[field].length >= 2 && item[field].every(value => typeof value === 'string' && value.trim()), `${item.id}:${field}`)
    }
    assert.equal(item.status, 'NOT_RUN')
    assert.equal(item.automation, 'SPEC_ONLY')
    assert.equal(item.result, null)
  }
  for (const group of suites) assert.equal(catalog.cases.filter(item => item.suite === group.id).length, 25, group.id)
  return true
}

const cell = value => value.replaceAll('|', '\\|').replaceAll('\n', ' ↵ ')

export function renderMarkdown(catalog) {
  validateCatalog(catalog)
  const lines = [
    '# Dougle 인수 테스트 1,000개', '',
    '**아래 NOT_RUN은 실행 전 명세의 초기값이다. 현재 실행 결과는 별도 보고서에서 관리하며 자동 테스트 구현 완료를 뜻하지 않는다.**', '',
    '[현재 실행 결과](evaluations/acceptance-remaining-20261027.md) · [첫100개 경계 검사](evaluations/acceptance-routing-20261027.md)', '',
    catalog.design, '',
    '## 실행 규칙', '',
    ...safetyRules.map(rule => '- ' + rule), '',
    '- 입력·조작·기대 결과는 아래 각 행에 있다. 사전조건·증거·정리 절차는 동일 ID의 JSON 항목에도 기록한다.',
    '- oracle은 현재 구현을 따라 정답을 바꾸지 않는다. R03의 query/fragment/호스트 정규화처럼 현재 미지원인 요구도 실패로 남긴다.',
    '- 거부 입력은 등록/요청0회를 검증한다. 거부 뒤 성공이 필요한 흐름을 건너뛰고 PASS로 표시하지 않는다.',
    '- 실제 AI는 승인된 유한 배치로만 실행하고 예산을 사전에 기록한다. 명세 생성은 AI1000회 실행 승인이 아니다.',
    '- 검색 적중률은 문서/구절을 분리하고, 답변은 허용 해석과 사실 창작을 구분해 평가한다. 같은 basename만으로 적중 처리하지 않는다.',
    '- 미구현·fixture 미준비·계정/ARM 부재는 BLOCKED/NOT_RUN으로 남기며 단위 검사로 실제 서비스 인수를 대체하지 않는다.',
    '- 이 명세는 기존 전문가50개 및54회 검색 기준선과 별개다. 기존 통과 수를 합산하지 않는다.', '',
    '## 공통 사전조건·증거', '',
    '독립 임시 Workspace/프로필을 사용한다. 앱 버전/JAR 해시, fixture 해시/manifest, 실행 시각, 조작 기록, 기대 결과별 관측값, 원격 읽기/쓰기 횟수를 기록한다. 검색/답변 검사는 원본 경로·행·정답 구절·허용 해석도 대조한다. 결과 보고서는 공개 가능한 요약만 남기고 자격 증명/원문을 넣지 않는다.', '',
    '동일 내용을 경로별 비교할 때는 전체 코퍼스/경쟁 문서를 같게 맞춘다. 대형/다수 파일은 manifest의 파일 수·바이트·첫/중간/끝 표식을 사전 고정한다. 작업 후 자신이 만든 fixture 프로세스/임시 데이터만 정리한다.', '',
    '## 영역별 목록', '',
    '| 영역 | 범위 | 케이스 |', '|---|---|---|',
  ]
  for (const group of suites) {
    const entries = catalog.cases.filter(item => item.suite === group.id)
    lines.push(`| ${group.id} ${group.title} | ${entries[0].id}–${entries.at(-1).id} | 25 |`)
  }
  for (const group of suites) {
    lines.push('', `## ${group.id} ${group.title}`, '', group.requirement, '', `환경: ${group.environment}. 우선순위: ${group.priority}.`, '',
      '| ID | 입력/상태 | 조작 | 기대 결과 | 상태 |', '|---|---|---|---|---|')
    for (const item of catalog.cases.filter(item => item.suite === group.id)) {
      lines.push(`| ${item.id} | ${cell(item.fixture)} | ${cell(item.steps[1])} | ${cell(item.expected.slice(0, 2).join(' / '))} | NOT_RUN |`)
    }
  }
  return lines.join('\n') + '\n'
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const catalog = buildCatalog()
  validateCatalog(catalog)
  const mode = process.argv[2] ?? 'summary'
  assert(['summary', 'json', 'markdown'].includes(mode), 'Usage: node scripts/acceptance-catalog.mjs [summary|json|markdown]')
  if (mode === 'json') console.log(JSON.stringify(catalog, null, 2))
  else if (mode === 'markdown') process.stdout.write(renderMarkdown(catalog))
  else console.log(JSON.stringify({ cases: catalog.count, suites: suites.length, status: 'NOT_RUN', automation: 'SPEC_ONLY' }))
}
